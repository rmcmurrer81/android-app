package com.kiraworld.sarahtravel.durableauth;

import com.kiraworld.sarahtravel.AndroidDurableDeviceCredentialManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Staged AndroidKeyStore adapter for {@link DurableDeviceAuthClientCore}.
 *
 * <p>Status: STAGED_NOT_CONNECTED. Factories make key creation explicit. A
 * missing key for a bound device is represented as unavailable and is never
 * silently regenerated.</p>
 */
public final class AndroidDurableDeviceCredential
        implements DurableDeviceAuthClientCore.Credential {
    public static final String IMPLEMENTATION_STATUS = "STAGED_NOT_CONNECTED";

    private static final Pattern PUBLIC_JWK_PATTERN = Pattern.compile(
            "\\{\\\"kty\\\":\\\"EC\\\",\\\"crv\\\":\\\"P-256\\\","
                    + "\\\"x\\\":\\\"([A-Za-z0-9_-]{43})\\\","
                    + "\\\"y\\\":\\\"([A-Za-z0-9_-]{43})\\\"\\}");

    private final int keyVersion;
    private final AndroidDurableDeviceCredentialManager.CredentialDescriptor descriptor;
    private final String unavailableCode;
    private AndroidDurableDeviceCredentialManager.DeviceBinding binding;

    private AndroidDurableDeviceCredential(
            int keyVersion,
            AndroidDurableDeviceCredentialManager.CredentialDescriptor descriptor,
            String unavailableCode,
            AndroidDurableDeviceCredentialManager.DeviceBinding binding) {
        this.keyVersion = keyVersion;
        this.descriptor = descriptor;
        this.unavailableCode = unavailableCode;
        this.binding = binding;
    }

    /** Explicit fresh-enrollment key creation; never called by the client core. */
    public static AndroidDurableDeviceCredential createForFreshEnrollment(int keyVersion) {
        AndroidDurableDeviceCredentialManager.CredentialDescriptor created =
                AndroidDurableDeviceCredentialManager.createForFreshEnrollment(
                        AndroidDurableDeviceCredentialManager.DeviceBinding.UNENROLLED,
                        keyVersion);
        return new AndroidDurableDeviceCredential(
                keyVersion,
                created,
                null,
                AndroidDurableDeviceCredentialManager.DeviceBinding.UNENROLLED);
    }

    /** Read-only bound-key inspection with a fail-closed KEY_MISSING result. */
    public static AndroidDurableDeviceCredential inspectBoundDevice(int keyVersion) {
        AndroidDurableDeviceCredentialManager.Inspection inspection =
                AndroidDurableDeviceCredentialManager.inspect(
                        AndroidDurableDeviceCredentialManager.DeviceBinding.BOUND_TO_DEVICE,
                        keyVersion);
        return new AndroidDurableDeviceCredential(
                keyVersion,
                inspection.credential,
                inspection.isUsable() ? null : inspection.diagnosticCode,
                AndroidDurableDeviceCredentialManager.DeviceBinding.BOUND_TO_DEVICE);
    }

    @Override
    public boolean isReady() {
        return descriptor != null;
    }

    @Override
    public String unavailableCode() {
        return unavailableCode;
    }

    @Override
    public int keyVersion() {
        return keyVersion;
    }

    @Override
    public Map<String, Object> publicJwk() {
        requireReady();
        Matcher matcher = PUBLIC_JWK_PATTERN.matcher(descriptor.publicJwkJson);
        if (!matcher.matches()) {
            throw new IllegalStateException("AndroidKeyStore public JWK was not canonical");
        }
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "EC");
        jwk.put("crv", "P-256");
        jwk.put("x", matcher.group(1));
        jwk.put("y", matcher.group(2));
        return jwk;
    }

    @Override
    public String keyThumbprint() {
        requireReady();
        return descriptor.keyThumbprint;
    }

    @Override
    public String signEnrollment(
            String enrollmentId,
            String challenge,
            String apiOrigin) {
        requireReady();
        return AndroidDurableDeviceCredentialManager.signEnrollmentChallenge(
                binding,
                keyVersion,
                enrollmentId,
                challenge,
                apiOrigin);
    }

    @Override
    public String signSession(
            String deviceId,
            String challengeId,
            String nonce,
            String apiOrigin,
            int requestedKeyVersion) {
        requireReady();
        if (requestedKeyVersion != keyVersion) {
            throw new IllegalStateException("Requested key version does not match credential");
        }
        return AndroidDurableDeviceCredentialManager.signSessionChallenge(
                binding,
                keyVersion,
                deviceId,
                challengeId,
                nonce,
                apiOrigin);
    }

    @Override
    public void markBoundToDevice() {
        requireReady();
        if (binding != AndroidDurableDeviceCredentialManager.DeviceBinding.UNENROLLED) {
            throw new IllegalStateException("Credential is already bound");
        }
        binding = AndroidDurableDeviceCredentialManager.DeviceBinding.BOUND_TO_DEVICE;
    }

    private void requireReady() {
        if (descriptor == null) {
            throw new IllegalStateException(
                    unavailableCode == null ? "DEVICE_CREDENTIAL_UNAVAILABLE" : unavailableCode);
        }
    }
}
