import assert from "node:assert/strict";
import test from "node:test";

import { clearAccessJwksCacheForTests } from "../src/access.js";
import {
  canonicalJwkJson,
  encodeBase64Url,
  enrollmentProofPayload,
  publicJwkThumbprint,
  revokeProofPayload,
  rotationProofPayload,
  sessionProofPayload,
  sha256Base64Url,
  utf8,
} from "../src/crypto.js";
import { createFullWorker } from "../src/index.js";
import { MemoryD1 } from "./memory-d1.js";

const API_ORIGIN = "https://sarah-full.example";
const OWNER_ORIGIN = "https://owner-full.example";
const ACCESS_ISSUER = "https://sarah-test.cloudflareaccess.com";
const ACCESS_AUDIENCE = "access-app-audience";
const OWNER_SUBJECT = "owner-subject-opaque";
const OWNER_ID = "own_000000000000000000000001";

function deterministicRandom() {
  let counter = 1;
  return (length) => {
    const bytes = new Uint8Array(length);
    for (let index = 0; index < length; index += 1) {
      bytes[index] = (counter + index * 29) & 0xff;
    }
    counter = (counter + 37) & 0xff;
    return bytes;
  };
}

async function publicJwk(keyPair) {
  const exported = await crypto.subtle.exportKey("jwk", keyPair.publicKey);
  return { kty: exported.kty, crv: exported.crv, x: exported.x, y: exported.y };
}

async function signP256(privateKey, payload) {
  const signature = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" }, privateKey, utf8(payload),
  );
  return encodeBase64Url(signature);
}

function jsonRequest(path, body, { token, headers = {}, method = "POST" } = {}) {
  const requestHeaders = new Headers({ "Content-Type": "application/json", ...headers });
  if (token) requestHeaders.set("Authorization", `Bearer ${token}`);
  return new Request(`${API_ORIGIN}${path}`, {
    method,
    headers: requestHeaders,
    body: method === "GET" ? undefined : JSON.stringify(body || {}),
  });
}

function getRequest(path, { token, headers = {} } = {}) {
  const requestHeaders = new Headers(headers);
  if (token) requestHeaders.set("Authorization", `Bearer ${token}`);
  return new Request(`${API_ORIGIN}${path}`, { headers: requestHeaders });
}

async function makeAccessIdentity(nowMs) {
  const pair = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  );
  const publicKey = await crypto.subtle.exportKey("jwk", pair.publicKey);
  const kid = "access-test-key";
  const header = encodeBase64Url(utf8(JSON.stringify({ alg: "RS256", kid, typ: "JWT" })));
  const payload = encodeBase64Url(utf8(JSON.stringify({
    iss: ACCESS_ISSUER,
    aud: [ACCESS_AUDIENCE],
    sub: OWNER_SUBJECT,
    iat: Math.floor(nowMs / 1000),
    exp: Math.floor(nowMs / 1000) + 600,
  })));
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5", pair.privateKey, utf8(`${header}.${payload}`),
  );
  return {
    assertion: `${header}.${payload}.${encodeBase64Url(signature)}`,
    jwks: { keys: [{ ...publicKey, kid, alg: "RS256", use: "sig" }] },
  };
}

async function makeHarness({ providerCalls = undefined } = {}) {
  clearAccessJwksCacheForTests();
  const clock = { value: Date.parse("2026-08-09T12:00:00.000Z") };
  const db = new MemoryD1();
  const access = await makeAccessIdentity(clock.value);
  db.owners.set(OWNER_ID, {
    owner_id: OWNER_ID,
    access_subject_hash: await sha256Base64Url(OWNER_SUBJECT),
    status: "active",
    created_at: new Date(clock.value).toISOString(),
  });
  const limiterKeys = [];
  const calls = providerCalls || { model: 0, fetch: [] };
  const env = {
    AUTH_DB: db,
    ENROLLMENT_RATE_LIMITER: {
      limit: async ({ key }) => {
        limiterKeys.push(key);
        return { success: true };
      },
    },
    DEVICE_RATE_LIMITER: {
      limit: async ({ key }) => {
        limiterKeys.push(key);
        return { success: true };
      },
    },
    RATE_LIMIT_BUCKET_SECRET: "test-only-secret-that-is-longer-than-thirty-two-characters",
    SARAH_FULL_API_ORIGIN: API_ORIGIN,
    SARAH_FULL_SERVICE_ID: "sarah-full-test-v1",
    SARAH_FULL_MODEL_PROVIDER: "workers-ai",
    SARAH_FULL_MODEL_ID: "@cf/google/gemma-test",
    JWT_ISSUER: API_ORIGIN,
    JWT_AUDIENCE: "sarah-full-clients",
    JWT_SIGNING_KID_CURRENT: "current-test-key",
    JWT_SIGNING_KEY_CURRENT: encodeBase64Url(new Uint8Array(32).fill(71)),
    CF_ACCESS_ISSUER: ACCESS_ISSUER,
    CF_ACCESS_AUDIENCE: ACCESS_AUDIENCE,
    OWNER_PORTAL_ORIGIN: OWNER_ORIGIN,
    ENROLLMENT_VERIFICATION_URL: `${OWNER_ORIGIN}/enroll`,
    TAVILY_API_KEY: "server-only-test-search-key",
    ELEVENLABS_API_KEY: "server-only-test-voice-key",
    SARAH_ELEVENLABS_VOICE_ID: "voice_test_123456",
    SARAH_ELEVENLABS_MODEL_ID: "eleven_flash_v2_5",
    AI: {
      run: async (model, input) => {
        calls.model += 1;
        calls.lastModel = model;
        calls.lastInput = input;
        return { response: "A protected Sarah reply." };
      },
    },
  };
  const fetchImpl = async (url, init) => {
    calls.fetch.push({ url: String(url), init });
    if (String(url) === `${ACCESS_ISSUER}/cdn-cgi/access/certs`) {
      return Response.json(access.jwks);
    }
    if (String(url).includes("tavily.com")) {
      return Response.json({ answer: "Current answer", results: [{ title: "Source" }] });
    }
    if (String(url).includes("elevenlabs.io")) {
      return new Response(new Uint8Array([1, 2, 3]), {
        headers: { "Content-Type": "audio/mpeg" },
      });
    }
    throw new Error(`unexpected fetch ${url}`);
  };
  const randomBytes = deterministicRandom();
  const worker = createFullWorker({ now: () => clock.value, randomBytes, fetchImpl });
  return { worker, env, db, clock, access, limiterKeys, calls, randomBytes };
}

function ownerHeaders(harness, csrf = undefined) {
  const headers = { "Cf-Access-Jwt-Assertion": harness.access.assertion };
  if (csrf) {
    headers.Origin = OWNER_ORIGIN;
    headers["X-Sarah-Owner-CSRF"] = csrf;
    headers.Cookie = `__Host-sarah_owner_csrf=${csrf}`;
  }
  return headers;
}

async function ownerCsrf(harness) {
  const response = await harness.worker.fetch(
    getRequest("/owner/devices", { headers: ownerHeaders(harness) }), harness.env,
  );
  assert.equal(response.status, 200);
  return (await response.json()).csrf_token;
}

async function enrollDevice(harness, pair, name = "Sarah test phone") {
  const jwk = await publicJwk(pair);
  const created = await harness.worker.fetch(jsonRequest("/v1/enrollments", {
    public_jwk: jwk,
    platform: "android",
    display_name: name,
    app_id: "com.kiraworld.sarahtravel",
    app_version: "3.0.0-test",
  }, { headers: { "CF-Connecting-IP": "192.0.2.10" } }), harness.env);
  assert.equal(created.status, 201);
  const enrollment = await created.json();

  const csrf = await ownerCsrf(harness);
  const lookup = await harness.worker.fetch(jsonRequest(
    "/owner/enrollments/lookup",
    { user_code: enrollment.user_code.toLowerCase() },
    { headers: ownerHeaders(harness, csrf) },
  ), harness.env);
  assert.equal(lookup.status, 200);
  assert.equal((await lookup.json()).enrollment.enrollment_id, enrollment.enrollment_id);
  const approved = await harness.worker.fetch(jsonRequest(
    `/owner/enrollments/${enrollment.enrollment_id}/approve`,
    {},
    { headers: ownerHeaders(harness, csrf) },
  ), harness.env);
  assert.equal(approved.status, 200);

  const signature = await signP256(pair.privateKey, enrollmentProofPayload({
    enrollmentId: enrollment.enrollment_id,
    challenge: enrollment.challenge,
    apiOrigin: API_ORIGIN,
    keyThumbprint: enrollment.key_thumbprint,
  }));
  const completed = await harness.worker.fetch(jsonRequest(
    `/v1/enrollments/${enrollment.enrollment_id}/complete`,
    {
      device_code: enrollment.device_code,
      challenge: enrollment.challenge,
      signature,
    },
  ), harness.env);
  assert.equal(completed.status, 201);
  return { pair, jwk, enrollment, device: await completed.json() };
}

async function issueSession(harness, enrolled) {
  const challengeResponse = await harness.worker.fetch(jsonRequest("/v1/auth/challenges", {
    device_id: enrolled.device.device_id,
    purpose: "session",
  }), harness.env);
  assert.equal(challengeResponse.status, 201);
  const challenge = await challengeResponse.json();
  const signature = await signP256(enrolled.pair.privateKey, sessionProofPayload({
    deviceId: enrolled.device.device_id,
    challengeId: challenge.challenge_id,
    nonce: challenge.nonce,
    apiOrigin: API_ORIGIN,
    keyVersion: challenge.key_version,
  }));
  const requestBody = {
    device_id: enrolled.device.device_id,
    challenge_id: challenge.challenge_id,
    nonce: challenge.nonce,
    key_version: challenge.key_version,
    signature,
  };
  const response = await harness.worker.fetch(
    jsonRequest("/v1/auth/token", requestBody), harness.env,
  );
  assert.equal(response.status, 200);
  return { challenge, requestBody, session: await response.json() };
}

test("public health is minimal and does not disclose provider or credential state", async () => {
  const harness = await makeHarness();
  const response = await harness.worker.fetch(getRequest("/health"), harness.env);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    ok: true,
    service: "sarah-full-auth-worker",
    contract_major: 1,
    contract_version: "sarah-full-device-auth-v1",
  });
});

test("owner preflight is limited to the exact configured HTTPS origin", async () => {
  const harness = await makeHarness();
  const accepted = await harness.worker.fetch(new Request(`${API_ORIGIN}/owner/devices`, {
    method: "OPTIONS",
    headers: {
      Origin: OWNER_ORIGIN,
      "Access-Control-Request-Method": "GET",
    },
  }), harness.env);
  assert.equal(accepted.status, 204);
  assert.equal(accepted.headers.get("Access-Control-Allow-Origin"), OWNER_ORIGIN);
  const rejected = await harness.worker.fetch(new Request(`${API_ORIGIN}/owner/devices`, {
    method: "OPTIONS",
    headers: {
      Origin: "https://attacker.example",
      "Access-Control-Request-Method": "GET",
    },
  }), harness.env);
  assert.equal(rejected.status, 403);
});

test("P-256 JWK thumbprints are canonical and private or extra fields fail closed", async () => {
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"],
  );
  const jwk = await publicJwk(pair);
  assert.equal(canonicalJwkJson(jwk), JSON.stringify({
    crv: "P-256", kty: "EC", x: jwk.x, y: jwk.y,
  }));
  assert.match(await publicJwkThumbprint(jwk), /^[A-Za-z0-9_-]{43}$/);
  assert.throws(() => canonicalJwkJson({ ...jwk, d: "private" }), /invalid_public_key_fields/);
  assert.throws(() => canonicalJwkJson({ ...jwk, use: "sig" }), /invalid_public_key_fields/);
});

test("owner-approved enrollment, signed challenge, memory token, capabilities and chat work", async () => {
  const harness = await makeHarness();
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"],
  );
  const enrolled = await enrollDevice(harness, pair);
  const issued = await issueSession(harness, enrolled);
  assert.equal(issued.session.token_type, "Bearer");
  assert.equal(issued.session.expires_in, 600);
  assert.equal(JSON.stringify(harness.db.devices).includes(issued.session.access_token), false);

  const capabilities = await harness.worker.fetch(
    getRequest("/v1/capabilities", { token: issued.session.access_token }), harness.env,
  );
  assert.equal(capabilities.status, 200);
  const truth = await capabilities.json();
  assert.equal(truth.provider, "workers-ai");
  assert.equal(truth.model, "@cf/google/gemma-test");
  assert.equal(truth.device_id, enrolled.device.device_id);

  const chat = await harness.worker.fetch(jsonRequest(
    "/v1/chat", { message: "Hello Sarah" }, { token: issued.session.access_token },
  ), harness.env);
  assert.equal(chat.status, 200);
  assert.equal((await chat.json()).reply, "A protected Sarah reply.");
  assert.equal(harness.calls.model, 1);
  assert.equal(harness.calls.lastModel, "@cf/google/gemma-test");
});

test("pending enrollment, forged proof, denial, expiry, and single consumption are distinct", async () => {
  const harness = await makeHarness();
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"],
  );
  const jwk = await publicJwk(pair);
  const created = await harness.worker.fetch(jsonRequest("/v1/enrollments", {
    public_jwk: jwk,
    platform: "windows",
    display_name: "Windows laptop",
    app_id: "SarahMorgan.Windows",
    app_version: "3.0.0-test",
  }), harness.env);
  const enrollment = await created.json();
  const signature = await signP256(pair.privateKey, enrollmentProofPayload({
    enrollmentId: enrollment.enrollment_id,
    challenge: enrollment.challenge,
    apiOrigin: API_ORIGIN,
    keyThumbprint: enrollment.key_thumbprint,
  }));
  const pending = await harness.worker.fetch(jsonRequest(
    `/v1/enrollments/${enrollment.enrollment_id}/complete`,
    { device_code: enrollment.device_code, challenge: enrollment.challenge, signature },
  ), harness.env);
  assert.equal(pending.status, 202);

  const csrf = await ownerCsrf(harness);
  const approved = await harness.worker.fetch(jsonRequest(
    `/owner/enrollments/${enrollment.enrollment_id}/approve`, {},
    { headers: ownerHeaders(harness, csrf) },
  ), harness.env);
  assert.equal(approved.status, 200);
  const forged = await harness.worker.fetch(jsonRequest(
    `/v1/enrollments/${enrollment.enrollment_id}/complete`,
    {
      device_code: enrollment.device_code,
      challenge: enrollment.challenge,
      signature: encodeBase64Url(new Uint8Array(64)),
    },
  ), harness.env);
  assert.equal(forged.status, 401);
  const accepted = await harness.worker.fetch(jsonRequest(
    `/v1/enrollments/${enrollment.enrollment_id}/complete`,
    { device_code: enrollment.device_code, challenge: enrollment.challenge, signature },
  ), harness.env);
  assert.equal(accepted.status, 201);
  const replay = await harness.worker.fetch(jsonRequest(
    `/v1/enrollments/${enrollment.enrollment_id}/complete`,
    { device_code: enrollment.device_code, challenge: enrollment.challenge, signature },
  ), harness.env);
  assert.equal(replay.status, 409);
  assert.equal(harness.db.devices.size, 1);
});

test("owner denial and ten-minute enrollment expiry both fail closed", async () => {
  const harness = await makeHarness();
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"],
  );
  const jwk = await publicJwk(pair);
  async function create() {
    const response = await harness.worker.fetch(jsonRequest("/v1/enrollments", {
      public_jwk: jwk,
      platform: "android",
      display_name: "Denied or expired phone",
      app_id: "com.kiraworld.sarahtravel",
      app_version: "3.0.0-test",
    }), harness.env);
    assert.equal(response.status, 201);
    return response.json();
  }

  const deniedEnrollment = await create();
  const csrf = await ownerCsrf(harness);
  const denial = await harness.worker.fetch(jsonRequest(
    `/owner/enrollments/${deniedEnrollment.enrollment_id}/deny`, {},
    { headers: ownerHeaders(harness, csrf) },
  ), harness.env);
  assert.equal(denial.status, 200);
  assert.equal((await denial.json()).state, "denied");
  const deniedSignature = await signP256(pair.privateKey, enrollmentProofPayload({
    enrollmentId: deniedEnrollment.enrollment_id,
    challenge: deniedEnrollment.challenge,
    apiOrigin: API_ORIGIN,
    keyThumbprint: deniedEnrollment.key_thumbprint,
  }));
  const deniedComplete = await harness.worker.fetch(jsonRequest(
    `/v1/enrollments/${deniedEnrollment.enrollment_id}/complete`,
    {
      device_code: deniedEnrollment.device_code,
      challenge: deniedEnrollment.challenge,
      signature: deniedSignature,
    },
  ), harness.env);
  assert.equal(deniedComplete.status, 403);

  const expiredEnrollment = await create();
  harness.clock.value += 606_000;
  const expiredComplete = await harness.worker.fetch(jsonRequest(
    `/v1/enrollments/${expiredEnrollment.enrollment_id}/complete`,
    {
      device_code: expiredEnrollment.device_code,
      challenge: expiredEnrollment.challenge,
      signature: encodeBase64Url(new Uint8Array(64)),
    },
  ), harness.env);
  assert.equal(expiredComplete.status, 410);
  assert.equal(harness.db.devices.size, 0);
});

test("concurrent enrollment completion consumes exactly once", async () => {
  const harness = await makeHarness();
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"],
  );
  const jwk = await publicJwk(pair);
  const created = await harness.worker.fetch(jsonRequest("/v1/enrollments", {
    public_jwk: jwk,
    platform: "windows",
    display_name: "Concurrent Windows install",
    app_id: "SarahMorgan.Windows",
    app_version: "3.0.0-test",
  }), harness.env);
  const enrollment = await created.json();
  const csrf = await ownerCsrf(harness);
  await harness.worker.fetch(jsonRequest(
    `/owner/enrollments/${enrollment.enrollment_id}/approve`, {},
    { headers: ownerHeaders(harness, csrf) },
  ), harness.env);
  const signature = await signP256(pair.privateKey, enrollmentProofPayload({
    enrollmentId: enrollment.enrollment_id,
    challenge: enrollment.challenge,
    apiOrigin: API_ORIGIN,
    keyThumbprint: enrollment.key_thumbprint,
  }));
  const body = {
    device_code: enrollment.device_code,
    challenge: enrollment.challenge,
    signature,
  };
  const [left, right] = await Promise.all([
    harness.worker.fetch(jsonRequest(
      `/v1/enrollments/${enrollment.enrollment_id}/complete`, body,
    ), harness.env),
    harness.worker.fetch(jsonRequest(
      `/v1/enrollments/${enrollment.enrollment_id}/complete`, body,
    ), harness.env),
  ]);
  assert.deepEqual([left.status, right.status].sort(), [201, 409]);
  assert.equal(harness.db.devices.size, 1);
});

test("challenge tamper and replay fail; fresh self-revoke immediately invalidates the JWT", async () => {
  const harness = await makeHarness();
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"],
  );
  const enrolled = await enrollDevice(harness, pair);
  const issued = await issueSession(harness, enrolled);
  const replay = await harness.worker.fetch(
    jsonRequest("/v1/auth/token", issued.requestBody), harness.env,
  );
  assert.equal(replay.status, 409);

  const revokeChallengeResponse = await harness.worker.fetch(jsonRequest(
    "/v1/auth/challenges",
    { device_id: enrolled.device.device_id, purpose: "self_revoke" },
  ), harness.env);
  const revokeChallenge = await revokeChallengeResponse.json();
  const reason = "owner chose to remove this install";
  const reasonHash = await sha256Base64Url(reason);
  const signature = await signP256(pair.privateKey, revokeProofPayload({
    deviceId: enrolled.device.device_id,
    challengeId: revokeChallenge.challenge_id,
    nonce: revokeChallenge.nonce,
    apiOrigin: API_ORIGIN,
    keyVersion: revokeChallenge.key_version,
    reasonHash,
  }));
  const revoked = await harness.worker.fetch(jsonRequest(
    "/v1/devices/me/revoke",
    {
      challenge_id: revokeChallenge.challenge_id,
      nonce: revokeChallenge.nonce,
      key_version: revokeChallenge.key_version,
      reason,
      signature,
    },
    { token: issued.session.access_token },
  ), harness.env);
  assert.equal(revoked.status, 200);

  const stale = await harness.worker.fetch(
    getRequest("/v1/capabilities", { token: issued.session.access_token }), harness.env,
  );
  assert.equal(stale.status, 403);
  assert.deepEqual(await stale.json(), { error: "device_revoked" });
});

test("expired challenge returns 410 and never invokes a provider", async () => {
  const harness = await makeHarness();
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"],
  );
  const enrolled = await enrollDevice(harness, pair);
  const challengeResponse = await harness.worker.fetch(jsonRequest(
    "/v1/auth/challenges", { device_id: enrolled.device.device_id, purpose: "session" },
  ), harness.env);
  const challenge = await challengeResponse.json();
  const signature = await signP256(pair.privateKey, sessionProofPayload({
    deviceId: enrolled.device.device_id,
    challengeId: challenge.challenge_id,
    nonce: challenge.nonce,
    apiOrigin: API_ORIGIN,
    keyVersion: challenge.key_version,
  }));
  harness.clock.value += 121_000;
  const expired = await harness.worker.fetch(jsonRequest("/v1/auth/token", {
    device_id: enrolled.device.device_id,
    challenge_id: challenge.challenge_id,
    nonce: challenge.nonce,
    key_version: challenge.key_version,
    signature,
  }), harness.env);
  assert.equal(expired.status, 410);
  assert.equal(harness.calls.model, 0);
});

test("a signature from the wrong device key cannot consume the challenge", async () => {
  const harness = await makeHarness();
  const enrolledPair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"],
  );
  const wrongPair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"],
  );
  const enrolled = await enrollDevice(harness, enrolledPair);
  const challengeResponse = await harness.worker.fetch(jsonRequest(
    "/v1/auth/challenges", { device_id: enrolled.device.device_id, purpose: "session" },
  ), harness.env);
  const challenge = await challengeResponse.json();
  const payload = sessionProofPayload({
    deviceId: enrolled.device.device_id,
    challengeId: challenge.challenge_id,
    nonce: challenge.nonce,
    apiOrigin: API_ORIGIN,
    keyVersion: challenge.key_version,
  });
  const requestBody = {
    device_id: enrolled.device.device_id,
    challenge_id: challenge.challenge_id,
    nonce: challenge.nonce,
    key_version: challenge.key_version,
    signature: await signP256(wrongPair.privateKey, payload),
  };
  const rejected = await harness.worker.fetch(
    jsonRequest("/v1/auth/token", requestBody), harness.env,
  );
  assert.equal(rejected.status, 401);
  assert.equal(harness.db.challenges.get(challenge.challenge_id).consumed_at, null);

  requestBody.signature = await signP256(enrolledPair.privateKey, payload);
  const accepted = await harness.worker.fetch(
    jsonRequest("/v1/auth/token", requestBody), harness.env,
  );
  assert.equal(accepted.status, 200);
});

test("missing D1 and absent signing key fail closed before model, search, or voice", async () => {
  const harness = await makeHarness();
  const noStore = { ...harness.env, AUTH_DB: undefined };
  const response = await harness.worker.fetch(
    getRequest("/v1/capabilities", { token: "forged.token.value" }), noStore,
  );
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: "auth_store_unavailable" });

  const noSigningKey = { ...harness.env, JWT_SIGNING_KEY_CURRENT: undefined };
  const chat = await harness.worker.fetch(
    jsonRequest("/v1/chat", { message: "do not call" }, { token: "forged.token.value" }),
    noSigningKey,
  );
  assert.equal(chat.status, 503);
  assert.deepEqual(await chat.json(), { error: "access_signing_key_unavailable" });
  assert.equal(harness.calls.model, 0);
  assert.equal(harness.calls.fetch.filter((entry) => !entry.url.includes("access/certs")).length, 0);
});

test("a primary D1 outage after token issue still fails before the model call", async () => {
  const harness = await makeHarness();
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"],
  );
  const enrolled = await enrollDevice(harness, pair);
  const issued = await issueSession(harness, enrolled);
  harness.db.available = false;
  const response = await harness.worker.fetch(jsonRequest(
    "/v1/chat", { message: "must not reach model" }, { token: issued.session.access_token },
  ), harness.env);
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: "auth_store_unavailable" });
  assert.equal(harness.calls.model, 0);
});

test("JWT signature, issuer, audience and time checks are independent of provider state", async () => {
  const harness = await makeHarness();
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"],
  );
  const enrolled = await enrollDevice(harness, pair);
  const issued = await issueSession(harness, enrolled);
  const token = issued.session.access_token;

  const forged = await harness.worker.fetch(
    getRequest("/v1/capabilities", { token: `${token.slice(0, -2)}AA` }), harness.env,
  );
  assert.equal(forged.status, 401);

  const wrongIssuer = await harness.worker.fetch(
    getRequest("/v1/capabilities", { token }),
    { ...harness.env, JWT_ISSUER: "https://wrong-issuer.example" },
  );
  assert.equal(wrongIssuer.status, 401);

  const wrongAudience = await harness.worker.fetch(
    getRequest("/v1/capabilities", { token }),
    { ...harness.env, JWT_AUDIENCE: "wrong-audience" },
  );
  assert.equal(wrongAudience.status, 401);

  harness.clock.value += 606_000;
  const expired = await harness.worker.fetch(
    getRequest("/v1/capabilities", { token }), harness.env,
  );
  assert.equal(expired.status, 401);
  assert.equal(harness.calls.model, 0);
});

test("a bounded previous JWT signing key overlap works and removal rejects the old kid", async () => {
  const harness = await makeHarness();
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"],
  );
  const enrolled = await enrollDevice(harness, pair);
  const issued = await issueSession(harness, enrolled);
  const rotatedServerKeys = {
    ...harness.env,
    JWT_SIGNING_KID_CURRENT: "next-test-key",
    JWT_SIGNING_KEY_CURRENT: encodeBase64Url(new Uint8Array(32).fill(99)),
    JWT_SIGNING_KID_PREVIOUS: harness.env.JWT_SIGNING_KID_CURRENT,
    JWT_SIGNING_KEY_PREVIOUS: harness.env.JWT_SIGNING_KEY_CURRENT,
  };
  const overlap = await harness.worker.fetch(
    getRequest("/v1/capabilities", { token: issued.session.access_token }), rotatedServerKeys,
  );
  assert.equal(overlap.status, 200);
  const removed = await harness.worker.fetch(
    getRequest("/v1/capabilities", { token: issued.session.access_token }),
    {
      ...rotatedServerKeys,
      JWT_SIGNING_KID_PREVIOUS: undefined,
      JWT_SIGNING_KEY_PREVIOUS: undefined,
    },
  );
  assert.equal(removed.status, 401);
});

test("forged Cloudflare Access assertion and missing CSRF cannot approve enrollment", async () => {
  const harness = await makeHarness();
  const forged = `${harness.access.assertion.slice(0, -2)}AA`;
  const before = harness.db.enrollments.size;
  const denied = await harness.worker.fetch(
    getRequest("/owner/devices", { headers: { "Cf-Access-Jwt-Assertion": forged } }),
    harness.env,
  );
  assert.equal(denied.status, 401);
  assert.equal(harness.db.enrollments.size, before);

  const csrfMissing = await harness.worker.fetch(jsonRequest(
    "/owner/enrollments/enr_0000000000000000/approve", {},
    { headers: ownerHeaders(harness) },
  ), harness.env);
  assert.equal(csrfMissing.status, 403);
  assert.deepEqual(await csrfMissing.json(), { error: "csrf_rejected" });
});

test("per-device rate-limit keys are independent", async () => {
  const harness = await makeHarness();
  const firstPair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"],
  );
  const secondPair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"],
  );
  const first = await enrollDevice(harness, firstPair, "First device");
  const second = await enrollDevice(harness, secondPair, "Second device");
  const firstSession = await issueSession(harness, first);
  const secondSession = await issueSession(harness, second);
  await harness.worker.fetch(jsonRequest(
    "/v1/chat", { message: "one" }, { token: firstSession.session.access_token },
  ), harness.env);
  await harness.worker.fetch(jsonRequest(
    "/v1/chat", { message: "two" }, { token: secondSession.session.access_token },
  ), harness.env);
  assert.ok(harness.limiterKeys.includes(`${first.device.device_id}:chat`));
  assert.ok(harness.limiterKeys.includes(`${second.device.device_id}:chat`));
  assert.notEqual(`${first.device.device_id}:chat`, `${second.device.device_id}:chat`);
});

test("owner revocation increments epoch and immediately rejects an otherwise valid token", async () => {
  const harness = await makeHarness();
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"],
  );
  const enrolled = await enrollDevice(harness, pair);
  const issued = await issueSession(harness, enrolled);
  const csrf = await ownerCsrf(harness);
  const revoked = await harness.worker.fetch(jsonRequest(
    `/owner/devices/${enrolled.device.device_id}/revoke`,
    { reason: "owner retired test device" },
    { headers: ownerHeaders(harness, csrf) },
  ), harness.env);
  assert.equal(revoked.status, 200);
  const stored = harness.db.devices.get(enrolled.device.device_id);
  assert.equal(stored.status, "revoked");
  assert.equal(stored.auth_epoch, 2);

  const rejected = await harness.worker.fetch(
    getRequest("/v1/capabilities", { token: issued.session.access_token }), harness.env,
  );
  assert.equal(rejected.status, 403);
  assert.equal(harness.calls.model, 0);
});

test("dual-proof key rotation increments key version and stale epoch, with idempotent recovery", async () => {
  const harness = await makeHarness();
  const oldPair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"],
  );
  const newPair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"],
  );
  const enrolled = await enrollDevice(harness, oldPair);
  const issued = await issueSession(harness, enrolled);
  const challengeResponse = await harness.worker.fetch(jsonRequest(
    "/v1/auth/challenges",
    { device_id: enrolled.device.device_id, purpose: "key_rotation" },
  ), harness.env);
  const challenge = await challengeResponse.json();
  const oldJwk = await publicJwk(oldPair);
  const newJwk = await publicJwk(newPair);
  const rotationId = "rotation_00000000000000000001";
  const oldThumbprint = await publicJwkThumbprint(oldJwk);
  const newThumbprint = await publicJwkThumbprint(newJwk);
  const payload = rotationProofPayload({
    deviceId: enrolled.device.device_id,
    rotationId,
    challengeId: challenge.challenge_id,
    nonce: challenge.nonce,
    apiOrigin: API_ORIGIN,
    currentKeyVersion: challenge.key_version,
    oldThumbprint,
    newThumbprint,
  });
  const body = {
    rotation_id: rotationId,
    challenge_id: challenge.challenge_id,
    nonce: challenge.nonce,
    current_key_version: challenge.key_version,
    old_thumbprint: oldThumbprint,
    new_public_jwk: newJwk,
    old_signature: await signP256(oldPair.privateKey, payload),
    new_signature: await signP256(newPair.privateKey, payload),
  };
  const rotated = await harness.worker.fetch(jsonRequest(
    "/v1/devices/me/key-rotations", body, { token: issued.session.access_token },
  ), harness.env);
  assert.equal(rotated.status, 200);
  const result = await rotated.json();
  assert.equal(result.key_version, 2);
  assert.equal(result.auth_epoch, 2);
  assert.equal(result.replayed, false);

  const oldToken = await harness.worker.fetch(
    getRequest("/v1/capabilities", { token: issued.session.access_token }), harness.env,
  );
  assert.equal(oldToken.status, 403);

  harness.clock.value += 121_000;
  const replay = await harness.worker.fetch(jsonRequest(
    "/v1/devices/me/key-rotations", body, { token: issued.session.access_token },
  ), harness.env);
  assert.equal(replay.status, 200);
  assert.equal((await replay.json()).replayed, true);
});

test("protected search and ElevenLabs voice run only after current device authorization", async () => {
  const harness = await makeHarness();
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"],
  );
  const enrolled = await enrollDevice(harness, pair);
  const issued = await issueSession(harness, enrolled);
  const search = await harness.worker.fetch(jsonRequest(
    "/v1/search", { query: "nearby event" }, { token: issued.session.access_token },
  ), harness.env);
  assert.equal(search.status, 200);
  assert.equal((await search.json()).answer, "Current answer");

  const voice = await harness.worker.fetch(jsonRequest(
    "/v1/voice", { text: "Hello from Sarah" }, { token: issued.session.access_token },
  ), harness.env);
  assert.equal(voice.status, 200);
  assert.equal(voice.headers.get("X-Sarah-Voice-Provider"), "elevenlabs");
  assert.deepEqual([...new Uint8Array(await voice.arrayBuffer())], [1, 2, 3]);
});
