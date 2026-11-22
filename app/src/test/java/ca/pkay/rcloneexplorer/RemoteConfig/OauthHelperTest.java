package ca.pkay.rcloneexplorer.RemoteConfig;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OauthHelperTest {

    @Test
    public void allowsStandardHttpsAndLoopbackOauthCallbackUrls() {
        assertTrue(OauthHelper.isAllowedOauthUrl("https://accounts.google.com/o/oauth2/v2/auth?client_id=abc"));
        assertTrue(OauthHelper.isAllowedOauthUrl("http://127.0.0.1:53682/auth?state=xyz"));
        assertTrue(OauthHelper.isAllowedOauthUrl("http://localhost:53682/auth"));
        assertTrue(OauthHelper.isAllowedOauthUrl("HTTP://LOCALHOST:53682/auth"));
    }

    @Test
    public void rejectsUnsafeSchemesUserInfoWrongLoopbackPortAndControlChars() {
        assertFalse(OauthHelper.isAllowedOauthUrl(null));
        assertFalse(OauthHelper.isAllowedOauthUrl(""));
        assertFalse(OauthHelper.isAllowedOauthUrl("file:///data/user/0/rclone.conf"));
        assertFalse(OauthHelper.isAllowedOauthUrl("intent://evil#Intent;scheme=https;end"));
        assertFalse(OauthHelper.isAllowedOauthUrl("javascript:alert(1)"));
        assertFalse(OauthHelper.isAllowedOauthUrl("http://accounts.google.com/auth"));
        assertFalse(OauthHelper.isAllowedOauthUrl("http://127.0.0.1:8080/auth"));
        assertFalse(OauthHelper.isAllowedOauthUrl("http://127.0.0.1:53682@evil.example/auth"));
        assertFalse(OauthHelper.isAllowedOauthUrl("https://user:pass@accounts.google.com/auth"));
        assertFalse(OauthHelper.isAllowedOauthUrl("https://accounts.google.com/auth\n"));
        assertFalse(OauthHelper.isAllowedOauthUrl("https:///missing-host"));
    }
}
