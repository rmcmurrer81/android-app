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

/**
 * Encrypts small per-profile JSON records with Android Keystore. It is not a
 * password manager and should never store airline, hotel, bank, or booking-site
 * passwords.
 */
public final class SecureProfileVault {
    private static final String PREFS = "sarah_profile_vault";
    private static final String ALIAS = "SarahProfileVaultAesV1";

    private SecureProfileVault() { }

    public static void put(Context context, String namespace, String personId, String value) {
        putVerified(context, namespace, personId, value);
    }

    public static boolean putVerified(
            Context context,
            String namespace,
            String personId,
            String value) {
        String key = key(namespace, personId);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(clean(value).getBytes(StandardCharsets.UTF_8));
            boolean committed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(key + "_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString(key + "_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .commit();
            return committed && clean(value).equals(get(context, namespace, personId));
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String get(Context context, String namespace, String personId) {
        try {
            String key = key(namespace, personId);
            SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String iv = preferences.getString(key + "_iv", "");
            String data = preferences.getString(key + "_data", "");
            if (iv.isEmpty() || data.isEmpty()) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(
                    cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)),
                    StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    public static void remove(Context context, String namespace, String personId) {
        removeVerified(context, namespace, personId);
    }

    /** Synchronous removal used only after a migration destination is verified. */
    public static boolean removeVerified(Context context, String namespace, String personId) {
        String key = key(namespace, personId);
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean committed = preferences
                .edit()
                .remove(key + "_iv")
                .remove(key + "_data")
                .commit();
        return committed
                && !preferences.contains(key + "_iv")
                && !preferences.contains(key + "_data");
    }

    /**
     * Move one encrypted record only when the confirmed target is empty.
     * A conflicting target preserves both records for a store-specific merge.
     */
    public static boolean moveIfTargetEmpty(
            Context context,
            String namespace,
            String oldPersonId,
            String newPersonId) {
        String oldKey = key(namespace, oldPersonId);
        String newKey = key(namespace, newPersonId);
        if (oldKey.equals(newKey)) return true;
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String oldIv = preferences.getString(oldKey + "_iv", "");
        String oldData = preferences.getString(oldKey + "_data", "");
        if (oldIv.isEmpty() || oldData.isEmpty()) return true;
        String newIv = preferences.getString(newKey + "_iv", "");
        String newData = preferences.getString(newKey + "_data", "");
        if (!newIv.isEmpty() && !newData.isEmpty()) {
            if (oldIv.equals(newIv) && oldData.equals(newData)) {
                return preferences.edit().remove(oldKey + "_iv").remove(oldKey + "_data").commit();
            }
            return false;
        }
        boolean written = preferences.edit()
                .putString(newKey + "_iv", oldIv)
                .putString(newKey + "_data", oldData)
                .commit();
        if (written
                && oldIv.equals(preferences.getString(newKey + "_iv", ""))
                && oldData.equals(preferences.getString(newKey + "_data", ""))) {
            return preferences.edit().remove(oldKey + "_iv").remove(oldKey + "_data").commit();
        }
        return false;
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    private static String key(String namespace, String personId) {
        return clean(namespace).replaceAll("[^A-Za-z0-9_-]", "_") + "_p_"
                + clean(personId).replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
