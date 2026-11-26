package ca.pkay.rcloneexplorer.Database.json;


import android.content.Context;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.regex.Pattern;

import ca.pkay.rcloneexplorer.Database.DatabaseHandler;
import ca.pkay.rcloneexplorer.Items.Filter;
import ca.pkay.rcloneexplorer.Items.Task;
import ca.pkay.rcloneexplorer.Items.Trigger;

public class Importer {

    private static final Pattern SAFE_REMOTE_NAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_. -]*$");

    public static void importJson(String json, Context context) throws JSONException {
        validateJson(json);
        ArrayList<Trigger> triggers = createTriggerlist(json);
        ArrayList<Filter> filters = createFilterList(json);
        ArrayList<Task> tasks = createTasklist(json);
        String internalDataDir = null;
        if (context != null && context.getApplicationInfo() != null && context.getApplicationInfo().dataDir != null) {
            try {
                internalDataDir = new File(context.getApplicationInfo().dataDir).getCanonicalPath();
            } catch (IOException ignored) {
                internalDataDir = null;
            }
        }
        ArrayList<Task> safeTasks = new ArrayList<>();
        for (Task task : tasks) {
            if (isSafeTask(task, internalDataDir)) {
                safeTasks.add(task);
            }
        }
        new DatabaseHandler(context).replaceAll(triggers, filters, safeTasks);
    }

    @VisibleForTesting
    public static boolean isSafeTask(@Nullable Task task, @Nullable String internalDataDir) {
        if (task == null || internalDataDir == null || internalDataDir.isEmpty()) {
            return false;
        }
        String remoteId = task.getRemoteId();
        if (remoteId != null && !remoteId.isEmpty() && !isSafeRemoteName(remoteId)) {
            return false;
        }
        String remoteId2 = task.getRemoteId2();
        if (remoteId2 != null && !remoteId2.isEmpty() && !isSafeRemoteName(remoteId2)) {
            return false;
        }
        String localPath = task.getLocalPath();
        if (localPath != null && !localPath.isEmpty()) {
            return isSafeLocalPath(localPath, internalDataDir);
        }
        return true;
    }

    public static boolean isSafeRemoteName(@Nullable String remoteName) {
        return remoteName != null && SAFE_REMOTE_NAME_PATTERN.matcher(remoteName).matches();
    }

    public static boolean isSafeLocalPath(@Nullable String localPath, @Nullable String internalDataDir) {
        if (localPath == null || localPath.isEmpty()
                || internalDataDir == null || internalDataDir.isEmpty()) {
            return false;
        }
        if (!localPath.startsWith("/") || localPath.indexOf(':') >= 0 || localPath.indexOf('\0') >= 0) {
            return false;
        }
        if (localPath.equals("/proc") || localPath.startsWith("/proc/")) {
            return false;
        }
        try {
            String canonicalDataDir = new File(internalDataDir).getCanonicalPath();
            String canonicalLocal = new File(localPath).getCanonicalPath();
            if ("/".equals(canonicalLocal)) {
                return false;
            }
            if (canonicalLocal.equals(canonicalDataDir)
                    || canonicalLocal.startsWith(canonicalDataDir + File.separator)
                    || canonicalDataDir.startsWith(canonicalLocal + File.separator)) {
                return false;
            }
            if (canonicalLocal.equals("/proc") || canonicalLocal.startsWith("/proc/")) {
                return false;
            }
            String pkgName = new File(canonicalDataDir).getName();
            if (!pkgName.isEmpty() && isAliasOfInternalDataDir(canonicalLocal, pkgName)) {
                return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @android.annotation.SuppressLint("SdCardPath")
    private static boolean isAliasOfInternalDataDir(String canonicalPath, String packageName) {
        String dataDataPrefix = "/data/data/" + packageName;
        if (canonicalPath.equals(dataDataPrefix)
                || canonicalPath.startsWith(dataDataPrefix + File.separator)
                || dataDataPrefix.startsWith(canonicalPath + File.separator)) {
            return true;
        }
        if (canonicalPath.equals("/data/user") || canonicalPath.equals("/data/user_de")) {
            return true;
        }
        if (canonicalPath.startsWith("/data/user/") || canonicalPath.startsWith("/data/user_de/")) {
            String rest = canonicalPath.startsWith("/data/user_de/")
                    ? canonicalPath.substring("/data/user_de/".length())
                    : canonicalPath.substring("/data/user/".length());
            // Block user root ancestor (e.g. /data/user_de/0) as well as /<uid>/<pkg> and its children
            if (!rest.contains("/")) {
                return true;
            }
            String suffix = "/" + packageName;
            int idx = canonicalPath.indexOf(suffix);
            if (idx > 0 && (idx + suffix.length() == canonicalPath.length()
                    || canonicalPath.charAt(idx + suffix.length()) == '/')) {
                return true;
            }
        }
        return false;
    }

    public static void validateJson(String json) throws JSONException {
        validateRoot(json);
        HashSet<Long> ids = new HashSet<>();
        for (Trigger item : createTriggerlist(json)) {
            if (!ids.add(item.getId())) throw new JSONException("Duplicate trigger ID");
        }
        ids.clear();
        for (Filter item : createFilterList(json)) {
            if (!ids.add(item.getId())) throw new JSONException("Duplicate filter ID");
        }
        ids.clear();
        for (Task item : createTasklist(json)) {
            if (!ids.add(item.getId())) throw new JSONException("Duplicate task ID");
        }
    }

    private static void validateRoot(String json) throws JSONException {
        JSONObject reader = new JSONObject(json);
        if (!reader.has("trigger") && !reader.has("filters") && !reader.has("tasks")) {
            throw new JSONException("No backup sections found");
        }
    }

    public static ArrayList<Trigger> createTriggerlist(String content) throws JSONException {
        ArrayList<Trigger> result = new ArrayList<>();
        JSONObject reader = new JSONObject(content);
        JSONArray array = reader.has("trigger") ? reader.getJSONArray("trigger") : new JSONArray();
        for (int i = 0; i < array.length(); i++) {
            JSONObject triggerObject = array.getJSONObject(i);
            result.add(Trigger.Companion.fromString(triggerObject.toString()));
        }
        return result;
    }

    public static ArrayList<Task> createTasklist(String content) throws JSONException {
        ArrayList<Task> result = new ArrayList<>();
        JSONObject reader = new JSONObject(content);
        JSONArray array = reader.has("tasks") ? reader.getJSONArray("tasks") : new JSONArray();
        for (int i = 0; i < array.length(); i++) {
            JSONObject taskObject = array.getJSONObject(i);
            result.add(Task.Companion.fromString(taskObject.toString()));
        }
        return result;
    }
    public static ArrayList<Filter> createFilterList(String content) throws JSONException {
        ArrayList<Filter> result = new ArrayList<>();
        JSONObject reader = new JSONObject(content);
        JSONArray array = reader.has("filters") ? reader.getJSONArray("filters") : new JSONArray();
        for (int i = 0; i < array.length(); i++) {
            JSONObject filterObject = array.getJSONObject(i);
            result.add(Filter.Companion.fromString(filterObject.toString()));
        }
        return result;
    }
}
