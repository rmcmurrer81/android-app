package com.kiraworld.sarahtravel;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureStore {
    private static final String ALIAS = "SarahMorganEncryptedSecrets";
    private static final String PREF = "secure_preferences";

    private SecureStore() { }

    /**
     * Retained only so older source branches still compile. Sarah 1.5 does not
     * ask an app user to save a provider key.
     */
    public static void saveApiKey(Context context, String value) throws Exception {
        saveSecret(context, "legacy_model_user_key", value);
    }

    /**
     * The conversation credential is owned by the build, not by the person who
     * installs the APK. Old user-entered keys are intentionally ignored.
     */
    public static String loadApiKey(Context context) {
        return SarahModelConfig.apiKey();
    }

    public static void saveDealBackendToken(Context context, String value) throws Exception {
        saveSecret(context, "deal_backend", value);
    }

    public static String loadDealBackendToken(Context context) {
        return loadSecret(context, "deal_backend");
    }

    private static void saveSecret(Context context, String name, String value) throws Exception {
        SecretKey key = getOrCreateKey(ALIAS);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        SharedPreferences preferences = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        preferences.edit()
                .putString(name + "_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString(name + "_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .apply();
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
