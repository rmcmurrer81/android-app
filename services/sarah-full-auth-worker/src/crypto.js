import { HttpError } from "./protocol.js";

const encoder = new TextEncoder();
const decoder = new TextDecoder("utf-8", { fatal: true });
const BASE64URL_RE = /^[A-Za-z0-9_-]+$/;
const SECRET_HASH_DOMAIN = "SARAH-HASH-V1";
const SECRET_HASH_PURPOSES = new Set([
  "device_code",
  "user_code",
  "enrollment_challenge",
  "auth_challenge_nonce",
]);

export function encodeBase64Url(value) {
  const bytes = value instanceof Uint8Array ? value : new Uint8Array(value);
  let binary = "";
  for (let index = 0; index < bytes.length; index += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(index, index + 0x8000));
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

export function decodeBase64Url(value, { exactBytes = undefined } = {}) {
  if (typeof value !== "string" || !value || !BASE64URL_RE.test(value)) {
    throw new HttpError(400, "invalid_base64url");
  }
  const pad = "=".repeat((4 - (value.length % 4)) % 4);
  let binary;
  try {
    binary = atob(value.replace(/-/g, "+").replace(/_/g, "/") + pad);
  } catch {
    throw new HttpError(400, "invalid_base64url");
  }
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  if (exactBytes !== undefined && bytes.byteLength !== exactBytes) {
    throw new HttpError(400, "invalid_base64url_length");
  }
  if (encodeBase64Url(bytes) !== value) {
    throw new HttpError(400, "noncanonical_base64url");
  }
  return bytes;
}

export function utf8(value) {
  return encoder.encode(String(value));
}

export function decodeUtf8(value) {
  try {
    return decoder.decode(value);
  } catch {
    throw new HttpError(401, "invalid_token");
  }
}

export function randomBase64Url(byteLength, randomBytes = defaultRandomBytes) {
  return encodeBase64Url(randomBytes(byteLength));
}

function defaultRandomBytes(byteLength) {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return bytes;
}

export async function sha256Base64Url(value) {
  const bytes = value instanceof Uint8Array ? value : utf8(value);
  return encodeBase64Url(await crypto.subtle.digest("SHA-256", bytes));
}

export async function hmacSha256Base64Url(keyBytes, value) {
  const key = await crypto.subtle.importKey(
    "raw", keyBytes, { name: "HMAC", hash: "SHA-256" }, false, ["sign"],
  );
  return encodeBase64Url(await crypto.subtle.sign("HMAC", key, utf8(value)));
}

export async function hashOpaqueValue(env, purpose, value) {
  const pepper = String(env.AUTH_CODE_HASH_PEPPER || "");
  if (utf8(pepper).byteLength < 32 || !SECRET_HASH_PURPOSES.has(String(purpose))) {
    throw new HttpError(503, "auth_hashing_unavailable");
  }
  const opaque = String(value || "");
  if (!opaque || opaque.includes("\n") || opaque.length > 512) {
    throw new HttpError(400, "opaque_value_invalid");
  }
  return hmacSha256Base64Url(
    utf8(pepper),
    `${SECRET_HASH_DOMAIN}\n${purpose}\n${opaque}`,
  );
}

export function constantTimeStringEqual(left, right) {
  const leftBytes = utf8(left);
  const rightBytes = utf8(right);
  let different = leftBytes.byteLength ^ rightBytes.byteLength;
  const length = Math.max(leftBytes.byteLength, rightBytes.byteLength);
  for (let index = 0; index < length; index += 1) {
    different |= (leftBytes[index % Math.max(1, leftBytes.byteLength)] || 0)
      ^ (rightBytes[index % Math.max(1, rightBytes.byteLength)] || 0);
  }
  return different === 0;
}

export function canonicalPublicJwk(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new HttpError(400, "invalid_public_key");
  }
  const keys = Object.keys(value).sort();
  const expected = ["crv", "kty", "x", "y"];
  if (keys.length !== expected.length || keys.some((key, index) => key !== expected[index])) {
    throw new HttpError(400, "invalid_public_key_fields");
  }
  if (value.kty !== "EC" || value.crv !== "P-256") {
    throw new HttpError(400, "invalid_public_key_curve");
  }
  decodeBase64Url(value.x, { exactBytes: 32 });
  decodeBase64Url(value.y, { exactBytes: 32 });
  return Object.freeze({ crv: "P-256", kty: "EC", x: value.x, y: value.y });
}

export function canonicalJwkJson(value) {
  const jwk = canonicalPublicJwk(value);
  return JSON.stringify({ crv: jwk.crv, kty: jwk.kty, x: jwk.x, y: jwk.y });
}

export async function publicJwkThumbprint(value) {
  return sha256Base64Url(canonicalJwkJson(value));
}

export async function verifyP256Signature(publicJwk, payload, signature) {
  const jwk = canonicalPublicJwk(publicJwk);
  let signatureBytes;
  try {
    signatureBytes = decodeBase64Url(signature, { exactBytes: 64 });
  } catch {
    return false;
  }
  try {
    const key = await crypto.subtle.importKey(
      "jwk", jwk, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"],
    );
    return crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" }, key, signatureBytes, utf8(payload),
    );
  } catch {
    return false;
  }
}

export function enrollmentProofPayload({ enrollmentId, challenge, apiOrigin, keyThumbprint }) {
  return [
    "SARAH-ENROLLMENT-V1",
    enrollmentId,
    challenge,
    apiOrigin,
    keyThumbprint,
  ].join("\n");
}

export function sessionProofPayload({ deviceId, challengeId, nonce, apiOrigin, keyVersion }) {
  return [
    "SARAH-AUTH-V1",
    deviceId,
    challengeId,
    nonce,
    apiOrigin,
    String(keyVersion),
  ].join("\n");
}

export function revokeProofPayload({
  deviceId, challengeId, nonce, apiOrigin, keyVersion, reasonHash,
}) {
  return [
    "SARAH-DEVICE-REVOKE-V1",
    deviceId,
    challengeId,
    nonce,
    apiOrigin,
    String(keyVersion),
    reasonHash,
  ].join("\n");
}

export function rotationProofPayload({
  deviceId,
  rotationId,
  challengeId,
  nonce,
  apiOrigin,
  currentKeyVersion,
  oldThumbprint,
  newThumbprint,
}) {
  return [
    "SARAH-KEY-ROTATION-V1",
    deviceId,
    rotationId,
    challengeId,
    nonce,
    apiOrigin,
    String(currentKeyVersion),
    oldThumbprint,
    newThumbprint,
  ].join("\n");
}

function signingKeys(env) {
  const currentKid = String(env.JWT_SIGNING_KID_CURRENT || "").trim();
  const currentSecret = String(env.JWT_SIGNING_KEY_CURRENT || "").trim();
  if (!currentKid || !/^[A-Za-z0-9._-]{1,64}$/.test(currentKid) || !currentSecret) {
    throw new HttpError(503, "auth_signing_unavailable");
  }
  let currentBytes;
  try {
    currentBytes = decodeBase64Url(currentSecret);
  } catch {
    throw new HttpError(503, "auth_signing_unavailable");
  }
  if (currentBytes.byteLength < 32) {
    throw new HttpError(503, "auth_signing_unavailable");
  }
  const keys = new Map([[currentKid, currentBytes]]);
  const previousKid = String(env.JWT_SIGNING_KID_PREVIOUS || "").trim();
  const previousSecret = String(env.JWT_SIGNING_KEY_PREVIOUS || "").trim();
  if (previousKid || previousSecret) {
    if (!previousKid || !previousSecret || previousKid === currentKid
        || !/^[A-Za-z0-9._-]{1,64}$/.test(previousKid)) {
      throw new HttpError(503, "auth_signing_unavailable");
    }
    let previousBytes;
    try {
      previousBytes = decodeBase64Url(previousSecret);
    } catch {
      throw new HttpError(503, "auth_signing_unavailable");
    }
    if (previousBytes.byteLength < 32) {
      throw new HttpError(503, "auth_signing_unavailable");
    }
    keys.set(previousKid, previousBytes);
  }
  return { currentKid, keys };
}

function jwtConfiguration(env) {
  const issuer = String(env.JWT_ISSUER || "").trim();
  const audience = String(env.JWT_AUDIENCE || "").trim();
  if (!issuer || !audience) throw new HttpError(503, "auth_signing_unavailable");
  return { issuer, audience };
}

export async function issueAccessJwt(env, claims, nowMs, randomBytes = defaultRandomBytes) {
  const { currentKid, keys } = signingKeys(env);
  const { issuer, audience } = jwtConfiguration(env);
  const nowSeconds = Math.floor(nowMs / 1000);
  const header = { alg: "HS256", kid: currentKid, typ: "JWT" };
  const payload = {
    iss: issuer,
    aud: audience,
    sub: claims.deviceId,
    device_id: claims.deviceId,
    owner_id: claims.ownerId,
    scope: [...claims.scopes],
    key_version: claims.keyVersion,
    auth_epoch: claims.authEpoch,
    iat: nowSeconds,
    nbf: nowSeconds - 5,
    exp: nowSeconds + 10 * 60,
    jti: randomBase64Url(16, randomBytes),
  };
  const encodedHeader = encodeBase64Url(utf8(JSON.stringify(header)));
  const encodedPayload = encodeBase64Url(utf8(JSON.stringify(payload)));
  const signingInput = `${encodedHeader}.${encodedPayload}`;
  const signature = await hmacSha256Base64Url(keys.get(currentKid), signingInput);
  return { token: `${signingInput}.${signature}`, claims: payload };
}

function parseJwtPart(value) {
  try {
    return JSON.parse(decodeUtf8(decodeBase64Url(value)));
  } catch {
    throw new HttpError(401, "invalid_token");
  }
}

export async function verifyAccessJwt(env, token, nowMs) {
  const { keys } = signingKeys(env);
  const { issuer, audience } = jwtConfiguration(env);
  const parts = String(token || "").split(".");
  if (parts.length !== 3) throw new HttpError(401, "invalid_token");
  const header = parseJwtPart(parts[0]);
  const payload = parseJwtPart(parts[1]);
  if (!header || header.alg !== "HS256" || header.typ !== "JWT"
      || typeof header.kid !== "string" || !keys.has(header.kid)) {
    throw new HttpError(401, "invalid_token");
  }
  let supplied;
  try {
    supplied = decodeBase64Url(parts[2], { exactBytes: 32 });
  } catch {
    throw new HttpError(401, "invalid_token");
  }
  const expected = await hmacSha256Base64Url(keys.get(header.kid), `${parts[0]}.${parts[1]}`);
  if (!constantTimeStringEqual(encodeBase64Url(supplied), expected)) {
    throw new HttpError(401, "invalid_token");
  }
  const nowSeconds = Math.floor(nowMs / 1000);
  if (payload.iss !== issuer || payload.aud !== audience
      || typeof payload.sub !== "string" || !payload.sub
      || typeof payload.device_id !== "string" || payload.device_id !== payload.sub
      || typeof payload.owner_id !== "string" || !payload.owner_id
      || !Array.isArray(payload.scope)
      || payload.scope.some((scope) => typeof scope !== "string")
      || !Number.isInteger(payload.key_version) || payload.key_version < 1
      || !Number.isInteger(payload.auth_epoch) || payload.auth_epoch < 1
      || !Number.isInteger(payload.iat) || !Number.isInteger(payload.nbf)
      || !Number.isInteger(payload.exp) || payload.exp <= nowSeconds - 5
      || payload.nbf > nowSeconds + 5 || payload.iat > nowSeconds + 5
      || payload.exp - payload.iat > 10 * 60
      || typeof payload.jti !== "string" || payload.jti.length < 16) {
    throw new HttpError(401, "invalid_token");
  }
  return payload;
}

export function bearerToken(request) {
  const value = String(request.headers.get("Authorization") || "");
  const match = /^Bearer ([A-Za-z0-9._-]+)$/.exec(value);
  if (!match) throw new HttpError(401, "invalid_token");
  return match[1];
}

export function parseStoredJwk(value) {
  try {
    return canonicalPublicJwk(JSON.parse(value));
  } catch {
    throw new HttpError(503, "auth_store_corrupt");
  }
}

export function signingKeyConfigurationPresent(env) {
  signingKeys(env);
  jwtConfiguration(env);
  return true;
}
