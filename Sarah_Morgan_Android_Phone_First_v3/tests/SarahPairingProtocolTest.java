import com.kiraworld.sarahtravel.SarahPairingProtocol;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Cross-language vector and fail-closed state tests for Windows/Python parity. */
public final class SarahPairingProtocolTest {
    private static final long NOW = 1_700_000_000L;
    private static final String EXPECTED_INITIATOR_PUBLIC =
            "j0DFrbaPJWJK5bIU6nZ6bslNgp09e14a0bpvPiE4KF8";
    private static final String EXPECTED_RESPONDER_PUBLIC =
            "NYBy1jZYgNGu6jKa35EhODhR7SGijjt16WXQ0s0WYlQ";
    private static final String EXPECTED_SAS = "488550";
    private static final String EXPECTED_TOKEN =
            "gAbYYzOdQO_D8AwC6Zsf3JgHRBfZFE1ZGuwZz_K0ioM";
    private static final String EXPECTED_INITIATOR_PROOF =
            "45qUZt9xa1nglGniksIABqaC_WD4arn22EYel1EKo-0";
    private static final String EXPECTED_RESPONDER_PROOF =
            "LfGkVWTorArFzXYsU2InyiJXdkgzyTWYFrhXWAkItUM";

    public static void main(String[] args) {
        exactPythonInteroperabilityVector();
        twoExplicitApprovalsAreRequired();
        expiryTamperAndReplayFailClosed();
        allZeroSharedSecretIsRejected();
        System.out.println("SarahPairingProtocolTest passed");
    }

    private static void exactPythonInteroperabilityVector() {
        Pair pair = pair();
        require(EXPECTED_INITIATOR_PUBLIC.equals(pair.initiator.offerMessage().get("public_key")),
                "initiator X25519 public key differs from Python cryptography");
        require(EXPECTED_RESPONDER_PUBLIC.equals(pair.response.message.get("public_key")),
                "responder X25519 public key differs from Python cryptography");
        require(EXPECTED_SAS.equals(pair.initiatorSession.sasCode),
                "initiator SAS differs from Python protocol");
        require(pair.initiatorSession.sasCode.equals(pair.response.session.sasCode),
                "both devices must derive the same SAS");

        Map<String, Object> initiatorConfirmation =
                pair.initiatorSession.localConfirmation(true, NOW + 2);
        Map<String, Object> responderConfirmation =
                pair.response.session.localConfirmation(true, NOW + 2);
        require(EXPECTED_INITIATOR_PROOF.equals(initiatorConfirmation.get("proof")),
                "initiator confirmation differs from Python protocol");
        require(EXPECTED_RESPONDER_PROOF.equals(responderConfirmation.get("proof")),
                "responder confirmation differs from Python protocol");
        pair.initiatorSession.acceptPeerConfirmation(responderConfirmation, NOW + 2);
        pair.response.session.acceptPeerConfirmation(initiatorConfirmation, NOW + 2);
        SarahPairingProtocol.Credential phone =
                pair.initiatorSession.finalizeCredential(NOW + 3);
        SarahPairingProtocol.Credential windows =
                pair.response.session.finalizeCredential(NOW + 3);
        require(EXPECTED_TOKEN.equals(phone.token),
                "final credential differs from Python protocol");
        require(phone.token.equals(windows.token),
                "both devices must derive the same credential");
        require("windows-instance".equals(phone.peerInstanceId),
                "phone credential must bind the Windows instance");
        require("android-instance".equals(windows.peerInstanceId),
                "Windows credential must bind the Android instance");
    }

    private static void twoExplicitApprovalsAreRequired() {
        Pair pair = pair();
        expectFailure(
                () -> pair.initiatorSession.finalizeCredential(NOW + 2),
                "finalize before confirmations");
        expectFailure(
                () -> pair.initiatorSession.localConfirmation(false, NOW + 2),
                "owner rejection");
        Map<String, Object> initiatorConfirmation =
                pair.initiatorSession.localConfirmation(true, NOW + 2);
        expectFailure(
                () -> pair.initiatorSession.finalizeCredential(NOW + 2),
                "finalize before peer confirmation");
        Map<String, Object> responderConfirmation =
                pair.response.session.localConfirmation(true, NOW + 2);
        pair.initiatorSession.acceptPeerConfirmation(responderConfirmation, NOW + 2);
        pair.response.session.acceptPeerConfirmation(initiatorConfirmation, NOW + 2);
        pair.initiatorSession.finalizeCredential(NOW + 2);
    }

    private static void expiryTamperAndReplayFailClosed() {
        Pair replay = pair();
        expectFailure(
                () -> replay.initiator.acceptResponse(replay.response.message, NOW + 1),
                "replayed response");

        Pair expired = pair();
        expectFailure(
                () -> expired.initiatorSession.localConfirmation(
                        true, NOW + SarahPairingProtocol.LIFETIME_SECONDS + 1),
                "expired confirmation");

        Pair tampered = pair();
        tampered.initiatorSession.localConfirmation(true, NOW + 2);
        Map<String, Object> peer = new LinkedHashMap<>(
                tampered.response.session.localConfirmation(true, NOW + 2));
        peer.put("proof", EXPECTED_RESPONDER_PROOF.substring(0, 42) + "A");
        expectFailure(
                () -> tampered.initiatorSession.acceptPeerConfirmation(peer, NOW + 2),
                "tampered confirmation");

        Pair confirmationReplay = pair();
        confirmationReplay.initiatorSession.localConfirmation(true, NOW + 2);
        Map<String, Object> responseConfirmation =
                confirmationReplay.response.session.localConfirmation(true, NOW + 2);
        confirmationReplay.initiatorSession.acceptPeerConfirmation(
                responseConfirmation, NOW + 2);
        expectFailure(
                () -> confirmationReplay.initiatorSession.acceptPeerConfirmation(
                        responseConfirmation, NOW + 2),
                "replayed peer confirmation");
    }

    private static void allZeroSharedSecretIsRejected() {
        SarahPairingProtocol.Initiator initiator = initiator();
        Map<String, Object> response = new LinkedHashMap<>(pair().response.message);
        response.put("request_id", initiator.offerMessage().get("request_id"));
        response.put("public_key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        expectFailure(
                () -> initiator.acceptResponse(response, NOW + 1),
                "all-zero X25519 shared secret");
    }

    private static Pair pair() {
        SarahPairingProtocol.Initiator initiator = initiator();
        SarahPairingProtocol.Response response = SarahPairingProtocol.respondForTest(
                initiator.offerMessage(),
                "windows-instance",
                "Sarah on Windows",
                "windows",
                NOW + 1,
                sequence(32, 32),
                sequence(64, 24));
        SarahPairingProtocol.Session initiatorSession =
                initiator.acceptResponse(response.message, NOW + 1);
        return new Pair(initiator, response, initiatorSession);
    }

    private static SarahPairingProtocol.Initiator initiator() {
        return SarahPairingProtocol.initiatorForTest(
                "android-instance",
                "Samsung Galaxy A17",
                "android-phone",
                NOW,
                sequence(0, 32),
                sequence(0, 18),
                sequence(40, 24));
    }

    private static byte[] sequence(int start, int size) {
        byte[] result = new byte[size];
        for (int index = 0; index < size; index++) result[index] = (byte) (start + index);
        return result;
    }

    private static void expectFailure(Runnable operation, String label) {
        try {
            operation.run();
            throw new AssertionError(label + " should fail closed");
        } catch (SarahPairingProtocol.PairingException expected) {
            // Expected.
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class Pair {
        final SarahPairingProtocol.Initiator initiator;
        final SarahPairingProtocol.Response response;
        final SarahPairingProtocol.Session initiatorSession;

        Pair(
                SarahPairingProtocol.Initiator initiator,
                SarahPairingProtocol.Response response,
                SarahPairingProtocol.Session initiatorSession) {
            this.initiator = initiator;
            this.response = response;
            this.initiatorSession = initiatorSession;
        }
    }
}
