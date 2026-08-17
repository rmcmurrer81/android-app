package com.kiraworld.sarahtravel;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.net.URI;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureStore {
    private static final String ALIAS = "SarahMorganEncryptedSecrets";
    private static final String PREF = "secure_preferences";
    private static final String TEAM_BACKEND_SENTINEL = "__SARAH_TEAM_BACKEND__";
    private static final String SARAH_BACKEND_URL = "sarah_backend_url";
    private static final String SARAH_BACKEND_ACCESS = "sarah_backend_access";

    private SecureStore() { }

    /**
     * Retained only so older source branches still compile. Sarah does not ask
     * an app user to save a provider key.
     */
    public static void saveApiKey(Context context, String value) throws Exception {
        if (value != null && !value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Direct provider keys are not accepted; activate Sarah's protected backend instead.");
        }
    }

    /** Compatibility route marker; the actual bearer never leaves this store. */
    public static String loadApiKey(Context context) {
        return hasSarahBackendAccess(context) ? TEAM_BACKEND_SENTINEL : "";
    }

    /**
     * Atomically persist one owner-provided HTTPS route and revocable access
     * code under Android Keystore AES-GCM. Neither value is compiled into the
     * APK, and provider credentials are never accepted here.
     */
    public static void saveSarahBackendAccess(
            Context context,
            String backendUrl,
            String accessCode) throws Exception {
        String normalizedUrl = normalizeProtectedBackendUrl(backendUrl);
        String normalizedCode = accessCode == null ? "" : accessCode.trim();
        if (!normalizedCode.matches("[A-Za-z0-9_-]{32,256}")) {
            throw new IllegalArgumentException(
                    "The Sarah access code must be 32-256 URL-safe letters, numbers, _ or -.");
        }

        SecretKey key = getOrCreateKey(ALIAS);
        EncryptedValue encryptedUrl = encrypt(key, normalizedUrl);
        EncryptedValue encryptedCode = encrypt(key, normalizedCode);
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putString(SARAH_BACKEND_URL + "_iv", encryptedUrl.iv)
                .putString(SARAH_BACKEND_URL + "_data", encryptedUrl.data)
                .putString(SARAH_BACKEND_ACCESS + "_iv", encryptedCode.iv)
                .putString(SARAH_BACKEND_ACCESS + "_data", encryptedCode.data)
                .apply();
    }

    public static String loadSarahBackendUrl(Context context) {
        return context == null ? "" : loadSecret(context, SARAH_BACKEND_URL);
    }

    public static String loadSarahBackendToken(Context context) {
        return context == null ? "" : loadSecret(context, SARAH_BACKEND_ACCESS);
    }

    public static boolean hasSarahBackendAccess(Context context) {
        return !loadSarahBackendUrl(context).isEmpty()
                && !loadSarahBackendToken(context).isEmpty();
    }

    public static void clearSarahBackendAccess(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .remove(SARAH_BACKEND_URL + "_iv")
                .remove(SARAH_BACKEND_URL + "_data")
                .remove(SARAH_BACKEND_ACCESS + "_iv")
                .remove(SARAH_BACKEND_ACCESS + "_data")
                .apply();
    }

    public static void saveDealBackendToken(Context context, String value) throws Exception {
        saveSecret(context, "deal_backend", value);
    }

    public static String loadDealBackendToken(Context context) {
        return loadSecret(context, "deal_backend");
    }

    private static void saveSecret(Context context, String name, String value) throws Exception {
        SecretKey key = getOrCreateKey(ALIAS);
        EncryptedValue encrypted = encrypt(key, value == null ? "" : value);
        SharedPreferences preferences = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        preferences.edit()
                .putString(name + "_iv", encrypted.iv)
                .putString(name + "_data", encrypted.data)
                .apply();
    }

    private static EncryptedValue encrypt(SecretKey key, String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return new EncryptedValue(
                Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP),
                Base64.encodeToString(encrypted, Base64.NO_WRAP));
    }

    private static String normalizeProtectedBackendUrl(String value) {
        String cleaned = value == null ? "" : value.trim();
        try {
            URI uri = new URI(cleaned);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getHost().trim().isEmpty()
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException(
                        "Sarah's protected connection must be a plain HTTPS address.");
            }
        } catch (java.net.URISyntaxException error) {
            throw new IllegalArgumentException(
                    "Sarah's protected connection address is not valid.");
        }
        while (cleaned.endsWith("/")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        for (String suffix : new String[]{"/chat", "/search", "/voice", "/capabilities"}) {
            if (cleaned.endsWith(suffix)) {
                cleaned = cleaned.substring(0, cleaned.length() - suffix.length());
                break;
            }
        }
        return cleaned;
    }

    private static final class EncryptedValue {
        final String iv;
        final String data;

        EncryptedValue(String iv, String data) {
            this.iv = iv;
            this.data = data;
        }
    }

    private static String loadSecret(Context context, String name) {
        try {
            SharedPreferences preferences = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String iv = preferences.getString(name + "_iv", "");
            String data = preferences.getString(name + "_data", "");
            if (iv.isEmpty() || data.isEmpty()) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(ALIAS),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static SecretKey getOrCreateKey(String alias) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(alias)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(alias, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
