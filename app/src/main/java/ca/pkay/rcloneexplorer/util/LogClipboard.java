package ca.pkay.rcloneexplorer.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.Toast;
import ca.pkay.rcloneexplorer.R;

public final class LogClipboard {
    public static final int MAX_CHARS = 400000;
    private LogClipboard() { }
    public static void copy(Context context, String text) {
        // Binder has a shared transaction limit; UTF-16 can require two bytes per character.
        if (text.length() > MAX_CHARS) {
            Toast.makeText(context, R.string.logs_too_large, Toast.LENGTH_LONG).show(); return;
        }
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IllegalStateException();
            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.logFragment), text));
            Toast.makeText(context, R.string.logs_copied, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) { Toast.makeText(context, R.string.logs_too_large, Toast.LENGTH_LONG).show(); }
    }
}
