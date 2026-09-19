package com.kiraworld.sarahtravel;

import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/**
 * Pure-Java wire helpers for Sarah's durable device-authentication protocol.
 *
 * <p>Status: STAGED_NOT_CONNECTED. Nothing in the production UI or current
 * event-candidate route invokes this class. It intentionally contains no
 * endpoint, bearer, provider credential, device identifier, or durable access
 * token.</p>
 */
public final class DurableDeviceAuthProtocol {
    public static final String IMPLEMENTATION_STATUS = "STAGED_NOT_CONNECTED";
    public static final String CURVE = "P-256";
    public static final int P256_COORDINATE_BYTES = 32;
    public static final int P256_SIGNATURE_BYTES = 64;

    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private DurableDeviceAuthProtocol() { }

    /** Public-only JWK. The private scalar is never accepted or represented. */
    public static final class PublicJwk {
        public final String kty;
        public final String crv;
        public final String x;
        public final String y;

        private PublicJwk(String x, String y) {
            this.kty = "EC";
            this.crv = CURVE;
            this.x = x;
            this.y = y;
        }

        /** Wire form contains exactly kty, crv, x, and y. */
        public String toWireJson() {
            return "{\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\""
                    + x + "\",\"y\":\"" + y + "\"}";
        }

        /** RFC 7638 requires lexicographic member order for the hash input. */
        public String toThumbprintJson() {
            return "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\""
                    + x + "\",\"y\":\"" + y + "\"}";
        }

        public String thumbprint() {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                return base64Url(digest.digest(
                        toThumbprintJson().getBytes(StandardCharsets.UTF_8)));
            } catch (Exception error) {
                throw new IllegalStateException("SHA-256 is unavailable", error);
            }
        }
    }

    public static PublicJwk publicJwk(ECPublicKey publicKey) {
        if (publicKey == null
                || publicKey.getParams() == null
                || publicKey.getParams().getCurve() == null
                || publicKey.getParams().getCurve().getField().getFieldSize() != 256) {
            throw new IllegalArgumentException("A P-256 EC public key is required");
        }
        return new PublicJwk(
                base64Url(unsignedFixed(publicKey.getW().getAffineX(), P256_COORDINATE_BYTES)),
                base64Url(unsignedFixed(publicKey.getW().getAffineY(), P256_COORDINATE_BYTES)));
    }

    public static String canonicalEnrollmentChallenge(
            String enrollmentId,
            String challenge,
            String apiOrigin,
            String keyThumbprint) {
        return String.join("\n",
                "SARAH-ENROLLMENT-V1",
                canonicalField(enrollmentId, "enrollment_id", 256),
                canonicalField(challenge, "challenge", 1024),
                canonicalApiOrigin(apiOrigin),
                canonicalBase64Url(keyThumbprint, "key_thumbprint", 32));
    }

    public static String canonicalSessionChallenge(
            String deviceId,
            String challengeId,
            String nonce,
            String apiOrigin,
            int keyVersion) {
        if (keyVersion < 1) {
            throw new IllegalArgumentException("key_version must be positive");
        }
        return String.join("\n",
                "SARAH-AUTH-V1",
                canonicalField(deviceId, "device_id", 256),
                canonicalField(challengeId, "challenge_id", 256),
                canonicalField(nonce, "nonce", 1024),
                canonicalApiOrigin(apiOrigin),
                Integer.toString(keyVersion));
    }

    /**
     * Signs an already canonical UTF-8 payload and returns the protocol's
     * base64url-without-padding IEEE-P1363 signature.
     */
    public static String signP1363Base64Url(PrivateKey privateKey, String canonicalPayload) {
        if (privateKey == null) throw new IllegalArgumentException("private key is required");
        if (canonicalPayload == null || canonicalPayload.isEmpty()) {
            throw new IllegalArgumentException("canonical payload is required");
        }
        try {
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(canonicalPayload.getBytes(StandardCharsets.UTF_8));
            return base64Url(derEcdsaToP1363(signer.sign(), P256_COORDINATE_BYTES));
        } catch (Exception error) {
            throw new IllegalStateException("P-256 challenge signing failed", error);
        }
    }

    /** Converts a strict DER ECDSA sequence into fixed-width r || s. */
    public static byte[] derEcdsaToP1363(byte[] derSignature, int coordinateBytes) {
        if (derSignature == null || coordinateBytes < 1) {
            throw new IllegalArgumentException("DER signature and coordinate size are required");
        }
        DerCursor cursor = new DerCursor(derSignature);
        cursor.expect(0x30, "ECDSA signature must be a DER sequence");
        int sequenceLength = cursor.readLength();
        if (sequenceLength != cursor.remaining()) {
            throw new IllegalArgumentException("DER sequence length is not exact");
        }

        byte[] r = cursor.readPositiveInteger(coordinateBytes);
        byte[] s = cursor.readPositiveInteger(coordinateBytes);
        if (cursor.remaining() != 0) {
            throw new IllegalArgumentException("DER signature contains trailing data");
        }

        byte[] p1363 = new byte[coordinateBytes * 2];
        System.arraycopy(r, 0, p1363, 0, coordinateBytes);
        System.arraycopy(s, 0, p1363, coordinateBytes, coordinateBytes);
        return p1363;
    }

    static String base64Url(byte[] value) {
        return BASE64_URL.encodeToString(value);
    }

    private static String canonicalApiOrigin(String value) {
        String origin = canonicalField(value, "api_origin", 2048);
        try {
            URI uri = new URI(origin);
            String rawPath = uri.getRawPath();
            if (!"https".equals(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getHost().isEmpty()
                    || !uri.getHost().equals(uri.getHost().toLowerCase(Locale.ROOT))
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || (rawPath != null && !rawPath.isEmpty())
                    || uri.getPort() == 443
                    || !origin.equals(uri.toASCIIString())) {
                throw new IllegalArgumentException("api_origin must be one canonical HTTPS origin");
            }
            return origin;
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("api_origin must be one canonical HTTPS origin", error);
        }
    }

    private static String canonicalBase64Url(String value, String name, int exactBytes) {
        String clean = canonicalField(value, name, exactBytes * 2);
        if (!clean.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException(name + " must be unpadded base64url");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(clean);
            if (decoded.length != exactBytes || !base64Url(decoded).equals(clean)) {
                throw new IllegalArgumentException(
                        name + " must be canonical base64url for " + exactBytes + " bytes");
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    name + " must be canonical base64url for " + exactBytes + " bytes", error);
        }
        return clean;
    }

    private static String canonicalField(String value, String name, int maxLength) {
        if (value == null || value.isEmpty() || value.length() > maxLength
                || !value.equals(value.trim())) {
            throw new IllegalArgumentException(name + " is missing or noncanonical");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\r' || character == '\n' || Character.isISOControl(character)) {
                throw new IllegalArgumentException(name + " contains a control character");
            }
        }
        return value;
    }

    private static byte[] unsignedFixed(BigInteger value, int size) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("EC coordinate must be non-negative");
        }
        byte[] encoded = value.toByteArray();
        int offset = encoded.length > 1 && encoded[0] == 0 ? 1 : 0;
        int length = encoded.length - offset;
        if (length > size) throw new IllegalArgumentException("EC coordinate is too large");
        byte[] fixed = new byte[size];
        System.arraycopy(encoded, offset, fixed, size - length, length);
        return fixed;
    }

    private static final class DerCursor {
        private final byte[] data;
        private int offset;

        DerCursor(byte[] data) {
            this.data = Arrays.copyOf(data, data.length);
        }

        int remaining() {
            return data.length - offset;
        }

        void expect(int expected, String message) {
            if (remaining() < 1 || (data[offset++] & 0xff) != expected) {
                throw new IllegalArgumentException(message);
            }
        }

        int readLength() {
            if (remaining() < 1) throw new IllegalArgumentException("DER length is missing");
            int first = data[offset++] & 0xff;
            if ((first & 0x80) == 0) return first;
            int count = first & 0x7f;
            if (count == 0 || count > 2 || count > remaining()) {
                throw new IllegalArgumentException("DER length is invalid");
            }
            if ((data[offset] & 0xff) == 0) {
                throw new IllegalArgumentException("DER length is noncanonical");
            }
            int length = 0;
            for (int index = 0; index < count; index++) {
                length = (length << 8) | (data[offset++] & 0xff);
            }
            if (length < 128 || length > remaining()) {
                throw new IllegalArgumentException("DER length is noncanonical or truncated");
            }
            return length;
        }

        byte[] readPositiveInteger(int size) {
            expect(0x02, "DER ECDSA component is not an integer");
            int length = readLength();
            if (length < 1 || length > remaining()) {
                throw new IllegalArgumentException("DER ECDSA component is truncated");
            }
            int start = offset;
            offset += length;
            if ((data[start] & 0x80) != 0) {
                throw new IllegalArgumentException("DER ECDSA component is negative");
            }
            if (length > 1 && data[start] == 0) {
                if ((data[start + 1] & 0x80) == 0) {
                    throw new IllegalArgumentException("DER ECDSA component has redundant padding");
                }
                start++;
                length--;
            }
            if (length > size) {
                throw new IllegalArgumentException("DER ECDSA component exceeds curve size");
            }
            boolean nonzero = false;
            for (int index = 0; index < length; index++) {
                nonzero |= data[start + index] != 0;
            }
            if (!nonzero) throw new IllegalArgumentException("DER ECDSA component is zero");
            byte[] fixed = new byte[size];
            System.arraycopy(data, start, fixed, size - length, length);
            return fixed;
        }
    }
}
