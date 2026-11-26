package ca.pkay.rcloneexplorer.util;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.google.android.material.color.DynamicColors;

import ca.pkay.rcloneexplorer.R;

public class ActivityHelper {

    private static final String TAG = "ActivityHelper";

    public static final int FOLLOW_SYSTEM = 0;
    public static final int DARK = 1;
    public static final int LIGHT = 2;
    public static final int AMOLED = 3;



    @SuppressLint("CheckResult")
    public static void tryStartActivity(Context context, Intent intent) {
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            showErrorToast(context, intent);
        }
    }

    @SuppressLint("CheckResult")
    public static void tryStartActivityForResult(Fragment fragment, Intent intent, int requestCode) {
        try {
            if (!fragment.isAdded()) {
                FLog.w(TAG, "tryStartActivityForResult: fragment is not attached, can't start");
                return;
            }
            fragment.startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException e) {
            showErrorToast(fragment.getContext(), intent);
        }
    }

    public static void tryStartActivityForResult(Activity activity, Intent intent, int requestCode) {
        try {
            activity.startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException e) {
            showErrorToast(activity, intent);
        }
    }

    private static boolean requiresDocumentsUI(Intent intent) {
        if (null == intent || null == intent.getAction()) {
            return false;
        }
        switch (intent.getAction()) {
            case Intent.ACTION_GET_CONTENT:
            case Intent.ACTION_CREATE_DOCUMENT:
            case Intent.ACTION_OPEN_DOCUMENT:
            case Intent.ACTION_OPEN_DOCUMENT_TREE:
                return true;
            default:
                return false;
        }
    }

    private static void showErrorToast(final Context context, Intent intent) {
        Looper main = Looper.getMainLooper();
        String errorMessage = requiresDocumentsUI(intent) ?
                context.getString(R.string.documentsui_missing) :
                context.getString(R.string.no_app_found_for_this_link);
        Toast errorToast = Toast.makeText(context, errorMessage, Toast.LENGTH_LONG);
        if (main.equals(Looper.myLooper())) {
            errorToast.show();
        } else {
            new Handler(main).post(errorToast::show);
        }
    }

    public static void tryStartService(@NonNull Context context, @NonNull Intent intent) {
        try {
            context.startService(intent);
        } catch (IllegalStateException e) {
            FLog.e(TAG, "Host context state is invalid, not starting service", e);
        }
    }

    public static void applyTheme(Activity activity) {
        applyDarkMode(activity);
        DynamicColors.applyToActivityIfAvailable(activity);
        if (getThemeMode(activity).equals(String.valueOf(AMOLED))) {
            activity.getTheme().applyStyle(R.style.ThemeOverlay_CloudBridge_Amoled, true);
            activity.getWindow().setBackgroundDrawableResource(android.R.color.black);
        }
    }

    /** Reserve bars, cutouts and the keyboard once, outside each activity's layout. */
    public static void applyWindowInsets(Activity activity) {
        Window window = activity.getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
        }
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        boolean lightBars = !isDarkTheme(activity);
        controller.setAppearanceLightStatusBars(lightBars);
        controller.setAppearanceLightNavigationBars(lightBars);
        View content = activity.findViewById(android.R.id.content);
        int left = content.getPaddingLeft();
        int top = content.getPaddingTop();
        int right = content.getPaddingRight();
        int bottom = content.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            int bottomInset = Math.max(bars.bottom, ime.bottom);
            View drawerLayout = view.findViewById(R.id.drawer_layout);
            View navView = view.findViewById(R.id.nav_view);
            if (drawerLayout instanceof android.view.ViewGroup && navView != null) {
                view.setPadding(0, 0, 0, 0);
                android.view.ViewGroup dg = (android.view.ViewGroup) drawerLayout;
                if (dg.getChildCount() > 0) {
                    View mainChild = dg.getChildAt(0);
                    mainChild.setPadding(bars.left, bars.top, bars.right, bottomInset);
                }
                navView.setPadding(bars.left, bars.top, 0, bars.bottom);
            } else {
                view.setPadding(left + bars.left, top + bars.top, right + bars.right,
                        bottom + bottomInset);
            }
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(content);
    }

    public static boolean isDarkTheme(Context context) {
        String mode = getThemeMode(context);

        if(mode.equals(String.valueOf(LIGHT))) {
            return false;
        } else if(mode.equals(String.valueOf(DARK)) || mode.equals(String.valueOf(AMOLED))) {
            return true;
        } else {
            Configuration config = context.getApplicationContext().getResources().getConfiguration();
            int currentNightMode = config.uiMode & Configuration.UI_MODE_NIGHT_MASK;
            switch (currentNightMode) {
                case Configuration.UI_MODE_NIGHT_NO:
                    return false;
                case Configuration.UI_MODE_NIGHT_YES:
                    return true;
            }
        }
        return false;
    }

    public static String getThemeMode(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String key = context.getString(R.string.pref_key_theme);
        String oldKey = context.getString(R.string.pref_key_theme_old);
        if (preferences.contains(oldKey)) {
            SharedPreferences.Editor editor = preferences.edit();
            if (!preferences.contains(key)) {
                Object oldValue = preferences.getAll().get(oldKey);
                if (oldValue instanceof Integer || oldValue instanceof String) {
                    editor.putString(key, String.valueOf(oldValue));
                }
            }
            editor.remove(oldKey).apply();
        }
        return preferences.getString(key, String.valueOf(FOLLOW_SYSTEM));
    }

    public static void applyDarkMode(Context context) {
        String mode = getThemeMode(context);

        if(mode.equals(String.valueOf(LIGHT))) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        } else if(mode.equals(String.valueOf(DARK)) || mode.equals(String.valueOf(AMOLED))) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }
}
