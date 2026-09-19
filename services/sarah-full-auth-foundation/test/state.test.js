import assert from "node:assert/strict";
import test from "node:test";

import {
  ACCESS_TOKEN_TTL_SECONDS,
  AuthStateError,
  applyKeyRotation,
  assertChallengeUsable,
  assertDeviceAuthorizesClaims,
  consumeChallenge,
  effectiveEnrollmentState,
  sessionTimes,
  transitionEnrollment,
} from "../src/state.js";

const NOW = "2026-08-09T12:00:00.000Z";

test("owner approval then a single consume is the only successful enrollment path", () => {
  const pending = { state: "pending_owner", expires_at: "2026-08-09T12:10:00.000Z" };
  const approved = transitionEnrollment(pending, "approve", NOW);
  assert.equal(approved.state, "approved");
  const consumed = transitionEnrollment(approved, "consume", "2026-08-09T12:01:00.000Z");
  assert.equal(consumed.state, "consumed");
  assert.throws(
    () => transitionEnrollment(consumed, "consume", "2026-08-09T12:01:01.000Z"),
    (error) => error instanceof AuthStateError && error.code === "enrollment_already_consumed" && error.httpStatus === 409,
  );
});
test("denied and expired enrollments fail with distinct truth", () => {
  const pending = { state: "pending_owner", expires_at: "2026-08-09T12:10:00.000Z" };
  const denied = transitionEnrollment(pending, "deny", NOW);
  assert.throws(() => transitionEnrollment(denied, "consume", NOW), /enrollment_denied/u);
  assert.equal(effectiveEnrollmentState(pending, "2026-08-09T12:10:00.000Z"), "expired");
  assert.throws(
    () => transitionEnrollment(pending, "consume", "2026-08-09T12:10:00.000Z"),
    (error) => error.code === "enrollment_expired" && error.httpStatus === 410,
  );
});

test("challenge binding, expiry, and replay gates are fail closed", () => {
  const challenge = {
    device_id: "dev_one",
    purpose: "access_token",
    expires_at: "2026-08-09T12:02:00.000Z",
    consumed_at: null,
  };
  assert.equal(assertChallengeUsable(challenge, { now: NOW, deviceId: "dev_one", purpose: "access_token" }), true);
  const consumed = consumeChallenge(challenge, { now: NOW, deviceId: "dev_one", purpose: "access_token" });
  assert.throws(
    () => assertChallengeUsable(consumed, { now: NOW, deviceId: "dev_one", purpose: "access_token" }),
    (error) => error.code === "challenge_already_consumed" && error.httpStatus === 409,
  );
  assert.throws(
    () => assertChallengeUsable(challenge, { now: "2026-08-09T12:02:00.000Z", deviceId: "dev_one", purpose: "access_token" }),
    (error) => error.code === "challenge_expired" && error.httpStatus === 410,
  );
  assert.throws(
    () => assertChallengeUsable(challenge, { now: NOW, deviceId: "dev_two", purpose: "access_token" }),
    (error) => error.code === "unauthorized" && error.httpStatus === 401,
  );
});

test("device middleware state rejects revoke, lease expiry, stale key, and stale epoch", () => {
  const device = {
    device_id: "dev_one", status: "active", key_version: 3, auth_epoch: 4,
    lease_expires_at: "2026-11-07T12:00:00.000Z",
  };
  const claims = { device_id: "dev_one", key_version: 3, auth_epoch: 4 };
  assert.equal(assertDeviceAuthorizesClaims(device, claims, NOW), true);
  assert.throws(() => assertDeviceAuthorizesClaims({ ...device, status: "revoked" }, claims, NOW), /device_revoked/u);
  assert.throws(() => assertDeviceAuthorizesClaims({ ...device, lease_expires_at: NOW }, claims, NOW), /device_lease_expired/u);
  assert.throws(() => assertDeviceAuthorizesClaims(device, { ...claims, key_version: 2 }, NOW), /stale_key_version/u);
  assert.throws(() => assertDeviceAuthorizesClaims(device, { ...claims, auth_epoch: 3 }, NOW), /stale_auth_epoch/u);
});

test("session timestamps produce ten-minute memory-token and rolling 90-day lease", () => {
  const times = sessionTimes(NOW);
  assert.equal(times.exp - times.iat, ACCESS_TOKEN_TTL_SECONDS);
  assert.equal(times.expires_in, 600);
  assert.equal(times.lease_expires_at, "2026-11-07T12:00:00.000Z");
});

test("key rotation is epoch-incrementing and idempotent only for the exact rotation id", () => {
  const device = {
    status: "active", key_version: 1, auth_epoch: 2,
    key_thumbprint: "old", last_rotation_id: null, public_jwk: { old: true },
  };
  const rotation = {
    rotation_id: "rot_one", current_key_version: 1, old_thumbprint: "old",
    new_thumbprint: "new", new_public_jwk: { new: true },
  };
  const applied = applyKeyRotation(device, rotation);
  assert.equal(applied.device.key_version, 2);
  assert.equal(applied.device.auth_epoch, 3);
  assert.equal(applyKeyRotation(applied.device, rotation).idempotentReplay, true);
  assert.throws(() => applyKeyRotation(device, { ...rotation, current_key_version: 0 }), /stale_rotation/u);
});
