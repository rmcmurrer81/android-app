package com.kiraworld.sarahtravel;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Platform-independent half of Sarah's explicit two-device pairing protocol.
 *
 * <p>Discovery is never identity.  This protocol derives a six-digit short
 * authentication string (SAS) from an X25519 exchange and the complete
 * canonical offer/response transcript.  A credential can be finalized only
 * after the owner explicitly confirms that the same SAS is visible on both
 * devices and each device verifies the other's transcript-bound HMAC proof.
 * No profile, model, Gmail, provider, or owner credential is carried by these
 * messages.</p>
 *
 * <p>The X25519 implementation is deliberately pure Java so the contract is
 * available on Sarah's API-26 minimum without relying on a vendor-specific
 * Android crypto provider.  Ephemeral private scalars live only in memory and
 * are erased after use.  Only the finalized per-device credential is eligible
 * for Android-Keystore-backed persistence by {@link TrustedDeviceStore}.</p>
 */
public final class SarahPairingProtocol {
    public static final String SCHEMA = "sarah-device-pairing-x25519-sas-v1";
    public static final int LIFETIME_SECONDS = 120;
    public static final int MAX_CLOCK_SKEW_SECONDS = 30;
    public static final int MAX_MESSAGE_BYTES = 8192;

    private static final byte[] INFO_PREFIX =
            "SarahDevicePairingV1\0".getBytes(StandardCharsets.UTF_8);
    private static final BigInteger PRIME = BigInteger.ONE.shiftLeft(255)
            .subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);
    private static final BigInteger MILLION = BigInteger.valueOf(1_000_000L);
    private static final SecureRandom RANDOM = new SecureRandom();

    private SarahPairingProtocol() { }

    public static final class PairingException extends SecurityException {
        public PairingException(String message) { super(message); }
        public PairingException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class Credential {
        public final String requestId;
        public final String peerInstanceId;
        public final String peerDeviceName;
        public final String peerDeviceType;
        public final String token;
        public final long establishedAt;

        Credential(
                String requestId,
                String peerInstanceId,
                String peerDeviceName,
                String peerDeviceType,
                String token,
                long establishedAt) {
            this.requestId = requestId;
            this.peerInstanceId = peerInstanceId;
            this.peerDeviceName = peerDeviceName;
            this.peerDeviceType = peerDeviceType;
            this.token = token;
            this.establishedAt = establishedAt;
        }
    }

    public static final class Initiator {
        private final Map<String, Object> offer;
        private byte[] privateScalar;
        private boolean responseAccepted;

        private Initiator(Map<String, Object> offer, byte[] privateScalar) {
            this.offer = immutable(offer);
            this.privateScalar = Arrays.copyOf(privateScalar, privateScalar.length);
        }

        public Map<String, Object> offerMessage() { return offer; }

        public synchronized Session acceptResponse(
                Map<String, ?> rawResponse,
                long nowSeconds) {
            if (responseAccepted || privateScalar == null) {
                throw new PairingException("A pairing response cannot be replayed");
            }
            Map<String, Object> normalizedOffer = normalizeOffer(offer, nowSeconds);
            Map<String, Object> response = normalizeResponse(
                    rawResponse, normalizedOffer, nowSeconds);
            byte[] scalar = privateScalar;
            privateScalar = null;
            responseAccepted = true;
            try {
                return deriveSession(
                        "initiator",
                        scalar,
                        decodeFixed((String) response.get("public_key"), 32, "public key"),
                        normalizedOffer,
                        response);
            } finally {
                Arrays.fill(scalar, (byte) 0);
            }
        }
    }

    public static final class Response {
        public final Map<String, Object> message;
        public final Session session;

        Response(Map<String, Object> message, Session session) {
            this.message = immutable(message);
            this.session = session;
        }
    }

    public static final class Session {
        public final String sasCode;
        public final long expiresAt;

        private final String localRole;
        private final String peerRole;
        private final Map<String, Object> offer;
        private final Map<String, Object> response;
        private final byte[] transcriptHash;
        private byte[] material;
        private boolean localConfirmed;
        private boolean peerConfirmed;
        private boolean finalized;

        private Session(
                String localRole,
                Map<String, Object> offer,
                Map<String, Object> response,
                byte[] material,
                byte[] transcriptHash,
                String sasCode) {
            this.localRole = localRole;
            this.peerRole = "initiator".equals(localRole) ? "responder" : "initiator";
            this.offer = immutable(offer);
            this.response = immutable(response);
            this.material = Arrays.copyOf(material, material.length);
            this.transcriptHash = Arrays.copyOf(transcriptHash, transcriptHash.length);
            this.sasCode = sasCode;
            this.expiresAt = asLong(offer.get("expires_at"), "expires_at");
        }

        public synchronized Map<String, Object> localConfirmation(
                boolean ownerConfirmedMatchingCode,
                long nowSeconds) {
            requireActive(nowSeconds);
            if (!ownerConfirmedMatchingCode) {
                throw new PairingException(
                        "The owner did not confirm the matching code on this device");
            }
            if (localConfirmed) {
                throw new PairingException("A local pairing confirmation cannot be replayed");
            }
            localConfirmed = true;
            byte[] proof = hmac(
                    Arrays.copyOfRange(material, 32, 64),
                    bytes("confirm\0" + localRole + "\0"),
                    transcriptHash);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schema", SCHEMA);
            result.put("kind", "confirmation");
            result.put("request_id", offer.get("request_id"));
            result.put("role", localRole);
            result.put("transcript_sha256", hex(transcriptHash));
            result.put("expires_at", expiresAt);
            result.put("proof", encode(proof));
            return immutable(result);
        }

        public synchronized void acceptPeerConfirmation(
                Map<String, ?> rawConfirmation,
                long nowSeconds) {
            requireActive(nowSeconds);
            if (peerConfirmed) {
                throw new PairingException("A peer pairing confirmation cannot be replayed");
            }
            Map<String, Object> value = copy(rawConfirmation);
            if (!SCHEMA.equals(value.get("schema"))
                    || !"confirmation".equals(value.get("kind"))) {
                throw new PairingException("Expected a Sarah pairing confirmation");
            }
            if (!offer.get("request_id").equals(value.get("request_id"))) {
                throw new PairingException("Pairing confirmation belongs to another request");
            }
            if (asLong(value.get("expires_at"), "expires_at") != expiresAt) {
                throw new PairingException("Pairing confirmation changed the request lifetime");
            }
            if (!peerRole.equals(value.get("role"))) {
                throw new PairingException("Pairing confirmation has the wrong device role");
            }
            if (!hex(transcriptHash).equals(value.get("transcript_sha256"))) {
                throw new PairingException("Pairing confirmation transcript does not match");
            }
            byte[] proof = decodeFixed(string(value.get("proof")), 32, "confirmation proof");
            byte[] expected = hmac(
                    Arrays.copyOfRange(material, 32, 64),
                    bytes("confirm\0" + peerRole + "\0"),
                    transcriptHash);
            if (!MessageDigest.isEqual(proof, expected)) {
                throw new PairingException("Pairing confirmation proof failed");
            }
            peerConfirmed = true;
        }

        public synchronized Credential finalizeCredential(long nowSeconds) {
            requireActive(nowSeconds);
            if (!localConfirmed || !peerConfirmed) {
                throw new PairingException(
                        "Both devices must explicitly confirm the same pairing code");
            }
            if (finalized) {
                throw new PairingException("A pairing session cannot be finalized twice");
            }
            byte[] tokenBytes = hmac(
                    Arrays.copyOfRange(material, 0, 32),
                    bytes("sync-credential\0"),
                    transcriptHash);
            Map<String, Object> peer = "initiator".equals(localRole) ? response : offer;
            Credential credential = new Credential(
                    string(offer.get("request_id")),
                    string(peer.get("instance_id")),
                    string(peer.get("device_name")),
                    string(peer.get("device_type")),
                    encode(tokenBytes),
                    nowSeconds);
            finalized = true;
            Arrays.fill(material, (byte) 0);
            material = null;
            return credential;
        }

        public synchronized void destroy() {
            if (material != null) {
                Arrays.fill(material, (byte) 0);
                material = null;
            }
            finalized = true;
        }

        private void requireActive(long nowSeconds) {
            if (finalized || material == null) {
                throw new PairingException("Pairing session is no longer active");
            }
            if (nowSeconds > expiresAt) {
                destroy();
                throw new PairingException("Pairing session expired");
            }
        }
    }

    public static Initiator newInitiator(
            String instanceId,
            String deviceName,
            String deviceType,
            long nowSeconds) {
        byte[] scalar = randomBytes(32);
        byte[] request = randomBytes(18);
        byte[] nonce = randomBytes(24);
        return initiatorForTest(
                instanceId, deviceName, deviceType, nowSeconds, scalar, request, nonce);
    }

    /** Deterministic construction used by the cross-language protocol vector. */
    public static Initiator initiatorForTest(
            String instanceId,
            String deviceName,
            String deviceType,
            long nowSeconds,
            byte[] privateScalar,
            byte[] requestId,
            byte[] nonce) {
        requireLength(privateScalar, 32, "private scalar");
        requireLength(requestId, 18, "request ID");
        requireLength(nonce, 24, "nonce");
        Map<String, Object> offer = new LinkedHashMap<>();
        offer.put("schema", SCHEMA);
        offer.put("kind", "offer");
        offer.put("request_id", encode(requestId));
        offer.put("created_at", nowSeconds);
        offer.put("expires_at", nowSeconds + LIFETIME_SECONDS);
        offer.put("instance_id", label(instanceId, ""));
        offer.put("device_name", label(deviceName, "Sarah device"));
        offer.put("device_type", label(deviceType, "device"));
        offer.put("public_key", encode(x25519(privateScalar, basePoint())));
        offer.put("nonce", encode(nonce));
        offer.put("approval_required_on_both_devices", Boolean.TRUE);
        return new Initiator(offer, privateScalar);
    }

    public static Response respond(
            Map<String, ?> rawOffer,
            String instanceId,
            String deviceName,
            String deviceType,
            long nowSeconds) {
        return respondForTest(
                rawOffer,
                instanceId,
                deviceName,
                deviceType,
                nowSeconds,
                randomBytes(32),
                randomBytes(24));
    }

    /** Deterministic construction used by the cross-language protocol vector. */
    public static Response respondForTest(
            Map<String, ?> rawOffer,
            String instanceId,
            String deviceName,
            String deviceType,
            long nowSeconds,
            byte[] privateScalar,
            byte[] nonce) {
        requireLength(privateScalar, 32, "private scalar");
        requireLength(nonce, 24, "nonce");
        Map<String, Object> offer = normalizeOffer(rawOffer, nowSeconds);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schema", SCHEMA);
        response.put("kind", "response");
        response.put("request_id", offer.get("request_id"));
        response.put("created_at", nowSeconds);
        response.put("expires_at", offer.get("expires_at"));
        response.put("instance_id", label(instanceId, ""));
        response.put("device_name", label(deviceName, "Sarah device"));
        response.put("device_type", label(deviceType, "device"));
        response.put("public_key", encode(x25519(privateScalar, basePoint())));
        response.put("nonce", encode(nonce));
        response.put("approval_required_on_both_devices", Boolean.TRUE);
        response = normalizeResponse(response, offer, nowSeconds);
        try {
            Session session = deriveSession(
                    "responder",
                    privateScalar,
                    decodeFixed(string(offer.get("public_key")), 32, "public key"),
                    offer,
                    response);
            return new Response(response, session);
        } finally {
            Arrays.fill(privateScalar, (byte) 0);
        }
    }

    public static String newInstanceId() { return encode(randomBytes(18)); }

    public static String canonicalJson(Map<String, ?> value) {
        StringBuilder result = new StringBuilder();
        appendCanonicalMap(result, value);
        return result.toString();
    }

    /** RFC 7748 X25519, exposed for deterministic compatibility tests only. */
    public static byte[] x25519(byte[] scalarInput, byte[] uInput) {
        requireLength(scalarInput, 32, "private scalar");
        requireLength(uInput, 32, "public coordinate");
        byte[] scalar = Arrays.copyOf(scalarInput, 32);
        byte[] uBytes = Arrays.copyOf(uInput, 32);
        scalar[0] &= (byte) 248;
        scalar[31] &= (byte) 127;
        scalar[31] |= (byte) 64;
        uBytes[31] &= (byte) 127;
        BigInteger x1 = fromLittleEndian(uBytes).mod(PRIME);
        BigInteger x2 = BigInteger.ONE;
        BigInteger z2 = BigInteger.ZERO;
        BigInteger x3 = x1;
        BigInteger z3 = BigInteger.ONE;
        int swap = 0;
        for (int bit = 254; bit >= 0; bit--) {
            int current = (scalar[bit >>> 3] >>> (bit & 7)) & 1;
            swap ^= current;
            if (swap != 0) {
                BigInteger temporary = x2; x2 = x3; x3 = temporary;
                temporary = z2; z2 = z3; z3 = temporary;
            }
            swap = current;
            BigInteger a = mod(x2.add(z2));
            BigInteger aa = mod(a.multiply(a));
            BigInteger b = mod(x2.subtract(z2));
            BigInteger bb = mod(b.multiply(b));
            BigInteger e = mod(aa.subtract(bb));
            BigInteger c = mod(x3.add(z3));
            BigInteger d = mod(x3.subtract(z3));
            BigInteger da = mod(d.multiply(a));
            BigInteger cb = mod(c.multiply(b));
            x3 = mod(da.add(cb).pow(2));
            z3 = mod(x1.multiply(mod(da.subtract(cb)).pow(2)));
            x2 = mod(aa.multiply(bb));
            z2 = mod(e.multiply(mod(aa.add(A24.multiply(e)))));
        }
        if (swap != 0) {
            BigInteger temporary = x2; x2 = x3; x3 = temporary;
            temporary = z2; z2 = z3; z3 = temporary;
        }
        byte[] result = toLittleEndian(mod(x2.multiply(z2.modPow(PRIME.subtract(
                BigInteger.valueOf(2)), PRIME))), 32);
        Arrays.fill(scalar, (byte) 0);
        Arrays.fill(uBytes, (byte) 0);
        return result;
    }

    private static Session deriveSession(
            String localRole,
            byte[] privateScalar,
            byte[] peerPublic,
            Map<String, Object> offer,
            Map<String, Object> response) {
        Map<String, Object> transcript = new LinkedHashMap<>();
        transcript.put("offer", offer);
        transcript.put("response", response);
        byte[] transcriptHash = sha256(bytes(canonicalJson(transcript)));
        byte[] shared = x25519(privateScalar, peerPublic);
        if (allZero(shared)) {
            throw new PairingException("Pairing produced an invalid all-zero shared secret");
        }
        byte[] salt = sha256(concat(
                decodeFixed(string(offer.get("nonce")), 24, "offer nonce"),
                decodeFixed(string(response.get("nonce")), 24, "response nonce")));
        byte[] material = hkdf(shared, salt, concat(INFO_PREFIX, transcriptHash), 64);
        byte[] sasDigest = hmac(
                Arrays.copyOfRange(material, 32, 64),
                bytes("sas\0"),
                transcriptHash);
        String sas = String.format(
                java.util.Locale.US,
                "%06d",
                new BigInteger(1, Arrays.copyOf(sasDigest, 8)).mod(MILLION).intValue());
        Arrays.fill(shared, (byte) 0);
        return new Session(localRole, offer, response, material, transcriptHash, sas);
    }

    private static Map<String, Object> normalizeOffer(Map<String, ?> raw, long nowSeconds) {
        Map<String, Object> value = copy(raw);
        if (!SCHEMA.equals(value.get("schema")) || !"offer".equals(value.get("kind"))) {
            throw new PairingException("Expected a Sarah pairing offer");
        }
        requireTwoDeviceApproval(value, "offer");
        long[] lifetime = validateLifetime(value, nowSeconds);
        String requestId = string(value.get("request_id"));
        decodeFixed(requestId, 18, "request ID");
        String publicKey = string(value.get("public_key"));
        decodeFixed(publicKey, 32, "public key");
        String nonce = string(value.get("nonce"));
        decodeFixed(nonce, 24, "nonce");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", SCHEMA);
        result.put("kind", "offer");
        result.put("request_id", requestId);
        result.put("created_at", lifetime[0]);
        result.put("expires_at", lifetime[1]);
        result.put("instance_id", label(value.get("instance_id"), ""));
        result.put("device_name", label(value.get("device_name"), "Sarah device"));
        result.put("device_type", label(value.get("device_type"), "device"));
        result.put("public_key", publicKey);
        result.put("nonce", nonce);
        result.put("approval_required_on_both_devices", Boolean.TRUE);
        return result;
    }

    private static Map<String, Object> normalizeResponse(
            Map<String, ?> raw,
            Map<String, Object> offer,
            long nowSeconds) {
        Map<String, Object> value = copy(raw);
        if (!SCHEMA.equals(value.get("schema")) || !"response".equals(value.get("kind"))) {
            throw new PairingException("Expected a Sarah pairing response");
        }
        requireTwoDeviceApproval(value, "response");
        long[] lifetime = validateLifetime(value, nowSeconds);
        if (!offer.get("request_id").equals(value.get("request_id"))) {
            throw new PairingException("Pairing response belongs to another request");
        }
        if (lifetime[1] != asLong(offer.get("expires_at"), "expires_at")) {
            throw new PairingException("Pairing response changed the request lifetime");
        }
        if (lifetime[0] < asLong(offer.get("created_at"), "created_at")) {
            throw new PairingException("Pairing response predates its request");
        }
        String publicKey = string(value.get("public_key"));
        decodeFixed(publicKey, 32, "public key");
        String nonce = string(value.get("nonce"));
        decodeFixed(nonce, 24, "nonce");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", SCHEMA);
        result.put("kind", "response");
        result.put("request_id", offer.get("request_id"));
        result.put("created_at", lifetime[0]);
        result.put("expires_at", lifetime[1]);
        result.put("instance_id", label(value.get("instance_id"), ""));
        result.put("device_name", label(value.get("device_name"), "Sarah device"));
        result.put("device_type", label(value.get("device_type"), "device"));
        result.put("public_key", publicKey);
        result.put("nonce", nonce);
        result.put("approval_required_on_both_devices", Boolean.TRUE);
        return result;
    }

    private static long[] validateLifetime(Map<String, Object> value, long nowSeconds) {
        long created = asLong(value.get("created_at"), "created_at");
        long expires = asLong(value.get("expires_at"), "expires_at");
        if (created > nowSeconds + MAX_CLOCK_SKEW_SECONDS) {
            throw new PairingException("Pairing request was created too far in the future");
        }
        if (expires <= created || expires - created > LIFETIME_SECONDS) {
            throw new PairingException("Pairing request has an invalid lifetime");
        }
        if (nowSeconds > expires) throw new PairingException("Pairing request expired");
        return new long[]{created, expires};
    }

    private static void requireTwoDeviceApproval(Map<String, Object> value, String kind) {
        if (!Boolean.TRUE.equals(value.get("approval_required_on_both_devices"))) {
            throw new PairingException(
                    "Pairing " + kind + " does not require approval on both devices");
        }
    }

    private static Map<String, Object> copy(Map<String, ?> raw) {
        if (raw == null) throw new PairingException("Pairing message is missing");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : raw.entrySet()) {
            result.put(entry.getKey(), entry.getValue());
        }
        if (bytes(canonicalJson(result)).length > MAX_MESSAGE_BYTES) {
            throw new PairingException("Pairing message exceeds its size limit");
        }
        return result;
    }

    private static Map<String, Object> immutable(Map<String, ?> source) {
        return Collections.unmodifiableMap(copy(source));
    }

    private static String label(Object value, String fallback) {
        String text = value == null ? fallback : String.valueOf(value);
        text = text.trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) text = fallback;
        if (text == null || text.isEmpty() || text.length() > 80) {
            throw new PairingException("Pairing device label is invalid");
        }
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character < 32 || Character.isSurrogate(character)) {
                throw new PairingException("Pairing device label is invalid");
            }
        }
        return text;
    }

    private static long asLong(Object value, String field) {
        if (value instanceof Number) return ((Number) value).longValue();
        try { return Long.parseLong(string(value)); }
        catch (NumberFormatException error) {
            throw new PairingException("Pairing " + field + " is invalid", error);
        }
    }

    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }

    private static byte[] decodeFixed(String text, int expected, String field) {
        if (text == null || text.isEmpty() || text.length() > expected * 2 + 8) {
            throw new PairingException("Pairing " + field + " is missing or malformed");
        }
        try {
            byte[] result = Base64.getUrlDecoder().decode(text);
            requireLength(result, expected, field);
            return result;
        } catch (IllegalArgumentException error) {
            throw new PairingException("Pairing " + field + " is not valid base64url", error);
        }
    }

    private static void requireLength(byte[] value, int expected, String field) {
        if (value == null || value.length != expected) {
            throw new PairingException("Pairing " + field + " has the wrong length");
        }
    }

    private static byte[] randomBytes(int size) {
        byte[] result = new byte[size];
        RANDOM.nextBytes(result);
        return result;
    }

    private static byte[] basePoint() {
        byte[] result = new byte[32];
        result[0] = 9;
        return result;
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static BigInteger mod(BigInteger value) { return value.mod(PRIME); }

    private static BigInteger fromLittleEndian(byte[] value) {
        byte[] reversed = new byte[value.length];
        for (int index = 0; index < value.length; index++) {
            reversed[value.length - 1 - index] = value[index];
        }
        return new BigInteger(1, reversed);
    }

    private static byte[] toLittleEndian(BigInteger value, int size) {
        byte[] big = value.toByteArray();
        byte[] result = new byte[size];
        for (int index = 0; index < size && index < big.length; index++) {
            result[index] = big[big.length - 1 - index];
        }
        return result;
    }

    private static byte[] hkdf(byte[] input, byte[] salt, byte[] info, int length) {
        byte[] pseudoRandomKey = hmac(salt, input);
        byte[] result = new byte[length];
        byte[] previous = new byte[0];
        int offset = 0;
        int counter = 1;
        while (offset < length) {
            previous = hmac(pseudoRandomKey, previous, info, new byte[]{(byte) counter});
            int count = Math.min(previous.length, length - offset);
            System.arraycopy(previous, 0, result, offset, count);
            offset += count;
            counter++;
        }
        Arrays.fill(pseudoRandomKey, (byte) 0);
        Arrays.fill(previous, (byte) 0);
        return result;
    }

    private static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] part : parts) mac.update(part);
            return mac.doFinal();
        } catch (Exception error) {
            throw new PairingException("HMAC-SHA256 is unavailable", error);
        }
    }

    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception error) {
            throw new PairingException("SHA-256 is unavailable", error);
        }
    }

    private static byte[] concat(byte[]... values) {
        int size = 0;
        for (byte[] value : values) size += value.length;
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static boolean allZero(byte[] value) {
        int combined = 0;
        for (byte item : value) combined |= item;
        return combined == 0;
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendCanonical(StringBuilder target, Object value) {
        if (value instanceof Map) {
            appendCanonicalMap(target, (Map<String, ?>) value);
        } else if (value instanceof String) {
            appendJsonString(target, (String) value);
        } else if (value instanceof Boolean) {
            target.append(Boolean.TRUE.equals(value) ? "true" : "false");
        } else if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            target.append(((Number) value).longValue());
        } else {
            throw new PairingException("Pairing message contains an unsupported value");
        }
    }

    private static void appendCanonicalMap(StringBuilder target, Map<String, ?> value) {
        List<String> keys = new ArrayList<>(value.keySet());
        Collections.sort(keys);
        target.append('{');
        boolean first = true;
        for (String key : keys) {
            if (key == null) throw new PairingException("Pairing message contains a null key");
            if (!first) target.append(',');
            first = false;
            appendJsonString(target, key);
            target.append(':');
            appendCanonical(target, value.get(key));
        }
        target.append('}');
    }

    private static void appendJsonString(StringBuilder target, String value) {
        target.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"': target.append("\\\""); break;
                case '\\': target.append("\\\\"); break;
                case '\b': target.append("\\b"); break;
                case '\f': target.append("\\f"); break;
                case '\n': target.append("\\n"); break;
                case '\r': target.append("\\r"); break;
                case '\t': target.append("\\t"); break;
                default:
                    if (character < 32) {
                        target.append(String.format("\\u%04x", (int) character));
                    } else {
                        target.append(character);
                    }
            }
        }
        target.append('"');
    }
}
