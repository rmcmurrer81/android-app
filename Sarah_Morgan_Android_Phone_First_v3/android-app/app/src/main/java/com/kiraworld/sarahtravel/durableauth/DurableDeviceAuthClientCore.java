package com.kiraworld.sarahtravel.durableauth;

import com.kiraworld.sarahtravel.DurableDeviceAuthProtocol;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure-Java orchestration for Sarah's future durable device-auth lane.
 *
 * <p>Status: STAGED_NOT_CONNECTED. No production activity, UI, model route,
 * event artifact, or current request path references this class. The only
 * access credential is held in a private in-memory {@code char[]} and is
 * erased on expiry, authorization failure, lifecycle closure, rotation,
 * revocation, or re-enrollment.</p>
 */
public final class DurableDeviceAuthClientCore implements AutoCloseable {
    public static final String IMPLEMENTATION_STATUS = "STAGED_NOT_CONNECTED";

    static final long ENROLLMENT_MAX_MILLIS = 10L * 60L * 1000L;
    static final long CHALLENGE_MAX_MILLIS = 2L * 60L * 1000L;
    static final long CHALLENGE_CLOCK_TOLERANCE_MILLIS = 30L * 1000L;
    static final long MIN_CHALLENGE_REMAINING_MILLIS = 5L * 1000L;
    static final long MAX_SERVER_CLOCK_SKEW_MILLIS = 5L * 60L * 1000L;
    static final long ACCESS_TOKEN_MAX_MILLIS = 10L * 60L * 1000L;
    static final long ACCESS_TOKEN_RENEWAL_MARGIN_MILLIS = 30L * 1000L;
    static final int MAX_REPLAY_MARKERS = 64;

    public enum State {
        UNENROLLED,
        ENROLLMENT_PENDING_OWNER,
        ENROLLED_NEEDS_SESSION,
        SESSION_READY,
        SESSION_RENEWAL_REQUIRED,
        KEY_MISSING_REENROLL_REQUIRED,
        REENROLLMENT_REQUIRED,
        ROTATION_REQUIRED,
        REVOKED,
        CLOSED
    }

    public interface Clock {
        long nowMillis();
    }

    /** Injectable HTTP boundary. Implementations must neither log nor persist requests. */
    public interface Transport {
        Response execute(Request request) throws Exception;
    }

    /**
     * Signing boundary implemented by the AndroidKeyStore adapter. The core
     * never receives a private key and never asks this interface to generate
     * or replace one.
     */
    public interface Credential {
        boolean isReady();
        String unavailableCode();
        int keyVersion();
        Map<String, Object> publicJwk();
        String keyThumbprint();
        String signEnrollment(
                String enrollmentId,
                String challenge,
                String apiOrigin);
        String signSession(
                String deviceId,
                String challengeId,
                String nonce,
                String apiOrigin,
                int keyVersion);
        void markBoundToDevice();
    }

    public static final class Request {
        public final String method;
        public final String path;
        public final Map<String, String> headers;
        public final Map<String, Object> body;

        private Request(
                String method,
                String path,
                Map<String, String> headers,
                Map<String, Object> body) {
            this.method = method;
            this.path = path;
            this.headers = immutableStringMap(headers);
            this.body = immutableObjectMap(body);
        }
    }

    public static final class Response {
        public final int status;
        public final Map<String, String> headers;
        public final Map<String, Object> body;

        public Response(
                int status,
                Map<String, String> headers,
                Map<String, Object> body) {
            this.status = status;
            this.headers = immutableStringMap(headers);
            this.body = immutableObjectMap(body);
        }

        public static Response of(int status, Map<String, Object> body) {
            return new Response(status, Collections.<String, String>emptyMap(), body);
        }
    }

    /** Durable, non-secret binding supplied by a future encrypted state store. */
    public static final class DeviceRecord {
        public final String deviceId;
        public final int keyVersion;
        public final long authEpoch;
        public final long leaseExpiresAtMillis;

        public DeviceRecord(
                String deviceId,
                int keyVersion,
                long authEpoch,
                long leaseExpiresAtMillis) {
            this.deviceId = requiredWireValue(deviceId, "deviceId");
            if (keyVersion < 1) throw new IllegalArgumentException("keyVersion must be positive");
            if (authEpoch < 1) throw new IllegalArgumentException("authEpoch must be positive");
            if (leaseExpiresAtMillis <= 0) {
                throw new IllegalArgumentException("lease expiry must be positive");
            }
            this.keyVersion = keyVersion;
            this.authEpoch = authEpoch;
            this.leaseExpiresAtMillis = leaseExpiresAtMillis;
        }
    }

    public static final class EnrollmentMetadata {
        public final String platform;
        public final String displayName;
        public final String appId;
        public final String appVersion;

        public EnrollmentMetadata(
                String platform,
                String displayName,
                String appId,
                String appVersion) {
            this.platform = requiredWireValue(platform, "platform");
            this.displayName = requiredWireValue(displayName, "displayName");
            this.appId = requiredWireValue(appId, "appId");
            this.appVersion = requiredWireValue(appVersion, "appVersion");
        }
    }

    /** Owner-safe enrollment view; device code, challenge and proof are omitted. */
    public static final class EnrollmentView {
        public final String enrollmentId;
        public final String userCode;
        public final String verificationUrl;
        public final long expiresAtMillis;
        public final int pollingIntervalSeconds;
        public final boolean awaitingOwner;

        private EnrollmentView(
                String enrollmentId,
                String userCode,
                String verificationUrl,
                long expiresAtMillis,
                int pollingIntervalSeconds,
                boolean awaitingOwner) {
            this.enrollmentId = enrollmentId;
            this.userCode = userCode;
            this.verificationUrl = verificationUrl;
            this.expiresAtMillis = expiresAtMillis;
            this.pollingIntervalSeconds = pollingIntervalSeconds;
            this.awaitingOwner = awaitingOwner;
        }
    }

    /** Non-secret state snapshot. It intentionally cannot reveal an access token. */
    public static final class Snapshot {
        public final State state;
        public final String deviceId;
        public final int keyVersion;
        public final long authEpoch;
        public final long leaseExpiresAtMillis;
        public final long sessionExpiresAtMillis;
        public final String diagnosticCode;

        private Snapshot(
                State state,
                DeviceRecord device,
                long sessionExpiresAtMillis,
                String diagnosticCode) {
            this.state = state;
            this.deviceId = device == null ? null : device.deviceId;
            this.keyVersion = device == null ? 0 : device.keyVersion;
            this.authEpoch = device == null ? 0 : device.authEpoch;
            this.leaseExpiresAtMillis = device == null ? 0 : device.leaseExpiresAtMillis;
            this.sessionExpiresAtMillis = sessionExpiresAtMillis;
            this.diagnosticCode = diagnosticCode;
        }
    }

    public static final class ClientException extends Exception {
        private static final long serialVersionUID = 1L;

        public final int httpStatus;
        public final String code;

        private ClientException(int httpStatus, String code) {
            super(code);
            this.httpStatus = httpStatus;
            this.code = code;
        }

        private ClientException(int httpStatus, String code, Throwable cause) {
            super(code, cause);
            this.httpStatus = httpStatus;
            this.code = code;
        }
    }

    private static final class PendingEnrollment {
        final String enrollmentId;
        final char[] deviceCode;
        final char[] challenge;
        final String userCode;
        final String verificationUrl;
        final long expiresAtMillis;
        final int pollingIntervalSeconds;
        char[] signature;

        PendingEnrollment(
                String enrollmentId,
                String deviceCode,
                String challenge,
                String userCode,
                String verificationUrl,
                long expiresAtMillis,
                int pollingIntervalSeconds) {
            this.enrollmentId = enrollmentId;
            this.deviceCode = deviceCode.toCharArray();
            this.challenge = challenge.toCharArray();
            this.userCode = userCode;
            this.verificationUrl = verificationUrl;
            this.expiresAtMillis = expiresAtMillis;
            this.pollingIntervalSeconds = pollingIntervalSeconds;
        }

        void erase() {
            eraseChars(deviceCode);
            eraseChars(challenge);
            eraseChars(signature);
            signature = null;
        }
    }

    private final String apiOrigin;
    private final Transport transport;
    private final Credential credential;
    private final Clock clock;
    private final Set<String> signedSessionChallenges = new LinkedHashSet<>();

    private State state;
    private DeviceRecord device;
    private PendingEnrollment pendingEnrollment;
    private char[] accessToken;
    private long accessTokenExpiresAtMillis;
    private String diagnosticCode;

    public DurableDeviceAuthClientCore(
            String apiOrigin,
            Transport transport,
            Credential credential,
            Clock clock,
            DeviceRecord durableDeviceRecord) {
        this.apiOrigin = canonicalApiOrigin(apiOrigin);
        this.transport = Objects.requireNonNull(transport, "transport");
        this.credential = Objects.requireNonNull(credential, "credential");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.device = durableDeviceRecord;
        if (durableDeviceRecord == null) {
            state = State.UNENROLLED;
            diagnosticCode = credential.isReady() ? "READY_FOR_ENROLLMENT" : safeCode(
                    credential.unavailableCode(), "ENROLLMENT_KEY_NOT_PREPARED");
        } else if (!credential.isReady()) {
            state = State.KEY_MISSING_REENROLL_REQUIRED;
            diagnosticCode = safeCode(credential.unavailableCode(), "KEY_MISSING_REENROLL_REQUIRED");
        } else if (credential.keyVersion() != durableDeviceRecord.keyVersion) {
            state = State.ROTATION_REQUIRED;
            diagnosticCode = "KEY_VERSION_MISMATCH";
        } else if (durableDeviceRecord.leaseExpiresAtMillis <= clock.nowMillis()) {
            state = State.REENROLLMENT_REQUIRED;
            diagnosticCode = "DEVICE_LEASE_EXPIRED";
        } else {
            state = State.ENROLLED_NEEDS_SESSION;
            diagnosticCode = "SESSION_REQUIRED";
        }
    }

    public synchronized Snapshot snapshot() {
        refreshExpiryState();
        return new Snapshot(state, device, accessTokenExpiresAtMillis, diagnosticCode);
    }

    public synchronized EnrollmentView beginEnrollment(EnrollmentMetadata metadata)
            throws ClientException {
        requireOpen();
        Objects.requireNonNull(metadata, "metadata");
        if (state != State.UNENROLLED || device != null) {
            throw localFailure("ENROLLMENT_STATE_INVALID");
        }
        requireCredentialReady(false);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("public_jwk", immutableObjectMap(credential.publicJwk()));
        body.put("platform", metadata.platform);
        body.put("display_name", metadata.displayName);
        body.put("app_id", metadata.appId);
        body.put("app_version", metadata.appVersion);
        Response response = execute(new Request(
                "POST", "/v1/enrollments", jsonHeaders(), body));
        if (response.status != 201) {
            applyEnrollmentFailure(response);
        }

        String enrollmentId = bodyString(response.body, "enrollment_id");
        String deviceCode = bodyString(response.body, "device_code");
        String challenge = bodyString(response.body, "challenge");
        String userCode = bodyString(response.body, "user_code");
        String thumbprint = bodyString(response.body, "key_thumbprint");
        if (!constantTimeTextEquals(credential.keyThumbprint(), thumbprint)) {
            throw localFailure("ENROLLMENT_THUMBPRINT_MISMATCH");
        }
        String verificationUrl = bodyString(response.body, "verification_url");
        requireHttpsUrl(verificationUrl, "verification_url");
        int expiresIn = positiveInt(response.body, "expires_in");
        if ((long) expiresIn * 1000L > ENROLLMENT_MAX_MILLIS) {
            throw localFailure("ENROLLMENT_TTL_OUT_OF_BOUNDS");
        }
        int interval = positiveInt(response.body, "interval");
        pendingEnrollment = new PendingEnrollment(
                enrollmentId,
                deviceCode,
                challenge,
                userCode,
                verificationUrl,
                clock.nowMillis() + expiresIn * 1000L,
                interval);
        state = State.ENROLLMENT_PENDING_OWNER;
        diagnosticCode = "AWAITING_OWNER_APPROVAL";
        return enrollmentView(true);
    }

    public synchronized EnrollmentView pollEnrollmentCompletion() throws ClientException {
        requireOpen();
        if (state != State.ENROLLMENT_PENDING_OWNER || pendingEnrollment == null) {
            throw localFailure("ENROLLMENT_NOT_PENDING");
        }
        if (clock.nowMillis() >= pendingEnrollment.expiresAtMillis) {
            clearPendingEnrollment();
            state = State.REENROLLMENT_REQUIRED;
            diagnosticCode = "ENROLLMENT_EXPIRED";
            throw localFailure("ENROLLMENT_EXPIRED");
        }
        requireCredentialReady(false);
        if (pendingEnrollment.signature == null) {
            String proof = credential.signEnrollment(
                    pendingEnrollment.enrollmentId,
                    new String(pendingEnrollment.challenge),
                    apiOrigin);
            requireP1363Signature(proof);
            pendingEnrollment.signature = proof.toCharArray();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("device_code", new String(pendingEnrollment.deviceCode));
        body.put("challenge", new String(pendingEnrollment.challenge));
        body.put("signature", new String(pendingEnrollment.signature));
        Response response = execute(new Request(
                "POST",
                "/v1/enrollments/" + pendingEnrollment.enrollmentId + "/complete",
                jsonHeaders(),
                body));
        if (response.status == 202) {
            diagnosticCode = "AWAITING_OWNER_APPROVAL";
            return enrollmentView(true);
        }
        if (response.status != 201) {
            applyEnrollmentFailure(response);
        }

        String deviceId = bodyString(response.body, "device_id");
        int keyVersion = positiveInt(response.body, "key_version");
        long authEpoch = positiveLong(response.body, "auth_epoch");
        long leaseExpiry = parseIsoMillis(bodyString(response.body, "lease_expires_at"));
        if (keyVersion != credential.keyVersion() || leaseExpiry <= clock.nowMillis()) {
            clearPendingEnrollment();
            state = State.ROTATION_REQUIRED;
            diagnosticCode = "ENROLLMENT_DEVICE_BINDING_MISMATCH";
            throw localFailure("ENROLLMENT_DEVICE_BINDING_MISMATCH");
        }
        credential.markBoundToDevice();
        device = new DeviceRecord(deviceId, keyVersion, authEpoch, leaseExpiry);
        clearPendingEnrollment();
        state = State.ENROLLED_NEEDS_SESSION;
        diagnosticCode = "SESSION_REQUIRED";
        return new EnrollmentView(
                null, null, null, 0L, 0, false);
    }

    /**
     * Obtains a fresh two-minute challenge and exchanges one P-256 proof for a
     * ten-minute access token. The token is never returned to the caller.
     */
    public synchronized Snapshot renewSession() throws ClientException {
        requireOpen();
        refreshExpiryState();
        if (state == State.SESSION_READY
                && accessTokenExpiresAtMillis - clock.nowMillis()
                > ACCESS_TOKEN_RENEWAL_MARGIN_MILLIS) {
            return snapshot();
        }
        if (state != State.ENROLLED_NEEDS_SESSION
                && state != State.SESSION_RENEWAL_REQUIRED
                && state != State.SESSION_READY) {
            throw localFailure("SESSION_RENEWAL_NOT_ALLOWED");
        }
        if (device == null) throw localFailure("DEVICE_BINDING_MISSING");
        requireCredentialReady(true);
        // Once renewal begins, do not retain an almost-expired bearer if the
        // challenge or proof exchange later fails.
        clearAccessToken();
        state = State.SESSION_RENEWAL_REQUIRED;
        diagnosticCode = "SESSION_RENEWAL_IN_PROGRESS";

        Map<String, Object> challengeBody = new LinkedHashMap<>();
        challengeBody.put("device_id", device.deviceId);
        challengeBody.put("purpose", "session");
        Response challengeResponse = execute(new Request(
                "POST", "/v1/auth/challenges", jsonHeaders(), challengeBody));
        if (challengeResponse.status != 201) {
            applySessionFailure(challengeResponse, true);
        }
        validateServerClock(challengeResponse);
        String challengeId = bodyString(challengeResponse.body, "challenge_id");
        String challengeDeviceId = bodyString(challengeResponse.body, "device_id");
        String purpose = bodyString(challengeResponse.body, "purpose");
        String nonce = bodyString(challengeResponse.body, "nonce");
        String returnedOrigin = bodyString(challengeResponse.body, "api_origin");
        int keyVersion = positiveInt(challengeResponse.body, "key_version");
        long challengeExpiry = parseIsoMillis(
                bodyString(challengeResponse.body, "expires_at"));
        long now = clock.nowMillis();
        if (!constantTimeTextEquals(device.deviceId, challengeDeviceId)
                || !"session".equals(purpose)
                || !constantTimeTextEquals(apiOrigin, returnedOrigin)
                || keyVersion != device.keyVersion) {
            markRotationRequired("CHALLENGE_BINDING_MISMATCH");
            throw localFailure("CHALLENGE_BINDING_MISMATCH");
        }
        long remaining = challengeExpiry - now;
        if (remaining < MIN_CHALLENGE_REMAINING_MILLIS
                || remaining > CHALLENGE_MAX_MILLIS + CHALLENGE_CLOCK_TOLERANCE_MILLIS) {
            throw localFailure("CHALLENGE_FRESHNESS_REJECTED");
        }
        String replayMarker = challengeId + "\n" + nonce;
        if (!rememberChallenge(replayMarker)) {
            throw localFailure("CHALLENGE_REPLAY_REJECTED");
        }

        String signature = credential.signSession(
                device.deviceId,
                challengeId,
                nonce,
                apiOrigin,
                device.keyVersion);
        requireP1363Signature(signature);
        Map<String, Object> tokenBody = new LinkedHashMap<>();
        tokenBody.put("device_id", device.deviceId);
        tokenBody.put("challenge_id", challengeId);
        tokenBody.put("nonce", nonce);
        tokenBody.put("signature", signature);
        tokenBody.put("key_version", device.keyVersion);
        Response tokenResponse = execute(new Request(
                "POST", "/v1/auth/token", jsonHeaders(), tokenBody));
        if (tokenResponse.status != 200) {
            applySessionFailure(tokenResponse, true);
        }
        String tokenType = bodyString(tokenResponse.body, "token_type");
        if (!"Bearer".equals(tokenType)) throw localFailure("TOKEN_TYPE_REJECTED");
        String token = bodyString(tokenResponse.body, "access_token");
        int expiresIn = positiveInt(tokenResponse.body, "expires_in");
        if ((long) expiresIn * 1000L > ACCESS_TOKEN_MAX_MILLIS) {
            throw localFailure("ACCESS_TOKEN_TTL_OUT_OF_BOUNDS");
        }
        String tokenDeviceId = bodyString(tokenResponse.body, "device_id");
        int tokenKeyVersion = positiveInt(tokenResponse.body, "key_version");
        long authEpoch = positiveLong(tokenResponse.body, "auth_epoch");
        long leaseExpiry = parseIsoMillis(
                bodyString(tokenResponse.body, "lease_expires_at"));
        if (!constantTimeTextEquals(device.deviceId, tokenDeviceId)
                || tokenKeyVersion != device.keyVersion
                || authEpoch < device.authEpoch
                || leaseExpiry <= clock.nowMillis()) {
            markRotationRequired("TOKEN_BINDING_MISMATCH");
            throw localFailure("TOKEN_BINDING_MISMATCH");
        }
        clearAccessToken();
        accessToken = token.toCharArray();
        accessTokenExpiresAtMillis = clock.nowMillis() + expiresIn * 1000L;
        device = new DeviceRecord(
                device.deviceId,
                device.keyVersion,
                authEpoch,
                leaseExpiry);
        state = State.SESSION_READY;
        diagnosticCode = "SESSION_READY";
        return snapshot();
    }

    /**
     * Executes one protected request. A 401 clears the session but is never
     * replayed automatically; a 403 clears the session and enters an explicit
     * revoked/rotation/re-enrollment state.
     */
    public synchronized Response executeProtected(
            String method,
            String path,
            Map<String, Object> body) throws ClientException {
        requireOpen();
        refreshExpiryState();
        if (state != State.SESSION_READY || accessToken == null) {
            throw localFailure("SESSION_NOT_READY");
        }
        String normalizedMethod = requiredWireValue(method, "method").toUpperCase(Locale.ROOT);
        if (!normalizedMethod.matches("GET|POST|PUT|PATCH|DELETE")) {
            throw new IllegalArgumentException("unsupported HTTP method");
        }
        String normalizedPath = protectedPath(path);
        Map<String, String> headers = jsonHeaders();
        headers.put("Authorization", "Bearer " + new String(accessToken));
        Response response = execute(new Request(
                normalizedMethod,
                normalizedPath,
                headers,
                body == null ? Collections.<String, Object>emptyMap() : body));
        if (response.status == 401 || response.status == 403) {
            applySessionFailure(response, false);
        }
        if (response.status == 409 && isRotationError(errorCode(response))) {
            markRotationRequired(errorCode(response));
            throw responseFailure(response);
        }
        return response;
    }

    /** Lifecycle hook for a server or owner revocation event. */
    public synchronized void markRevoked(String reasonCode) {
        requireOpenUnchecked();
        clearAccessToken();
        clearPendingEnrollment();
        state = State.REVOKED;
        diagnosticCode = safeCode(reasonCode, "DEVICE_REVOKED");
    }

    /** Lifecycle hook; actual dual-signature key rotation is deliberately not connected yet. */
    public synchronized void markRotationRequired(String reasonCode) {
        requireOpenUnchecked();
        clearAccessToken();
        state = State.ROTATION_REQUIRED;
        diagnosticCode = safeCode(reasonCode, "KEY_ROTATION_REQUIRED");
    }

    /** Explicit fail-closed hook when a bound AndroidKeyStore alias disappears. */
    public synchronized void markCredentialMissing() {
        requireOpenUnchecked();
        clearAccessToken();
        state = State.KEY_MISSING_REENROLL_REQUIRED;
        diagnosticCode = "KEY_MISSING_REENROLL_REQUIRED";
    }

    /**
     * Records that future UI must run fresh owner-approved enrollment. This
     * method never deletes, creates, exports, or silently replaces a key.
     */
    public synchronized void requireExplicitReenrollment(String reasonCode) {
        requireOpenUnchecked();
        clearAccessToken();
        clearPendingEnrollment();
        state = State.REENROLLMENT_REQUIRED;
        diagnosticCode = safeCode(reasonCode, "REENROLLMENT_REQUIRED");
    }

    @Override
    public synchronized void close() {
        clearAccessToken();
        clearPendingEnrollment();
        signedSessionChallenges.clear();
        state = State.CLOSED;
        diagnosticCode = "CLOSED";
    }

    private EnrollmentView enrollmentView(boolean awaitingOwner) {
        return new EnrollmentView(
                pendingEnrollment.enrollmentId,
                pendingEnrollment.userCode,
                pendingEnrollment.verificationUrl,
                pendingEnrollment.expiresAtMillis,
                pendingEnrollment.pollingIntervalSeconds,
                awaitingOwner);
    }

    private void refreshExpiryState() {
        if (state == State.SESSION_READY
                && (accessToken == null || accessTokenExpiresAtMillis <= clock.nowMillis())) {
            clearAccessToken();
            state = State.SESSION_RENEWAL_REQUIRED;
            diagnosticCode = "ACCESS_TOKEN_EXPIRED";
        }
        if (device != null
                && device.leaseExpiresAtMillis <= clock.nowMillis()
                && state != State.CLOSED
                && state != State.REVOKED) {
            clearAccessToken();
            state = State.REENROLLMENT_REQUIRED;
            diagnosticCode = "DEVICE_LEASE_EXPIRED";
        }
    }

    private void requireCredentialReady(boolean bound) throws ClientException {
        if (!credential.isReady()) {
            clearAccessToken();
            state = bound
                    ? State.KEY_MISSING_REENROLL_REQUIRED
                    : State.UNENROLLED;
            diagnosticCode = safeCode(
                    credential.unavailableCode(),
                    bound ? "KEY_MISSING_REENROLL_REQUIRED" : "ENROLLMENT_KEY_NOT_PREPARED");
            throw localFailure(diagnosticCode);
        }
        if (bound && (device == null || credential.keyVersion() != device.keyVersion)) {
            markRotationRequired("KEY_VERSION_MISMATCH");
            throw localFailure("KEY_VERSION_MISMATCH");
        }
    }

    private void applyEnrollmentFailure(Response response) throws ClientException {
        String code = errorCode(response);
        if (response.status == 403 || response.status == 409 || response.status == 410) {
            clearPendingEnrollment();
            state = State.REENROLLMENT_REQUIRED;
            diagnosticCode = code;
        }
        throw responseFailure(response);
    }

    private void applySessionFailure(Response response, boolean duringRenewal)
            throws ClientException {
        String code = errorCode(response);
        clearAccessToken();
        if (response.status == 401) {
            state = duringRenewal
                    ? State.REENROLLMENT_REQUIRED
                    : State.ENROLLED_NEEDS_SESSION;
        } else if (response.status == 403) {
            if ("device_revoked".equals(code)) {
                state = State.REVOKED;
            } else if (isRotationError(code)) {
                state = State.ROTATION_REQUIRED;
            } else {
                state = State.REENROLLMENT_REQUIRED;
            }
        } else if (response.status == 409 && isRotationError(code)) {
            state = State.ROTATION_REQUIRED;
        }
        diagnosticCode = code;
        throw responseFailure(response);
    }

    private Response execute(Request request) throws ClientException {
        try {
            return Objects.requireNonNull(transport.execute(request), "transport response");
        } catch (ClientException error) {
            throw error;
        } catch (Exception error) {
            throw new ClientException(0, "AUTH_TRANSPORT_FAILED", error);
        }
    }

    private void validateServerClock(Response response) throws ClientException {
        String date = headerValue(response.headers, "Date");
        if (date == null) throw localFailure("SERVER_DATE_REQUIRED");
        long serverMillis;
        try {
            serverMillis = java.time.ZonedDateTime.parse(
                    date,
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli();
        } catch (DateTimeParseException error) {
            throw localFailure("SERVER_DATE_INVALID");
        }
        if (Math.abs(serverMillis - clock.nowMillis()) > MAX_SERVER_CLOCK_SKEW_MILLIS) {
            throw localFailure("CLOCK_SKEW_REJECTED");
        }
    }

    private boolean rememberChallenge(String marker) {
        if (!signedSessionChallenges.add(marker)) return false;
        if (signedSessionChallenges.size() > MAX_REPLAY_MARKERS) {
            String oldest = signedSessionChallenges.iterator().next();
            signedSessionChallenges.remove(oldest);
        }
        return true;
    }

    private void clearAccessToken() {
        eraseChars(accessToken);
        accessToken = null;
        accessTokenExpiresAtMillis = 0L;
    }

    private void clearPendingEnrollment() {
        if (pendingEnrollment != null) pendingEnrollment.erase();
        pendingEnrollment = null;
    }

    private void requireOpen() throws ClientException {
        if (state == State.CLOSED) throw localFailure("CLIENT_CLOSED");
    }

    private void requireOpenUnchecked() {
        if (state == State.CLOSED) throw new IllegalStateException("client is closed");
    }

    private static ClientException localFailure(String code) {
        return new ClientException(0, safeCode(code, "AUTH_CLIENT_REJECTED"));
    }

    private static ClientException responseFailure(Response response) {
        return new ClientException(response.status, errorCode(response));
    }

    private static String errorCode(Response response) {
        Object value = response.body.get("error");
        if (value instanceof String && isSafeCode((String) value)) return (String) value;
        return "HTTP_" + response.status;
    }

    private static boolean isRotationError(String code) {
        return "stale_key_version".equals(code)
                || "stale_auth_epoch".equals(code)
                || "stale_device_state".equals(code);
    }

    private static String protectedPath(String path) {
        String value = requiredWireValue(path, "path");
        if (!value.startsWith("/v1/")
                || value.contains("?")
                || value.contains("#")
                || value.contains("\\")
                || value.contains("..")
                || value.startsWith("/v1/auth/")
                || value.startsWith("/v1/enrollments")) {
            throw new IllegalArgumentException("protected path rejected");
        }
        return value;
    }

    private static String canonicalApiOrigin(String value) {
        String required = requiredWireValue(value, "apiOrigin");
        URI uri;
        try {
            uri = URI.create(required);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("apiOrigin is invalid", error);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getRawAuthority() == null
                || uri.getRawAuthority().isEmpty()
                || uri.getRawUserInfo() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || required.endsWith("/")) {
            throw new IllegalArgumentException("apiOrigin must be an HTTPS origin without a path");
        }
        String canonical = "https://" + uri.getRawAuthority().toLowerCase(Locale.ROOT);
        if (!required.equals(canonical)) {
            throw new IllegalArgumentException("apiOrigin must be canonical lowercase HTTPS origin");
        }
        return required;
    }

    private static void requireHttpsUrl(String value, String field) {
        URI uri;
        try {
            uri = URI.create(requiredWireValue(value, field));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(field + " is invalid", error);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException(field + " must use HTTPS");
        }
    }

    private static long parseIsoMillis(String value) throws ClientException {
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException error) {
            throw localFailure("SERVER_TIME_INVALID");
        }
    }

    private static String bodyString(Map<String, Object> body, String name)
            throws ClientException {
        Object value = body.get(name);
        if (!(value instanceof String)) throw localFailure("RESPONSE_FIELD_INVALID_" + name);
        try {
            return requiredWireValue((String) value, name);
        } catch (IllegalArgumentException error) {
            throw localFailure("RESPONSE_FIELD_INVALID_" + name);
        }
    }

    private static int positiveInt(Map<String, Object> body, String name)
            throws ClientException {
        Object value = body.get(name);
        if (!(value instanceof Number)) throw localFailure("RESPONSE_FIELD_INVALID_" + name);
        long number = ((Number) value).longValue();
        if (number < 1 || number > Integer.MAX_VALUE) {
            throw localFailure("RESPONSE_FIELD_INVALID_" + name);
        }
        return (int) number;
    }

    private static long positiveLong(Map<String, Object> body, String name)
            throws ClientException {
        Object value = body.get(name);
        if (!(value instanceof Number)) throw localFailure("RESPONSE_FIELD_INVALID_" + name);
        long number = ((Number) value).longValue();
        if (number < 1) throw localFailure("RESPONSE_FIELD_INVALID_" + name);
        return number;
    }

    private static void requireP1363Signature(String encoded) throws ClientException {
        if (encoded == null || !encoded.matches("[A-Za-z0-9_-]{86}")) {
            throw localFailure("DEVICE_SIGNATURE_INVALID");
        }
        try {
            if (java.util.Base64.getUrlDecoder().decode(encoded).length
                    != DurableDeviceAuthProtocol.P256_SIGNATURE_BYTES) {
                throw localFailure("DEVICE_SIGNATURE_INVALID");
            }
        } catch (IllegalArgumentException error) {
            throw localFailure("DEVICE_SIGNATURE_INVALID");
        }
    }

    private static String requiredWireValue(String value, String name) {
        if (value == null || value.isEmpty() || value.length() > 2048
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static Map<String, String> jsonHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Content-Type", "application/json; charset=utf-8");
        return headers;
    }

    private static String headerValue(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private static Map<String, String> immutableStringMap(Map<String, String> input) {
        if (input == null || input.isEmpty()) return Collections.emptyMap();
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> input) {
        if (input == null || input.isEmpty()) return Collections.emptyMap();
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?>) {
                Map<String, Object> nested = new LinkedHashMap<>();
                for (Map.Entry<?, ?> nestedEntry : ((Map<?, ?>) value).entrySet()) {
                    if (!(nestedEntry.getKey() instanceof String)) {
                        throw new IllegalArgumentException("map keys must be strings");
                    }
                    nested.put((String) nestedEntry.getKey(), nestedEntry.getValue());
                }
                value = immutableObjectMap(nested);
            } else if (value instanceof List<?>) {
                value = Collections.unmodifiableList(new ArrayList<>((List<?>) value));
            }
            copy.put(entry.getKey(), value);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static boolean constantTimeTextEquals(String left, String right) {
        if (left == null || right == null) return false;
        int different = left.length() ^ right.length();
        int length = Math.max(left.length(), right.length());
        for (int index = 0; index < length; index++) {
            char a = index < left.length() ? left.charAt(index) : 0;
            char b = index < right.length() ? right.charAt(index) : 0;
            different |= a ^ b;
        }
        return different == 0;
    }

    private static void eraseChars(char[] value) {
        if (value == null) return;
        java.util.Arrays.fill(value, '\0');
    }

    private static String safeCode(String value, String fallback) {
        return isSafeCode(value) ? value : fallback;
    }

    private static boolean isSafeCode(String value) {
        return value != null && value.matches("[A-Za-z0-9_]{1,96}");
    }
}
