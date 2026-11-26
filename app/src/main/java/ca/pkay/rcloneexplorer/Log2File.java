package ca.pkay.rcloneexplorer;

import android.content.Context;
import ca.pkay.rcloneexplorer.util.SyncLog;

/** Legacy worker diagnostics, kept synchronized with log clearing. */
public final class Log2File {
    private final Context context;
    public Log2File(Context context) { this.context = context.getApplicationContext(); }
    public void log(String message) { SyncLog.appendLegacy(context, message); }
}
