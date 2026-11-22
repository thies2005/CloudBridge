package ca.pkay.rcloneexplorer.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Date;
import java.util.List;

public class ServeTlsHelperTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void generatesValidSelfSignedX509V3CertAndPemMaterial() throws Exception {
        KeyPair keyPair = ServeTlsHelper.generateEcKeyPair();
        byte[] certDer = ServeTlsHelper.generateSelfSignedCertDer(keyPair, "192.168.1.42", new Date());

        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certDer));
        cert.checkValidity();
        cert.verify(keyPair.getPublic());
        assertEquals(3, cert.getVersion());

        Collection<List<?>> sans = cert.getSubjectAlternativeNames();
        assertNotNull(sans);
        boolean foundDns = false;
        boolean foundLanIp = false;
        for (List<?> entry : sans) {
            if (Integer.valueOf(2).equals(entry.get(0)) && "localhost".equals(entry.get(1))) {
                foundDns = true;
            }
            if (Integer.valueOf(7).equals(entry.get(0)) && "192.168.1.42".equals(entry.get(1))) {
                foundLanIp = true;
            }
        }
        assertTrue(foundDns);
        assertTrue(foundLanIp);

        String fp = ServeTlsHelper.computeSha256Fingerprint(certDer);
        assertTrue(fp.matches("^([0-9A-F]{2}:){31}[0-9A-F]{2}$"));

        File cacheDir = tempFolder.newFolder("cache");
        ServeTlsHelper.TlsMaterial material = ServeTlsHelper.createEphemeralServerTls(cacheDir, "192.168.1.42");
        assertTrue(material.certFile.exists());
        assertTrue(material.keyFile.exists());
        String keyPem = new String(Files.readAllBytes(material.keyFile.toPath()), StandardCharsets.US_ASCII);
        assertTrue(keyPem.startsWith("-----BEGIN PRIVATE KEY-----\n"));
        assertTrue(keyPem.endsWith("-----END PRIVATE KEY-----\n"));

        material.delete();
        assertFalse(material.certFile.exists());
        assertFalse(material.keyFile.exists());
    }
}
