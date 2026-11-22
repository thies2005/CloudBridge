package ca.pkay.rcloneexplorer.Services;

import android.app.IntentService;
import android.content.Intent;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.Rclone;
import ca.pkay.rcloneexplorer.util.FLog;

public class ThumbnailsLoadingService extends IntentService {

    private static final String TAG = "ThumbnailsLoadingSvc";
    public static final String REMOTE_ARG = "ca.pkay.rcexplorer.ThumbnailsLoadingService.REMOTE_ARG";
    public static final String HIDDEN_PATH = "ca.pkay.rcexplorer.ThumbnailsLoadingService.HIDDEN_PATH";
    public static final String SERVER_PORT = "ca.pkay.rcexplorer.ThumbnailsLoadingService.PORT";

    private Rclone rclone;
    private Process process;
    private volatile boolean stopping;

    public ThumbnailsLoadingService() {
        super("ca.pkay.rcexplorer.ThumbnailLoadingService");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        rclone = new Rclone(this);
    }

    @VisibleForTesting
    static boolean isValidServeRequest(@Nullable RemoteItem remote, @Nullable String token, int port) {
        return remote != null
                && remote.getName() != null && !remote.getName().isEmpty()
                && token != null && !token.trim().isEmpty()
                && port > 0 && port <= 65535;
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        if (intent == null) {
            return;
        }

        RemoteItem remote = intent.getParcelableExtra(REMOTE_ARG);
        String hiddenPathExtra = intent.getStringExtra(HIDDEN_PATH);
        int serverPort = intent.getIntExtra(SERVER_PORT, -1);
        if (!isValidServeRequest(remote, hiddenPathExtra, serverPort)) {
            FLog.w(TAG, "onHandleIntent: refusing thumbnail server start without valid port and token");
            return;
        }
        String hiddenPath = "/" + hiddenPathExtra + '/' + remote.getName();
        process = rclone.serve(Rclone.SERVE_PROTOCOL_HTTP, serverPort, false, null, null, remote, "", hiddenPath, true, null, null);
        if (process != null) {
            boolean exited = false;
            try {
                rclone.collectDiagnostics(process);
                process.waitFor();
                exited = true;
            } catch (InterruptedException e) {
                FLog.e(TAG, "onHandleIntent: error waiting for process", e);
                Thread.currentThread().interrupt();
            }
            if (exited) {
                if (stopping) rclone.discardDiagnostics(process);
                else rclone.logErrorOutput(process);
            }
        }
    }

    @Override
    public void onDestroy() {
        stopping = true;
        super.onDestroy();
        if (process != null) {
            process.destroy();
        }
    }
}
