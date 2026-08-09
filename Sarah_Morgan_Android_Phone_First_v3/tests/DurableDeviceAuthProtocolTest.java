package com.kiraworld.sarahtravel;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/** Host-JDK checks for the Android/Worker P-256 wire contract. */
public final class DurableDeviceAuthProtocolTest {
    private DurableDeviceAuthProtocolTest() { }

    public static void main(String[] args) throws Exception {
        testRfc7638P256ThumbprintVector();
        testCanonicalPayload();
        testDerToP1363();
        testRealSignatureRoundTrip();
        testInvalidInputsFailClosed();
        System.out.println("DURABLE_DEVICE_AUTH_PROTOCOL_TESTS_PASS");
    }

    private static void testRfc7638P256ThumbprintVector() throws Exception {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec p256 = parameters.getParameterSpec(ECParameterSpec.class);
        ECPoint generator = new ECPoint(
                new BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16),
                new BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16));
        ECPublicKey publicKey = (ECPublicKey) KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(generator, p256));

        DurableDeviceAuthProtocol.PublicJwk jwk =
                DurableDeviceAuthProtocol.publicJwk(publicKey);
        equal("axfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpY", jwk.x, "JWK x");
        equal("T-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU", jwk.y, "JWK y");
        equal("xx0BcA-wMohw8atYDJOe6peGModklG2wRHBlXHMvl0M",
                jwk.thumbprint(), "RFC 7638 thumbprint");
        equal("{\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\""
                        + jwk.x + "\",\"y\":\"" + jwk.y + "\"}",
                jwk.toWireJson(), "public-only wire JWK");
    }

    private static void testCanonicalPayload() {
        equal(
                "SARAH-AUTH-V1\ndev_123\nchallenge_456\nnonce_789\n"
                        + "https://full.sarah.example\n7",
                DurableDeviceAuthProtocol.canonicalSessionChallenge(
                        "dev_123",
                        "challenge_456",
                        "nonce_789",
                        "https://full.sarah.example",
                        7),
                "session proof payload");
        equal(
                "SARAH-ENROLLMENT-V1\nenrollment_1\nchallenge_2\n"
                        + "https://full.sarah.example\n"
                        + "xx0BcA-wMohw8atYDJOe6peGModklG2wRHBlXHMvl0M",
                DurableDeviceAuthProtocol.canonicalEnrollmentChallenge(
                        "enrollment_1",
                        "challenge_2",
                        "https://full.sarah.example",
                        "xx0BcA-wMohw8atYDJOe6peGModklG2wRHBlXHMvl0M"),
                "enrollment proof payload");
    }

    private static void testDerToP1363() {
        byte[] der = new byte[]{0x30, 0x07, 0x02, 0x01, 0x01, 0x02, 0x02, 0x00, (byte) 0x80};
        byte[] raw = DurableDeviceAuthProtocol.derEcdsaToP1363(der, 32);
        check(raw.length == 64, "P1363 signature must be 64 bytes");
        check(raw[31] == 0x01, "r must be left padded");
        check(raw[63] == (byte) 0x80, "s sign padding must be removed");
        for (int index = 0; index < 31; index++) check(raw[index] == 0, "r prefix");
        for (int index = 32; index < 63; index++) check(raw[index] == 0, "s prefix");
    }

    private static void testRealSignatureRoundTrip() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        String payload = DurableDeviceAuthProtocol.canonicalSessionChallenge(
                "device-real",
                "challenge-real",
                "nonce-real",
                "https://full.sarah.example",
                1);
        String encoded = DurableDeviceAuthProtocol.signP1363Base64Url(
                pair.getPrivate(), payload);
        check(!encoded.contains("=") && encoded.matches("[A-Za-z0-9_-]+"),
                "signature must be canonical unpadded base64url");
        byte[] raw = Base64.getUrlDecoder().decode(encoded);
        check(raw.length == 64, "real signature must be P-256 P1363");

        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(pair.getPublic());
        verifier.update(payload.getBytes(StandardCharsets.UTF_8));
        check(verifier.verify(p1363ToDer(raw)), "real P1363 signature must verify");
    }

    private static void testInvalidInputsFailClosed() {
        expectFailure(() -> DurableDeviceAuthProtocol.canonicalSessionChallenge(
                "dev\nforged", "challenge", "nonce", "https://full.sarah.example", 1));
        expectFailure(() -> DurableDeviceAuthProtocol.canonicalSessionChallenge(
                "dev", "challenge", "nonce", "https://full.sarah.example/", 1));
        expectFailure(() -> DurableDeviceAuthProtocol.canonicalSessionChallenge(
                "dev", "challenge", "nonce", "http://full.sarah.example", 1));
        expectFailure(() -> DurableDeviceAuthProtocol.canonicalSessionChallenge(
                "dev", "challenge", "nonce", "https://full.sarah.example", 0));
        expectFailure(() -> DurableDeviceAuthProtocol.derEcdsaToP1363(
                new byte[]{0x30, 0x06, 0x02, 0x01, (byte) 0x80, 0x02, 0x01, 0x01}, 32));
        expectFailure(() -> DurableDeviceAuthProtocol.derEcdsaToP1363(
                new byte[]{0x30, 0x07, 0x02, 0x02, 0x00, 0x01, 0x02, 0x01, 0x01}, 32));
        expectFailure(() -> DurableDeviceAuthProtocol.derEcdsaToP1363(
                new byte[]{0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01, 0x00}, 32));
    }

    private static byte[] p1363ToDer(byte[] raw) {
        int half = raw.length / 2;
        byte[] r = integer(Arrays.copyOfRange(raw, 0, half));
        byte[] s = integer(Arrays.copyOfRange(raw, half, raw.length));
        int length = 2 + r.length + 2 + s.length;
        byte[] der = new byte[2 + length];
        int offset = 0;
        der[offset++] = 0x30;
        der[offset++] = (byte) length;
        der[offset++] = 0x02;
        der[offset++] = (byte) r.length;
        System.arraycopy(r, 0, der, offset, r.length);
        offset += r.length;
        der[offset++] = 0x02;
        der[offset++] = (byte) s.length;
        System.arraycopy(s, 0, der, offset, s.length);
        return der;
    }

    private static byte[] integer(byte[] fixed) {
        int start = 0;
        while (start < fixed.length - 1 && fixed[start] == 0) start++;
        byte[] magnitude = Arrays.copyOfRange(fixed, start, fixed.length);
        if ((magnitude[0] & 0x80) == 0) return magnitude;
        byte[] positive = new byte[magnitude.length + 1];
        System.arraycopy(magnitude, 0, positive, 1, magnitude.length);
        return positive;
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected fail-closed rejection");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void equal(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + " mismatch: " + actual);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
