import com.kiraworld.sarahtravel.SecureVaultReadPolicy;

public final class SecureVaultReadPolicyTest {
    public static void main(String[] args) {
        require(SecureVaultReadPolicy.classify(false, false)
                        == SecureVaultReadPolicy.StoredState.MISSING,
                "two absent fields are an absent record");
        require(SecureVaultReadPolicy.classify(true, true)
                        == SecureVaultReadPolicy.StoredState.COMPLETE_CIPHERTEXT,
                "two present fields are a complete ciphertext record");
        require(SecureVaultReadPolicy.classify(true, false)
                        == SecureVaultReadPolicy.StoredState.CORRUPT_PARTIAL,
                "an IV without ciphertext is corruption");
        require(SecureVaultReadPolicy.classify(false, true)
                        == SecureVaultReadPolicy.StoredState.CORRUPT_PARTIAL,
                "ciphertext without an IV is corruption");

        SecureVaultReadPolicy.requireReadable(
                SecureVaultReadPolicy.StoredState.MISSING, false);
        SecureVaultReadPolicy.requireReadable(
                SecureVaultReadPolicy.StoredState.COMPLETE_CIPHERTEXT, true);
        expectRejected(
                SecureVaultReadPolicy.StoredState.CORRUPT_PARTIAL, false,
                "partial encrypted data must stop all mutation");
        expectRejected(
                SecureVaultReadPolicy.StoredState.COMPLETE_CIPHERTEXT, false,
                "failed AES-GCM authentication must stop all mutation");

        System.out.println("SecureVaultReadPolicyTest passed");
    }

    private static void expectRejected(
            SecureVaultReadPolicy.StoredState state,
            boolean decrypted,
            String message) {
        boolean rejected = false;
        try {
            SecureVaultReadPolicy.requireReadable(state, decrypted);
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        require(rejected, message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
