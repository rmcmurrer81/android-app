export const ENROLLMENT_TTL_MS = 10 * 60 * 1000;
export const CHALLENGE_TTL_MS = 2 * 60 * 1000;
export const ACCESS_TOKEN_TTL_SECONDS = 10 * 60;
export const DEVICE_LEASE_MS = 90 * 24 * 60 * 60 * 1000;

const ENROLLMENT_STATES = new Set([
  "pending_owner", "approved", "consumed", "denied", "expired",
]);

export class AuthStateError extends Error {
  constructor(code, httpStatus) {
    super(code);
    this.name = "AuthStateError";
    this.code = code;
    this.httpStatus = httpStatus;
  }
}
function instantMs(value, label) {
  const result = value instanceof Date ? value.getTime() : Date.parse(String(value));
  if (!Number.isFinite(result)) throw new TypeError(`${label} must be a valid instant`);
  return result;
}

export function expiryUtc(now, ttlMs) {
  const base = instantMs(now, "now");
  if (!Number.isSafeInteger(ttlMs) || ttlMs < 1) throw new TypeError("ttlMs must be positive");
  return new Date(base + ttlMs).toISOString();
}

export function effectiveEnrollmentState(enrollment, now) {
  const state = String(enrollment.state || "");
  if (!ENROLLMENT_STATES.has(state)) throw new TypeError("unknown enrollment state");
  if ((state === "pending_owner" || state === "approved")
      && instantMs(now, "now") >= instantMs(enrollment.expires_at, "expires_at")) {
    return "expired";
  }
  return state;
}

export function transitionEnrollment(enrollment, action, now) {
  const effective = effectiveEnrollmentState(enrollment, now);
  if (effective === "expired") {
    if (action === "expire" && enrollment.state !== "expired") {
      return { ...enrollment, state: "expired" };
    }
    throw new AuthStateError("enrollment_expired", 410);
  }
  if (effective === "denied") throw new AuthStateError("enrollment_denied", 403);
  if (effective === "consumed") throw new AuthStateError("enrollment_already_consumed", 409);

  if (action === "approve" && effective === "pending_owner") {
    return { ...enrollment, state: "approved", approved_at: new Date(instantMs(now, "now")).toISOString() };
  }
  if (action === "deny" && effective === "pending_owner") {
    return { ...enrollment, state: "denied" };
  }
  if (action === "consume" && effective === "approved") {
    return { ...enrollment, state: "consumed", consumed_at: new Date(instantMs(now, "now")).toISOString() };
  }
  if (action === "expire") {
    throw new AuthStateError("enrollment_not_expired", 409);
  }
  throw new AuthStateError("enrollment_state_conflict", 409);
}

export function assertChallengeUsable(challenge, { now, deviceId, purpose }) {
  if (String(challenge.device_id) !== String(deviceId)
      || String(challenge.purpose) !== String(purpose)) {
    throw new AuthStateError("unauthorized", 401);
  }
  if (challenge.consumed_at) {
    throw new AuthStateError("challenge_already_consumed", 409);
  }
  if (instantMs(now, "now") >= instantMs(challenge.expires_at, "expires_at")) {
    throw new AuthStateError("challenge_expired", 410);
  }
  return true;
}

export function consumeChallenge(challenge, options) {
  assertChallengeUsable(challenge, options);
  return {
    ...challenge,
    consumed_at: new Date(instantMs(options.now, "now")).toISOString(),
  };
}

export function assertDeviceAuthorizesClaims(device, claims, now) {
  if (String(device.device_id) !== String(claims.device_id)) {
    throw new AuthStateError("unauthorized", 401);
  }
  if (device.status !== "active") {
    throw new AuthStateError("device_revoked", 403);
  }
  if (instantMs(now, "now") >= instantMs(device.lease_expires_at, "lease_expires_at")) {
    throw new AuthStateError("device_lease_expired", 403);
  }
  if (Number(claims.key_version) !== Number(device.key_version)) {
    throw new AuthStateError("stale_key_version", 403);
  }
  if (Number(claims.auth_epoch) !== Number(device.auth_epoch)) {
    throw new AuthStateError("stale_auth_epoch", 403);
  }
  return true;
}

export function sessionTimes(now) {
  const nowMs = instantMs(now, "now");
  const issuedAt = Math.floor(nowMs / 1000);
  return {
    iat: issuedAt,
    nbf: issuedAt,
    exp: issuedAt + ACCESS_TOKEN_TTL_SECONDS,
    expires_in: ACCESS_TOKEN_TTL_SECONDS,
    lease_expires_at: new Date(nowMs + DEVICE_LEASE_MS).toISOString(),
  };
}

export function applyKeyRotation(device, rotation) {
  if (device.status !== "active") throw new AuthStateError("device_revoked", 403);
  if (device.last_rotation_id === rotation.rotation_id) {
    return { device, idempotentReplay: true };
  }
  if (Number(rotation.current_key_version) !== Number(device.key_version)
      || String(rotation.old_thumbprint) !== String(device.key_thumbprint)) {
    throw new AuthStateError("stale_rotation", 409);
  }
  if (!rotation.rotation_id || !rotation.new_public_jwk || !rotation.new_thumbprint
      || rotation.new_thumbprint === device.key_thumbprint) {
    throw new AuthStateError("invalid_rotation", 401);
  }
  return {
    device: {
      ...device,
      public_jwk: rotation.new_public_jwk,
      key_thumbprint: rotation.new_thumbprint,
      key_version: Number(device.key_version) + 1,
      auth_epoch: Number(device.auth_epoch) + 1,
      last_rotation_id: rotation.rotation_id,
    },
    idempotentReplay: false,
  };
}
