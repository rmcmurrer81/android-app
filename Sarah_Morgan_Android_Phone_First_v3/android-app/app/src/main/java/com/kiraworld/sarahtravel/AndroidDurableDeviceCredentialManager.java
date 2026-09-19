package com.kiraworld.sarahtravel;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;

/**
 * AndroidKeyStore foundation for the later full Sarah device-auth lane.
 *
 * <p>Status: STAGED_NOT_CONNECTED. This class is deliberately not referenced by
 * production activities, the backend client, or the 72-hour event candidate.
 * It does not enroll a device, persist a device ID or token, or contact a
 * server.</p>
 */
public final class AndroidDurableDeviceCredentialManager {
    public static final String IMPLEMENTATION_STATUS = "STAGED_NOT_CONNECTED";
    public static final int CURRENT_KEY_VERSION = 1;
    public static final String KEY_ALIAS_PREFIX = "SarahDurableDeviceAuthP256V1.Key.";

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private AndroidDurableDeviceCredentialManager() { }

    /** Durable enrollment truth must be supplied by the future state store. */
    public enum DeviceBinding {
        UNENROLLED,
        BOUND_TO_DEVICE
    }

    public enum KeyState {
        UNENROLLED_KEY_ABSENT,
        READY,
        KEY_MISSING,
        KEYSTORE_ERROR
    }

    public static final class Inspection {
        public final KeyState state;
        public final int keyVersion;
        public final String alias;
        public final CredentialDescriptor credential;
        public final String diagnosticCode;

        private Inspection(
                KeyState state,
                int keyVersion,
                String alias,
                CredentialDescriptor credential,
                String diagnosticCode) {
            this.state = state;
            this.keyVersion = keyVersion;
            this.alias = alias;
            this.credential = credential;
            this.diagnosticCode = diagnosticCode;
        }

        public boolean isUsable() {
            return state == KeyState.READY && credential != null;
        }
    }

    /** Public-only material safe to send during a future enrollment. */
    public static final class CredentialDescriptor {
        public final int keyVersion;
        public final String alias;
        public final String publicJwkJson;
        public final String keyThumbprint;

        private CredentialDescriptor(
                int keyVersion,
                String alias,
                String publicJwkJson,
                String keyThumbprint) {
            this.keyVersion = keyVersion;
            this.alias = alias;
            this.publicJwkJson = publicJwkJson;
            this.keyThumbprint = keyThumbprint;
        }
    }

    /**
     * Read-only state inspection. A missing key for a bound device is reported
     * as KEY_MISSING and is never silently regenerated.
     */
    public static Inspection inspect(DeviceBinding binding, int keyVersion) {
        requireBinding(binding);
        requireKeyVersion(keyVersion);
        String alias = aliasFor(keyVersion);
        try {
            KeyStore store = androidKeyStore();
            if (!store.containsAlias(alias)) {
                return new Inspection(
                        binding == DeviceBinding.BOUND_TO_DEVICE
                                ? KeyState.KEY_MISSING
                                : KeyState.UNENROLLED_KEY_ABSENT,
                        keyVersion,
                        alias,
                        null,
                        binding == DeviceBinding.BOUND_TO_DEVICE
                                ? "KEY_MISSING_REENROLL_REQUIRED"
                                : "UNENROLLED_KEY_ABSENT");
            }
            return new Inspection(
                    KeyState.READY,
                    keyVersion,
                    alias,
                    descriptor(store, alias, keyVersion),
                    "READY");
        } catch (Exception error) {
            return new Inspection(
                    KeyState.KEYSTORE_ERROR,
                    keyVersion,
                    alias,
                    null,
                    "KEYSTORE_UNAVAILABLE");
        }
    }

    /**
     * Idempotently creates a key only for a fresh, unbound enrollment. The
     * BOUND_TO_DEVICE state always fails closed and must use rotation or fresh
     * owner-approved re-enrollment instead.
     */
    public static CredentialDescriptor createForFreshEnrollment(
            DeviceBinding binding,
            int keyVersion) {
        requireBinding(binding);
        requireKeyVersion(keyVersion);
        if (binding != DeviceBinding.UNENROLLED) {
            throw new IllegalStateException(
                    "A bound device may not generate a replacement key; use rotation or re-enrollment");
        }
        String alias = aliasFor(keyVersion);
        try {
            KeyStore store = androidKeyStore();
            if (!store.containsAlias(alias)) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_EC,
                        ANDROID_KEYSTORE);
                generator.initialize(new KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(false)
                        .build());
                generator.generateKeyPair();
                store = androidKeyStore();
            }
            return descriptor(store, alias, keyVersion);
        } catch (Exception error) {
            throw new IllegalStateException("Android device-auth key creation failed closed", error);
        }
    }

    public static String signEnrollmentChallenge(
            DeviceBinding binding,
            int keyVersion,
            String enrollmentId,
            String challenge,
            String apiOrigin) {
        if (binding != DeviceBinding.UNENROLLED) {
            throw new IllegalStateException("Enrollment proof requires an unbound credential");
        }
        KeyStore.PrivateKeyEntry entry = requireEntry(binding, keyVersion);
        DurableDeviceAuthProtocol.PublicJwk jwk = publicJwk(entry);
        String payload = DurableDeviceAuthProtocol.canonicalEnrollmentChallenge(
                enrollmentId,
                challenge,
                apiOrigin,
                jwk.thumbprint());
        return DurableDeviceAuthProtocol.signP1363Base64Url(entry.getPrivateKey(), payload);
    }

    public static String signSessionChallenge(
            DeviceBinding binding,
            int keyVersion,
            String deviceId,
            String challengeId,
            String nonce,
            String apiOrigin) {
        if (binding != DeviceBinding.BOUND_TO_DEVICE) {
            throw new IllegalStateException("Session proof requires a bound device");
        }
        KeyStore.PrivateKeyEntry entry = requireEntry(binding, keyVersion);
        String payload = DurableDeviceAuthProtocol.canonicalSessionChallenge(
                deviceId,
                challengeId,
                nonce,
                apiOrigin,
                keyVersion);
        return DurableDeviceAuthProtocol.signP1363Base64Url(entry.getPrivateKey(), payload);
    }

    public static String aliasFor(int keyVersion) {
        requireKeyVersion(keyVersion);
        return KEY_ALIAS_PREFIX + keyVersion;
    }

    private static KeyStore.PrivateKeyEntry requireEntry(
            DeviceBinding binding,
            int keyVersion) {
        Inspection inspection = inspect(binding, keyVersion);
        if (!inspection.isUsable()) {
            throw new IllegalStateException(
                    "Durable device credential unavailable: " + inspection.diagnosticCode);
        }
        try {
            return privateEntry(androidKeyStore(), inspection.alias);
        } catch (Exception error) {
            throw new IllegalStateException("Durable device credential became unavailable", error);
        }
    }

    private static CredentialDescriptor descriptor(
            KeyStore store,
            String alias,
            int keyVersion) throws Exception {
        KeyStore.PrivateKeyEntry entry = privateEntry(store, alias);
        PrivateKey privateKey = entry.getPrivateKey();
        // AndroidKeyStore private-key handles must never expose PKCS#8 bytes.
        if (privateKey.getEncoded() != null) {
            throw new IllegalStateException("Android device-auth key is unexpectedly exportable");
        }
        DurableDeviceAuthProtocol.PublicJwk jwk = publicJwk(entry);
        return new CredentialDescriptor(
                keyVersion,
                alias,
                jwk.toWireJson(),
                jwk.thumbprint());
    }

    private static DurableDeviceAuthProtocol.PublicJwk publicJwk(
            KeyStore.PrivateKeyEntry entry) {
        if (!(entry.getCertificate().getPublicKey() instanceof ECPublicKey)) {
            throw new IllegalStateException("Android device-auth public key is not EC");
        }
        return DurableDeviceAuthProtocol.publicJwk(
                (ECPublicKey) entry.getCertificate().getPublicKey());
    }

    private static KeyStore.PrivateKeyEntry privateEntry(
            KeyStore store,
            String alias) throws Exception {
        KeyStore.Entry entry = store.getEntry(alias, null);
        if (!(entry instanceof KeyStore.PrivateKeyEntry)) {
            throw new IllegalStateException("Android device-auth key entry is missing or invalid");
        }
        return (KeyStore.PrivateKeyEntry) entry;
    }

    private static KeyStore androidKeyStore() throws Exception {
        KeyStore store = KeyStore.getInstance(ANDROID_KEYSTORE);
        store.load(null);
        return store;
    }

    private static void requireBinding(DeviceBinding binding) {
        if (binding == null) throw new IllegalArgumentException("device binding truth is required");
    }

    private static void requireKeyVersion(int keyVersion) {
        if (keyVersion < 1) throw new IllegalArgumentException("key version must be positive");
    }
}
