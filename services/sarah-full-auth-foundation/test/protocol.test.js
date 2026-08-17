import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
  ProtocolValueError,
  base64UrlDecode,
  base64UrlEncode,
  buildAuthPayload,
  buildEnrollmentPayload,
  canonicalPublicJwk,
  derToP1363,
  hmacOpaqueValue,
  jwkThumbprint,
  p1363ToDer,
  sha256Hex,
  verifyP256Signature,
} from "../src/protocol.js";

const fixture = JSON.parse(await readFile(new URL("../fixtures/p256-v1.json", import.meta.url), "utf8"));

test("RFC 7638 JWK canonicalization and thumbprint match the public fixture", async () => {
  assert.equal(canonicalPublicJwk(fixture.public_jwk), fixture.public_jwk_canonical_json);
  assert.equal(await jwkThumbprint(fixture.public_jwk), fixture.jwk_thumbprint_sha256_base64url);
  assert.equal("d" in fixture.public_jwk, false);
});
test("auth payload bytes, digest, and P1363 signature match the cross-language vector", async () => {
  const payload = buildAuthPayload({
    deviceId: fixture.auth.device_id,
    challengeId: fixture.auth.challenge_id,
    nonce: fixture.auth.nonce,
    apiOrigin: fixture.auth.api_origin,
    keyVersion: fixture.auth.key_version,
  });
  assert.equal(payload, fixture.auth.payload_utf8);
  assert.equal(await sha256Hex(payload), fixture.auth.payload_sha256_hex);
  assert.equal(await verifyP256Signature({
    publicJwk: fixture.public_jwk,
    payload,
    signatureP1363: fixture.auth.signature_p1363_base64url,
  }), true);
});

test("enrollment payload bytes, digest, and P1363 signature match the vector", async () => {
  const payload = buildEnrollmentPayload({
    enrollmentId: fixture.enrollment.enrollment_id,
    challenge: fixture.enrollment.challenge,
    apiOrigin: fixture.enrollment.api_origin,
    keyThumbprint: fixture.enrollment.key_thumbprint,
  });
  assert.equal(payload, fixture.enrollment.payload_utf8);
  assert.equal(await sha256Hex(payload), fixture.enrollment.payload_sha256_hex);
  assert.equal(await verifyP256Signature({
    publicJwk: fixture.public_jwk,
    payload,
    signatureP1363: fixture.enrollment.signature_p1363_base64url,
  }), true);
});

test("P1363 and canonical DER conversions round-trip both fixture signatures", () => {
  for (const signature of [
    fixture.auth.signature_p1363_base64url,
    fixture.enrollment.signature_p1363_base64url,
  ]) {
    const raw = base64UrlDecode(signature, 64);
    const der = p1363ToDer(raw);
    assert.deepEqual(derToP1363(der), raw);
    assert.equal(base64UrlEncode(derToP1363(der)), signature);
  }
});

test("canonical payload construction rejects ambiguity and malformed key material", () => {
  assert.throws(() => buildAuthPayload({
    deviceId: "dev_bad\nextra",
    challengeId: fixture.auth.challenge_id,
    nonce: fixture.auth.nonce,
    apiOrigin: fixture.auth.api_origin,
    keyVersion: 7,
  }), ProtocolValueError);
  assert.throws(() => buildAuthPayload({
    deviceId: fixture.auth.device_id,
    challengeId: fixture.auth.challenge_id,
    nonce: fixture.auth.nonce,
    apiOrigin: "https://api.sarah.example/",
    keyVersion: 7,
  }), ProtocolValueError);
  assert.throws(() => canonicalPublicJwk({ ...fixture.public_jwk, use: "sig" }), ProtocolValueError);
  assert.throws(() => derToP1363(Uint8Array.of(0x30, 0x03, 0x02, 0x01, 0x80)), ProtocolValueError);
});

test("tampered payload and signature fail verification", async () => {
  assert.equal(await verifyP256Signature({
    publicJwk: fixture.public_jwk,
    payload: `${fixture.auth.payload_utf8}x`,
    signatureP1363: fixture.auth.signature_p1363_base64url,
  }), false);
  const signature = base64UrlDecode(fixture.auth.signature_p1363_base64url, 64);
  signature[0] ^= 1;
  assert.equal(await verifyP256Signature({
    publicJwk: fixture.public_jwk,
    payload: fixture.auth.payload_utf8,
    signatureP1363: signature,
  }), false);
});

test("secret hashes are deterministic, purpose-separated HMAC values", async () => {
  const pepper = "test-only-pepper-with-at-least-thirty-two-bytes";
  const first = await hmacOpaqueValue({ pepper, purpose: "device_code", value: "opaque-value" });
  const again = await hmacOpaqueValue({ pepper, purpose: "device_code", value: "opaque-value" });
  const otherPurpose = await hmacOpaqueValue({ pepper, purpose: "user_code", value: "opaque-value" });
  assert.equal(first, again);
  assert.notEqual(first, otherPurpose);
  assert.match(first, /^[A-Za-z0-9_-]{43}$/u);
  assert.equal(first.includes("opaque-value"), false);
});
