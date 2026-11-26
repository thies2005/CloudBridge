package ca.pkay.rcloneexplorer.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SecretRedactorTest {

    @Test
    public void redactsCredentialsTokensHeadersAndFlagsWhilePreservingSafeText() {
        assertNull(SecretRedactor.redact(null));
        assertEquals("", SecretRedactor.redact(""));

        String input = "proxy=http://alice:hunter2@proxy.local:8080 "
                + "{\"access_token\":\"tok_123\",\"refresh_token\":\"ref_456\",\"totp_secret\":\"JBSWY3DPEHPK3PXP\"} "
                + "https://api.example.com/cb?code=authcode99&state=abc "
                + "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc.def "
                + "--rc-pass supersecret RCLONE_CONFIG_LOCAL-SAF_HEADERS=Authorization,Basic_dXNlcjpwYXNz";

        String redacted = SecretRedactor.redact(input);
        assertFalse(redacted.contains("hunter2"));
        assertFalse(redacted.contains("tok_123"));
        assertFalse(redacted.contains("ref_456"));
        assertFalse(redacted.contains("JBSWY3DPEHPK3PXP"));
        assertFalse(redacted.contains("authcode99"));
        assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiJ9"));
        assertFalse(redacted.contains("supersecret"));
        assertFalse(redacted.contains("Basic_dXNlcjpwYXNz"));
        assertTrue(redacted.contains("<redacted>"));

        String safEnvRedacted = SecretRedactor.redact(
                "RCLONE_CONFIG_LOCAL-SAF_HEADERS=Authorization,Basic dXNlcjpwYXNz");
        assertFalse(safEnvRedacted.contains("dXNlcjpwYXNz"));
        assertTrue(safEnvRedacted.contains("<redacted>"));

        String normal = "Copied Vacation_2026-07-01.jpg (4.2 MB)";
        assertEquals(normal, SecretRedactor.redact(normal));
    }
}
