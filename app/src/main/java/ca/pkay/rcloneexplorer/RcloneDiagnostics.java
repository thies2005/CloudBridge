package ca.pkay.rcloneexplorer;

import java.util.Locale;

/** Classification shared by stderr collection and completed-operation logging. */
final class RcloneDiagnostics {
    private RcloneDiagnostics() { }
    static boolean isCancellation(int exit) { return exit == 130 || exit == 137 || exit == 143; }
    static boolean isSharedClientRetirement(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("shared") && (lower.contains("client id") || lower.contains("client_id"))
                && (lower.contains("retir") || lower.contains("deprecat"));
    }
}
