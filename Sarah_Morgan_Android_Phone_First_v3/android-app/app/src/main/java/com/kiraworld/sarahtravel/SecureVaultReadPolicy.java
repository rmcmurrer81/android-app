package com.kiraworld.sarahtravel;

/** Pure policy for distinguishing an absent encrypted record from corruption. */
public final class SecureVaultReadPolicy {
    public enum StoredState {
        MISSING,
        COMPLETE_CIPHERTEXT,
        CORRUPT_PARTIAL
    }

    private SecureVaultReadPolicy() { }

    public static StoredState classify(boolean hasIv, boolean hasCiphertext) {
        if (!hasIv && !hasCiphertext) return StoredState.MISSING;
        if (hasIv && hasCiphertext) return StoredState.COMPLETE_CIPHERTEXT;
        return StoredState.CORRUPT_PARTIAL;
    }

    /** A present record is usable only after authenticated decryption succeeds. */
    public static void requireReadable(StoredState state, boolean authenticatedDecryptionPassed) {
        if (state == StoredState.CORRUPT_PARTIAL
                || (state == StoredState.COMPLETE_CIPHERTEXT
                        && !authenticatedDecryptionPassed)) {
            throw new IllegalStateException(
                    "Encrypted profile data is unreadable; no record may be changed.");
        }
    }
}
