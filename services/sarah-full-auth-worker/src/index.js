import { issueOwnerCsrf, verifyOwnerAccess, verifyOwnerCsrf } from "./access.js";
import {
  bearerToken,
  canonicalJwkJson,
  constantTimeStringEqual,
  enrollmentProofPayload,
  hashOpaqueValue,
  issueAccessJwt,
  parseStoredJwk,
  publicJwkThumbprint,
  randomBase64Url,
  revokeProofPayload,
  rotationProofPayload,
  sessionProofPayload,
  sha256Base64Url,
  signingKeyConfigurationPresent,
  hmacSha256Base64Url,
  utf8,
  verifyAccessJwt,
  verifyP256Signature,
} from "./crypto.js";
import {
  ACCESS_TOKEN_TTL_SECONDS,
  assertExactFields,
  boundedString,
  canonicalApiOrigin,
  CHALLENGE_TTL_SECONDS,
  CONTRACT_MAJOR,
  CONTRACT_VERSION,
  DEFAULT_SCOPES,
  DEVICE_LEASE_SECONDS,
  ENROLLMENT_POLL_INTERVAL_SECONDS,
  ENROLLMENT_TTL_SECONDS,
  HttpError,
  isoTime,
  json,
  parseIsoTime,
  publicErrorResponse,
  readJson,
  SERVICE_NAME,
} from "./protocol.js";
import { requireAuthStore } from "./store.js";

const USER_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const CHALLENGE_PURPOSES = new Set(["access_token", "self_revoke", "key_rotation"]);
const MAX_CHAT_TEXT = 40_000;
const MAX_VOICE_TEXT = 9_000;

function defaultNow() {
  return Date.now();
}

function defaultRandomBytes(length) {
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return bytes;
}

export function createFullWorker({
  now = defaultNow,
  randomBytes = defaultRandomBytes,
  fetchImpl = (...args) => fetch(...args),
} = {}) {
  async function fetchHandler(request, env) {
    try {
      return await routeRequest(request, env, { now, randomBytes, fetchImpl });
    } catch (error) {
      return publicErrorResponse(error);
    }
  }

  async function scheduledHandler(_controller, env) {
    try {
      const store = requireAuthStore(env);
      const nowMs = now();
      await store.cleanup(isoTime(nowMs), isoTime(nowMs - 30 * 24 * 60 * 60 * 1000));
    } catch {
      // Scheduled cleanup is never an authorization dependency. Runtime reads
      // still fail closed against the primary D1 state if cleanup is unavailable.
    }
  }

  return { fetch: fetchHandler, scheduled: scheduledHandler };
}

const worker = createFullWorker();
export default worker;

async function routeRequest(request, env, deps) {
  const url = new URL(request.url);

  if (request.method === "GET" && url.pathname === "/health") {
    return json({
      ok: true,
      service: SERVICE_NAME,
      contract_major: CONTRACT_MAJOR,
      contract_version: CONTRACT_VERSION,
    });
  }

  if (request.method === "POST" && url.pathname === "/v1/enrollments") {
    return createEnrollment(request, env, deps);
  }
  const completeMatch = /^\/v1\/enrollments\/([A-Za-z0-9_-]{16,100})\/complete$/.exec(url.pathname);
  if (request.method === "POST" && completeMatch) {
    return completeEnrollment(request, env, deps, completeMatch[1]);
  }
  if (request.method === "POST" && url.pathname === "/v1/auth/challenges") {
    return createChallenge(request, env, deps);
  }
  if (request.method === "POST" && url.pathname === "/v1/auth/token") {
    return exchangeToken(request, env, deps);
  }

  if (url.pathname.startsWith("/owner/")) {
    if (request.method === "OPTIONS") return ownerPreflight(request, env);
    return withOwnerCors(await routeOwnerRequest(request, env, deps, url), request, env);
  }

  if (request.method === "GET" && url.pathname === "/v1/capabilities") {
    const principal = await authenticateAccess(request, env, deps, "capabilities:read");
    return capabilities(env, principal, deps.now());
  }
  if (request.method === "GET" && url.pathname === "/v1/devices/me") {
    const principal = await authenticateAccess(request, env, deps, "device:read");
    return deviceTruth(principal.device);
  }
  if (request.method === "POST" && url.pathname === "/v1/devices/me/revoke") {
    const principal = await authenticateAccess(request, env, deps, "device:write");
    return selfRevoke(request, env, deps, principal);
  }
  if (request.method === "POST" && url.pathname === "/v1/devices/me/key-rotations") {
    const principal = await authenticateAccess(
      request, env, deps, "device:write", { allowRotationRecovery: true },
    );
    return rotateDeviceKey(request, env, deps, principal);
  }
  if (request.method === "POST" && url.pathname === "/v1/chat") {
    const principal = await authenticateAccess(request, env, deps, "chat:write");
    return protectedChat(request, env, deps, principal);
  }
  if (request.method === "POST" && url.pathname === "/v1/search") {
    const principal = await authenticateAccess(request, env, deps, "search:write");
    return protectedSearch(request, env, deps, principal);
  }
  if (request.method === "POST" && url.pathname === "/v1/voice") {
    const principal = await authenticateAccess(request, env, deps, "voice:write");
    return protectedVoice(request, env, deps, principal);
  }

  throw new HttpError(404, "not_found");
}

function newId(prefix, bytes, randomBytes) {
  return `${prefix}_${randomBase64Url(bytes, randomBytes)}`;
}

function newUserCode(randomBytes) {
  const bytes = randomBytes(8);
  let output = "";
  for (const byte of bytes) output += USER_CODE_ALPHABET[byte % USER_CODE_ALPHABET.length];
  return output;
}

async function limitRequest(binding, key, route) {
  if (!binding || typeof binding.limit !== "function") {
    throw new HttpError(503, "rate_limiter_unavailable", { route });
  }
  let result;
  try {
    result = await binding.limit({ key });
  } catch {
    throw new HttpError(503, "rate_limiter_unavailable", { route });
  }
  if (!result?.success) {
    throw new HttpError(429, "rate_limited", { route }, { "Retry-After": "60" });
  }
}

async function enrollmentRateKey(request, env) {
  const secret = String(env.RATE_LIMIT_BUCKET_SECRET || "");
  if (secret.length < 32) throw new HttpError(503, "rate_limiter_unavailable");
  const networkSource = String(request.headers.get("CF-Connecting-IP") || "unknown");
  return `enroll:${await hmacSha256Base64Url(utf8(secret), networkSource)}`;
}

function verificationUrl(env) {
  const raw = String(env.ENROLLMENT_VERIFICATION_URL || "").trim();
  let parsed;
  try {
    parsed = new URL(raw);
  } catch {
    throw new HttpError(503, "owner_portal_not_configured");
  }
  if (parsed.protocol !== "https:") throw new HttpError(503, "owner_portal_not_configured");
  return parsed.toString();
}

async function createEnrollment(request, env, deps) {
  canonicalApiOrigin(env, request);
  const store = requireAuthStore(env);
  await limitRequest(
    env.ENROLLMENT_RATE_LIMITER,
    await enrollmentRateKey(request, env),
    "enrollment",
  );
  const body = assertExactFields(await readJson(request), [
    "platform", "display_name", "app_id", "app_version", "public_jwk",
  ]);
  const publicJwk = canonicalJwkJson(body.public_jwk);
  const keyThumbprint = await publicJwkThumbprint(body.public_jwk);
  const platform = boundedString(body.platform, 16, "platform").toLowerCase();
  if (!new Set(["android", "windows"]).has(platform)) {
    throw new HttpError(400, "platform_invalid");
  }
  const displayName = boundedString(body.display_name, 80, "display_name");
  const appId = boundedString(body.app_id, 160, "app_id");
  const appVersion = boundedString(body.app_version, 40, "app_version");
  const enrollmentId = newId("enr", 18, deps.randomBytes);
  const deviceCode = randomBase64Url(32, deps.randomBytes);
  const userCode = newUserCode(deps.randomBytes);
  const challenge = randomBase64Url(32, deps.randomBytes);
  const nowMs = deps.now();
  const createdAt = isoTime(nowMs);
  const expiresAt = isoTime(nowMs + ENROLLMENT_TTL_SECONDS * 1000);
  await store.insertEnrollment({
    enrollment_id: enrollmentId,
    device_code_hash: await hashOpaqueValue(env, "device_code", deviceCode),
    user_code_hash: await hashOpaqueValue(env, "user_code", userCode),
    challenge_hash: await hashOpaqueValue(env, "enrollment_challenge", challenge),
    public_jwk: publicJwk,
    key_thumbprint: keyThumbprint,
    platform,
    display_name: displayName,
    app_id: appId,
    app_version: appVersion,
    created_at: createdAt,
    expires_at: expiresAt,
  });
  return json({
    enrollment_id: enrollmentId,
    device_code: deviceCode,
    user_code: userCode,
    challenge,
    key_thumbprint: keyThumbprint,
    verification_uri: verificationUrl(env),
    expires_at: expiresAt,
    poll_interval_seconds: ENROLLMENT_POLL_INTERVAL_SECONDS,
  }, 201);
}

function enrollmentStateFailure(enrollment, nowMs) {
  if (!enrollment) throw new HttpError(401, "invalid_enrollment");
  if (parseIsoTime(enrollment.expires_at) <= nowMs || enrollment.state === "expired") {
    throw new HttpError(410, "enrollment_expired");
  }
  if (enrollment.state === "denied") throw new HttpError(403, "enrollment_denied");
  if (enrollment.state === "consumed") {
    throw new HttpError(409, "enrollment_already_consumed");
  }
  if (enrollment.state === "pending_owner") {
    throw new HttpError(202, "authorization_pending", { retry_after: 5 }, { "Retry-After": "5" });
  }
  if (enrollment.state !== "approved") throw new HttpError(409, "enrollment_state_invalid");
}

async function completeEnrollment(request, env, deps, enrollmentId) {
  const apiOrigin = canonicalApiOrigin(env, request);
  const store = requireAuthStore(env);
  const body = assertExactFields(
    await readJson(request), ["device_code", "challenge", "signature"],
  );
  const deviceCode = boundedString(body.device_code, 100, "device_code");
  const challenge = boundedString(body.challenge, 100, "challenge");
  const signature = boundedString(body.signature, 200, "signature");
  const enrollment = await store.getEnrollment(enrollmentId);
  const nowMs = deps.now();
  if (enrollment && parseIsoTime(enrollment.expires_at) <= nowMs
      && new Set(["pending_owner", "approved"]).has(enrollment.state)) {
    await store.markEnrollmentExpired(enrollmentId, isoTime(nowMs));
  }
  if (!enrollment) enrollmentStateFailure(enrollment, nowMs);
  const deviceCodeHash = await hashOpaqueValue(env, "device_code", deviceCode);
  const challengeHash = await hashOpaqueValue(env, "enrollment_challenge", challenge);
  if (!constantTimeStringEqual(deviceCodeHash, enrollment.device_code_hash)
      || !constantTimeStringEqual(challengeHash, enrollment.challenge_hash)) {
    throw new HttpError(401, "invalid_enrollment_proof");
  }
  if (new Set(["pending_owner", "approved"]).has(enrollment.state)) {
    const nowIso = isoTime(nowMs);
    const allowedBeforeIso = isoTime(
      nowMs - ENROLLMENT_POLL_INTERVAL_SECONDS * 1000,
    );
    const claimedPoll = await store.claimEnrollmentPoll(
      enrollmentId, nowIso, allowedBeforeIso,
    );
    if (claimedPoll.changes !== 1) {
      throw new HttpError(
        429,
        "rate_limited",
        { route: "enrollment_complete", retry_after: ENROLLMENT_POLL_INTERVAL_SECONDS },
        { "Retry-After": String(ENROLLMENT_POLL_INTERVAL_SECONDS) },
      );
    }
  }
  enrollmentStateFailure(enrollment, nowMs);
  const owner = await store.getOwnerById(enrollment.owner_id);
  if (!owner || owner.status !== "active") throw new HttpError(403, "owner_access_denied");
  const publicJwk = parseStoredJwk(enrollment.public_jwk);
  const payload = enrollmentProofPayload({
    enrollmentId,
    challenge,
    apiOrigin,
    keyThumbprint: enrollment.key_thumbprint,
  });
  if (!(await verifyP256Signature(publicJwk, payload, signature))) {
    throw new HttpError(401, "invalid_enrollment_proof");
  }
  const deviceId = newId("dev", 18, deps.randomBytes);
  const nowIso = isoTime(nowMs);
  const leaseExpiresAt = isoTime(nowMs + DEVICE_LEASE_SECONDS * 1000);
  let results;
  try {
    results = await store.consumeEnrollmentAndCreateDevice(
      enrollment,
      {
        device_id: deviceId,
        lease_expires_at: leaseExpiresAt,
        audit_event_id: newId("aud", 18, deps.randomBytes),
      },
      nowIso,
    );
  } catch (error) {
    const current = await store.getEnrollment(enrollmentId);
    if (current?.state === "consumed") {
      throw new HttpError(409, "enrollment_already_consumed");
    }
    throw error;
  }
  if (results[0]?.changes !== 1 || results[1]?.changes !== 1) {
    throw new HttpError(409, "enrollment_already_consumed");
  }
  return json({
    device_id: deviceId,
    state: "consumed",
  }, 201);
}

function assertActiveDevice(device, nowMs) {
  if (!device) throw new HttpError(401, "unknown_device");
  if (device.status !== "active") throw new HttpError(403, "device_revoked");
  if (parseIsoTime(device.lease_expires_at) <= nowMs) {
    throw new HttpError(403, "device_lease_expired");
  }
}

async function createChallenge(request, env, deps) {
  const apiOrigin = canonicalApiOrigin(env, request);
  const store = requireAuthStore(env);
  const body = assertExactFields(
    await readJson(request), ["device_id", "purpose"], ["device_id"],
  );
  const deviceId = boundedString(body.device_id, 100, "device_id");
  const purpose = boundedString(body.purpose || "access_token", 32, "purpose");
  if (!CHALLENGE_PURPOSES.has(purpose)) throw new HttpError(400, "purpose_invalid");
  await limitRequest(env.DEVICE_RATE_LIMITER, `${deviceId}:challenge:${purpose}`, "challenge");
  const device = await store.getDevice(deviceId);
  const nowMs = deps.now();
  assertActiveDevice(device, nowMs);
  const challengeId = newId("chl", 18, deps.randomBytes);
  const nonce = randomBase64Url(32, deps.randomBytes);
  const expiresAt = isoTime(nowMs + CHALLENGE_TTL_SECONDS * 1000);
  await store.insertChallenge({
    challenge_id: challengeId,
    device_id: deviceId,
    purpose,
    nonce_hash: await hashOpaqueValue(env, "auth_challenge_nonce", nonce),
    created_at: isoTime(nowMs),
    expires_at: expiresAt,
  });
  return json({
    challenge_id: challengeId,
    device_id: deviceId,
    purpose,
    nonce,
    api_origin: apiOrigin,
    key_version: device.key_version,
    expires_at: expiresAt,
  }, 201);
}

async function requireChallengeProof({
  env,
  store,
  device,
  challengeId,
  nonce,
  purpose,
  payload,
  signature,
  nowMs,
  publicJwk = undefined,
  allowConsumed = false,
}) {
  const challenge = await store.getChallenge(challengeId);
  if (!challenge || challenge.device_id !== device.device_id || challenge.purpose !== purpose) {
    throw new HttpError(401, "invalid_device_proof");
  }
  if (parseIsoTime(challenge.expires_at) <= nowMs
      && !(allowConsumed && challenge.consumed_at)) {
    throw new HttpError(410, "challenge_expired");
  }
  if (challenge.consumed_at && !allowConsumed) {
    throw new HttpError(409, "challenge_already_consumed");
  }
  const nonceHash = await hashOpaqueValue(env, "auth_challenge_nonce", nonce);
  if (!constantTimeStringEqual(nonceHash, challenge.nonce_hash)) {
    throw new HttpError(401, "invalid_device_proof");
  }
  const key = publicJwk || parseStoredJwk(device.public_jwk);
  if (!(await verifyP256Signature(key, payload, signature))) {
    throw new HttpError(401, "invalid_device_proof");
  }
  return challenge;
}

async function exchangeToken(request, env, deps) {
  const apiOrigin = canonicalApiOrigin(env, request);
  signingKeyConfigurationPresent(env);
  const store = requireAuthStore(env);
  const body = assertExactFields(await readJson(request), [
    "device_id", "challenge_id", "nonce", "signature", "key_version",
  ]);
  const deviceId = boundedString(body.device_id, 100, "device_id");
  const challengeId = boundedString(body.challenge_id, 100, "challenge_id");
  const nonce = boundedString(body.nonce, 100, "nonce");
  const signature = boundedString(body.signature, 200, "signature");
  if (!Number.isInteger(body.key_version) || body.key_version < 1) {
    throw new HttpError(400, "key_version_invalid");
  }
  await limitRequest(env.DEVICE_RATE_LIMITER, `${deviceId}:token`, "token");
  const device = await store.getDevice(deviceId);
  const nowMs = deps.now();
  assertActiveDevice(device, nowMs);
  if (device.key_version !== body.key_version) throw new HttpError(403, "stale_key_version");
  const payload = sessionProofPayload({
    deviceId,
    challengeId,
    nonce,
    apiOrigin,
    keyVersion: body.key_version,
  });
  await requireChallengeProof({
    env,
    store,
    device,
    challengeId,
    nonce,
    purpose: "access_token",
    payload,
    signature,
    nowMs,
  });
  const nowIso = isoTime(nowMs);
  const consumed = await store.consumeChallenge(challengeId, deviceId, "access_token", nowIso);
  if (consumed.changes !== 1) {
    throw new HttpError(409, "challenge_already_consumed");
  }
  const leaseExpiresAt = isoTime(nowMs + DEVICE_LEASE_SECONDS * 1000);
  const renewed = await store.renewLease(device, nowIso, leaseExpiresAt);
  if (renewed.changes !== 1) {
    const current = await store.getDevice(deviceId);
    assertActiveDevice(current, nowMs);
    throw new HttpError(409, "device_state_changed");
  }
  const issued = await issueAccessJwt(env, {
    deviceId,
    ownerId: device.owner_id,
    scopes: DEFAULT_SCOPES,
    keyVersion: device.key_version,
    authEpoch: device.auth_epoch,
  }, nowMs, deps.randomBytes);
  return json({
    access_token: issued.token,
    token_type: "Bearer",
    expires_in: ACCESS_TOKEN_TTL_SECONDS,
    device_id: deviceId,
    key_version: device.key_version,
    auth_epoch: device.auth_epoch,
    lease_expires_at: leaseExpiresAt,
  });
}

async function authenticateAccess(
  request, env, deps, requiredScope, { allowRotationRecovery = false } = {},
) {
  canonicalApiOrigin(env, request);
  signingKeyConfigurationPresent(env);
  const store = requireAuthStore(env);
  const claims = await verifyAccessJwt(env, bearerToken(request), deps.now());
  const device = await store.getDevice(claims.device_id);
  assertActiveDevice(device, deps.now());
  if (device.owner_id !== claims.owner_id) throw new HttpError(403, "stale_device_state");
  let staleForRotation = false;
  if (device.key_version !== claims.key_version || device.auth_epoch !== claims.auth_epoch) {
    staleForRotation = Boolean(
      allowRotationRecovery
      && device.key_version === claims.key_version + 1
      && device.auth_epoch === claims.auth_epoch + 1
      && device.last_rotation_id,
    );
    if (!staleForRotation) {
      if (device.key_version !== claims.key_version) {
        throw new HttpError(403, "stale_key_version");
      }
      throw new HttpError(403, "stale_auth_epoch");
    }
  }
  if (!claims.scope.includes(requiredScope)) throw new HttpError(403, "scope_denied");
  return { claims, device, store, staleForRotation };
}

function runtimeConfiguration(env) {
  const serviceId = String(env.SARAH_FULL_SERVICE_ID || "").trim();
  const provider = String(env.SARAH_FULL_MODEL_PROVIDER || "").trim().toLowerCase();
  const model = String(env.SARAH_FULL_MODEL_ID || "").trim();
  if (!serviceId || !/^[A-Za-z0-9._-]{3,100}$/.test(serviceId)
      || !new Set(["workers-ai", "openai"]).has(provider) || !model) {
    throw new HttpError(503, "runtime_not_configured");
  }
  return { serviceId, provider, model };
}

function nonBlank(value) {
  return typeof value === "string" && value.trim().length > 0;
}

function rateLimiterReady(binding) {
  return Boolean(binding && typeof binding.limit === "function");
}

function modelProviderReady(env, runtime) {
  return runtime.provider === "workers-ai"
    ? Boolean(env.AI && typeof env.AI.run === "function")
    : nonBlank(env.OPENAI_API_KEY);
}

function searchProviderReady(env) {
  return nonBlank(env.TAVILY_API_KEY);
}

function voiceProviderConfiguration(env) {
  const apiKey = String(env.ELEVENLABS_API_KEY || "").trim();
  const voiceId = String(env.SARAH_ELEVENLABS_VOICE_ID || "").trim();
  const modelId = String(env.SARAH_ELEVENLABS_MODEL_ID || "eleven_flash_v2_5").trim();
  if (!apiKey || !/^[A-Za-z0-9_-]{10,100}$/.test(voiceId) || !modelId) return null;
  return { apiKey, voiceId, modelId };
}

function capabilities(env, principal, nowMs) {
  const runtime = runtimeConfiguration(env);
  const modelReady = modelProviderReady(env, runtime);
  return json({
    ok: modelReady,
    service_id: runtime.serviceId,
    contract_major: CONTRACT_MAJOR,
    contract_version: CONTRACT_VERSION,
    device_id: principal.device.device_id,
    device_state: principal.device.status,
    lease_expires_at: principal.device.lease_expires_at,
    key_version: principal.device.key_version,
    auth_epoch: principal.device.auth_epoch,
    provider: runtime.provider,
    model: runtime.model,
    model_ready: modelReady,
    current_source_ready: searchProviderReady(env),
    voice_ready: Boolean(voiceProviderConfiguration(env)),
    rate_limit_ready: rateLimiterReady(env.DEVICE_RATE_LIMITER),
    server_time: isoTime(nowMs),
  });
}

function deviceTruth(device) {
  return json({
    device_id: device.device_id,
    platform: device.platform,
    display_name: device.display_name,
    app_id: device.app_id,
    status: device.status,
    key_version: device.key_version,
    auth_epoch: device.auth_epoch,
    lease_expires_at: device.lease_expires_at,
    created_at: device.created_at,
    last_seen_at: device.last_seen_at,
  });
}

async function selfRevoke(request, env, deps, principal) {
  const apiOrigin = canonicalApiOrigin(env, request);
  const body = assertExactFields(
    await readJson(request),
    ["challenge_id", "nonce", "signature", "key_version", "reason"],
    ["challenge_id", "nonce", "signature", "key_version"],
  );
  const challengeId = boundedString(body.challenge_id, 100, "challenge_id");
  const nonce = boundedString(body.nonce, 100, "nonce");
  const signature = boundedString(body.signature, 200, "signature");
  const reason = boundedString(body.reason, 200, "reason", { required: false })
    || "self_revoked";
  if (body.key_version !== principal.device.key_version) {
    throw new HttpError(403, "stale_key_version");
  }
  const reasonHash = await sha256Base64Url(reason);
  const payload = revokeProofPayload({
    deviceId: principal.device.device_id,
    challengeId,
    nonce,
    apiOrigin,
    keyVersion: body.key_version,
    reasonHash,
  });
  await requireChallengeProof({
    env,
    store: principal.store,
    device: principal.device,
    challengeId,
    nonce,
    purpose: "self_revoke",
    payload,
    signature,
    nowMs: deps.now(),
  });
  const nowIso = isoTime(deps.now());
  const consumed = await principal.store.consumeChallenge(
    challengeId, principal.device.device_id, "self_revoke", nowIso,
  );
  if (consumed.changes !== 1) {
    throw new HttpError(409, "challenge_already_consumed");
  }
  const revoked = await principal.store.revokeDevice({
    deviceId: principal.device.device_id,
    ownerId: principal.device.owner_id,
    expectedEpoch: principal.device.auth_epoch,
    nowIso,
    reason,
  });
  if (revoked.changes !== 1) throw new HttpError(409, "device_state_changed");
  await principal.store.insertAudit({
    eventId: newId("aud", 18, deps.randomBytes),
    ownerId: principal.device.owner_id,
    deviceId: principal.device.device_id,
    eventType: "device_self_revoked",
    nowIso,
    metadata: { reason_hash: reasonHash },
  });
  return json({ status: "revoked", device_id: principal.device.device_id });
}

async function rotateDeviceKey(request, env, deps, principal) {
  const apiOrigin = canonicalApiOrigin(env, request);
  const body = assertExactFields(await readJson(request), [
    "rotation_id",
    "challenge_id",
    "nonce",
    "current_key_version",
    "old_thumbprint",
    "new_public_jwk",
    "old_signature",
    "new_signature",
  ]);
  const rotationId = boundedString(body.rotation_id, 100, "rotation_id");
  if (!/^[A-Za-z0-9_-]{16,100}$/.test(rotationId)) {
    throw new HttpError(400, "rotation_id_invalid");
  }
  const challengeId = boundedString(body.challenge_id, 100, "challenge_id");
  const nonce = boundedString(body.nonce, 100, "nonce");
  const oldSignature = boundedString(body.old_signature, 200, "old_signature");
  const newSignature = boundedString(body.new_signature, 200, "new_signature");
  const newPublicJwk = canonicalJwkJson(body.new_public_jwk);
  const newThumbprint = await publicJwkThumbprint(body.new_public_jwk);
  const oldThumbprint = boundedString(body.old_thumbprint, 100, "old_thumbprint");
  if (!Number.isInteger(body.current_key_version) || body.current_key_version < 1) {
    throw new HttpError(400, "key_version_invalid");
  }
  const payload = rotationProofPayload({
    deviceId: principal.device.device_id,
    rotationId,
    challengeId,
    nonce,
    apiOrigin,
    currentKeyVersion: body.current_key_version,
    oldThumbprint,
    newThumbprint,
  });

  if (principal.staleForRotation) {
    if (principal.device.last_rotation_id !== rotationId
        || principal.device.key_thumbprint !== newThumbprint
        || principal.claims.key_version !== body.current_key_version) {
      throw new HttpError(403, "stale_device_state");
    }
    await requireChallengeProof({
      env,
      store: principal.store,
      device: principal.device,
      challengeId,
      nonce,
      purpose: "key_rotation",
      payload,
      signature: newSignature,
      nowMs: deps.now(),
      publicJwk: parseStoredJwk(principal.device.public_jwk),
      allowConsumed: true,
    });
    return json({
      status: "rotated",
      replayed: true,
      device_id: principal.device.device_id,
      key_version: principal.device.key_version,
      auth_epoch: principal.device.auth_epoch,
      key_thumbprint: principal.device.key_thumbprint,
    });
  }

  if (principal.device.key_version !== body.current_key_version
      || principal.device.key_thumbprint !== oldThumbprint) {
    throw new HttpError(403, "stale_key_version");
  }
  const oldPublicJwk = parseStoredJwk(principal.device.public_jwk);
  await requireChallengeProof({
    env,
    store: principal.store,
    device: principal.device,
    challengeId,
    nonce,
    purpose: "key_rotation",
    payload,
    signature: oldSignature,
    nowMs: deps.now(),
    publicJwk: oldPublicJwk,
  });
  if (!(await verifyP256Signature(body.new_public_jwk, payload, newSignature))) {
    throw new HttpError(401, "invalid_device_proof");
  }
  const nowIso = isoTime(deps.now());
  const consumed = await principal.store.consumeChallenge(
    challengeId, principal.device.device_id, "key_rotation", nowIso,
  );
  if (consumed.changes !== 1) {
    throw new HttpError(409, "challenge_already_consumed");
  }
  const rotated = await principal.store.rotateDeviceKey({
    deviceId: principal.device.device_id,
    ownerId: principal.device.owner_id,
    expectedKeyVersion: principal.device.key_version,
    expectedAuthEpoch: principal.device.auth_epoch,
    newPublicJwk,
    newThumbprint,
    rotationId,
    oldThumbprint,
    nowIso,
  });
  if (rotated.changes !== 1) {
    const current = await principal.store.getDevice(principal.device.device_id);
    if (current?.last_rotation_id === rotationId && current.key_thumbprint === newThumbprint) {
      return json({
        status: "rotated",
        replayed: true,
        device_id: current.device_id,
        key_version: current.key_version,
        auth_epoch: current.auth_epoch,
        key_thumbprint: current.key_thumbprint,
      });
    }
    throw new HttpError(409, "device_state_changed");
  }
  await principal.store.insertAudit({
    eventId: newId("aud", 18, deps.randomBytes),
    ownerId: principal.device.owner_id,
    deviceId: principal.device.device_id,
    eventType: "device_key_rotated",
    nowIso,
    metadata: { rotation_id: rotationId, key_version: principal.device.key_version + 1 },
  });
  return json({
    status: "rotated",
    replayed: false,
    device_id: principal.device.device_id,
    key_version: principal.device.key_version + 1,
    auth_epoch: principal.device.auth_epoch + 1,
    key_thumbprint: newThumbprint,
  });
}

async function routeOwnerRequest(request, env, deps, url) {
  canonicalApiOrigin(env, request);
  const store = requireAuthStore(env);
  const owner = await verifyOwnerAccess(request, env, store, deps.now(), deps.fetchImpl);

  if (request.method === "GET" && url.pathname === "/owner/devices") {
    const csrf = issueOwnerCsrf(deps.randomBytes);
    return json({
      devices: await store.listDevices(owner.ownerId),
      csrf_token: csrf.token,
    }, 200, { "Set-Cookie": csrf.cookie });
  }
  if (request.method === "GET" && url.pathname === "/owner/enrollments") {
    const csrf = issueOwnerCsrf(deps.randomBytes);
    return json({
      enrollments: await store.listPendingEnrollments(owner.ownerId, isoTime(deps.now())),
      csrf_token: csrf.token,
    }, 200, { "Set-Cookie": csrf.cookie });
  }

  verifyOwnerCsrf(request, env);
  if (request.method === "POST" && url.pathname === "/owner/enrollments/lookup") {
    const body = await readJson(request);
    const userCode = boundedString(body.user_code, 16, "user_code").toUpperCase();
    if (!/^[A-HJ-NP-Z2-9]{8}$/.test(userCode)) {
      throw new HttpError(400, "user_code_invalid");
    }
    const enrollment = await store.getEnrollmentByUserCodeHash(
      await sha256Base64Url(userCode),
    );
    if (!enrollment || (enrollment.owner_id && enrollment.owner_id !== owner.ownerId)) {
      throw new HttpError(404, "enrollment_not_found");
    }
    if (parseIsoTime(enrollment.expires_at) <= deps.now()) {
      await store.markEnrollmentExpired(enrollment.enrollment_id, isoTime(deps.now()));
      throw new HttpError(410, "enrollment_expired");
    }
    if (enrollment.state !== "pending_owner") {
      throw new HttpError(409, "enrollment_state_conflict");
    }
    return json({ enrollment });
  }
  const enrollmentMatch = /^\/owner\/enrollments\/([A-Za-z0-9_-]{16,100})\/(approve|deny)$/.exec(
    url.pathname,
  );
  if (request.method === "POST" && enrollmentMatch) {
    const enrollment = await store.getEnrollment(enrollmentMatch[1]);
    const nowMs = deps.now();
    if (!enrollment) throw new HttpError(404, "enrollment_not_found");
    if (parseIsoTime(enrollment.expires_at) <= nowMs) {
      await store.markEnrollmentExpired(enrollment.enrollment_id, isoTime(nowMs));
      throw new HttpError(410, "enrollment_expired");
    }
    const action = enrollmentMatch[2];
    const result = action === "approve"
      ? await store.approveEnrollment(enrollment.enrollment_id, owner.ownerId, isoTime(nowMs))
      : await store.denyEnrollment(enrollment.enrollment_id, owner.ownerId, isoTime(nowMs));
    if (result.changes !== 1) throw new HttpError(409, "enrollment_state_conflict");
    await store.insertAudit({
      eventId: newId("aud", 18, deps.randomBytes),
      ownerId: owner.ownerId,
      eventType: action === "approve" ? "enrollment_approved" : "enrollment_denied",
      nowIso: isoTime(nowMs),
      metadata: { enrollment_id: enrollment.enrollment_id },
    });
    return json({
      enrollment_id: enrollment.enrollment_id,
      state: action === "approve" ? "approved" : "denied",
    });
  }

  const revokeMatch = /^\/owner\/devices\/([A-Za-z0-9_-]{16,100})\/revoke$/.exec(url.pathname);
  if (request.method === "POST" && revokeMatch) {
    const device = await store.getDevice(revokeMatch[1]);
    if (!device || device.owner_id !== owner.ownerId) throw new HttpError(404, "device_not_found");
    if (device.status !== "active") throw new HttpError(409, "device_already_revoked");
    const body = await readJson(request);
    const reason = boundedString(body.reason, 200, "reason", { required: false })
      || "owner_revoked";
    const nowIso = isoTime(deps.now());
    const result = await store.revokeDevice({
      deviceId: device.device_id,
      ownerId: owner.ownerId,
      expectedEpoch: device.auth_epoch,
      nowIso,
      reason,
    });
    if (result.changes !== 1) throw new HttpError(409, "stale_device_state");
    await store.insertAudit({
      eventId: newId("aud", 18, deps.randomBytes),
      ownerId: owner.ownerId,
      deviceId: device.device_id,
      eventType: "device_owner_revoked",
      nowIso,
      metadata: { reason_hash: await sha256Base64Url(reason) },
    });
    return json({ status: "revoked", device_id: device.device_id });
  }
  throw new HttpError(404, "not_found");
}

function configuredOwnerOrigin(env) {
  const expected = String(env.OWNER_PORTAL_ORIGIN || "").trim().replace(/\/$/, "");
  let parsed;
  try {
    parsed = new URL(expected);
  } catch {
    throw new HttpError(503, "owner_portal_not_configured");
  }
  if (parsed.protocol !== "https:" || parsed.origin !== expected || parsed.pathname !== "/") {
    throw new HttpError(503, "owner_portal_not_configured");
  }
  return expected;
}

function ownerCorsHeaders(request, env) {
  const origin = configuredOwnerOrigin(env);
  if (request.headers.get("Origin") && request.headers.get("Origin") !== origin) {
    throw new HttpError(403, "owner_origin_rejected");
  }
  return {
    "Access-Control-Allow-Origin": origin,
    "Access-Control-Allow-Credentials": "true",
    Vary: "Origin",
  };
}

function withOwnerCors(response, request, env) {
  const headers = new Headers(response.headers);
  for (const [key, value] of Object.entries(ownerCorsHeaders(request, env))) {
    headers.set(key, value);
  }
  return new Response(response.body, { status: response.status, headers });
}

function ownerPreflight(request, env) {
  canonicalApiOrigin(env, request);
  const requestedMethod = String(request.headers.get("Access-Control-Request-Method") || "");
  if (!new Set(["GET", "POST"]).has(requestedMethod)) {
    throw new HttpError(405, "method_not_allowed");
  }
  const headers = new Headers(ownerCorsHeaders(request, env));
  headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  headers.set(
    "Access-Control-Allow-Headers",
    "Content-Type, X-Sarah-Owner-CSRF",
  );
  headers.set("Access-Control-Max-Age", "600");
  headers.set("Cache-Control", "no-store");
  return new Response(null, { status: 204, headers });
}

async function deviceRouteLimit(env, principal, route) {
  await limitRequest(
    env.DEVICE_RATE_LIMITER,
    `${principal.device.device_id}:${route}`,
    route,
  );
}

function normalizedHistory(value) {
  if (!Array.isArray(value)) return [];
  return value.slice(-24).flatMap((entry) => {
    if (!entry || typeof entry !== "object") return [];
    if (!new Set(["user", "assistant"]).has(entry.role)) return [];
    if (typeof entry.content !== "string" || !entry.content.trim()) return [];
    return [{ role: entry.role, content: entry.content.trim().slice(0, MAX_CHAT_TEXT) }];
  });
}

async function protectedChat(request, env, deps, principal) {
  await deviceRouteLimit(env, principal, "chat");
  const runtime = runtimeConfiguration(env);
  const body = await readJson(request, 512 * 1024);
  const message = boundedString(body.message, MAX_CHAT_TEXT, "message");
  const system = boundedString(body.system_prompt, MAX_CHAT_TEXT, "system_prompt", { required: false });
  const messages = [
    ...(system ? [{ role: "system", content: system }] : []),
    ...normalizedHistory(body.history),
    { role: "user", content: message },
  ];
  if (runtime.provider === "workers-ai") {
    if (!modelProviderReady(env, runtime)) throw new HttpError(503, "model_unavailable");
    let result;
    try {
      result = await env.AI.run(runtime.model, { messages, max_tokens: 1200 });
    } catch {
      throw new HttpError(502, "model_upstream_failed");
    }
    const reply = String(result?.response || result?.result?.response || "").trim();
    if (!reply) throw new HttpError(502, "model_upstream_failed");
    return json({ reply, provider: runtime.provider, model: runtime.model });
  }
  if (!modelProviderReady(env, runtime)) throw new HttpError(503, "model_unavailable");
  let upstream;
  try {
    upstream = await deps.fetchImpl("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${String(env.OPENAI_API_KEY).trim()}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ model: runtime.model, input: messages, max_output_tokens: 1200 }),
    });
  } catch {
    throw new HttpError(502, "model_upstream_failed");
  }
  if (!upstream.ok) throw new HttpError(502, "model_upstream_failed");
  const result = await upstream.json();
  const reply = String(result.output_text || "").trim();
  if (!reply) throw new HttpError(502, "model_upstream_failed");
  return json({ reply, provider: runtime.provider, model: runtime.model });
}

async function protectedSearch(request, env, deps, principal) {
  await deviceRouteLimit(env, principal, "search");
  const body = await readJson(request);
  const query = boundedString(body.query, 2_000, "query");
  if (!searchProviderReady(env)) throw new HttpError(503, "search_unavailable");
  let upstream;
  try {
    upstream = await deps.fetchImpl("https://api.tavily.com/search", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${String(env.TAVILY_API_KEY).trim()}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ query, max_results: 8, search_depth: "basic" }),
    });
  } catch {
    throw new HttpError(502, "search_upstream_failed");
  }
  if (!upstream.ok) throw new HttpError(502, "search_upstream_failed");
  const result = await upstream.json();
  return json({
    query,
    answer: typeof result.answer === "string" ? result.answer : "",
    results: Array.isArray(result.results) ? result.results.slice(0, 8) : [],
  });
}

async function protectedVoice(request, env, deps, principal) {
  await deviceRouteLimit(env, principal, "voice");
  const body = await readJson(request);
  const text = boundedString(body.text, MAX_VOICE_TEXT, "text");
  const voice = voiceProviderConfiguration(env);
  if (!voice) {
    throw new HttpError(503, "voice_unavailable");
  }
  const { apiKey, voiceId, modelId } = voice;
  let upstream;
  try {
    upstream = await deps.fetchImpl(
      `https://api.elevenlabs.io/v1/text-to-speech/${encodeURIComponent(voiceId)}/stream?output_format=mp3_44100_128`,
      {
        method: "POST",
        headers: {
          "xi-api-key": apiKey,
          Accept: "audio/mpeg",
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ text, model_id: modelId }),
      },
    );
  } catch {
    throw new HttpError(502, "voice_upstream_failed");
  }
  if (!upstream.ok || !upstream.body) throw new HttpError(502, "voice_upstream_failed");
  return new Response(upstream.body, {
    status: 200,
    headers: {
      "Content-Type": upstream.headers.get("Content-Type") || "audio/mpeg",
      "Cache-Control": "no-store",
      "X-Sarah-Voice-Provider": "elevenlabs",
      "X-Sarah-Voice-Model": modelId,
    },
  });
}
