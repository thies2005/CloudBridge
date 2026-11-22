package ca.pkay.rcloneexplorer.util;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;

import com.google.android.material.color.MaterialColors;
import java.util.Locale;
import android.os.Build;
import android.util.AttributeSet;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import org.markdownj.MarkdownProcessor;

import ca.pkay.rcloneexplorer.R;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;


public class MarkdownView extends WebView {

    private static final String TAG = "MarkdownView";
    private final Executor ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MarkdownView-io");
        t.setDaemon(true);
        return t;
    });

    public MarkdownView(Context context) {
        super(patchContext(context));
        initSettings();
    }

    public MarkdownView(Context context, AttributeSet attrs) {
        super(patchContext(context), attrs);
        initSettings();
    }

    private void initSettings() {
        WebSettings settings = getSettings();
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptEnabled(false);
    }

    public static void closeOnMissingWebView(Activity host, Exception exception) {
        if (exception.getMessage() != null && exception.getMessage().contains("Failed to load WebView provider: No WebView installed")) {
            FLog.e(TAG, "onCreate: Failed to load WebView (Appcenter PUB #49494606u)", exception);
            Toast.makeText(host.getApplicationContext(), host.getString(R.string.install_webview_retry), Toast.LENGTH_LONG).show();
            host.finish();
        } else {
            throw new RuntimeException(exception);
        }
    }

    private static Context patchContext(Context context) {
        // TODO: Only affects appcompat 1.1.x, remove with 1.2.x
        //       https://stackoverflow.com/questions/41025200/android-view-inflateexception-error-inflating-class-android-webkit-webview/58131421
        if (Build.VERSION.SDK_INT == 22 || Build.VERSION.SDK_INT == 23) {
            return context.createConfigurationContext(new Configuration());
        }
        return context;
    }

    public void loadAsset(String path) {
        // RC-39: migrated off AsyncTask to a background executor; the WebView is updated on the
        // UI thread via View.post(). LoadMarkdownAsset used to be a static nested AsyncTask.
        final String assetName = path;
        final int background = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface);
        final int foreground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface);
        final int link = MaterialColors.getColor(this, R.attr.colorPrimary);
        setBackgroundColor(background);
        final WebView webView = this;
        ioExecutor.execute(() -> {
            Context context = webView.getContext();
            AssetManager assetManager = context.getAssets();
            String html;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(assetManager.open(assetName)))) {
                StringBuilder markdown = new StringBuilder(4096);
                String line;
                while ((line = br.readLine()) != null) {
                    markdown.append(line).append('\n');
                }
                html = new MarkdownProcessor().markdown(markdown.toString());
            } catch (IOException e) {
                FLog.e(TAG, "Could not load asset ", e);
                html = null;
            }
            final String result = html;
            webView.post(() -> {
                if (null == result) {
                    webView.loadUrl("about:blank");
                } else {
                    String document = String.format(Locale.ROOT,
                            "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                            + "<style>body{background:#%06X;color:#%06X;font:16px/1.55 sans-serif;margin:20px;overflow-wrap:anywhere}"
                            + "a{color:#%06X}pre{white-space:pre-wrap;overflow-wrap:anywhere}img{max-width:100%%}</style>"
                            + "</head><body>%s</body></html>",
                            background & 0xffffff, foreground & 0xffffff, link & 0xffffff, result);
                    webView.loadDataWithBaseURL("local://", document, "text/html", "UTF-8", null);
                }
            });
        });
    }
}
