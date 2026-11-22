package ca.pkay.rcloneexplorer.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import io.github.x0b.safdav.SafBase64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Generates ephemeral ECDSA P-256 self-signed TLS certificates in pure Java without third-party dependencies.
 */
public final class ServeTlsHelper {

    public static final class TlsMaterial {
        public final File certFile;
        public final File keyFile;
        public final String sha256Fingerprint;

        TlsMaterial(File certFile, File keyFile, String sha256Fingerprint) {
            this.certFile = certFile;
            this.keyFile = keyFile;
            this.sha256Fingerprint = sha256Fingerprint;
        }

        public void delete() {
            deleteQuietly(certFile);
            deleteQuietly(keyFile);
        }
    }

    private static final int[] OID_ECDSA_WITH_SHA256 = {1, 2, 840, 10045, 4, 3, 2};
    private static final int[] OID_COMMON_NAME = {2, 5, 4, 3};
    private static final int[] OID_BASIC_CONSTRAINTS = {2, 5, 29, 19};
    private static final int[] OID_KEY_USAGE = {2, 5, 29, 15};
    private static final int[] OID_SAN = {2, 5, 29, 17};
    private static final int[] OID_EXT_KEY_USAGE = {2, 5, 29, 37};
    private static final int[] OID_SERVER_AUTH = {1, 3, 6, 1, 5, 5, 7, 3, 1};
    private static final long CERT_BACKDATE_MS = 5L * 60L * 1000L;
    private static final long CERT_LIFETIME_MS = 7L * 24L * 60L * 60L * 1000L;

    private ServeTlsHelper() {
    }

    @NonNull
    public static TlsMaterial createEphemeralServerTls(
            @NonNull File cacheDir,
            @Nullable String lanIpAddress) throws GeneralSecurityException, IOException {
        File tlsDir = new File(cacheDir, "serve_tls");
        if (!tlsDir.exists() && !tlsDir.mkdirs()) {
            throw new IOException("Failed to create TLS directory");
        }
        setOwnerOnlyDirPermissions(tlsDir);

        KeyPair keyPair = generateEcKeyPair();
        byte[] certDer = generateSelfSignedCertDer(keyPair, lanIpAddress, new Date());
        String fingerprint = computeSha256Fingerprint(certDer);

        String certPem = encodePem("CERTIFICATE", certDer);
        String keyPem = encodePem("PRIVATE KEY", keyPair.getPrivate().getEncoded());

        String prefix = "serve-" + System.currentTimeMillis() + "-" + Integer.toHexString(new SecureRandom().nextInt());
        File certFile = new File(tlsDir, prefix + ".crt");
        File keyFile = new File(tlsDir, prefix + ".key");
        try {
            writeOwnerOnlyFile(certFile, certPem.getBytes(StandardCharsets.US_ASCII));
            writeOwnerOnlyFile(keyFile, keyPem.getBytes(StandardCharsets.US_ASCII));
            return new TlsMaterial(certFile, keyFile, fingerprint);
        } catch (IOException e) {
            deleteQuietly(certFile);
            deleteQuietly(keyFile);
            throw e;
        }
    }

    public static void cleanupTlsDirectory(@Nullable File cacheDir) {
        if (cacheDir == null) {
            return;
        }
        File tlsDir = new File(cacheDir, "serve_tls");
        File[] children = tlsDir.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteQuietly(child);
            }
        }
    }

    @Nullable
    public static String discoverLanIpv4Address() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @VisibleForTesting
    static KeyPair generateEcKeyPair() throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        return kpg.generateKeyPair();
    }

    @VisibleForTesting
    static byte[] generateSelfSignedCertDer(
            @NonNull KeyPair keyPair,
            @Nullable String lanIpAddress,
            @NonNull Date now) throws GeneralSecurityException, IOException {
        SecureRandom random = new SecureRandom();
        byte[] serialBytes = new byte[16];
        random.nextBytes(serialBytes);
        serialBytes[0] = (byte) ((serialBytes[0] & 0x7F) | 0x40);
        BigInteger serial = new BigInteger(1, serialBytes);

        Date notBefore = new Date(now.getTime() - CERT_BACKDATE_MS);
        Date notAfter = new Date(now.getTime() + CERT_LIFETIME_MS);

        byte[] sigAlg = derSequence(derOid(OID_ECDSA_WITH_SHA256));
        byte[] name = derSequence(derSet(derSequence(derOid(OID_COMMON_NAME), derUtf8String("CloudBridge Serve"))));
        byte[] validity = derSequence(derUtcTime(notBefore), derUtcTime(notAfter));
        byte[] spki = keyPair.getPublic().getEncoded();

        List<byte[]> extensions = new ArrayList<>();
        extensions.add(derExtension(OID_BASIC_CONSTRAINTS, true, derSequence()));
        // keyUsage: digitalSignature (bit 0) -> 0x80, 7 unused bits
        extensions.add(derExtension(OID_KEY_USAGE, true, derBitString(new byte[]{(byte) 0x80}, 7)));
        // extKeyUsage: id-kp-serverAuth
        extensions.add(derExtension(OID_EXT_KEY_USAGE, false, derSequence(derOid(OID_SERVER_AUTH))));

        List<byte[]> sanEntries = new ArrayList<>();
        sanEntries.add(derTagged(0x82, "localhost".getBytes(StandardCharsets.US_ASCII)));
        sanEntries.add(derTagged(0x87, new byte[]{127, 0, 0, 1}));
        if (lanIpAddress != null && !lanIpAddress.isEmpty()) {
            try {
                byte[] ipBytes = InetAddress.getByName(lanIpAddress).getAddress();
                if (ipBytes.length == 4 || ipBytes.length == 16) {
                    sanEntries.add(derTagged(0x87, ipBytes));
                }
            } catch (IOException ignored) {
            }
        }
        extensions.add(derExtension(OID_SAN, false, derSequence(sanEntries.toArray(new byte[0][]))));

        byte[] extensionsField = derExplicitTag(0xA3, derSequence(extensions.toArray(new byte[0][])));
        byte[] versionField = derExplicitTag(0xA0, derInteger(BigInteger.valueOf(2)));

        byte[] tbsCert = derSequence(
                versionField,
                derInteger(serial),
                sigAlg,
                name,
                validity,
                name,
                spki,
                extensionsField);

        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate(), random);
        signer.update(tbsCert);
        byte[] signature = signer.sign();

        return derSequence(
                tbsCert,
                sigAlg,
                derBitString(signature, 0));
    }

    @VisibleForTesting
    static String computeSha256Fingerprint(@NonNull byte[] certDer) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certDer);
        StringBuilder sb = new StringBuilder(digest.length * 3);
        for (int i = 0; i < digest.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format(Locale.US, "%02X", digest[i] & 0xFF));
        }
        return sb.toString();
    }

    @VisibleForTesting
    static String encodePem(@NonNull String type, @NonNull byte[] der) {
        String b64 = SafBase64.encodeStandard(der);
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN ").append(type).append("-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            int end = Math.min(i + 64, b64.length());
            sb.append(b64, i, end).append('\n');
        }
        sb.append("-----END ").append(type).append("-----\n");
        return sb.toString();
    }

    private static void writeOwnerOnlyFile(File file, byte[] content) throws IOException {
        if (!file.createNewFile() && !file.exists()) {
            throw new IOException("Failed to create TLS file");
        }
        setOwnerOnlyFilePermissions(file);
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(content);
            fos.flush();
        }
    }

    private static void setOwnerOnlyDirPermissions(File dir) {
        dir.setReadable(false, false);
        dir.setWritable(false, false);
        dir.setExecutable(false, false);
        dir.setReadable(true, true);
        dir.setWritable(true, true);
        dir.setExecutable(true, true);
    }

    private static void setOwnerOnlyFilePermissions(File file) {
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
    }

    private static void deleteQuietly(@Nullable File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static byte[] derExtension(int[] oid, boolean critical, byte[] value) throws IOException {
        if (critical) {
            return derSequence(derOid(oid), new byte[]{0x01, 0x01, (byte) 0xFF}, derOctetString(value));
        }
        return derSequence(derOid(oid), derOctetString(value));
    }

    private static byte[] derSequence(byte[]... items) throws IOException {
        return derWrap(0x30, concat(items));
    }

    private static byte[] derSet(byte[]... items) throws IOException {
        return derWrap(0x31, concat(items));
    }

    private static byte[] derExplicitTag(int tag, byte[] content) throws IOException {
        return derWrap(tag, content);
    }

    private static byte[] derTagged(int tag, byte[] content) throws IOException {
        return derWrap(tag, content);
    }

    private static byte[] derInteger(BigInteger value) throws IOException {
        return derWrap(0x02, value.toByteArray());
    }

    private static byte[] derOctetString(byte[] content) throws IOException {
        return derWrap(0x04, content);
    }

    private static byte[] derUtf8String(String value) throws IOException {
        return derWrap(0x0C, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] derBitString(byte[] bytes, int unusedBits) throws IOException {
        byte[] content = new byte[1 + bytes.length];
        content[0] = (byte) (unusedBits & 0x07);
        System.arraycopy(bytes, 0, content, 1, bytes.length);
        return derWrap(0x03, content);
    }

    private static byte[] derUtcTime(Date date) throws IOException {
        SimpleDateFormat fmt = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return derWrap(0x17, fmt.format(date).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] derOid(int[] arcs) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            int val = arcs[i];
            if (val < 128) {
                out.write(val);
            } else {
                byte[] buf = new byte[5];
                int idx = 0;
                buf[idx++] = (byte) (val & 0x7F);
                val >>>= 7;
                while (val > 0) {
                    buf[idx++] = (byte) ((val & 0x7F) | 0x80);
                    val >>>= 7;
                }
                for (int j = idx - 1; j >= 0; j--) {
                    out.write(buf[j]);
                }
            }
        }
        return derWrap(0x06, out.toByteArray());
    }

    private static byte[] concat(byte[]... items) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] item : items) {
            out.write(item);
        }
        return out.toByteArray();
    }

    private static byte[] derWrap(int tag, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(2 + content.length + 4);
        out.write(tag);
        int len = content.length;
        if (len < 128) {
            out.write(len);
        } else if (len <= 0xFF) {
            out.write(0x81);
            out.write(len);
        } else if (len <= 0xFFFF) {
            out.write(0x82);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x83);
            out.write((len >>> 16) & 0xFF);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        }
        out.write(content);
        return out.toByteArray();
    }
}
