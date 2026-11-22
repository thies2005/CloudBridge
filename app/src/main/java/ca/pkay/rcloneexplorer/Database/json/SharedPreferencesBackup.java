package ca.pkay.rcloneexplorer.Database.json;

import static ca.pkay.rcloneexplorer.util.ActivityHelper.AMOLED;
import static ca.pkay.rcloneexplorer.util.ActivityHelper.DARK;
import static ca.pkay.rcloneexplorer.util.ActivityHelper.FOLLOW_SYSTEM;
import static ca.pkay.rcloneexplorer.util.ActivityHelper.LIGHT;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.preference.PreferenceManager;

import org.json.JSONException;
import org.json.JSONObject;

import ca.pkay.rcloneexplorer.R;

public class SharedPreferencesBackup {

    public static String export(Context context) throws JSONException {

        //General Settings
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean showThumbnails = sharedPreferences.getBoolean(context.getString(R.string.pref_key_show_thumbnails), false);
        boolean isWifiOnly = sharedPreferences.getBoolean(context.getString(R.string.pref_key_wifi_only_transfers), false);
        boolean allowWhileIdle = sharedPreferences.getBoolean(context.getString(R.string.shared_preferences_allow_sync_trigger_while_idle), false);
        boolean useProxy = sharedPreferences.getBoolean(context.getString(R.string.pref_key_use_proxy), false);
        String proxyProtocol = sharedPreferences.getString(context.getString(R.string.pref_key_proxy_protocol), "http");
        String proxyHost = sharedPreferences.getString(context.getString(R.string.pref_key_proxy_host), "localhost");
        int proxyPort = sharedPreferences.getInt(context.getString(R.string.pref_key_proxy_port), 8080);
        String proxyUser = sharedPreferences.getString(context.getString(R.string.pref_key_proxy_username), "");
        String transfers = sharedPreferences.getString(context.getString(R.string.pref_key_transfers), "4");

        // File Access
        boolean safEnabled = sharedPreferences.getBoolean(context.getString(R.string.pref_key_enable_saf), false);
        boolean refreshLaEnabled = sharedPreferences.getBoolean(context.getString(R.string.pref_key_refresh_local_aliases), true);
        boolean vcpEnabled = sharedPreferences.getBoolean(context.getString(R.string.pref_key_enable_vcp), false);
        boolean vcpDeclareLocal = sharedPreferences.getBoolean(context.getString(R.string.pref_key_vcp_declare_local), true);
        boolean vcpGrantAll = sharedPreferences.getBoolean(context.getString(R.string.pref_key_vcp_grant_all), false);

        // Look and Feel
        int oldTheme = sharedPreferences.getInt(context.getString(R.string.pref_key_theme_old), FOLLOW_SYSTEM);
        String newTheme = sharedPreferences.getString(context.getString(R.string.pref_key_theme), String.valueOf(oldTheme));
        boolean isWrapFilenames = sharedPreferences.getBoolean(context.getString(R.string.pref_key_wrap_filenames), true);

        // Notifications
        boolean appUpdates = sharedPreferences.getBoolean(context.getString(R.string.pref_key_app_updates), false);

        // Logging
        boolean useLogs = sharedPreferences.getBoolean(context.getString(R.string.pref_key_logs), false);


        JSONObject main = new JSONObject();

        main.put("showThumbnails", showThumbnails);
        main.put("isWifiOnly", isWifiOnly);
        main.put("allowWhileIdle", allowWhileIdle);
        main.put("useProxy", useProxy);
        main.put("proxyProtocol", proxyProtocol);
        main.put("proxyHost", proxyHost);
        main.put("proxyPort", proxyPort);
        main.put("proxyUser", proxyUser);
        main.put("transfers", transfers);
        main.put("safEnabled", safEnabled);
        main.put("refreshLaEnabled", refreshLaEnabled);
        main.put("vcpEnabled", vcpEnabled);
        main.put("vcpDeclareLocal", vcpDeclareLocal);
        main.put("vcpGrantAll", vcpGrantAll);
        main.put("isDarkTheme", newTheme);
        main.put("isWrapFilenames", isWrapFilenames);
        main.put("appUpdates", appUpdates);
        main.put("useLogs", useLogs);

        return main.toString();
    }

    /** Check everything that can fail during parsing before replacing a ZIP's configuration. */
    public static void validateJson(String json) throws JSONException {
        JSONObject object = new JSONObject(json);
        for (String key : new String[]{"showThumbnails", "isWifiOnly", "safEnabled", "refreshLaEnabled",
                "vcpEnabled", "vcpDeclareLocal", "isWrapFilenames", "appUpdates", "useLogs"}) {
            object.getBoolean(key);
        }
        Object theme = object.get("isDarkTheme");
        if (theme instanceof String) {
            try {
                Integer.parseInt((String) theme);
            } catch (NumberFormatException e) {
                throw new JSONException("Invalid theme");
            }
        } else if (!(theme instanceof Boolean)) {
            throw new JSONException("Invalid theme");
        }
    }

    public static void importJson(String json, Context context) throws JSONException {
        validateJson(json);
        JSONObject jsonObject = new JSONObject(json);

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();

        //General Settings
        editor.putBoolean(context.getString(R.string.pref_key_show_thumbnails), jsonObject.getBoolean("showThumbnails"));
        editor.putBoolean(context.getString(R.string.pref_key_wifi_only_transfers), jsonObject.getBoolean("isWifiOnly"));
        // Never silently enable outbound proxy interception or import attacker proxy endpoints/credentials
        editor.putBoolean(context.getString(R.string.pref_key_use_proxy), false);
        editor.putBoolean(context.getString(R.string.shared_preferences_allow_sync_trigger_while_idle), jsonObject.optBoolean("allowWhileIdle", false));
        editor.putString(context.getString(R.string.pref_key_proxy_protocol), "http");
        editor.putString(context.getString(R.string.pref_key_proxy_host), "localhost");
        editor.putInt(context.getString(R.string.pref_key_proxy_port), 8080);
        editor.putString(context.getString(R.string.pref_key_proxy_username), "");
        editor.putString(context.getString(R.string.pref_key_transfers), sanitizeTransfers(jsonObject.optString("transfers", "4")));

        // File Access: never silently enable privileged storage/content exposure from imported backups
        editor.putBoolean(context.getString(R.string.pref_key_enable_saf), false);
        editor.putBoolean(context.getString(R.string.pref_key_refresh_local_aliases), jsonObject.getBoolean("refreshLaEnabled"));
        editor.putBoolean(context.getString(R.string.pref_key_enable_vcp), false);
        editor.putBoolean(context.getString(R.string.pref_key_vcp_declare_local), jsonObject.getBoolean("vcpDeclareLocal"));
        // Never silently enable grant-all URI permissions from an imported backup
        editor.putBoolean(context.getString(R.string.pref_key_vcp_grant_all), false);

        // Look and Feel
        // The type changed. So we try to use boolean first, and if it fails we use the proper int
        int valueForTheme = sanitizeTheme(jsonObject.get("isDarkTheme"));
        editor.putString(context.getString(R.string.pref_key_theme), String.valueOf(valueForTheme));

        editor.putBoolean(context.getString(R.string.pref_key_wrap_filenames), jsonObject.getBoolean("isWrapFilenames"));

        // Notifications
        editor.putBoolean(context.getString(R.string.pref_key_app_updates), jsonObject.getBoolean("appUpdates"));

        // Logging: never silently enable sensitive logging from imported backups
        editor.putBoolean(context.getString(R.string.pref_key_logs), false);

        editor.apply();
    }

    @VisibleForTesting
    public static String sanitizeTransfers(@Nullable String raw) {
        if (raw == null) {
            return "4";
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < 1) {
                parsed = 1;
            } else if (parsed > 32) {
                parsed = 32;
            }
            return String.valueOf(parsed);
        } catch (NumberFormatException e) {
            return "4";
        }
    }

    @VisibleForTesting
    public static int sanitizeTheme(@Nullable Object rawTheme) {
        if (rawTheme instanceof Boolean) {
            return ((Boolean) rawTheme) ? DARK : LIGHT;
        }
        int parsed = FOLLOW_SYSTEM;
        if (rawTheme instanceof Number) {
            parsed = ((Number) rawTheme).intValue();
        } else if (rawTheme instanceof String) {
            try {
                parsed = Integer.parseInt(((String) rawTheme).trim());
            } catch (NumberFormatException e) {
                return FOLLOW_SYSTEM;
            }
        }
        if (parsed == FOLLOW_SYSTEM || parsed == DARK || parsed == LIGHT || parsed == AMOLED) {
            return parsed;
        }
        return FOLLOW_SYSTEM;
    }

}
