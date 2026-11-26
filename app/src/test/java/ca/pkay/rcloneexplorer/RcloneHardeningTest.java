package ca.pkay.rcloneexplorer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import ca.pkay.rcloneexplorer.util.ProxyPasswordStore;

public class RcloneHardeningTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void resolveSafeDownloadTargetBlocksTraversalAndAcceptsNormalFiles() throws IOException {
        File base = tempFolder.newFolder("downloads");
        assertNull(Rclone.resolveSafeDownloadTarget(base.getAbsolutePath(), "../evil.apk", false));
        assertNull(Rclone.resolveSafeDownloadTarget(base.getAbsolutePath(), "..", true));
        assertNull(Rclone.resolveSafeDownloadTarget(base.getAbsolutePath(), "a/b.txt", false));
        assertNull(Rclone.resolveSafeDownloadTarget(base.getAbsolutePath(), "a\\b.txt", false));
        assertNull(Rclone.resolveSafeDownloadTarget(base.getAbsolutePath(), "bad\0name", false));
        assertNull(Rclone.resolveSafeDownloadTarget("", "ok.txt", false));

        String fileDest = Rclone.resolveSafeDownloadTarget(base.getAbsolutePath(), "photo.jpg", false);
        assertEquals(base.getCanonicalPath(), fileDest);

        String dirDest = Rclone.resolveSafeDownloadTarget(base.getAbsolutePath(), "Vacation", true);
        assertEquals(new File(base.getCanonicalPath(), "Vacation").getCanonicalPath(), dirDest);
    }

    @Test
    public void buildSafDavEnvOverridesPreservesHyphensInSectionName() {
        List<String> env = Rclone.buildSafDavEnvOverrides(
                Collections.singletonList("local-saf"),
                "http://127.0.0.1:41234/",
                "Basic dXNlcjpzZWNyZXQ=");
        assertEquals(
                Arrays.asList(
                        "RCLONE_CONFIG_LOCAL-SAF_URL=http://127.0.0.1:41234/",
                        "RCLONE_CONFIG_LOCAL-SAF_HEADERS=Authorization,Basic dXNlcjpzZWNyZXQ=",
                        "RCLONE_CONFIG_LOCAL-SAF_USER=",
                        "RCLONE_CONFIG_LOCAL-SAF_PASS="
                ),
                env);
    }

    @Test
    public void buildServeArgsEnforcesReadOnlySeparatorTlsAndNoCredentialsInArgv() {
        File cert = new File("/cache/serve_tls/serve.crt");
        File key = new File("/cache/serve_tls/serve.key");

        List<String> httpRemote = Rclone.buildServeArgs(
                Rclone.SERVE_PROTOCOL_HTTP, 9000, true, "alice", "s3cr3t",
                "--remote:", "tok", "8", true, cert, key, null);
        assertTrue(httpRemote.contains("--read-only"));
        assertTrue(httpRemote.contains("--cert"));
        assertTrue(httpRemote.contains("--key"));
        assertFalse(httpRemote.contains("--user"));
        assertFalse(httpRemote.contains("--pass"));
        int dashDash = httpRemote.indexOf("--");
        assertTrue(dashDash > httpRemote.indexOf("--cert"));
        assertEquals("--remote:", httpRemote.get(dashDash + 1));

        List<String> dlnaRemote = Rclone.buildServeArgs(
                Rclone.SERVE_PROTOCOL_DLNA, 9001, true, "alice", "s3cr3t",
                "remote:media", null, "4", true, cert, key, null);
        assertFalse(dlnaRemote.contains("--cert"));
        assertFalse(dlnaRemote.contains("--key"));
        assertTrue(dlnaRemote.contains(":9001"));

        List<String> envCreds = Rclone.buildServeCredentialEnv(Rclone.SERVE_PROTOCOL_WEBDAV, "bob", "pw");
        assertEquals(Arrays.asList("RCLONE_USER=bob", "RCLONE_PASS=pw"), envCreds);
        assertTrue(Rclone.buildServeCredentialEnv(Rclone.SERVE_PROTOCOL_DLNA, "bob", "pw").isEmpty());

        List<String> failClosedRemote = Rclone.buildServeArgs(
                Rclone.SERVE_PROTOCOL_WEBDAV, 9002, true, "", "",
                "remote:docs", null, "4", true, cert, key, null);
        assertTrue(failClosedRemote.contains("127.0.0.1:9002"));
    }

    @Test
    public void extractZipEntryBoundedEnforcesMaxBytes() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("rclone.conf"));
            zos.write("[remote]\ntype = drive\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("huge.txt"));
            zos.write(new byte[256]);
            zos.closeEntry();
        }
        byte[] zipBytes = baos.toByteArray();

        File okOut = tempFolder.newFile("ok.conf");
        File extracted = Rclone.extractZipEntryBounded(
                new ByteArrayInputStream(zipBytes), "rclone.conf", okOut, 1024);
        assertNotNull(extracted);
        assertTrue(new String(Files.readAllBytes(okOut.toPath()), StandardCharsets.UTF_8).contains("type = drive"));

        File oversizedOut = new File(tempFolder.getRoot(), "huge.txt");
        try {
            Rclone.extractZipEntryBounded(new ByteArrayInputStream(zipBytes), "huge.txt", oversizedOut, 64);
            fail("Expected IOException for oversized entry");
        } catch (IOException expected) {
            assertFalse(oversizedOut.exists());
        }

        File missing = Rclone.extractZipEntryBounded(
                new ByteArrayInputStream(zipBytes), "missing.txt", okOut, 1024);
        assertNull(missing);
    }

    @Test
    public void proxyUrlEncodingAndAesGcmRoundTripWorkCorrectly() throws GeneralSecurityException {
        assertEquals(
                "http://user%40corp:p%40ss%3A%2Fword%201@proxy.local:8080",
                ProxyPasswordStore.buildProxyUrl("http", "proxy.local", 8080, "user@corp", "p@ss:/word 1"));

        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        SecretKey key = kg.generateKey();
        SecureRandom random = new SecureRandom();
        byte[] enc1 = ProxyPasswordStore.encryptWithKey(key, "super-secret-proxy-pw", random);
        byte[] enc2 = ProxyPasswordStore.encryptWithKey(key, "super-secret-proxy-pw", random);
        assertFalse(Arrays.equals(enc1, enc2));
        assertEquals("super-secret-proxy-pw", ProxyPasswordStore.decryptWithKey(key, enc1));
        assertEquals("super-secret-proxy-pw", ProxyPasswordStore.decryptWithKey(key, enc2));

        enc1[enc1.length - 1] ^= 0x01;
        try {
            ProxyPasswordStore.decryptWithKey(key, enc1);
            fail("Expected GCM tag failure on tampered ciphertext");
        } catch (GeneralSecurityException expected) {
            assertNotNull(expected);
        }
    }
}
