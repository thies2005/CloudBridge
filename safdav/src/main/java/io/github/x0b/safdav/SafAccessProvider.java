package io.github.x0b.safdav;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.preference.PreferenceManager;

import java.io.IOException;
import java.security.SecureRandom;

/**
 * Saf Emulation Server
 */
public class SafAccessProvider {

    private static final String PREF_KEY_SAF_USER = "io.github.x0b.safdav.safDavUser";
    private static final String PREF_KEY_SAF_PASS = "io.github.x0b.safdav.safDavPass";
    private static final String SESSION_USER = "dav";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static SafDAVServer davServer;
    private static SafDirectServer directServer;
    private static String sessionUser;
    private static String sessionPassword;
    private static String sessionAuthHeader;

    public static synchronized SafDAVServer getServer(Context context) throws IOException {
        if (davServer == null || !davServer.isAlive()) {
            if (davServer != null) {
                try {
                    davServer.stop();
                } catch (Exception ignored) {
                }
                davServer = null;
            }
            Context appCtx = context.getApplicationContext();
            clearLegacyPersistedCredentials(appCtx);

            String user = SESSION_USER;
            String pass = generateSessionPassword();
            SafDAVServer server = new SafDAVServer(0, user, pass, appCtx);
            try {
                server.start();
                if (server.getListeningPort() <= 0 || !server.isAlive()) {
                    throw new IOException("SafDAVServer failed to bind ephemeral loopback port");
                }
                davServer = server;
                sessionUser = user;
                sessionPassword = pass;
                sessionAuthHeader = SafBase64.encodeBasicAuth(user, pass);
            } catch (IOException | RuntimeException e) {
                try {
                    server.stop();
                } catch (Exception ignored) {
                }
                throw e instanceof IOException ? (IOException) e : new IOException(e);
            }
        }
        return davServer;
    }

    @VisibleForTesting
    static String generateSessionPassword() {
        byte[] values = new byte[32];
        SECURE_RANDOM.nextBytes(values);
        return SafBase64.encodeUrlSafeNoPadding(values);
    }

    private static void clearLegacyPersistedCredentials(Context context) {
        try {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
            if (preferences.contains(PREF_KEY_SAF_PASS) || preferences.contains(PREF_KEY_SAF_USER)) {
                preferences.edit()
                        .remove(PREF_KEY_SAF_PASS)
                        .remove(PREF_KEY_SAF_USER)
                        .apply();
            }
        } catch (Exception ignored) {
        }
    }

    @Nullable
    public static synchronized String getServerUrl() {
        if (davServer != null && davServer.isAlive()) {
            return davServer.getBaseUrl();
        }
        return null;
    }

    @Nullable
    public static synchronized String getSessionAuthHeader() {
        if (davServer != null && davServer.isAlive()) {
            return sessionAuthHeader;
        }
        return null;
    }

    public static synchronized boolean isServerRunning() {
        return davServer != null && davServer.isAlive();
    }

    public static synchronized void stopServer() {
        if (davServer != null) {
            try {
                davServer.stop();
            } catch (Exception ignored) {
            }
            davServer = null;
        }
        sessionUser = null;
        sessionPassword = null;
        sessionAuthHeader = null;
    }

    public static synchronized SafDirectServer getDirectServer(Context context) {
        if (null == directServer) {
            directServer = new SafDirectServer(context.getApplicationContext());
        }
        return directServer;
    }

    @Nullable
    public static synchronized String getUser(Context context) {
        return sessionUser;
    }

    @Nullable
    public static synchronized String getPassword(Context context) {
        return sessionPassword;
    }
}
