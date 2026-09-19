import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Android/JDK interoperability proof for the public Sarah fixture only. */
public final class ProtocolVectorVerifier {
    private static String field(String json, String name, int occurrence) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
        Matcher matcher = pattern.matcher(json);
        for (int index = 0; matcher.find(); index++) {
            if (index == occurrence) {
                return matcher.group(1).replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
            }
        }
        throw new IllegalArgumentException("Missing fixture field: " + name + " occurrence " + occurrence);
    }

    private static byte[] b64(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private static byte[] derInteger(byte[] raw, int offset) {
        int first = offset;
        int end = offset + 32;
        while (first < end - 1 && raw[first] == 0) first++;
        boolean prefix = (raw[first] & 0x80) != 0;
        int length = end - first + (prefix ? 1 : 0);
        byte[] result = new byte[length + 2];
        result[0] = 0x02;
        result[1] = (byte) length;
        System.arraycopy(raw, first, result, 2 + (prefix ? 1 : 0), end - first);
        return result;
    }

    private static byte[] p1363ToDer(byte[] raw) {
        if (raw.length != 64) throw new IllegalArgumentException("P1363 signature must contain 64 bytes");
        byte[] r = derInteger(raw, 0);
        byte[] s = derInteger(raw, 32);
        byte[] der = new byte[2 + r.length + s.length];
        der[0] = 0x30;
        der[1] = (byte) (r.length + s.length);
        System.arraycopy(r, 0, der, 2, r.length);
        System.arraycopy(s, 0, der, 2 + r.length, s.length);
        return der;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static void verify(ECPublicKey key, String payload, String expectedHash, String signature) throws Exception {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        if (!hex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expectedHash)) {
            throw new AssertionError("payload digest mismatch");
        }
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update(bytes);
        if (!verifier.verify(p1363ToDer(b64(signature)))) throw new AssertionError("signature did not verify");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass fixtures/p256-v1.json");
        String json = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        String x = field(json, "x", 0);
        String y = field(json, "y", 0);
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec curve = parameters.getParameterSpec(ECParameterSpec.class);
        ECPublicKey key = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(
            new ECPublicKeySpec(new ECPoint(new BigInteger(1, b64(x)), new BigInteger(1, b64(y))), curve));

        verify(key, field(json, "payload_utf8", 0), field(json, "payload_sha256_hex", 0),
            field(json, "signature_p1363_base64url", 0));
        verify(key, field(json, "payload_utf8", 1), field(json, "payload_sha256_hex", 1),
            field(json, "signature_p1363_base64url", 1));
        System.out.println("PASS: Java verified Sarah P-256 auth and enrollment vectors");
    }
}
