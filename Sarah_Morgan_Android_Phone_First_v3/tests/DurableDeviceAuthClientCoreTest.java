package com.kiraworld.sarahtravel.durableauth;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Host-JDK acceptance checks for the disconnected durable-auth client layer. */
public final class DurableDeviceAuthClientCoreTest {
    private static final String ORIGIN = "https://full.sarah.example";
    private static final long NOW = Instant.parse("2026-08-09T12:00:00Z").toEpochMilli();
    private static final String THUMBPRINT =
            "xx0BcA-wMohw8atYDJOe6peGModklG2wRHBlXHMvl0M";

    private DurableDeviceAuthClientCoreTest() { }

    public static void main(String[] args) throws Exception {
        testEnrollmentOrchestrationAndSecretBoundary();
        testSessionRenewalAndProtectedRequest();
        testChallengeReplayRejectedBeforeSecondSignature();
        testChallengeFreshnessAndClockBounds();
        test401And403FailClosedWithoutReplay();
        testMissingKeyAndLifecycleHooks();
        testTokenTtlAndLifecycleErasure();
        System.out.println("DURABLE_DEVICE_AUTH_CLIENT_CORE_TESTS_PASS");
    }

    private static void testEnrollmentOrchestrationAndSecretBoundary() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        FakeCredential credential = new FakeCredential(false, true);
        FakeTransport transport = new FakeTransport();
        transport.enqueue(response(201, map(
                "enrollment_id", "enr_1234567890123456",
                "device_code", "private-device-code-1234567890",
                "user_code", "ABCD-EFGH",
                "challenge", "enrollment-challenge-1234567890",
                "key_thumbprint", THUMBPRINT,
                "verification_url", "https://full.sarah.example/activate",
                "expires_in", 600,
                "interval", 5)));
        transport.enqueue(response(202, map("error", "authorization_pending")));
        transport.enqueue(response(201, map(
                "status", "enrolled",
                "device_id", "dev_1234567890123456",
                "key_version", 1,
                "auth_epoch", 1,
                "lease_expires_at", iso(NOW + 90L * 24L * 60L * 60L * 1000L))));

        DurableDeviceAuthClientCore client = new DurableDeviceAuthClientCore(
                ORIGIN, transport, credential, clock, null);
        DurableDeviceAuthClientCore.EnrollmentView view = client.beginEnrollment(
                new DurableDeviceAuthClientCore.EnrollmentMetadata(
                        "android", "Sarah on Galaxy", "com.example.sarah", "3.0"));
        equal("ABCD-EFGH", view.userCode, "owner code");
        check(view.awaitingOwner, "enrollment must await owner");
        check(!view.verificationUrl.contains("private-device-code"),
                "owner view must not embed device code");
        DurableDeviceAuthClientCore.Request create = transport.requests.get(0);
        equal("POST", create.method, "create method");
        equal("/v1/enrollments", create.path, "create path");
        check(!create.headers.containsKey("Authorization"), "enrollment must not use bearer");
        check(create.body.get("public_jwk") instanceof Map<?, ?>, "JWK must be JSON object");

        DurableDeviceAuthClientCore.EnrollmentView pending =
                client.pollEnrollmentCompletion();
        check(pending.awaitingOwner, "202 remains pending");
        client.pollEnrollmentCompletion();
        check(credential.enrollmentSignCount == 1,
                "polling must reuse one in-memory enrollment proof");
        DurableDeviceAuthClientCore.Request complete = transport.requests.get(1);
        equal("/v1/enrollments/enr_1234567890123456/complete",
                complete.path, "complete path");
        check(complete.body.containsKey("device_code"), "wire request needs device code");
        check(!hasPublicSecretSurface(), "client must not expose bearer/device secret getter");
        equal(DurableDeviceAuthClientCore.State.ENROLLED_NEEDS_SESSION,
                client.snapshot().state, "post enrollment state");
        check(credential.bound, "credential must switch to bound signing after enrollment");
        client.close();
    }

    private static void testSessionRenewalAndProtectedRequest() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        FakeCredential credential = new FakeCredential(true, true);
        FakeTransport transport = new FakeTransport();
        enqueueSession(transport, clock.nowMillis(), "chl_one", "nonce_one", "access-one", 600);
        transport.enqueue(response(200, map("ok", true)));
        DurableDeviceAuthClientCore client = boundClient(transport, credential, clock);
        DurableDeviceAuthClientCore.Snapshot ready = client.renewSession();
        equal(DurableDeviceAuthClientCore.State.SESSION_READY, ready.state, "ready state");
        check(credential.sessionSignCount == 1, "one session signature expected");
        DurableDeviceAuthClientCore.Request challenge = transport.requests.get(0);
        equal("/v1/auth/challenges", challenge.path, "challenge path");
        equal("session", challenge.body.get("purpose"), "challenge purpose");
        DurableDeviceAuthClientCore.Request exchange = transport.requests.get(1);
        equal("/v1/auth/token", exchange.path, "token path");
        check(!exchange.headers.containsKey("Authorization"), "token exchange has no bearer");

        DurableDeviceAuthClientCore.Response result = client.executeProtected(
                "POST", "/v1/chat", map("message", "hello"));
        check(Boolean.TRUE.equals(result.body.get("ok")), "protected result");
        DurableDeviceAuthClientCore.Request protectedRequest = transport.requests.get(2);
        equal("Bearer access-one", protectedRequest.headers.get("Authorization"),
                "memory token header");
        check(client.snapshot().diagnosticCode.equals("SESSION_READY"), "snapshot is nonsecret");
        client.close();
    }

    private static void testChallengeReplayRejectedBeforeSecondSignature() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        FakeCredential credential = new FakeCredential(true, true);
        FakeTransport transport = new FakeTransport();
        enqueueSession(transport, clock.nowMillis(), "chl_replay", "nonce_replay", "first", 600);
        DurableDeviceAuthClientCore client = boundClient(transport, credential, clock);
        client.renewSession();
        clock.advance(580_000L);
        transport.enqueue(challengeResponse(
                clock.nowMillis(), "chl_replay", "nonce_replay", 120_000L));
        expectCode("CHALLENGE_REPLAY_REJECTED", client::renewSession);
        check(credential.sessionSignCount == 1,
                "replayed challenge must be rejected before second signature");
        check(transport.requests.size() == 3,
                "replayed challenge must never reach token exchange");
        equal(DurableDeviceAuthClientCore.State.SESSION_RENEWAL_REQUIRED,
                client.snapshot().state, "failed renewal must not retain prior session");
        client.close();
    }

    private static void testChallengeFreshnessAndClockBounds() throws Exception {
        MutableClock expiredClock = new MutableClock(NOW);
        FakeTransport expiredTransport = new FakeTransport();
        expiredTransport.enqueue(challengeResponse(
                expiredClock.nowMillis(), "chl_expired", "nonce_expired", -1L));
        DurableDeviceAuthClientCore expired = boundClient(
                expiredTransport, new FakeCredential(true, true), expiredClock);
        expectCode("CHALLENGE_FRESHNESS_REJECTED", expired::renewSession);

        MutableClock futureClock = new MutableClock(NOW);
        FakeTransport futureTransport = new FakeTransport();
        futureTransport.enqueue(challengeResponse(
                futureClock.nowMillis(), "chl_future", "nonce_future", 151_000L));
        DurableDeviceAuthClientCore future = boundClient(
                futureTransport, new FakeCredential(true, true), futureClock);
        expectCode("CHALLENGE_FRESHNESS_REJECTED", future::renewSession);

        MutableClock skewClock = new MutableClock(NOW);
        FakeTransport skewTransport = new FakeTransport();
        DurableDeviceAuthClientCore.Response skewed = challengeResponse(
                skewClock.nowMillis(), "chl_skew", "nonce_skew", 120_000L);
        Map<String, String> headers = new LinkedHashMap<>(skewed.headers);
        headers.put("Date", httpDate(NOW + 301_000L));
        skewTransport.enqueue(new DurableDeviceAuthClientCore.Response(
                skewed.status, headers, skewed.body));
        DurableDeviceAuthClientCore skew = boundClient(
                skewTransport, new FakeCredential(true, true), skewClock);
        expectCode("CLOCK_SKEW_REJECTED", skew::renewSession);
        expired.close();
        future.close();
        skew.close();
    }

    private static void test401And403FailClosedWithoutReplay() throws Exception {
        MutableClock clock401 = new MutableClock(NOW);
        FakeTransport transport401 = new FakeTransport();
        enqueueSession(transport401, NOW, "chl_401", "nonce_401", "token-401", 600);
        transport401.enqueue(response(401, map("error", "invalid_access_token")));
        DurableDeviceAuthClientCore client401 = boundClient(
                transport401, new FakeCredential(true, true), clock401);
        client401.renewSession();
        int before = transport401.requests.size();
        expectCode("invalid_access_token", () -> client401.executeProtected(
                "POST", "/v1/chat", map("message", "one request only")));
        check(transport401.requests.size() == before + 1, "401 request must not replay");
        equal(DurableDeviceAuthClientCore.State.ENROLLED_NEEDS_SESSION,
                client401.snapshot().state, "401 clears session");

        MutableClock clock403 = new MutableClock(NOW);
        FakeTransport transport403 = new FakeTransport();
        enqueueSession(transport403, NOW, "chl_403", "nonce_403", "token-403", 600);
        transport403.enqueue(response(403, map("error", "device_revoked")));
        DurableDeviceAuthClientCore client403 = boundClient(
                transport403, new FakeCredential(true, true), clock403);
        client403.renewSession();
        expectCode("device_revoked", () -> client403.executeProtected(
                "GET", "/v1/capabilities", Collections.<String, Object>emptyMap()));
        equal(DurableDeviceAuthClientCore.State.REVOKED,
                client403.snapshot().state, "403 revoked state");
        expectCode("SESSION_NOT_READY", () -> client403.executeProtected(
                "GET", "/v1/capabilities", Collections.<String, Object>emptyMap()));
        client401.close();
        client403.close();
    }

    private static void testMissingKeyAndLifecycleHooks() throws Exception {
        FakeCredential missing = new FakeCredential(true, false);
        DurableDeviceAuthClientCore client = boundClient(
                new FakeTransport(), missing, new MutableClock(NOW));
        equal(DurableDeviceAuthClientCore.State.KEY_MISSING_REENROLL_REQUIRED,
                client.snapshot().state, "missing bound key state");
        expectCode("SESSION_RENEWAL_NOT_ALLOWED", client::renewSession);
        client.requireExplicitReenrollment("OWNER_REENROLLMENT_REQUIRED");
        equal(DurableDeviceAuthClientCore.State.REENROLLMENT_REQUIRED,
                client.snapshot().state, "explicit reenrollment state");
        client.markRotationRequired("SERVER_KEY_ROTATION_REQUIRED");
        equal(DurableDeviceAuthClientCore.State.ROTATION_REQUIRED,
                client.snapshot().state, "rotation hook");
        client.markRevoked("OWNER_REVOKED_DEVICE");
        equal(DurableDeviceAuthClientCore.State.REVOKED,
                client.snapshot().state, "revocation hook");
        client.close();
    }

    private static void testTokenTtlAndLifecycleErasure() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        FakeTransport transport = new FakeTransport();
        enqueueSession(transport, NOW, "chl_ttl", "nonce_ttl", "too-long", 601);
        DurableDeviceAuthClientCore client = boundClient(
                transport, new FakeCredential(true, true), clock);
        expectCode("ACCESS_TOKEN_TTL_OUT_OF_BOUNDS", client::renewSession);

        FakeTransport goodTransport = new FakeTransport();
        enqueueSession(goodTransport, NOW, "chl_close", "nonce_close", "erase-me", 600);
        DurableDeviceAuthClientCore good = boundClient(
                goodTransport, new FakeCredential(true, true), clock);
        good.renewSession();
        Field token = DurableDeviceAuthClientCore.class.getDeclaredField("accessToken");
        check(token.getType() == char[].class, "token must use erasable in-memory char array");
        token.setAccessible(true);
        check(token.get(good) != null, "test precondition: token in memory");
        good.close();
        check(token.get(good) == null, "close must discard token reference");
        equal(DurableDeviceAuthClientCore.State.CLOSED,
                good.snapshot().state, "closed state");
        client.close();
    }

    private static DurableDeviceAuthClientCore boundClient(
            FakeTransport transport,
            FakeCredential credential,
            MutableClock clock) {
        return new DurableDeviceAuthClientCore(
                ORIGIN,
                transport,
                credential,
                clock,
                new DurableDeviceAuthClientCore.DeviceRecord(
                        "dev_1234567890123456",
                        1,
                        1,
                        clock.nowMillis() + 90L * 24L * 60L * 60L * 1000L));
    }

    private static void enqueueSession(
            FakeTransport transport,
            long now,
            String challengeId,
            String nonce,
            String token,
            int expiresIn) {
        transport.enqueue(challengeResponse(now, challengeId, nonce, 120_000L));
        transport.enqueue(response(200, map(
                "access_token", token,
                "token_type", "Bearer",
                "expires_in", expiresIn,
                "device_id", "dev_1234567890123456",
                "key_version", 1,
                "auth_epoch", 1,
                "lease_expires_at", iso(now + 90L * 24L * 60L * 60L * 1000L))));
    }

    private static DurableDeviceAuthClientCore.Response challengeResponse(
            long now,
            String challengeId,
            String nonce,
            long expiryDelta) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Date", httpDate(now));
        return new DurableDeviceAuthClientCore.Response(201, headers, map(
                "challenge_id", challengeId,
                "device_id", "dev_1234567890123456",
                "purpose", "session",
                "nonce", nonce,
                "api_origin", ORIGIN,
                "key_version", 1,
                "expires_at", iso(now + expiryDelta)));
    }

    private static DurableDeviceAuthClientCore.Response response(
            int status,
            Map<String, Object> body) {
        return DurableDeviceAuthClientCore.Response.of(status, body);
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private static String iso(long millis) {
        return Instant.ofEpochMilli(millis).toString();
    }

    private static String httpDate(long millis) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC));
    }

    private static boolean hasPublicSecretSurface() {
        for (Method method : DurableDeviceAuthClientCore.class.getMethods()) {
            String name = method.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("accesstoken") || name.contains("devicecode")) return true;
        }
        return false;
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static void expectCode(String expected, ThrowingAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected failure " + expected);
        } catch (DurableDeviceAuthClientCore.ClientException error) {
            equal(expected, error.code, "failure code");
        }
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(label + " mismatch: " + actual);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class MutableClock implements DurableDeviceAuthClientCore.Clock {
        private long now;

        MutableClock(long now) {
            this.now = now;
        }

        @Override
        public long nowMillis() {
            return now;
        }

        void advance(long millis) {
            now += millis;
        }
    }

    private static final class FakeTransport implements DurableDeviceAuthClientCore.Transport {
        final Deque<DurableDeviceAuthClientCore.Response> responses = new ArrayDeque<>();
        final List<DurableDeviceAuthClientCore.Request> requests = new ArrayList<>();

        void enqueue(DurableDeviceAuthClientCore.Response response) {
            responses.addLast(response);
        }

        @Override
        public DurableDeviceAuthClientCore.Response execute(
                DurableDeviceAuthClientCore.Request request) {
            requests.add(request);
            if (responses.isEmpty()) throw new AssertionError("Unexpected HTTP request: " + request.path);
            return responses.removeFirst();
        }
    }

    private static final class FakeCredential implements DurableDeviceAuthClientCore.Credential {
        private final boolean ready;
        private boolean bound;
        int enrollmentSignCount;
        int sessionSignCount;

        FakeCredential(boolean bound, boolean ready) {
            this.bound = bound;
            this.ready = ready;
        }

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public String unavailableCode() {
            return ready ? null : "KEY_MISSING_REENROLL_REQUIRED";
        }

        @Override
        public int keyVersion() {
            return 1;
        }

        @Override
        public Map<String, Object> publicJwk() {
            return map(
                    "kty", "EC",
                    "crv", "P-256",
                    "x", "axfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpY",
                    "y", "T-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU");
        }

        @Override
        public String keyThumbprint() {
            return THUMBPRINT;
        }

        @Override
        public String signEnrollment(String enrollmentId, String challenge, String apiOrigin) {
            if (!ready || bound) throw new AssertionError("invalid enrollment signing state");
            enrollmentSignCount++;
            return signature();
        }

        @Override
        public String signSession(
                String deviceId,
                String challengeId,
                String nonce,
                String apiOrigin,
                int keyVersion) {
            if (!ready || !bound) throw new AssertionError("invalid session signing state");
            sessionSignCount++;
            return signature();
        }

        @Override
        public void markBoundToDevice() {
            if (bound) throw new AssertionError("already bound");
            bound = true;
        }

        private static String signature() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]);
        }
    }
}
