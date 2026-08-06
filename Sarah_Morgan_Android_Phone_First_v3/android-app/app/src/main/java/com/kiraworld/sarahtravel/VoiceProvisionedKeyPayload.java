package com.kiraworld.sarahtravel;

/**
 * Device-bound encrypted ElevenLabs credential payload.
 *
 * The public repository contains only ciphertext. It can be decrypted only by
 * the Android Keystore private key generated on the specifically provisioned
 * phone. The raw ElevenLabs key must never be committed here.
 */
public final class VoiceProvisionedKeyPayload {
    public static final String TARGET_DEVICE_FINGERPRINT = "";
    public static final String ENCRYPTED_API_KEY_BASE64 = "";

    private VoiceProvisionedKeyPayload() { }
}
