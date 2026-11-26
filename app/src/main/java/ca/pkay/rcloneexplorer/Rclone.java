package ca.pkay.rcloneexplorer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.preference.PreferenceManager;

import de.schuelken.cloudbridge.cache.CacheManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.net.InetAddress;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import ca.pkay.rcloneexplorer.Database.json.Exporter;
import ca.pkay.rcloneexplorer.Database.json.Importer;
import ca.pkay.rcloneexplorer.Database.json.SharedPreferencesBackup;
import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.Items.FilterEntry;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.Items.SyncDirectionObject;
import ca.pkay.rcloneexplorer.notifications.support.StatusObject;
import ca.pkay.rcloneexplorer.rclone.Provider;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.ProxyPasswordStore;
import ca.pkay.rcloneexplorer.util.SecretRedactor;
import ca.pkay.rcloneexplorer.util.SyncLog;
import io.github.x0b.safdav.SafAccessProvider;
import io.github.x0b.safdav.file.SafConstants;

public class Rclone {

    private static final String TAG = "Rclone";
    public static final int SYNC_DIRECTION_LOCAL_TO_REMOTE = 1;
    public static final int SYNC_DIRECTION_REMOTE_TO_LOCAL = 2;
    public static final int SERVE_PROTOCOL_HTTP = 1;
    public static final int SERVE_PROTOCOL_WEBDAV = 2;
    public static final int SERVE_PROTOCOL_FTP = 3;
    public static final int SERVE_PROTOCOL_DLNA = 4;

    public static final String RCLONE_CONFIG_NAME_KEY = "rclone_remote_name";
    private static volatile Boolean isCompatible;
    private Context context;
    private String rclone;
    private String rcloneConf;
    // RC-38: cache of the parsed `rclone config dump` JSON. Validated against the rclone.conf
    // file's mtime/length on every read so per-instance caches self-invalidate when another
    // Rclone instance (e.g. a config dialog) mutates the config. Volatile for cross-thread visibility.
    private volatile JSONObject cachedRemotesConfig;
    private volatile long cachedConfMtime;
    private volatile long cachedConfLength;

    public Rclone(Context context) {
        this.context = context;
        this.rclone = context.getApplicationInfo().nativeLibraryDir + "/librclone.so";
        this.rcloneConf = context.getFilesDir().getPath() + "/rclone.conf";
    }

    private String[] createCommand(ArrayList<String> args) {
        String[] command = new String[args.size()];
        for (int i = 0; i < args.size(); i++) {
            command[i]= args.get(i);
        }
        return command;
    }
    private String[] createCommand(String ...args) {
        boolean loggingEnabled = PreferenceManager
                .getDefaultSharedPreferences(context)
                .getBoolean(context.getString(R.string.pref_key_logs), false);
        ArrayList<String> command = new ArrayList<>();

        command.add(rclone);
        command.add("--config");
        command.add(rcloneConf);

        if(loggingEnabled) {
            command.add("-vvv");
        }

        command.addAll(Arrays.asList(args));
        return createCommand(command);
    }

    private String[] createCommandWithOptions(String ...args) {
        ArrayList<String> arguments = new ArrayList<String>(Arrays.asList(args));
        return createCommandWithOptions(arguments);
    }

    private String[] createCommandWithOptions(ArrayList<String> args) {
        boolean loggingEnabled = PreferenceManager
                .getDefaultSharedPreferences(context)
                .getBoolean(context.getString(R.string.pref_key_logs), false);
        ArrayList<String> command = new ArrayList<>();

        String cachePath = context.getCacheDir().getAbsolutePath();

        command.add(rclone);
        command.add("--cache-chunk-path");
        command.add(cachePath);
        command.add("--cache-db-path");
        command.add(cachePath);

        /*

        This fixed some bug. I dont know which one, but it breaks transfer of big files where
        the checksum needs to be calculated.
        This was probably due to some timeout for connecting misconfigured remotes.

        command.add("--low-level-retries");
        command.add("2");

        command.add("--timeout");
        command.add("5s");
        command.add("--contimeout");
        command.add("5s");
        */

        command.add("--config");
        command.add(rcloneConf);

        if(loggingEnabled) {
            command.add("-vvv");
        }

        command.addAll(args);
        return createCommand(command);
    }

    private String getTransfers() {
        return getTransfers(null);
    }

    private String getTransfers(String override) {
        String raw = (override != null && !override.isEmpty())
                ? override
                : PreferenceManager
                        .getDefaultSharedPreferences(context)
                        .getString(context.getString(R.string.pref_key_transfers), "4");
        return SharedPreferencesBackup.sanitizeTransfers(raw);
    }

    public String[] getRcloneEnv(String... overwriteOptions) {
        Map<String, String> envMap = new LinkedHashMap<>();
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);

        boolean proxyEnabled = pref.getBoolean(context.getString(R.string.pref_key_use_proxy), false);
        if (proxyEnabled) {
            String noProxy = pref.getString(context.getString(R.string.pref_key_no_proxy_hosts), "localhost");
            String protocol = pref.getString(context.getString(R.string.pref_key_proxy_protocol), "http");
            String host = pref.getString(context.getString(R.string.pref_key_proxy_host), "localhost");
            String user = pref.getString(context.getString(R.string.pref_key_proxy_username), "");
            String pass = ProxyPasswordStore.getDecryptedPassword(context);
            int port = pref.getInt(context.getString(R.string.pref_key_proxy_port), 8080);
            String url = ProxyPasswordStore.buildProxyUrl(protocol, host, port, user, pass);
            // per https://golang.org/pkg/net/http/#ProxyFromEnvironment
            envMap.put("http_proxy", url);
            envMap.put("https_proxy", url);
            envMap.put("no_proxy", noProxy);
        }

        // if TMPDIR is not set, golang uses /data/local/tmp which is only
        // accessible for the shell user
        String tmpDir = context.getCacheDir().getAbsolutePath();
        envMap.put("TMPDIR", tmpDir);

        // ignore chtimes errors
        // ref: https://github.com/rclone/rclone/issues/2446
        envMap.put("RCLONE_LOCAL_NO_SET_MODTIME", "true");

        // The pre-built linux rclone binaries do not know how to find the Android certificate store.
        // We set SSL_CERT_DIR to Android's native certificate store path.
        envMap.put("SSL_CERT_DIR", "/system/etc/security/cacerts");

        envMap.put("RCLONE_DNS_SERVERS", getDnsServers());

        if (overwriteOptions != null) {
            for (String overwrite : overwriteOptions) {
                if (overwrite == null) {
                    continue;
                }
                int eq = overwrite.indexOf('=');
                if (eq > 0) {
                    envMap.put(overwrite.substring(0, eq), overwrite.substring(eq + 1));
                }
            }
        }
        ArrayList<String> environmentValues = new ArrayList<>(envMap.size());
        for (Map.Entry<String, String> entry : envMap.entrySet()) {
            environmentValues.add(entry.getKey() + "=" + entry.getValue());
        }
        return environmentValues.toArray(new String[0]);
    }

    @VisibleForTesting
    static List<String> buildSafDavEnvOverrides(
            @NonNull Collection<String> remoteNames,
            @NonNull String serverUrl,
            @NonNull String authHeader) {
        ArrayList<String> overrides = new ArrayList<>();
        for (String name : remoteNames) {
            if (name == null || name.isEmpty()) {
                continue;
            }
            // Rclone ConfigToEnv keeps '-' in the remote section name intact.
            String prefix = "RCLONE_CONFIG_" + name.toUpperCase(Locale.ROOT) + "_";
            overrides.add(prefix + "URL=" + serverUrl);
            overrides.add(prefix + "HEADERS=Authorization," + authHeader);
            overrides.add(prefix + "USER=");
            overrides.add(prefix + "PASS=");
        }
        return overrides;
    }

    private String[] ensureSafDavAndGetEnv(@Nullable RemoteItem... remotes) throws IOException {
        return ensureSafDavAndGetEnv(remotes, Collections.emptyList());
    }

    private String[] ensureSafDavAndGetEnv(
            @Nullable RemoteItem[] remotes,
            @NonNull Collection<String> extraEnv) throws IOException {
        Set<String> safRemoteNames = new LinkedHashSet<>();
        boolean needsSafDav = false;
        if (remotes != null) {
            for (RemoteItem remote : remotes) {
                if (remote != null && isSafDavRemote(remote)) {
                    needsSafDav = true;
                    safRemoteNames.add(SafConstants.SAF_REMOTE_NAME);
                    if (remote.isRemoteType(RemoteItem.SAFW) && remote.getName() != null) {
                        safRemoteNames.add(remote.getName());
                    }
                    String target = resolveWrappedSafTargetName(remote);
                    if (target != null && !target.isEmpty()) {
                        safRemoteNames.add(target);
                    }
                }
            }
        }
        return finalizeSafDavEnv(needsSafDav, safRemoteNames, extraEnv);
    }

    private String[] ensureSafDavAndGetEnvForName(
            @Nullable String remoteName,
            @NonNull Collection<String> extraEnv) throws IOException {
        Set<String> safRemoteNames = new LinkedHashSet<>();
        boolean needsSafDav = false;
        if (remoteName != null && !remoteName.isEmpty()) {
            if (SafConstants.SAF_REMOTE_NAME.equals(remoteName)) {
                needsSafDav = true;
                safRemoteNames.add(remoteName);
            } else {
                RemoteItem resolved = getRemoteItemFromName(remoteName);
                if (resolved != null && isSafDavRemote(resolved)) {
                    return ensureSafDavAndGetEnv(new RemoteItem[]{resolved}, extraEnv);
                }
            }
        }
        return finalizeSafDavEnv(needsSafDav, safRemoteNames, extraEnv);
    }

    private String[] finalizeSafDavEnv(
            boolean needsSafDav,
            @NonNull Set<String> safRemoteNames,
            @NonNull Collection<String> extraEnv) throws IOException {
        ArrayList<String> overrides = new ArrayList<>();
        if (needsSafDav) {
            io.github.x0b.safdav.SafDAVServer server = SafAccessProvider.getServer(context);
            String serverUrl = server != null ? server.getBaseUrl() : SafAccessProvider.getServerUrl();
            String authHeader = SafAccessProvider.getSessionAuthHeader();
            if (serverUrl == null || authHeader == null) {
                throw new IOException("SAF WebDAV bridge unavailable");
            }
            overrides.addAll(buildSafDavEnvOverrides(
                    safRemoteNames, serverUrl, authHeader));
        }
        overrides.addAll(extraEnv);
        return getRcloneEnv(overrides.toArray(new String[0]));
    }

    @Nullable
    private String resolveWrappedSafTargetName(@NonNull RemoteItem remote) {
        if (!remote.isAlias() && !remote.isCrypt() && !remote.isCache()) {
            return null;
        }
        JSONObject config = getCachedRemotesConfig();
        if (config == null || remote.getName() == null) {
            return null;
        }
        String current = remote.getName();
        for (int depth = 0; depth < 8; depth++) {
            JSONObject obj = config.optJSONObject(current);
            if (obj == null) {
                return null;
            }
            String type = remoteType(obj);
            if (SafConstants.SAF_REMOTE_NAME.equals(type)) {
                return current;
            }
            if (!"crypt".equals(type) && !"alias".equals(type) && !"cache".equals(type)) {
                return null;
            }
            String target = obj.optString("remote");
            int colon = target.indexOf(':');
            if (colon <= 0) {
                return null;
            }
            current = target.substring(0, colon);
        }
        return null;
    }

    private String getDnsServers() {
        String fallback = "8.8.8.8:53,8.8.4.4:53";
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return fallback;
            Network network = cm.getActiveNetwork();
            if (network == null) return fallback;
            LinkProperties lp = cm.getLinkProperties(network);
            if (lp == null) return fallback;
            StringBuilder sb = new StringBuilder();
            for (InetAddress dns : lp.getDnsServers()) {
                if (sb.length() > 0) sb.append(",");
                sb.append(dns.getHostAddress()).append(":53");
            }
            return sb.length() > 0 ? sb.toString() : fallback;
        } catch (Exception e) {
            FLog.e(TAG, "Failed to detect DNS servers, using fallback", e);
            return fallback;
        }
    }

    // Only commands whose stderr has no other consumer use this collector.
    private static final java.util.Map<Process, DiagnosticCollector> diagnostics =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static class DiagnosticCollector extends Thread {
        private final Process process;
        private final InputStream stream;
        @Nullable private final StatusObject statusObject;
        private final StringBuilder warnings = new StringBuilder();
        private final StringBuilder output = new StringBuilder();
        DiagnosticCollector(@Nullable Context context, Process process) {
            this.process = process;
            this.stream = process.getErrorStream();
            this.statusObject = context != null ? new StatusObject(context.getApplicationContext()) : null;
            setDaemon(true);
        }
        @Override public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (statusObject != null && trimmed.startsWith("{") && trimmed.contains("\"stats\"")) {
                        try {
                            JSONObject logline = new JSONObject(trimmed);
                            if (logline.has("stats")) {
                                statusObject.parseLoglineToStatusObject(logline);
                                ProcessRegistry.updateFromStatusObject(process, null, statusObject);
                            }
                        } catch (JSONException ignored) { }
                    }
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (warnings.length() < 8192 && RcloneDiagnostics.isSharedClientRetirement(lower))
                        warnings.append(line, 0, Math.min(line.length(), 8192 - warnings.length())).append('\n');
                    if (line.length() > 262144) line = line.substring(line.length() - 262144);
                    output.append(line).append('\n');
                    if (output.length() > 262144) output.delete(0, output.length() - 262144);
                }
            } catch (IOException ignored) { }
        }
    }

    public void collectDiagnostics(Process process) {
        if (process == null) return;
        DiagnosticCollector collector = new DiagnosticCollector(context, process);
        diagnostics.put(process, collector);
        collector.start();
    }

    public void discardDiagnostics(Process process) { if (process != null) diagnostics.remove(process); }

    public void logErrorOutput(Process process) {
        if (process == null) return;
        final int exit;
        try { exit = process.exitValue(); } catch (IllegalThreadStateException stillRunning) { return; }
        DiagnosticCollector collector = diagnostics.remove(process);
        String output;
        if (collector != null) {
            try { collector.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            output = collector.isAlive() ? "" : collector.warnings.toString() + collector.output.toString();
        } else {
            if (exit == 0) return;
            StringBuilder buffer = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null && buffer.length() < 262144) buffer.append(line).append('\n');
            } catch (IOException ignored) { /* Report the exit status if stderr is unavailable. */ }
            output = buffer.toString();
        }
        if (!PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(context.getString(R.string.pref_key_logs), false)) return;
        // Standard shell signal statuses represent cancellation, not a completed failure.
        if (RcloneDiagnostics.isCancellation(exit)) return;
        if (exit != 0) {
            String message = context.getString(R.string.rclone_exit_status, exit);
            if (!output.trim().isEmpty()) message += "\n" + output;
            SyncLog.error(context, context.getString(R.string.rclone_operation), message);
        } else {
            java.util.LinkedHashSet<String> warnings = new java.util.LinkedHashSet<>();
            for (String line : output.split("\\r?\\n")) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (RcloneDiagnostics.isSharedClientRetirement(lower)) warnings.add(line);
            }
            if (!warnings.isEmpty()) SyncLog.info(context, context.getString(R.string.rclone_operation), android.text.TextUtils.join("\n", warnings));
        }
    }

    @Nullable
    public List<FileItem> getDirectoryContent(RemoteItem remote, String path, boolean startAtRoot) {
        if (remote == null) {
            return null;
        }
        String remoteAndPath = remote.getName() + ":";
        if (startAtRoot) {
            remoteAndPath += "/";
        }
        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isCrypt() && !remote.isAlias() && !remote.isCache())) {
            remoteAndPath += getLocalRemotePathPrefix(remote, context) + "/";
        }
        if (path.compareTo("//" + remote.getName()) != 0) {
            remoteAndPath += path;
        }
        String[] env;
        try {
            env = ensureSafDavAndGetEnv(remote);
        } catch (IOException e) {
            FLog.e(TAG, "Cannot start SAF WebDAV bridge", e);
            return null;
        }

        String[] command;
        if (remote.isRemoteType(RemoteItem.LOCAL) || remote.isPathAlias()) {
            // ignore .android_secure errors
            // ref: https://github.com/rclone/rclone/issues/3179
            command = createCommandWithOptions("--ignore-errors", "lsjson", "--", remoteAndPath);
        } else {
            command = createCommandWithOptions("lsjson", "--", remoteAndPath);
        }
        JSONArray results;
        Process process = null;
        try {
            process = getRuntimeProcess(command, env);
            collectDiagnostics(process);

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            process.waitFor();
            // For local/alias remotes, exit(6) is not a fatal error.
            if (process.exitValue() != 0 && (process.exitValue() != 6 || !remote.isRemoteType(RemoteItem.LOCAL, RemoteItem.ALIAS))) {
                logErrorOutput(process);
                return null;
            }

            if (process.exitValue() == 0) logErrorOutput(process);
            else diagnostics.remove(process); // Accepted local exit status.
            String outputStr = output.toString();
            results = new JSONArray(outputStr);
            if (remote.isRemoteType(RemoteItem.INTERNXT)) {
                ca.pkay.rcloneexplorer.notifications.AppErrorNotificationManager
                        .clearSessionExpiredNotification(context, remote.getName());
            }

        } catch (InterruptedException e) {
            if (process != null) process.destroy();
            Thread.currentThread().interrupt();
            FLog.d(TAG, "getDirectoryContent: Aborted refreshing folder");
            return null;
        } catch (IOException | JSONException e) {
            logErrorOutput(process);
            FLog.e(TAG, "getDirectoryContent: Could not get folder content", e);
            return null;
        }

        return parseLsJsonArray(remote, path, results, startAtRoot, Integer.MAX_VALUE, process);
    }

    public interface RecursiveListProgressListener {
        void onProgress(int itemsFound, long bytesFound, @Nullable String currentPath);
    }

    @Nullable
    public List<FileItem> getDirectoryContentRecursive(
            RemoteItem remote,
            String path,
            boolean startAtRoot,
            int maxDepth,
            int maxItems,
            @Nullable java.util.concurrent.atomic.AtomicReference<Process> processOut) {
        return getDirectoryContentRecursive(remote, path, startAtRoot, maxDepth, maxItems, processOut, null);
    }

    @Nullable
    public List<FileItem> getDirectoryContentRecursive(
            RemoteItem remote,
            String path,
            boolean startAtRoot,
            int maxDepth,
            int maxItems,
            @Nullable java.util.concurrent.atomic.AtomicReference<Process> processOut,
            @Nullable RecursiveListProgressListener progressListener) {
        if (remote == null) {
            return null;
        }
        String remoteAndPath = buildRemoteAndPath(remote, path, startAtRoot);
        String[] env;
        try {
            env = ensureSafDavAndGetEnv(remote);
        } catch (IOException e) {
            FLog.e(TAG, "Cannot start SAF WebDAV bridge", e);
            return null;
        }

        ArrayList<String> args = new ArrayList<>();
        if (remote.isRemoteType(RemoteItem.LOCAL) || remote.isPathAlias()) {
            args.add("--ignore-errors");
        }
        args.add("lsjson");
        args.add("-R");
        args.add("--fast-list");
        if (maxDepth > 0) {
            args.add("--max-depth");
            args.add(String.valueOf(maxDepth));
        }
        args.add("--");
        args.add(remoteAndPath);

        String[] command = createCommandWithOptions(args);
        Process process = null;
        try {
            process = getRuntimeProcess(command, env);
            collectDiagnostics(process);
            if (processOut != null) {
                processOut.set(process);
            }
            if (progressListener != null) {
                progressListener.onProgress(0, 0L, null);
            }
            List<FileItem> fileItemList = new ArrayList<>();
            String prefix = (path == null || path.compareTo("//" + remote.getName()) == 0 || path.isEmpty())
                    ? ""
                    : path + "/";
            boolean hitMaxItems = false;
            int itemsFound = 0;
            long bytesFound = 0L;
            long lastProgressMs = 0L;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("{")) {
                        String jsonText = trimmed.endsWith(",")
                                ? trimmed.substring(0, trimmed.length() - 1)
                                : trimmed;
                        JSONObject entryJson = new JSONObject(jsonText);
                        FileItem item = parseLsJsonObject(remote, prefix, entryJson, startAtRoot);
                        fileItemList.add(item);
                        itemsFound++;
                        bytesFound += Math.max(0L, item.getSize());
                        long now = System.currentTimeMillis();
                        if (progressListener != null && (itemsFound == 1 || now - lastProgressMs >= 200L)) {
                            lastProgressMs = now;
                            progressListener.onProgress(itemsFound, bytesFound, entryJson.optString("Path", null));
                        }
                        if (maxItems > 0 && fileItemList.size() >= maxItems) {
                            hitMaxItems = true;
                            process.destroy();
                            break;
                        }
                    }
                }
            }
            if (progressListener != null && itemsFound > 0) {
                progressListener.onProgress(itemsFound, bytesFound, null);
            }
            process.waitFor();
            if (hitMaxItems) {
                discardDiagnostics(process);
                return fileItemList;
            }
            if (process.exitValue() != 0 && (process.exitValue() != 6 || !remote.isRemoteType(RemoteItem.LOCAL, RemoteItem.ALIAS))) {
                logErrorOutput(process);
                return null;
            }
            if (process.exitValue() == 0) {
                logErrorOutput(process);
            } else {
                discardDiagnostics(process);
            }
            return fileItemList;
        } catch (InterruptedException e) {
            if (process != null) process.destroy();
            Thread.currentThread().interrupt();
            logErrorOutput(process);
            return null;
        } catch (IOException | JSONException e) {
            if (process != null) {
                process.destroy();
                discardDiagnostics(process);
            }
            FLog.e(TAG, "getDirectoryContentRecursive: failed", e);
            return null;
        } finally {
            if (processOut != null) {
                processOut.set(null);
            }
        }
    }

    private String buildRemoteAndPath(RemoteItem remote, String path, boolean startAtRoot) {
        String remoteAndPath = remote.getName() + ":";
        if (startAtRoot) {
            remoteAndPath += "/";
        }
        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isCrypt() && !remote.isAlias() && !remote.isCache())) {
            remoteAndPath += getLocalRemotePathPrefix(remote, context) + "/";
        }
        if (path != null && !path.isEmpty() && path.compareTo("//" + remote.getName()) != 0) {
            remoteAndPath += path;
        }
        return remoteAndPath;
    }

    @Nullable
    private List<FileItem> parseLsJsonArray(
            RemoteItem remote,
            String basePath,
            JSONArray results,
            boolean startAtRoot,
            int maxItems,
            Process process) {
        List<FileItem> fileItemList = new ArrayList<>();
        int count = Math.min(results.length(), maxItems > 0 ? maxItems : results.length());
        String prefix = (basePath == null || basePath.compareTo("//" + remote.getName()) == 0 || basePath.isEmpty())
                ? ""
                : basePath + "/";
        for (int i = 0; i < count; i++) {
            try {
                JSONObject jsonObject = results.getJSONObject(i);
                fileItemList.add(parseLsJsonObject(remote, prefix, jsonObject, startAtRoot));
            } catch (JSONException e) {
                logErrorOutput(process);
                FLog.e(TAG, "parseLsJsonArray: Could not decode JSON", e);
                return null;
            }
        }
        return fileItemList;
    }

    private FileItem parseLsJsonObject(
            RemoteItem remote,
            String prefix,
            JSONObject jsonObject,
            boolean startAtRoot) throws JSONException {
        String filePath = prefix + jsonObject.getString("Path");
        String fileName = jsonObject.getString("Name");
        long fileSize = jsonObject.getLong("Size");
        String fileModTime = jsonObject.getString("ModTime");
        boolean fileIsDir = jsonObject.getBoolean("IsDir");
        String mimeType = jsonObject.getString("MimeType");

        if (remote.isCrypt()) {
            String extension = FileItem.extractExtension(fileName);
            if (!extension.isEmpty()) {
                String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                if (type != null) {
                    mimeType = type;
                }
            }
        }

        return new FileItem(remote, filePath, fileName, fileSize, fileModTime, mimeType, fileIsDir, startAtRoot);
    }

    public List<RemoteItem> getRemotes() {
        // RC-38: avoid spawning an rclone process (and parsing its JSON) on every UI-thread call.
        // The expensive config-dump result is cached and only re-read after the config is mutated
        // via config()/deleteRemote() or an explicit invalidateRemotesCache(). Pin/favorite state
        // is re-applied from SharedPreferences on every call so it stays fresh.
        JSONObject remotesJSON = getCachedRemotesConfig();
        if (remotesJSON == null) {
            return new ArrayList<>();
        }

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> pinnedRemotes = sharedPreferences.getStringSet(context.getString(R.string.shared_preferences_pinned_remotes), new HashSet<>());
        Set<String> favoriteRemotes = sharedPreferences.getStringSet(context.getString(R.string.shared_preferences_drawer_pinned_remotes), new HashSet<>());

        List<RemoteItem> remoteItemList = new ArrayList<>();
        Iterator<String> iterator = remotesJSON.keys();
        while (iterator.hasNext()) {
            String key = iterator.next();
            try {
                JSONObject remoteJSON = new JSONObject(remotesJSON.get(key).toString());
                String type = remoteType(remoteJSON);
                if (type.trim().isEmpty()) {
                    Toast.makeText(context, context.getResources().getString(R.string.error_retrieving_remote, key), Toast.LENGTH_SHORT).show();
                    continue;
                }
                RemoteItem newRemote = new RemoteItem(key, type);
                if (type.equals("crypt") || type.equals("alias") || type.equals("cache")) {
                    newRemote = getRemoteType(remotesJSON, newRemote, key, 8, false);
                    if (newRemote == null) {
                        Toast.makeText(context, context.getResources().getString(R.string.error_retrieving_remote, key), Toast.LENGTH_SHORT).show();
                        continue;
                    }
                }

                if (pinnedRemotes.contains(newRemote.getName())) {
                    newRemote.pin(true);
                }

                if (favoriteRemotes.contains(newRemote.getName())) {
                    newRemote.setDrawerPinned(true);
                }

                remoteItemList.add(newRemote);
            } catch (JSONException e) {
                FLog.e(TAG, "getRemotes: error decoding remotes", e);
                return new ArrayList<>();
            }
        }

        return remoteItemList;
    }

    /** Returns the cached rclone config dump, fetching and caching it on first use. */
    private JSONObject getCachedRemotesConfig() {
        File confFile = new File(rcloneConf);
        long mtime = confFile.lastModified();
        long length = confFile.length();
        synchronized (this) {
            if (cachedRemotesConfig != null && cachedConfMtime == mtime && cachedConfLength == length) {
                return cachedRemotesConfig;
            }
        }
        String[] command = createCommand("config", "dump");
        StringBuilder output = new StringBuilder();
        Process process = null;
        try {
            process = getRuntimeProcess(command);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }
            process.waitFor();
            if (process.exitValue() != 0) {
                Toast.makeText(context, context.getString(R.string.error_getting_remotes), Toast.LENGTH_SHORT).show();
                logErrorOutput(process);
                return null;
            }
            JSONObject parsed = new JSONObject(output.toString());
            synchronized (this) {
                cachedRemotesConfig = parsed;
                cachedConfMtime = mtime;
                cachedConfLength = length;
            }
            return parsed;
        } catch (IOException | InterruptedException | JSONException e) {
            logErrorOutput(process);
            FLog.e(TAG, "getRemotes: error retrieving remotes", e);
            return null;
        }
    }

    /** Drop the cached config dump so the next getRemotes() re-reads it from rclone. */
    public void invalidateRemotesCache() {
        cachedRemotesConfig = null;
    }

    public RemoteItem getRemoteItemFromName(String remoteName) {
        List<RemoteItem> remoteItemList = getRemotes();
        for (RemoteItem remoteItem : remoteItemList) {
            if (remoteItem.getName().equals(remoteName)) {
                return remoteItem;
            }
        }
        return null;
    }


    private Process getRuntimeProcess(String[] command) throws IOException {
        return getRuntimeProcess(command, new String[0]);
    }

    private Process getRuntimeProcess(String[] command, String[] env) throws IOException {
        Process process = Runtime.getRuntime().exec(command, env);
        ProcessRegistry.register(process, command);
        return process;
    }

    private static String remoteType(JSONObject config) {
        return remoteType(config.optString("type"), config.optString("url"));
    }

    static String remoteType(String type, String url) {
        if ("webdav".equals(type) && url.startsWith(SafConstants.SAF_REMOTE_URL)) {
            return SafConstants.SAF_REMOTE_NAME;
        }
        return type;
    }

    boolean isSafDavRemote(@Nullable RemoteItem remote) {
        if (remote == null) {
            return false;
        }
        if (remote.isRemoteType(RemoteItem.SAFW)) {
            return true;
        }
        // Resolve the transport while retaining the wrapper's existing display and provider type.
        if (remote.isRemoteType(RemoteItem.WEBDAV) && (remote.isAlias() || remote.isCrypt() || remote.isCache())) {
            JSONObject config = getCachedRemotesConfig();
            RemoteItem resolved = config == null ? null
                    : getRemoteType(config, new RemoteItem(remote.getName(), "webdav"), remote.getName(), 8, true);
            return resolved != null && resolved.isRemoteType(RemoteItem.SAFW);
        }
        return false;
    }

    @Nullable
    private RemoteItem getRemoteType(JSONObject remotesJSON, RemoteItem remoteItem, String remoteName,
                                     int maxDepth, boolean detectSafDav) {
        Iterator<String> iterator = remotesJSON.keys();

        while (iterator.hasNext()) {
            String key = iterator.next();

            if (!key.equals(remoteName)) {
                continue;
            }

            try {
                JSONObject remoteJSON = new JSONObject(remotesJSON.get(key).toString());
                String type = detectSafDav ? remoteType(remoteJSON) : remoteJSON.optString("type");
                if (type.trim().isEmpty()) {
                    return null;
                }

                boolean recurse = true;
                switch (type) {
                    case "crypt":
                        remoteItem.setIsCrypt(true);
                        break;
                    case "alias":
                        remoteItem.setIsAlias(true);
                        break;
                    case "cache":
                        remoteItem.setIsCache(true);
                        break;
                    default:
                        recurse = false;
                }

                if (recurse && maxDepth > 0) {
                    String remote = remoteJSON.optString("remote");
                    if (remote.trim().isEmpty() || (!remote.contains(":") && !remote.startsWith("/"))) {
                        return null;
                    }

                    if (remote.startsWith("/")) { // local remote
                        remoteItem.setType("local");
                        remoteItem.setIsPathAlias(true);
                        return remoteItem;
                    } else {
                        int index = remote.indexOf(":");
                        remote = remote.substring(0, index);
                        return getRemoteType(remotesJSON, remoteItem, remote, maxDepth - 1, detectSafDav);
                    }
                }
                remoteItem.setType(type);
                return remoteItem;
            } catch (JSONException e) {
                FLog.e(TAG, "getRemoteType: error decoding remote type", e);
            }
        }

        return null;
    }

    @Nullable
    public Process configCreate(List<String> options) {
        // https://rclone.org/commands/rclone_config_create/
        // Place --obscure BEFORE "--" so values starting with "-" are never parsed as flags.
        // Keep configCreate interactive (without --non-interactive) for OauthHelper.createOptionsWithOauth.
        ArrayList<String> args = new ArrayList<>();
        args.add("--obscure");
        args.add("--");
        args.addAll(options);
        return config("create", args);
    }

    /**
     * Like configCreate but passes --non-interactive and --no-output so the backend's Config()
     * function is invoked but exits immediately returning no JSON questions. Only the
     * key/value pairs are saved to rclone.conf. Use this when a separate `config reconnect`
     * step will handle the interactive auth.
     */
    public Process configCreateNoInteract(List<String> options) {
        ArrayList<String> args = new ArrayList<>();
        args.add("--obscure");
        args.add("--non-interactive");
        args.add("--no-output");
        args.add("--");
        args.addAll(options);
        return config("create", args);
    }

    @Nullable
    public Process configUpdate(List<String> options) {
        ArrayList<String> args = new ArrayList<>();
        args.add("--obscure");
        args.add("--non-interactive");
        args.add("--");
        args.addAll(options);
        return config("update", args);
    }
    
    public Process config(String task, List<String> options) {
        invalidateRemotesCache();
        String[] command = createCommand("config", task);
        String[] opt = options.toArray(new String[0]);
        String[] commandWithOptions = new String[command.length + options.size()];

        System.arraycopy(command, 0, commandWithOptions, 0, command.length);

        System.arraycopy(opt, 0, commandWithOptions, command.length, opt.length);

        String[] env = getRcloneEnv();
        try {
            return getRuntimeProcess(commandWithOptions, env);
        } catch (IOException e) {
            FLog.e(TAG, "configCreate: error starting rclone", e);
            return null;
        }
    }

    @Nullable
    public HashMap<String, String> getConfig(String name) {
        String[] command = createCommand("config", "dump");
        StringBuilder output = new StringBuilder();
        Process process;
        JSONObject configs = new JSONObject();

        HashMap<String, String> options = new HashMap<>();

        try {
            process = getRuntimeProcess(command);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            process.waitFor();
            if (process.exitValue() != 0) {
                Toast.makeText(context, context.getString(R.string.error_getting_config), Toast.LENGTH_SHORT).show();
                logErrorOutput(process);
            }

            configs = new JSONObject(output.toString());
        } catch (IOException | InterruptedException | JSONException e) {
            FLog.e(TAG, "getRemotes: error retrieving remotes", e);
        }

        if (configs == null) {
            return options;
        }
        JSONObject selectedConfig = configs.optJSONObject(name);
        if (selectedConfig == null) {
            return options;
        }
        Iterator<String> keys = selectedConfig.keys();

        while(keys.hasNext()) {
            String key = keys.next();
            options.put(key,  selectedConfig.optString(key));
        }

        options.put(RCLONE_CONFIG_NAME_KEY,  name);
        return options;
        
    }

    public Process configInteractive() throws IOException {
        String[] command = createCommand("config");
        String[] environment = getRcloneEnv();
        return getRuntimeProcess(command, environment);
    }

    public void deleteRemote(String remoteName) {
        if (remoteName == null || remoteName.startsWith("-")) {
            FLog.e(TAG, "deleteRemote: rejected invalid remote name");
            return;
        }
        invalidateRemotesCache();
        CacheManager.getInstance(context).clearRemoteCache(remoteName);
        String[] command = createCommandWithOptions("config", "delete", "--", remoteName);
        Process process;

        try {
            process = getRuntimeProcess(command);
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "deleteRemote: error starting rclone", e);
        }
    }

    public String obscure(String pass) {
        String[] command = createCommand("obscure", "--", pass);

        Process process;
        try {
            process = getRuntimeProcess(command);
            process.waitFor();
            if (process.exitValue() != 0) {
                return null;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                return reader.readLine();
            }
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "obscure: error starting rclone", e);
            // TODO: guard callers against null result
            return null;
        }
    }

    public Process serve(int protocol, int port, boolean allowRemoteAccess, @Nullable String user,
                         @Nullable String password, @NonNull RemoteItem remote, @Nullable String servePath,
                         @Nullable String baseUrl) {
        return serve(protocol, port, allowRemoteAccess, user, password, remote, servePath, baseUrl, true, null, null);
    }

    public Process serve(int protocol, int port, boolean allowRemoteAccess, @Nullable String user,
                         @Nullable String password, @NonNull RemoteItem remote, @Nullable String servePath,
                         @Nullable String baseUrl, boolean readOnly,
                         @Nullable File certFile, @Nullable File keyFile) {
        String remoteName = remote.getName();
        String localRemotePath = (remote.isRemoteType(RemoteItem.LOCAL)) ? getLocalRemotePathPrefix(remote, context) + "/" : "";
        String safeServePath = servePath != null ? servePath : "";
        String path = (safeServePath.compareTo("//" + remoteName) == 0)
                ? remoteName + ":" + localRemotePath
                : remoteName + ":" + localRemotePath + safeServePath;

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isLoggingEnabled = sharedPreferences.getBoolean(context.getString(R.string.pref_key_logs), false);
        String serveLogPath = null;
        if (isLoggingEnabled) {
            File logsDir = new File(context.getFilesDir(), "logs");
            if (!logsDir.exists()) {
                logsDir.mkdirs();
            }
            serveLogPath = new File(logsDir, "serve.log").getAbsolutePath();
        }

        List<String> serveArgs = buildServeArgs(
                protocol, port, allowRemoteAccess, user, password, path, baseUrl,
                getTransfers(), readOnly, certFile, keyFile, serveLogPath);
        List<String> credEnv = buildServeCredentialEnv(protocol, user, password);

        String[] command = createCommandWithOptions(new ArrayList<>(serveArgs));
        try {
            String[] env = ensureSafDavAndGetEnv(new RemoteItem[]{remote}, credEnv);
            return getRuntimeProcess(command, env);
        } catch (IOException e) {
            FLog.e(TAG, "serve: error starting rclone", e);
            return null;
        }
    }

    @VisibleForTesting
    static List<String> buildServeArgs(
            int protocol,
            int port,
            boolean allowRemoteAccess,
            @Nullable String user,
            @Nullable String password,
            @NonNull String path,
            @Nullable String baseUrl,
            @NonNull String transfers,
            boolean readOnly,
            @Nullable File certFile,
            @Nullable File keyFile,
            @Nullable String logFilePath) {
        String commandProtocol;
        switch (protocol) {
            case SERVE_PROTOCOL_HTTP:
                commandProtocol = "http";
                break;
            case SERVE_PROTOCOL_FTP:
                commandProtocol = "ftp";
                break;
            case SERVE_PROTOCOL_DLNA:
                commandProtocol = "dlna";
                break;
            default:
                commandProtocol = "webdav";
                break;
        }

        boolean hasCreds = user != null && !user.isEmpty() && password != null && !password.isEmpty();
        boolean hasTls = certFile != null && keyFile != null;
        String address;
        if (allowRemoteAccess) {
            if (protocol == SERVE_PROTOCOL_DLNA) {
                address = ":" + port;
            } else if (hasCreds && hasTls) {
                address = ":" + port;
            } else {
                address = "127.0.0.1:" + port;
            }
        } else {
            address = "127.0.0.1:" + port;
        }

        ArrayList<String> params = new ArrayList<>();
        params.add("serve");
        params.add(commandProtocol);
        params.add("--addr");
        params.add(address);
        params.add("--transfers");
        params.add(SharedPreferencesBackup.sanitizeTransfers(transfers));
        params.add("--buffer-size");
        params.add("16M");
        params.add("--multi-thread-streams");
        params.add("4");
        params.add("--vfs-read-chunk-size");
        params.add("4M");
        params.add("--vfs-read-chunk-size-limit");
        params.add("off");
        params.add("--dir-cache-time");
        params.add("5m");

        if (readOnly) {
            params.add("--read-only");
        }
        if (protocol != SERVE_PROTOCOL_DLNA && allowRemoteAccess && hasTls) {
            params.add("--cert");
            params.add(certFile.getAbsolutePath());
            params.add("--key");
            params.add(keyFile.getAbsolutePath());
        }
        if (baseUrl != null && !baseUrl.isEmpty()) {
            params.add("--baseurl");
            params.add(baseUrl);
        }
        if (logFilePath != null && !logFilePath.isEmpty()) {
            params.add("--log-file");
            params.add(logFilePath);
        }
        params.add("--");
        params.add(path);
        return params;
    }

    @VisibleForTesting
    static List<String> buildServeCredentialEnv(int protocol, @Nullable String user, @Nullable String password) {
        if (protocol == SERVE_PROTOCOL_DLNA) {
            return Collections.emptyList();
        }
        ArrayList<String> env = new ArrayList<>();
        if (user != null && !user.isEmpty()) {
            env.add("RCLONE_USER=" + user);
        }
        if (password != null && !password.isEmpty()) {
            env.add("RCLONE_PASS=" + password);
        }
        return env;
    }

    public Process serve(int protocol, int port, boolean allowRemoteAccess, String user, String password, RemoteItem remote, String servePath) {
        return serve(protocol, port, allowRemoteAccess, user, password, remote, servePath, null);
    }

    /**
     * This is only kept for legacy purposes. It was used before md5-checksum was introduced.
     * @param remoteItem
     * @param localPath
     * @param remotePath
     * @param syncDirection
     * @return
     */
    @Deprecated
    public Process sync(RemoteItem remoteItem, String localPath, String remotePath, int syncDirection) {
        return sync(remoteItem, localPath, remotePath, syncDirection, false, new ArrayList<>(0), false);
    }

    public Process sync(RemoteItem remoteItem, String localPath, String remotePath, int syncDirection, boolean useMD5Sum, ArrayList<FilterEntry> filters, boolean deleteExcluded) {
        return sync(remoteItem, localPath, remotePath, syncDirection, useMD5Sum, filters, deleteExcluded, null);
    }

    public Process sync(RemoteItem remoteItem, String localPath, String remotePath, int syncDirection, boolean useMD5Sum, ArrayList<FilterEntry> filters, boolean deleteExcluded, String transfersOverride) {
        // Cloud-to-cloud directions require a second remote; route to the dedicated overload.
        if (syncDirection == SyncDirectionObject.SYNC_REMOTE_TO_REMOTE
                || syncDirection == SyncDirectionObject.COPY_REMOTE_TO_REMOTE) {
            return null;
        }
        return syncLocalRemote(remoteItem, localPath, remotePath, syncDirection, useMD5Sum, filters, deleteExcluded, transfersOverride);
    }

    /**
     * Cloud-to-cloud sync/copy between two remotes. The primary {@code remoteItem/remotePath} pair is
     * the source; {@code remoteItem2/remotePath2} is the destination. rclone performs a server-side
     * copy when the backend pair supports it, otherwise data streams through this device's rclone
     * process.
     */
    public Process sync(RemoteItem remoteItem, String remotePath, RemoteItem remoteItem2, String remotePath2, int syncDirection, boolean useMD5Sum, ArrayList<FilterEntry> filters, boolean deleteExcluded, String transfersOverride) {
        if (syncDirection != SyncDirectionObject.SYNC_REMOTE_TO_REMOTE
                && syncDirection != SyncDirectionObject.COPY_REMOTE_TO_REMOTE) {
            return null;
        }
        String srcSection = buildRemoteSection(remoteItem, remotePath, context);
        String dstSection = buildRemoteSection(remoteItem2, remotePath2, context);
        String op = (syncDirection == SyncDirectionObject.SYNC_REMOTE_TO_REMOTE) ? "sync" : "copy";

        ArrayList<String> defaultParameter = new ArrayList<>(Arrays.asList("--transfers", getTransfers(transfersOverride), "--stats=1s", "--stats-log-level", "NOTICE", "--use-json-log"));
        if (useMD5Sum) {
            defaultParameter.add("--checksum");
        }
        if (deleteExcluded) {
            defaultParameter.add("--delete-excluded");
        }
        for (FilterEntry filter : filters) {
            defaultParameter.add("--filter");
            defaultParameter.add((filter.filterType == FilterEntry.FILTER_INCLUDE ? "+ " : "- ") + filter.filter);
        }

        return startFileTransfer(op, defaultParameter, srcSection, dstSection, remoteItem, remoteItem2);
    }

    @Nullable
    private String getCanonicalInternalDataDir() {
        if (context == null || context.getApplicationInfo() == null || context.getApplicationInfo().dataDir == null) {
            return null;
        }
        try {
            return new File(context.getApplicationInfo().dataDir).getCanonicalPath();
        } catch (IOException e) {
            return null;
        }
    }

    private Process syncLocalRemote(RemoteItem remoteItem, String localPath, String remotePath, int syncDirection, boolean useMD5Sum, ArrayList<FilterEntry> filters, boolean deleteExcluded, String transfersOverride) {
        if (remoteItem == null
                || !Importer.isSafeRemoteName(remoteItem.getName())
                || !Importer.isSafeLocalPath(localPath, getCanonicalInternalDataDir())) {
            FLog.e(TAG, "syncLocalRemote: rejected unsafe task remote or local path");
            return null;
        }
        String remoteSection = buildRemoteSection(remoteItem, remotePath, context);

        ArrayList<String> defaultParameter = new ArrayList<>(Arrays.asList("--transfers", getTransfers(transfersOverride), "--stats=1s", "--stats-log-level", "NOTICE", "--use-json-log"));

        if(useMD5Sum){
            defaultParameter.add("--checksum");
        }
        if(deleteExcluded){
            defaultParameter.add("--delete-excluded");
        }

        for (FilterEntry filter : filters) {
            defaultParameter.add("--filter");
            defaultParameter.add((filter.filterType == FilterEntry.FILTER_INCLUDE ? "+ " : "- ") + filter.filter);
        }

        localPath = encodePath(localPath);

        return switch (syncDirection) {
            case SyncDirectionObject.SYNC_LOCAL_TO_REMOTE ->
                    startFileTransfer("sync", defaultParameter, localPath, remoteSection, null, remoteItem);
            case SyncDirectionObject.SYNC_REMOTE_TO_LOCAL ->
                    startFileTransfer("sync", defaultParameter, remoteSection, localPath, remoteItem, null);
            case SyncDirectionObject.COPY_LOCAL_TO_REMOTE ->
                    startFileTransfer("copy", defaultParameter, localPath, remoteSection, null, remoteItem);
            case SyncDirectionObject.COPY_REMOTE_TO_LOCAL ->
                    startFileTransfer("copy", defaultParameter, remoteSection, localPath, remoteItem, null);
            default -> null;
        };
    }

    static ArrayList<String> savedTransferArguments(String operation, List<String> options, String source, String destination) {
        return savedTransferArguments(operation, options, source, destination, false);
    }

    static ArrayList<String> savedTransferArguments(String operation, List<String> options, String source,
                                                    String destination, boolean safDavSource) {
        ArrayList<String> arguments = new ArrayList<>(options.size() + 6);
        arguments.add(operation);
        arguments.addAll(options);
        if (safDavSource) {
            Collections.addAll(arguments, "--multi-thread-streams", "0");
        }
        // Keep path operands after the separator so leading dashes cannot become flags.
        Collections.addAll(arguments, "--", source, destination);
        return arguments;
    }

    @Nullable
    private Process startFileTransfer(String operation, List<String> options, String source, String destination,
                                      @Nullable RemoteItem sourceRemote, @Nullable RemoteItem destinationRemote) {
        boolean safDavSource = isSafDavRemote(sourceRemote);
        try {
            String[] env = ensureSafDavAndGetEnv(sourceRemote, destinationRemote);
            return getRuntimeProcess(createCommandWithOptions(
                    savedTransferArguments(operation, options, source, destination, safDavSource)), env);
        } catch (IOException e) {
            FLog.e(TAG, operation + ": error starting transfer", e);
            return null;
        }
    }

    @VisibleForTesting
    @Nullable
    static String resolveSafeDownloadTarget(
            @Nullable String downloadPath,
            @Nullable String itemName,
            boolean isDir) {
        if (downloadPath == null || downloadPath.trim().isEmpty()) {
            return null;
        }
        if (itemName == null || itemName.isEmpty() || ".".equals(itemName) || "..".equals(itemName)) {
            return null;
        }
        if (itemName.indexOf('/') >= 0 || itemName.indexOf('\\') >= 0 || itemName.indexOf('\0') >= 0) {
            return null;
        }
        try {
            File baseDir = new File(downloadPath);
            String canonicalBase = baseDir.getCanonicalPath();
            File targetFile = new File(baseDir, itemName);
            String canonicalTarget = targetFile.getCanonicalPath();
            String prefix = "/".equals(canonicalBase) ? "/" : canonicalBase + File.separator;
            if (!canonicalTarget.startsWith(prefix)) {
                return null;
            }
            return isDir ? canonicalTarget : canonicalBase;
        } catch (IOException e) {
            return null;
        }
    }

    public Process downloadFile(RemoteItem remote, FileItem downloadItem, String downloadPath) {
        if (remote == null || downloadItem == null) {
            return null;
        }
        String resolvedTarget = resolveSafeDownloadTarget(downloadPath, downloadItem.getName(), downloadItem.isDir());
        if (resolvedTarget == null) {
            FLog.e(TAG, "downloadFile: rejected unsafe download destination or item name");
            return null;
        }

        String remoteFilePath;
        remoteFilePath = remote.getName() + ":";
        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            remoteFilePath += getLocalRemotePathPrefix(remote, context)  + "/";
        }
        remoteFilePath += downloadItem.getPath();

        String localFilePath = encodePath(resolvedTarget);

        return startFileTransfer("copy", Arrays.asList("--transfers", getTransfers(), "--stats=1s",
                "--stats-log-level", "NOTICE", "--use-json-log"), remoteFilePath, localFilePath, remote, null);
    }

    public Process uploadFile(RemoteItem remote, String uploadPath, String uploadFile) {
        String remoteName = remote.getName();
        String path;
        String localRemotePath;

        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            localRemotePath = getLocalRemotePathPrefix(remote, context) + "/";
        } else {
            localRemotePath = "";
        }

        File file = new File(uploadFile);
        if (file.isDirectory()) {
            int index = uploadFile.lastIndexOf('/');
            String dirName = uploadFile.substring(index + 1);
            path = (uploadPath.compareTo("//" + remoteName) == 0) ? remoteName + ":" + localRemotePath + dirName : remoteName + ":" + localRemotePath + uploadPath + "/" + dirName;
        } else {
            path = (uploadPath.compareTo("//" + remoteName) == 0) ? remoteName + ":" + localRemotePath : remoteName + ":" + localRemotePath + uploadPath;
        }

        return startFileTransfer("copy", Arrays.asList("--transfers", getTransfers(), "--stats=1s",
                "--stats-log-level", "NOTICE", "--use-json-log"), uploadFile, path, null, remote);

    }

    public Process downloadFilesFrom(RemoteItem remote, String sourceParent, String destination,
                                     File filesFrom, File report) {
        return startFileTransfer("copy", batchCopyOptions(filesFrom, report, getTransfers()),
                buildFullRemotePath(remote, sourceParent), encodePath(destination), remote, null);
    }

    public Process uploadFilesFrom(RemoteItem remote, String sourceParent, String destination,
                                   File filesFrom, File report) {
        return startFileTransfer("copy", batchCopyOptions(filesFrom, report, getTransfers()),
                encodePath(sourceParent), buildRemoteSection(remote, destination, context), null, remote);
    }

    static List<String> batchCopyOptions(File filesFrom, File report, String transfers) {
        return Arrays.asList("--files-from-raw", filesFrom.getAbsolutePath(), "--no-traverse",
                "--combined", report.getAbsolutePath(), "--transfers", transfers, "--stats=1s",
                "--stats-log-level", "NOTICE", "--use-json-log");
    }

    // Can't pass \u0000 as cmd arg - encode like rclone with U+2400
    // Ref: Appcenter #22305285
    // TODO Appcenter #170195533 - rclone serve
    @NonNull
    private String encodePath(String localFilePath) {
        if (localFilePath.indexOf('\u0000') < 0) {
            return localFilePath;
        }
        StringBuilder localPathBuilder = new StringBuilder(localFilePath.length());
        for (char c : localFilePath.toCharArray()) {
            if (c == '\u0000') {
                localPathBuilder.append('\u2400');
            } else {
                localPathBuilder.append(c);
            }

        }
        return localPathBuilder.toString();
    }

    /**
     * Batch-deletes plain files listed in {@code filesFrom} (one root-relative path per line) with
     * a single rclone process, so a multi-select delete occupies one worker instead of hundreds.
     */
    public Process deleteFilesFrom(RemoteItem remote, File filesFrom) {
        // Plain-local remotes root at the SAF-mapped prefix; wrapped remotes (crypt/alias/cache)
        // must NOT get that prefix — buildFullRemotePath applies the same exclusion as the
        // per-file delete paths, and an empty path yields exactly the remote root.
        String root = buildFullRemotePath(remote, "");
        String[] command = createCommandWithOptions("delete",
                "--files-from-raw", filesFrom.getAbsolutePath(),
                "--transfers", getTransfers(),
                "--stats=1s", "--stats-log-level", "NOTICE", "--use-json-log",
                "--contimeout", "30s", "--timeout", "2m",
                "--", root);

        try {
            String[] env = ensureSafDavAndGetEnv(remote);
            return getRuntimeProcess(command, env);
        } catch (IOException e) {
            FLog.e(TAG, "deleteFilesFrom: error starting rclone", e);
            return null;
        }
    }

    /** Recursively deletes a single directory on the remote. */
    public Process purgeItem(RemoteItem remote, String path) {
        String[] command = createCommandWithOptions("purge",
                "--stats=1s", "--stats-log-level", "NOTICE", "--use-json-log",
                "--contimeout", "30s", "--timeout", "2m",
                "--", buildFullRemotePath(remote, path));

        try {
            String[] env = ensureSafDavAndGetEnv(remote);
            return getRuntimeProcess(command, env);
        } catch (IOException e) {
            FLog.e(TAG, "purgeItem: error starting rclone", e);
            return null;
        }
    }

    /** Deletes a single plain file on the remote (fallback for names unusable in --files-from). */
    public Process deleteItems(RemoteItem remote, String path) {
        String[] command = createCommandWithOptions("deletefile",
                "--stats=1s", "--stats-log-level", "NOTICE", "--use-json-log",
                "--contimeout", "30s", "--timeout", "2m",
                "--", buildFullRemotePath(remote, path));

        try {
            String[] env = ensureSafDavAndGetEnv(remote);
            return getRuntimeProcess(command, env);
        } catch (IOException e) {
            FLog.e(TAG, "deleteItems: error starting rclone", e);
            return null;
        }
    }

    private String buildFullRemotePath(RemoteItem remote, String path) {
        String localRemotePath;
        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            localRemotePath = getLocalRemotePathPrefix(remote, context) + "/";
        } else {
            localRemotePath = "";
        }
        return remote.getName() + ":" + localRemotePath + path;
    }

    public Boolean makeDirectory(RemoteItem remote, String path) {
        String localRemotePath;

        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            localRemotePath = getLocalRemotePathPrefix(remote, context) + "/";
        } else {
            localRemotePath = "";
        }

        String newDir = remote.getName() + ":" + localRemotePath + path;
        String[] command = createCommandWithOptions("mkdir", "--", newDir);
        try {
            String[] env = ensureSafDavAndGetEnv(remote);
            Process process = getRuntimeProcess(command, env);
            process.waitFor();
            if (process.exitValue() != 0) {
                logErrorOutput(process);
                return false;
            }
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "makeDirectory: error running rclone", e);
            return false;
        }
        return true;
    }

    public Process moveTo(RemoteItem remote, FileItem moveItem, String newLocation) {
        String remoteName = remote.getName();
        String oldFilePath;
        String newFilePath;
        String localRemotePath;

        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            localRemotePath = getLocalRemotePathPrefix(remote, context) + "/";
        } else {
            localRemotePath = "";
        }

        oldFilePath = remoteName + ":" + localRemotePath + moveItem.getPath();
        newFilePath = (newLocation.compareTo("//" + remoteName) == 0) ? remoteName + ":" + localRemotePath + moveItem.getName() : remoteName + ":" + localRemotePath + newLocation + "/" + moveItem.getName();
        return startFileTransfer("moveto",
                Arrays.asList("--stats=1s", "--stats-log-level", "NOTICE", "--use-json-log"),
                oldFilePath, newFilePath, remote, remote);
    }

    public Boolean moveTo(RemoteItem remote, String oldFile, String newFile) {
        String remoteName = remote.getName();
        String localRemotePath;

        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            localRemotePath = getLocalRemotePathPrefix(remote, context) + "/";
        } else {
            localRemotePath = "";
        }

        String oldFilePath = remoteName + ":" + localRemotePath + oldFile;
        String newFilePath = remoteName + ":" + localRemotePath + newFile;
        Process process = startFileTransfer("moveto",
                Arrays.asList("--stats=1s", "--stats-log-level", "NOTICE", "--use-json-log"),
                oldFilePath, newFilePath, remote, remote);
        if (process == null) {
            return false;
        }
        collectDiagnostics(process);
        try {
            process.waitFor();
            if (process.exitValue() != 0) {
                logErrorOutput(process);
                return false;
            }
            logErrorOutput(process);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FLog.e(TAG, "moveTo: error running rclone", e);
            return false;
        }
        return true;
    }

    @Nullable
    private static String extractRemoteName(@Nullable String rclonePath) {
        if (rclonePath == null) {
            return null;
        }
        int colon = rclonePath.indexOf(':');
        return colon > 0 ? rclonePath.substring(0, colon) : null;
    }

    public InputStream downloadToPipe(String rclonePath) throws IOException {
        String[] command = createCommandWithOptions("cat", "--", rclonePath);
        String[] env = ensureSafDavAndGetEnvForName(extractRemoteName(rclonePath), Collections.emptyList());
        final Process process = getRuntimeProcess(command, env);
        collectDiagnostics(process);
        new Thread() {
            @Override
            public void run() {
                try {
                    process.waitFor();
                    logErrorOutput(process);
                } catch (InterruptedException e) {
                    FLog.e(TAG, "downloadToPipe: error waiting for process", e);
                }
            }
        }.start();
        return process.getInputStream();
    }

    public OutputStream uploadFromPipe(String rclonePath) throws IOException {
        String[] command = createCommandWithOptions("rcat", "--streaming-upload-cutoff", "500K", "--", rclonePath);
        String[] env = ensureSafDavAndGetEnvForName(extractRemoteName(rclonePath), Collections.emptyList());
        final Process process = getRuntimeProcess(command, env);
        collectDiagnostics(process);
        new Thread() {
            @Override
            public void run() {
                try {
                    process.waitFor();
                    logErrorOutput(process);
                } catch (InterruptedException e) {
                    FLog.e(TAG, "uploadFromPipe: error waiting for process", e);
                }
            }
        }.start();
        return process.getOutputStream();
    }

    public boolean emptyTrashCan(String remote) {
        if (remote == null || remote.startsWith("-")) {
            return false;
        }
        String[] command = createCommandWithOptions("cleanup", "--", remote + ":");
        Process process = null;
        try {
            String[] env = ensureSafDavAndGetEnvForName(remote, Collections.emptyList());
            process = getRuntimeProcess(command, env);
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "emptyTrashCan: error running rclone", e);
        }

        return process != null && process.exitValue() == 0;
    }

    public static class LinkResult {
        @Nullable public final String url;
        @Nullable public final String errorMessage;
        public final boolean unlinked;

        public LinkResult(@Nullable String url, @Nullable String errorMessage, boolean unlinked) {
            this.url = url;
            this.errorMessage = errorMessage;
            this.unlinked = unlinked;
        }

        public boolean isSuccess() {
            return errorMessage == null && (unlinked || (url != null && !url.isEmpty()));
        }
    }

    public String link(RemoteItem remote, String filePath) {
        return linkWithOptions(remote, filePath, null, false).url;
    }

    public LinkResult linkWithOptions(RemoteItem remote, String filePath, @Nullable String expire, boolean unlink) {
        if (remote == null || !remote.hasLinkSupport()) {
            return new LinkResult(null, context.getString(R.string.public_link_not_supported), false);
        }
        String linkPath = remote.getName() + ":";
        if (remote.isRemoteType(RemoteItem.LOCAL)) {
            linkPath += getLocalRemotePathPrefix(remote, context) + "/";
        }
        if (filePath != null && !filePath.isEmpty() && !filePath.equals("//" + remote.getName())) {
            linkPath += filePath;
        }

        ArrayList<String> args = new ArrayList<>();
        args.add("link");
        if (unlink) {
            args.add("--unlink");
        } else if (expire != null && !expire.isEmpty()) {
            args.add("--expire");
            args.add(expire);
        }
        args.add("--");
        args.add(linkPath);

        String[] command = createCommandWithOptions(args);
        Process process = null;

        try {
            String[] env = ensureSafDavAndGetEnv(remote);
            process = getRuntimeProcess(command, env);
            final Process procRef = process;
            final StringBuilder stderrBuilder = new StringBuilder();
            Thread stderrThread = new Thread(() -> {
                try (BufferedReader errReader = new BufferedReader(new InputStreamReader(procRef.getErrorStream()))) {
                    String line;
                    while ((line = errReader.readLine()) != null) {
                        stderrBuilder.append(line).append("\n");
                    }
                } catch (IOException ignored) {
                }
            }, "rclone-link-stderr");
            stderrThread.start();

            String stdoutLine = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && stdoutLine == null) {
                        stdoutLine = trimmed;
                    }
                }
            }

            process.waitFor();
            stderrThread.join(1000);

            if (process.exitValue() != 0) {
                String errText = stderrBuilder.toString().trim();
                String redactedErr = SecretRedactor.redact(errText);
                if (BuildConfig.DEBUG) {
                    FLog.d(TAG, "linkWithOptions failed (%d): %s", process.exitValue(), redactedErr);
                } else {
                    FLog.w(TAG, "linkWithOptions failed (%d)", process.exitValue());
                }
                return new LinkResult(null, mapLinkError(remote, errText), false);
            }

            CacheManager.getInstance(context).setPublicLinkSupport(remote.getName(), true);
            if (unlink) {
                return new LinkResult(null, null, true);
            }
            if (stdoutLine == null || stdoutLine.isEmpty()) {
                return new LinkResult(null, context.getString(R.string.error_generating_link), false);
            }
            return new LinkResult(stdoutLine, null, false);
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "link: error running rclone", e);
            return new LinkResult(null, context.getString(R.string.error_generating_link), false);
        }
    }

    private String mapLinkError(RemoteItem remote, String stderr) {
        if (stderr == null || stderr.isEmpty()) {
            return context.getString(R.string.error_generating_link);
        }
        String lower = stderr.toLowerCase(Locale.ROOT);
        if (lower.contains("not implemented") || lower.contains("doesn't support") || lower.contains("not supported")) {
            CacheManager.getInstance(context).setPublicLinkSupport(remote.getName(), false);
            return context.getString(R.string.public_link_not_supported);
        }
        if (lower.contains("403") || lower.contains("forbidden") || lower.contains("permission") || lower.contains("not allowed")) {
            return context.getString(R.string.public_link_forbidden);
        }
        if (lower.contains("401") || lower.contains("unauthorized") || lower.contains("token") || lower.contains("expired") || lower.contains("auth")) {
            return context.getString(R.string.public_link_auth_error);
        }
        String[] lines = stderr.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                return SecretRedactor.redact(line);
            }
        }
        return context.getString(R.string.error_generating_link);
    }

    public String calculateMD5(RemoteItem remote, FileItem fileItem) {
        String localRemotePath;

        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            localRemotePath = getLocalRemotePathPrefix(remote, context) + "/";
        } else {
            localRemotePath = "";
        }

        String remoteAndPath = remote.getName() + ":" + localRemotePath + fileItem.getName();
        String[] command = createCommandWithOptions("md5sum", "--", remoteAndPath);
        Process process;
        try {
            String[] env = ensureSafDavAndGetEnv(remote);
            process = getRuntimeProcess(command, env);
            process.waitFor();
            if (process.exitValue() != 0) {
                return context.getString(R.string.hash_error);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line == null || line.trim().isEmpty()) {
                    return context.getString(R.string.hash_error);
                }
                String[] split = line.split("\\s+");
                if (split[0].trim().isEmpty()) {
                    return context.getString(R.string.hash_unsupported);
                } else {
                    return split[0];
                }
            }
        } catch (IOException e) {
            FLog.e(TAG, "calculateMD5: error running rclone", e);
            return context.getString(R.string.hash_error);
        } catch (InterruptedException e) {
            FLog.v(TAG, "calculateMD5: calculation stopped");
            return context.getString(R.string.hash_error);
        }
    }

    public String calculateSHA1(RemoteItem remote, FileItem fileItem) {
        String localRemotePath;

        if (remote.isRemoteType(RemoteItem.LOCAL) && (!remote.isAlias() && !remote.isCrypt() && !remote.isCache())) {
            localRemotePath = getLocalRemotePathPrefix(remote, context) + "/";
        } else {
            localRemotePath = "";
        }

        String remoteAndPath = remote.getName() + ":" + localRemotePath + fileItem.getName();
        String[] command = createCommandWithOptions("sha1sum", "--", remoteAndPath);
        Process process;
        try {
            String[] env = ensureSafDavAndGetEnv(remote);
            process = getRuntimeProcess(command, env);
            process.waitFor();
            if (process.exitValue() != 0) {
                return context.getString(R.string.hash_error);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line == null || line.trim().isEmpty()) {
                    return context.getString(R.string.hash_error);
                }
                String[] split = line.split("\\s+");
                if (split[0].trim().isEmpty()) {
                    return context.getString(R.string.hash_unsupported);
                } else {
                    return split[0];
                }
            }
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "calculateSHA1: error running rclone", e);
            return context.getString(R.string.hash_error);
        }
    }

    public String getRcloneVersion() {
        String[] command = createCommand("--version");
        ArrayList<String> result = new ArrayList<>();
        try {
            Process process = getRuntimeProcess(command);
            process.waitFor();
            if (process.exitValue() != 0) {
                logErrorOutput(process);
                return "-1";
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    result.add(line);
                }
            }
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "getRcloneVersion: error running rclone", e);
            return "-1";
        }

        if (result.isEmpty()) {
            return "-1";
        }
        String[] version = result.get(0).split("\\s+");
        if (version.length < 2) {
            return "-1";
        }
        return version[1];
    }

    public Process reconnectRemote(RemoteItem remoteItem) {
        if (remoteItem == null || remoteItem.getName() == null || remoteItem.getName().startsWith("-")) {
            return null;
        }
        String[] command = createCommand("config", "update", "--", remoteItem.getName());

        try {
            return getRuntimeProcess(command, getRcloneEnv());
        } catch (IOException e) {
            return null;
        }
    }

    public AboutResult aboutRemote(RemoteItem remoteItem) {
        if (remoteItem == null) {
            return new AboutResult();
        }
        String remoteName = remoteItem.getName() + ':';
        String[] command = createCommand("about", "--json", "--", remoteName);
        StringBuilder output = new StringBuilder();
        AboutResult stats;
        Process process = null;
        JSONObject aboutJSON;

        try {
            process = getRuntimeProcess(command, ensureSafDavAndGetEnv(remoteItem));
            collectDiagnostics(process);
            try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))){
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }
            process.waitFor();
            if (0 != process.exitValue()) {
                FLog.e(TAG, "aboutRemote: rclone error, exit(%d)", process.exitValue());
                FLog.e(TAG, "aboutRemote: ", output);
                logErrorOutput(process);
                return new AboutResult();
            }

            logErrorOutput(process);
            aboutJSON = new JSONObject(output.toString());
        } catch (InterruptedException e) {
            if (process != null) { discardDiagnostics(process); process.destroy(); }
            Thread.currentThread().interrupt();
            return new AboutResult();
        } catch (IOException | JSONException e) {
            if (process != null) discardDiagnostics(process);
            FLog.e(TAG, "aboutRemote: unexpected error", e);
            return new AboutResult();
        }

        try {
            stats = new AboutResult(
                    aboutJSON.opt("used") != null ? aboutJSON.getLong("used") : -1,
                    aboutJSON.opt("total") != null ? aboutJSON.getLong("total") : -1,
                    aboutJSON.opt("free") != null ? aboutJSON.getLong("free") : -1,
                    aboutJSON.opt("trashed") != null ? aboutJSON.getLong("trashed") : -1
            );
        } catch (JSONException e) {
            FLog.e(TAG, "aboutRemote: JSON format error ", e);
            return new AboutResult();
        }

        return stats;
    }

    public String configDump() {
        String[] command = createCommand("config", "dump");
        StringBuilder output = new StringBuilder();
        Process process;

        try {
            process = getRuntimeProcess(command);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            process.waitFor();
            if (process.exitValue() != 0) {
                FLog.e(TAG, "configDump: rclone error, exit(%d)", process.exitValue());
                logErrorOutput(process);
                return null;
            }

            return output.toString();
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "configDump: unexpected error", e);
            return null;
        }
    }

    public DirectoryProbeResult listDirectories(String remoteName, int maxDepth) {
        if (remoteName == null || remoteName.startsWith("-")) {
            return new DirectoryProbeResult(1, "Rejected invalid remote name");
        }
        String[] command = createCommand("--contimeout", "15s", "--timeout", "30s", "--retries", "1",
                "lsd", "--max-depth", String.valueOf(maxDepth), "--", remoteName + ":");
        Process process = null;
        try {
            String[] env = ensureSafDavAndGetEnvForName(remoteName, Collections.emptyList());
            process = getRuntimeProcess(command, env);
            // lsd may list more entries than the stdout pipe can buffer.
            final Process probe = process;
            new Thread(() -> {
                try (InputStream input = probe.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    while (input.read(buffer) != -1) {
                        // The health check only needs the exit code and diagnostics.
                    }
                } catch (IOException ignored) {
                    // Process termination can close the pipe.
                }
            }, "rclone-probe-stdout").start();
            StringBuilder stderr = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append('\n');
                    if (stderr.length() > 16384) {
                        stderr.delete(0, stderr.length() - 16384);
                    }
                }
            }
            return new DirectoryProbeResult(process.waitFor(), stderr.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DirectoryProbeResult(-1, "");
        } catch (IOException e) {
            FLog.e(TAG, "Unable to start or read Internxt probe", e);
            return new DirectoryProbeResult(-1, "");
        } finally {
            if (process != null) process.destroy();
        }
    }

    public static class DirectoryProbeResult {
        private final int exitCode;
        private final String stderr;

        public DirectoryProbeResult(int exitCode, String stderr) {
            this.exitCode = exitCode;
            this.stderr = stderr != null ? stderr : "";
        }

        public int getExitCode() {
            return exitCode;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }

        public boolean requiresInternxtReauthentication() {
            if (exitCode <= 0) return false;
            for (String line : stderr.toLowerCase(Locale.ROOT).split("\n")) {
                if (line.contains("account requires 2fa but no totp_secret configured")
                        || line.contains("password appears to be stored as plaintext")
                        || line.contains("failed to get token - please run: rclone config reconnect")) {
                    return true;
                }
                int reLogin = line.indexOf("re-login fallback failed:");
                if (reLogin >= 0) {
                    // NewFs appends the original refresh failure after the re-login error.
                    // Its 401 does not prove the attempted re-login was itself rejected.
                    int originalError = line.indexOf("): ", reLogin);
                    String error = originalError < 0 ? line.substring(reLogin)
                            : line.substring(reLogin, originalError);
                    if (error.contains("status 401") || error.contains("status 403")) return true;
                }
            }
            // A generic retry limit can also be reached during a provider outage.
            return false;
        }
    }

    public class AboutResult {
        private final long used;
        private final long total;
        private final long free;
        private final long trashed;
        private boolean failed;

        public AboutResult(long used, long total, long free, long trashed) {
            this.used = used;
            this.total = total;
            this.free = free;
            this.trashed = trashed;
            this.failed = false;
        }

        public AboutResult () {
            this(-1, -1, -1,  -1);
            this.failed = true;
        }

        public long getUsed() {
            return used;
        }

        public long getTotal() {
            return total;
        }

        public long getFree() {
            return free;
        }

        public long getTrashed() {
            return trashed;
        }

        public boolean hasFailed(){
            return failed;
        }
    }

    public Boolean isConfigEncrypted() {
        if (!isConfigFileCreated()) {
            return false;
        }
        String[] command = createCommand( "--ask-password=false", "listremotes");
        Process process;
        try {
            process = getRuntimeProcess(command);
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            FLog.e(TAG, "Error running rclone %s", e, Arrays.toString(command));
            return false;
        }
        return process.exitValue() != 0;
    }

    public Boolean decryptConfig(String password) {
        String[] command = createCommand("--ask-password=false", "config", "show");
        String[] environmentalVars = {"RCLONE_CONFIG_PASS=" + password};
        Process process;

        try {
            process = getRuntimeProcess(command, environmentalVars);
        } catch (IOException e) {
            FLog.e(TAG, "decryptConfig: error running rclone", e);
            return false;
        }

        ArrayList<String> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.add(line);
            }
        } catch (IOException e) {
            FLog.e(TAG, "decryptConfig: error copying rclone stdout", e);
            return false;
        }

        try {
            process.waitFor();
        } catch (InterruptedException e) {
            FLog.e(TAG, "decryptConfig: error waiting for rclone", e);
            return false;
        }

        if (process.exitValue() != 0) {
            return false;
        }

        String appsFileDir = context.getFilesDir().getPath();
        File file = new File(appsFileDir, "rclone.conf");
        File tmpFile = new File(appsFileDir, "rclone.conf.tmp");

        try {
            if (tmpFile.exists()) {
                tmpFile.delete();
            }
            tmpFile.createNewFile();
            tmpFile.setReadable(false, false);
            tmpFile.setReadable(true, true);
            tmpFile.setWritable(false, false);
            tmpFile.setWritable(true, true);
            try (FileOutputStream fileOutputStream = new FileOutputStream(tmpFile);
                 OutputStreamWriter outputStreamWriter = new OutputStreamWriter(fileOutputStream, java.nio.charset.StandardCharsets.UTF_8)) {
                for (String line2 : result) {
                    outputStreamWriter.append(line2);
                    outputStreamWriter.append("\n");
                }
                outputStreamWriter.flush();
            }
            if (!tmpFile.renameTo(file)) {
                tmpFile.delete();
                return false;
            }
        } catch (IOException e) {
            FLog.e(TAG, "decryptConfig: error writing config", e);
            tmpFile.delete();
            return false;
        }
        return true;
    }

    public boolean isConfigFileCreated() {
        String appsFileDir = context.getFilesDir().getPath();
        String configFile = appsFileDir + "/rclone.conf";
        File file = new File(configFile);
        return file.exists();
    }

    // on all devices, look under ./Android/data/ca.pkay.rcloneexplorer/files/rclone.conf
    public Uri searchExternalConfig(){
        File[] extDir = context.getExternalFilesDirs(null);
        for(File dir : extDir){
            File file = new File(dir + "/rclone.conf");
            if(file.exists() && isValidConfig(file.getAbsolutePath())){
                return Uri.fromFile(file);
            }
        }
        return null;
    }

    private static final long MAX_BACKUP_ENTRY_BYTES = 10L * 1024L * 1024L;

    @VisibleForTesting
    @Nullable
    static File extractZipEntryBounded(
            @Nullable InputStream rawInput,
            @NonNull String target,
            @NonNull File targetFile,
            long maxBytes) throws IOException {
        if (rawInput == null) {
            throw new IOException("Null backup input stream");
        }
        try (ZipInputStream zipInputStream = new ZipInputStream(new BufferedInputStream(rawInput))) {
            ZipEntry zipEntry;
            while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                try {
                    if (target.equals(zipEntry.getName())) {
                        long declaredSize = zipEntry.getSize();
                        if (declaredSize > maxBytes) {
                            throw new IOException("Backup entry exceeds maximum allowed size");
                        }
                        copyStreamBounded(zipInputStream, targetFile, maxBytes);
                        return targetFile;
                    }
                } finally {
                    zipInputStream.closeEntry();
                }
            }
        }
        return null;
    }

    @VisibleForTesting
    static void copyStreamBounded(
            @NonNull InputStream inputStream,
            @NonNull File targetFile,
            long maxBytes) throws IOException {
        setOwnerOnlyPermissions(targetFile);
        byte[] buffer = new byte[8192];
        long total = 0;
        boolean completed = false;
        try (FileOutputStream fileOutputStream = new FileOutputStream(targetFile)) {
            setOwnerOnlyPermissions(targetFile);
            int count;
            while ((count = inputStream.read(buffer)) != -1) {
                if (count > 0) {
                    total += count;
                    if (total > maxBytes) {
                        throw new IOException("Backup file exceeds maximum allowed size");
                    }
                    fileOutputStream.write(buffer, 0, count);
                }
            }
            fileOutputStream.flush();
            completed = true;
        } finally {
            if (!completed) {
                deleteQuietly(targetFile);
            }
        }
    }

    private static void setOwnerOnlyPermissions(@NonNull File file) {
        if (!file.exists()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                file.createNewFile();
            } catch (IOException ignored) {
            }
        }
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
    }

    private static void deleteQuietly(@Nullable File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    public File getFileFromZip(Uri uri, String target, File targetfile) throws IOException {
        if (uri == null || target == null || targetfile == null) {
            throw new IOException("Invalid zip extraction parameters");
        }
        InputStream inputStream;
        try {
            inputStream = context.getContentResolver().openInputStream(uri);
        } catch (NullPointerException e) {
            throw new IOException(e);
        }
        if (inputStream == null) {
            throw new IOException("Unable to open backup stream");
        }
        return extractZipEntryBounded(inputStream, target, targetfile, MAX_BACKUP_ENTRY_BYTES);
    }

    public String readDatabaseJson(Uri uri) throws Exception {
        return readTextfileFromZip(uri, "rcx.json-tmp", "rcx.json");
    }

    public String readSharedPrefs(Uri uri) throws Exception {
        return readTextfileFromZip(uri, "rcx.prefs-tmp", "rcx.prefs");
    }

    public String readTextfileFromZip(Uri uri, String tempfile, String targetfile) throws Exception {
        File temp = new File(context.getFilesDir().getPath(), tempfile);
        File extracted = null;
        try {
            extracted = getFileFromZip(uri, targetfile, temp);
            if (extracted == null) {
                throw new FileNotFoundException("Backup entry not found: " + targetfile);
            }
            char[] buffer = new char[4096];
            StringBuilder json = new StringBuilder();
            try (InputStream inputStream = new FileInputStream(extracted);
                 Reader in = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                int numRead;
                while ((numRead = in.read(buffer, 0, buffer.length)) > 0) {
                    if (json.length() + numRead > MAX_BACKUP_ENTRY_BYTES) {
                        throw new IOException("Backup text entry exceeds maximum size");
                    }
                    json.append(buffer, 0, numRead);
                }
            }
            return json.toString();
        } finally {
            deleteQuietly(temp);
            if (extracted != null && !extracted.equals(temp)) {
                deleteQuietly(extracted);
            }
        }
    }

    public boolean copyConfigFileFromZip(Uri uri) throws Exception {
        String appsFileDir = context.getFilesDir().getPath();
        File tempFile = new File(appsFileDir, "rclone.conf-tmp");
        File configFile = new File(appsFileDir, "rclone.conf");
        try {
            File extracted = getFileFromZip(uri, "rclone.conf", tempFile);
            if (extracted == null) {
                return false;
            }
            if (isValidConfig(extracted.getAbsolutePath())) {
                if (!extracted.renameTo(configFile)) {
                    throw new IOException("Failed to install restored rclone configuration");
                }
                setOwnerOnlyPermissions(configFile);
                invalidateRemotesCache();
                return true;
            }
            return false;
        } finally {
            deleteQuietly(tempFile);
        }
    }


    /***
     * This function replaces the config by replacing rclone.conf.
     * First a rclone.conf-tmp is created, which is then verified to be working.
     * Then the rclone.conf is beeing replaced by the temp file.
     * @param uri Uri to the new rclone file.
     * @return True if rclone.conf has been replaced, false if not.
     * @throws IOException
     */
    public boolean copyConfigFile(Uri uri) throws IOException {
        if (uri == null) {
            throw new IOException("Null configuration URI");
        }
        String appsFileDir = context.getFilesDir().getPath();
        InputStream inputStream;
        try {
            inputStream = context.getContentResolver().openInputStream(uri);
        } catch (NullPointerException e) {
            throw new IOException(e);
        }
        if (inputStream == null) {
            throw new IOException("Unable to open configuration stream");
        }
        File tempFile = new File(appsFileDir, "rclone.conf-tmp");
        File configFile = new File(appsFileDir, "rclone.conf");
        try (InputStream managedInput = inputStream) {
            copyStreamBounded(managedInput, tempFile, MAX_BACKUP_ENTRY_BYTES);
            if (isValidConfig(tempFile.getAbsolutePath())) {
                if (!tempFile.renameTo(configFile)) {
                    throw new IOException("Failed to install imported rclone configuration");
                }
                setOwnerOnlyPermissions(configFile);
                invalidateRemotesCache();
                return true;
            }
            return false;
        } finally {
            deleteQuietly(tempFile);
        }
    }

    public boolean isValidConfig(String path) {
        String[] command = {rclone, "-vvv", "--ask-password=false", "--config", path, "listremotes"};
        Process process = null;
        try {
            process = getRuntimeProcess(command);
            final java.util.concurrent.atomic.AtomicBoolean stdErrParseError = new java.util.concurrent.atomic.AtomicBoolean(false);
            final Process procRef = process;
            Thread stderrDrain = new Thread(() -> {
                try (BufferedReader stdErr = new BufferedReader(new InputStreamReader(procRef.getErrorStream()))) {
                    String errLine;
                    while ((errLine = stdErr.readLine()) != null) {
                        if (errLine.contains("could not parse line")) {
                            stdErrParseError.set(true);
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "rclone-config-stderr");
            stderrDrain.setDaemon(true);
            stderrDrain.start();

            boolean valid = true;
            try (BufferedReader stdOut = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = stdOut.readLine()) != null) {
                    if (line.contains("could not parse line")) {
                        valid = false;
                    }
                    String trimmed = line.trim();
                    if (trimmed.endsWith(":")) {
                        String remoteName = trimmed.substring(0, trimmed.length() - 1);
                        if (remoteName.isEmpty() || remoteName.startsWith("-") || !remoteName.matches("^[A-Za-z0-9_][A-Za-z0-9_. -]*$")) {
                            valid = false;
                        }
                    }
                }
            }
            process.waitFor();
            stderrDrain.join(2000);
            if (!valid || stdErrParseError.get()) {
                return false;
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (process != null) {
                process.destroy();
            }
            return false;
        }
        return true;
    }

    public void exportConfigFile(Uri uri) throws IOException {
        File configFile = new File(rcloneConf);
        Uri config = Uri.fromFile(configFile);
        InputStream inputStream = context.getContentResolver().openInputStream(config);
        OutputStream outputStream = context.getContentResolver().openOutputStream(uri);

        if (inputStream == null || outputStream == null) {
            return;
        }
        char[] buffer = new char[4096];
        StringBuilder out = new StringBuilder();
        Reader in = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
        for (int numRead; (numRead = in.read(buffer, 0, buffer.length)) > 0; ) {
            out.append(buffer, 0, numRead);
        }

        ZipOutputStream zos = new ZipOutputStream(outputStream);
        try {
            ZipEntry zipEntry = new ZipEntry("rcx.json");
            zos.putNextEntry(zipEntry);
            zos.write(Exporter.create(this.context).getBytes());
            zos.closeEntry();
            zipEntry = new ZipEntry("rcx.prefs");
            zos.putNextEntry(zipEntry);
            zos.write(SharedPreferencesBackup.export(context).getBytes());
            zos.closeEntry();
            zipEntry = new ZipEntry("rclone.conf");
            zos.putNextEntry(zipEntry);
            zos.write(out.toString().getBytes());
            zos.closeEntry();
        }
        catch (Exception e) {
            // unable to write zip
        }
        finally {
            zos.close();
            inputStream.close();
            outputStream.flush();
            outputStream.close();
        }
    }

    public boolean isCompatible() {
        if (isCompatible != null) {
            return isCompatible;
        }
        synchronized (Rclone.class) {
            if (isCompatible == null) {
                isCompatible = checkCompatibility();
            }
        }
        return isCompatible;
    }

    private boolean checkCompatibility() {
        String nativelibraryDir = context.getApplicationInfo().nativeLibraryDir;
        File nativeRcloneBinary = new File(nativelibraryDir, "librclone.so");
        if (!nativeRcloneBinary.exists()) {
            return false;
        }
        if ("-1".equals(getRcloneVersion())) {
            return false;
        }
        return true;
    }

    /**
     * Prefixes local remotes with a base path on the primary external storage.
     * @param item
     * @param context
     * @return
     */
    /**
     * Builds the canonical {@code remoteName:[localPrefix]path} argument rclone expects for a
     * remote endpoint. Centralizes the {@code "//"+name} root-sentinel handling and the LOCAL-remote
     * storage prefix that were duplicated across call sites. Used for both source and destination
     * of cloud-to-cloud operations.
     */
    public static String buildRemoteSection(RemoteItem remoteItem, String remotePath, Context context) {
        String remoteName = remoteItem.getName();
        boolean plainLocal = remoteItem.isRemoteType(RemoteItem.LOCAL)
                && !remoteItem.isAlias() && !remoteItem.isCrypt() && !remoteItem.isCache();
        String localRemotePath = plainLocal ? getLocalRemotePathPrefix(remoteItem, context) + "/" : "";
        if (("//" + remoteName).equals(remotePath)) {
            return remoteName + ":" + localRemotePath;
        }
        return remoteName + ":" + localRemotePath + remotePath;
    }

    public static String getLocalRemotePathPrefix(RemoteItem item, Context context) {
        if (item.isPathAlias()) {
            return "";
        }
        // lower version boundary check if legacy external storage = false
        if(Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            File extDir = context.getExternalFilesDir(null);
            if(null != extDir) {
                return extDir.getAbsolutePath();
            } else {
                File internalDir = context.getFilesDir();
                File fallbackLocal = new File(internalDir, "fallback-local");
                if (!fallbackLocal.exists() && !fallbackLocal.mkdir()) {
                    throw new IllegalStateException();
                }
                return fallbackLocal.getAbsolutePath();
            }
        } else {
            return Environment.getExternalStorageDirectory().getAbsolutePath();
        }
    }

    public ArrayList<Provider> getProviders() throws JSONException {
        return getProviders(false);
    }
    public ArrayList<Provider> getProviders(boolean silent) throws JSONException {

        JSONArray remotesJSON;
        int versionCode = BuildConfig.VERSION_CODE;
        File file = new File(context.getCacheDir(), "rclone.provider."+versionCode);

        if(!file.exists()) {
            String[] command = createCommand("config", "providers");
            StringBuilder output = new StringBuilder();
            Process process;

            try {
                process = getRuntimeProcess(command, getRcloneEnv());
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line);
                    }
                }

                process.waitFor();
                if (process.exitValue() != 0) {
                    if(!silent){
                        Toast.makeText(context, context.getString(R.string.error_getting_remotes), Toast.LENGTH_SHORT).show();
                    }
                    logErrorOutput(process);
                    return new ArrayList<>();
                }

                remotesJSON = new JSONArray(output.toString());
            } catch (IOException | InterruptedException | JSONException e) {
                FLog.e(TAG, "getRemotes: error retrieving remotes", e);
                return new ArrayList<>();
            }

            try {
                FileWriter fw = new FileWriter(file.getAbsoluteFile());
                BufferedWriter bw = new BufferedWriter(fw);
                bw.write(remotesJSON.toString(4));
                bw.close();
            } catch (IOException e) {
                Toast.makeText(context, context.getString(R.string.error_getting_remotes), Toast.LENGTH_SHORT).show();
                FLog.e(TAG, "Could not save providers to cache!", e);
                return new ArrayList<>();
            }
        } else {
            StringBuilder fileContent = new StringBuilder();
            try {
                FileInputStream inputstream = new FileInputStream(file);
                byte[] buffer = new byte[8128];
                int size;
                while ((size = inputstream.read(buffer)) != -1) {
                    fileContent.append(new String(buffer, 0, size));
                }
            } catch (IOException e) {
                Toast.makeText(context, context.getString(R.string.error_getting_remotes), Toast.LENGTH_SHORT).show();
                FLog.e(TAG, "Could not read cached providers, but the file exists! Please clear your app cache.", e);
                return new ArrayList<>();
            }

            remotesJSON = new JSONArray(fileContent.toString());
        }

        ArrayList<Provider> providerItems = new ArrayList<>();

        for (int i = 0; i < remotesJSON.length(); i++) {
            providerItems.add(Provider.Companion.newInstance(remotesJSON.getJSONObject(i)));
        }

        return providerItems;
    }

    public Provider getProvider(String name) throws JSONException {
        for (Provider provider : getProviders()) {
            if(provider.getName().equals(name)){
                return provider;
            }
        }
        return null;
    }

}
