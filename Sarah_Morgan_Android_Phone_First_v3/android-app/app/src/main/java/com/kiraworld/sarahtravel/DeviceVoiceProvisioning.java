package com.kiraworld.sarahtravel;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * Provisions a private hackathon ElevenLabs credential to one Android install
 * without committing the raw key or placing it statically in the APK.
 *
 * Flow:
 * 1. Sarah generates a non-exportable RSA private key in Android Keystore.
 * 2. The app displays only the public setup code.
 * 3. The team encrypts the restricted ElevenLabs key to that public key.
 * 4. A later APK contains only ciphertext and the target fingerprint.
 * 5. Only the phone holding the matching private key can decrypt it.
 */
public final class DeviceVoiceProvisioning {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "SarahMorganElevenLabsDeviceKeyV1";
    private static final String TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final OAEPParameterSpec OAEP_SHA256_MGF1_SHA1 = new OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA1,
            PSource.PSpecified.DEFAULT);
    private static volatile String cachedApiKey;

    private DeviceVoiceProvisioning() { }

    public static String setupCode(Context context) throws Exception {
        PublicKey key = ensurePublicKey();
        return "SARAHVOICE1."
                + fingerprint(key)
                + "."
                + Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP);
    }

    public static String deviceFingerprint(Context context) {
        try {
            return fingerprint(ensurePublicKey());
        } catch (Exception ignored) {
            return "unavailable";
        }
    }

    public static boolean payloadTargetsThisPhone(Context context) {
        String expected = clean(VoiceProvisionedKeyPayload.TARGET_DEVICE_FINGERPRINT);
        return !expected.isEmpty()
                && expected.equalsIgnoreCase(deviceFingerprint(context));
    }

    public static String apiKey(Context context) {
        String existing = cachedApiKey;
        if (existing != null) return existing;
        synchronized (DeviceVoiceProvisioning.class) {
            if (cachedApiKey != null) return cachedApiKey;
            cachedApiKey = decryptPayload(context);
            return cachedApiKey;
        }
    }

    public static boolean isActivated(Context context) {
        return !apiKey(context).isEmpty();
    }

    public static String status(Context context) {
        if (isActivated(context)) {
            return "Sarah Morgan ElevenLabs voice is activated for this phone.";
        }
        if (!clean(VoiceProvisionedKeyPayload.ENCRYPTED_API_KEY_BASE64).isEmpty()
                && !payloadTargetsThisPhone(context)) {
            return "This APK contains a voice activation for a different phone. Generate this phone's setup code.";
        }
        return "This phone has a secure setup code, but its encrypted ElevenLabs activation has not been added yet.";
    }

    private static String decryptPayload(Context context) {
        try {
            String encrypted = clean(VoiceProvisionedKeyPayload.ENCRYPTED_API_KEY_BASE64);
            if (encrypted.isEmpty() || !payloadTargetsThisPhone(context)) return "";

            KeyStore store = KeyStore.getInstance(KEYSTORE);
            store.load(null);
            PrivateKey privateKey = (PrivateKey) store.getKey(ALIAS, null);
            if (privateKey == null) return "";

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, privateKey, OAEP_SHA256_MGF1_SHA1);
            byte[] clear = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP));
            String key = new String(clear, StandardCharsets.UTF_8).trim();
            if (key.length() < 24 || !key.startsWith("sk_")) return "";
            return key;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static PublicKey ensurePublicKey() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA,
                    KEYSTORE);
            generator.initialize(new KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            generator.generateKeyPair();
            store.load(null);
        }
        if (store.getCertificate(ALIAS) == null) {
            throw new IllegalStateException("Android Keystore did not return Sarah's provisioning certificate.");
        }
        return store.getCertificate(ALIAS).getPublicKey();
    }

    private static String fingerprint(PublicKey key) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            value.append(String.format(Locale.US, "%02x", digest[i] & 0xff));
        }
        return value.toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
