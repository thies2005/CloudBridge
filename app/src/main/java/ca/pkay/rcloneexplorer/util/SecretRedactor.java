package ca.pkay.rcloneexplorer.util;

import androidx.annotation.Nullable;

import java.util.regex.Pattern;

/**
 * Redacts credentials, OAuth tokens, and proxy passwords before strings reach logs or UI errors.
 */
public final class SecretRedactor {

    private static final Pattern URL_USERINFO_PATTERN = Pattern.compile(
            "(?i)\\b([a-z][a-z0-9+.-]*://)([^/\\s:@]+(?::[^/\\s@]*)?@)");

    private static final Pattern JSON_SECRET_PATTERN = Pattern.compile(
            "(?i)(\"(?:access_token|refresh_token|id_token|client_secret|totp_secret|secret|password|pass|token|authorization|auth_code|code_verifier)\"\\s*:\\s*\")([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)(\")");

    private static final Pattern QUERY_PARAM_SECRET_PATTERN = Pattern.compile(
            "(?i)([?&](?:access_token|refresh_token|id_token|client_secret|totp_secret|password|pass|token|code|state|auth)=)([^&\\s\"']+)");

    private static final Pattern AUTH_HEADER_PATTERN = Pattern.compile(
            "(?i)((?:authorization|proxy-authorization|x-auth-token)\\s*[:=]\\s*(?:(?:bearer|basic)\\s+)?)([^\\s,;\"']+)");

    private static final Pattern BEARER_OR_BASIC_TOKEN_PATTERN = Pattern.compile(
            "(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}");

    private static final Pattern ENV_OR_FLAG_SECRET_PATTERN = Pattern.compile(
            "(?i)((?:--(?:rc-pass|rc-user|pass|password|totp_secret|state|result)|RCLONE_RC_PASS|RCLONE_RC_USER|RCLONE_PASS|RCLONE_USER|RCLONE_CONFIG_[A-Z0-9_-]*_(?:PASS|PASSWORD|TOKEN|HEADERS)|HTTPS?_PROXY|ALL_PROXY)(?:\\s*[:=]|\\s+))((?:Authorization,(?:Bearer|Basic)\\s+[^\\s\"']+)|[^\\s\"']+|\"[^\"]*\"|'[^']*')");

    private SecretRedactor() {
    }

    @Nullable
    public static String redact(@Nullable String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String sanitized = URL_USERINFO_PATTERN.matcher(input).replaceAll("$1<redacted>@");
        sanitized = JSON_SECRET_PATTERN.matcher(sanitized).replaceAll("$1<redacted>$3");
        sanitized = QUERY_PARAM_SECRET_PATTERN.matcher(sanitized).replaceAll("$1<redacted>");
        sanitized = ENV_OR_FLAG_SECRET_PATTERN.matcher(sanitized).replaceAll("$1<redacted>");
        sanitized = AUTH_HEADER_PATTERN.matcher(sanitized).replaceAll("$1<redacted>");
        sanitized = BEARER_OR_BASIC_TOKEN_PATTERN.matcher(sanitized).replaceAll("$1 <redacted>");
        return sanitized;
    }
}
