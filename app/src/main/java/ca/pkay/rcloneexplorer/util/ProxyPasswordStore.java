package ca.pkay.rcloneexplorer.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.preference.PreferenceManager;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import ca.pkay.rcloneexplorer.R;

/**
 * Encrypts the proxy password using AndroidKeyStore AES-GCM and migrates any legacy plaintext preference.
 */
public final class ProxyPasswordStore {

    public static final String PREF_KEY_ENCRYPTED = "pref_key_proxy_password_enc";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "cloudbridge_proxy_password_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final byte VERSION_V1 = 1;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private ProxyPasswordStore() {
    }

    @NonNull
    public static String getDecryptedPassword(@NonNull Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String legacyKey = context.getString(R.string.pref_key_proxy_password);
        String encrypted = prefs.getString(PREF_KEY_ENCRYPTED, "");
        if (encrypted != null && !encrypted.isEmpty()) {
            try {
                SecretKey key = getOrCreateKey(false);
                if (key == null) {
                    return "";
                }
                String decrypted = decryptWithKey(key, Base64.decode(encrypted, Base64.NO_WRAP));
                if (prefs.contains(legacyKey)) {
                    prefs.edit().remove(legacyKey).apply();
                }
                return decrypted;
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                FLog.w("ProxyPasswordStore", "Failed to decrypt stored proxy password");
                return "";
            }
        }

        String legacyPlaintext = prefs.getString(legacyKey, "");
        if (legacyPlaintext != null && !legacyPlaintext.isEmpty()) {
            setPassword(context, legacyPlaintext);
            return legacyPlaintext;
        }
        return "";
    }

    public static void setPassword(@NonNull Context context, @Nullable String password) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String legacyKey = context.getString(R.string.pref_key_proxy_password);
        if (password == null || password.isEmpty()) {
            prefs.edit().remove(legacyKey).remove(PREF_KEY_ENCRYPTED).apply();
            return;
        }
        try {
            SecretKey key = getOrCreateKey(true);
            if (key == null) {
                FLog.e("ProxyPasswordStore", "Failed to obtain encryption key for proxy password");
                return;
            }
            byte[] encoded = encryptWithKey(key, password, SECURE_RANDOM);
            prefs.edit()
                    .remove(legacyKey)
                    .putString(PREF_KEY_ENCRYPTED, Base64.encodeToString(encoded, Base64.NO_WRAP))
                    .apply();
        } catch (GeneralSecurityException e) {
            FLog.e("ProxyPasswordStore", "Failed to encrypt proxy password", e);
        }
    }

    public static boolean hasPassword(@NonNull Context context) {
        return !getDecryptedPassword(context).isEmpty();
    }

    public static void clear(@NonNull Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String legacyKey = context.getString(R.string.pref_key_proxy_password);
        prefs.edit().remove(legacyKey).remove(PREF_KEY_ENCRYPTED).apply();
    }

    @NonNull
    public static String buildProxyUrl(
            @Nullable String protocol,
            @Nullable String host,
            int port,
            @Nullable String user,
            @Nullable String pass) {
        String safeProtocol = (protocol != null && !protocol.isEmpty()) ? protocol : "http";
        String safeHost = (host != null && !host.isEmpty()) ? host : "localhost";
        String safeUser = user != null ? user : "";
        String safePass = pass != null ? pass : "";
        String auth = "";
        if (!safeUser.isEmpty() || !safePass.isEmpty()) {
            auth = encodeUserInfoComponent(safeUser) + ":" + encodeUserInfoComponent(safePass) + "@";
        }
        return safeProtocol + "://" + auth + safeHost + ":" + port;
    }

    @NonNull
    private static String encodeUserInfoComponent(@NonNull String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    @VisibleForTesting
    public static byte[] encryptWithKey(@NonNull SecretKey key, @NonNull String plaintext, @NonNull SecureRandom random)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        // AndroidKeyStore keys with setRandomizedEncryptionRequired(true) reject caller-provided
        // GCMParameterSpec IVs and require the provider to generate the IV on init(ENCRYPT_MODE, key).
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != GCM_IV_BYTES) {
            throw new GeneralSecurityException("Unexpected GCM IV length");
        }
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[1 + iv.length + ciphertext.length];
        out[0] = VERSION_V1;
        System.arraycopy(iv, 0, out, 1, iv.length);
        System.arraycopy(ciphertext, 0, out, 1 + iv.length, ciphertext.length);
        return out;
    }

    @VisibleForTesting
    public static String decryptWithKey(@NonNull SecretKey key, @NonNull byte[] payload)
            throws GeneralSecurityException {
        if (payload.length < 1 + GCM_IV_BYTES + 16 || payload[0] != VERSION_V1) {
            throw new GeneralSecurityException("Invalid encrypted payload");
        }
        byte[] iv = new byte[GCM_IV_BYTES];
        System.arraycopy(payload, 1, iv, 0, GCM_IV_BYTES);
        int cipherLen = payload.length - 1 - GCM_IV_BYTES;
        byte[] ciphertext = new byte[cipherLen];
        System.arraycopy(payload, 1 + GCM_IV_BYTES, ciphertext, 0, cipherLen);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plain = cipher.doFinal(ciphertext);
        return new String(plain, StandardCharsets.UTF_8);
    }

    @Nullable
    private static SecretKey getOrCreateKey(boolean createIfMissing) throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            }
            if (!createIfMissing) {
                return null;
            }
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build();
            keyGenerator.init(spec);
            return keyGenerator.generateKey();
        } catch (GeneralSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralSecurityException("Unable to load AndroidKeyStore", e);
        }
    }
}
