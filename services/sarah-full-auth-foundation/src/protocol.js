export const AUTH_DOMAIN = "SARAH-AUTH-V1";
export const ENROLLMENT_DOMAIN = "SARAH-ENROLLMENT-V1";
export const SECRET_HASH_DOMAIN = "SARAH-HASH-V1";
export const P256_P1363_BYTES = 64;

const encoder = new TextEncoder();
const HEX = "0123456789abcdef";

export class ProtocolValueError extends Error {
  constructor(message) {
    super(message);
    this.name = "ProtocolValueError";
  }
}

export function base64UrlEncode(bytes) {
  let binary = "";
  for (const value of bytes) binary += String.fromCharCode(value);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/u, "");
}

export function base64UrlDecode(value, expectedBytes = null) {
  const text = String(value || "");
  if (!/^[A-Za-z0-9_-]+$/u.test(text)) {
    throw new ProtocolValueError("value is not unpadded base64url");
  }
  const padded = text.replaceAll("-", "+").replaceAll("_", "/")
    + "=".repeat((4 - (text.length % 4)) % 4);
  let binary;
  try {
    binary = atob(padded);
  } catch {
    throw new ProtocolValueError("value is not valid base64url");
  }
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  if (base64UrlEncode(bytes) !== text) {
    throw new ProtocolValueError("value is not canonical base64url");
  }
  if (expectedBytes !== null && bytes.length !== expectedBytes) {
    throw new ProtocolValueError(`expected ${expectedBytes} decoded bytes`);
  }
  return bytes;
}

function canonicalLine(value, label, pattern = /^[A-Za-z0-9_-]+$/u) {
  const text = String(value ?? "");
  if (!text || text.length > 256 || !pattern.test(text)) {
    throw new ProtocolValueError(`${label} is not canonical`);
  }
  return text;
}

export function canonicalApiOrigin(value) {
  const text = String(value || "").trim();
  let parsed;
  try {
    parsed = new URL(text);
  } catch {
    throw new ProtocolValueError("api_origin is not a URL");
  }
  if (parsed.protocol !== "https:" || parsed.username || parsed.password
      || parsed.pathname !== "/" || parsed.search || parsed.hash) {
    throw new ProtocolValueError("api_origin must be a bare HTTPS origin");
  }
  if (parsed.origin !== text) {
    throw new ProtocolValueError("api_origin must already be canonical");
  }
  return text;
}

export function canonicalPublicJwk(jwk) {
  if (!jwk || typeof jwk !== "object" || Array.isArray(jwk)) {
    throw new ProtocolValueError("public_jwk must be an object");
  }
  const keys = Object.keys(jwk).sort();
  if (JSON.stringify(keys) !== JSON.stringify(["crv", "kty", "x", "y"])) {
    throw new ProtocolValueError("public_jwk must contain only crv, kty, x, and y");
  }
  if (jwk.kty !== "EC" || jwk.crv !== "P-256") {
    throw new ProtocolValueError("public_jwk must be an EC P-256 key");
  }
  base64UrlDecode(jwk.x, 32);
  base64UrlDecode(jwk.y, 32);
  return JSON.stringify({ crv: "P-256", kty: "EC", x: jwk.x, y: jwk.y });
}

export function buildAuthPayload({ deviceId, challengeId, nonce, apiOrigin, keyVersion }) {
  const version = Number(keyVersion);
  if (!Number.isSafeInteger(version) || version < 1) {
    throw new ProtocolValueError("key_version must be a positive integer");
  }
  base64UrlDecode(nonce, 32);
  return [
    AUTH_DOMAIN,
    canonicalLine(deviceId, "device_id"),
    canonicalLine(challengeId, "challenge_id"),
    nonce,
    canonicalApiOrigin(apiOrigin),
    String(version),
  ].join("\n");
}

export function buildEnrollmentPayload({ enrollmentId, challenge, apiOrigin, keyThumbprint }) {
  base64UrlDecode(challenge, 32);
  base64UrlDecode(keyThumbprint, 32);
  return [
    ENROLLMENT_DOMAIN,
    canonicalLine(enrollmentId, "enrollment_id"),
    challenge,
    canonicalApiOrigin(apiOrigin),
    keyThumbprint,
  ].join("\n");
}

export async function sha256Bytes(value) {
  const bytes = typeof value === "string" ? encoder.encode(value) : value;
  return new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
}

export async function sha256Hex(value) {
  const digest = await sha256Bytes(value);
  return Array.from(digest, (byte) => HEX[byte >> 4] + HEX[byte & 15]).join("");
}

export async function jwkThumbprint(jwk) {
  return base64UrlEncode(await sha256Bytes(canonicalPublicJwk(jwk)));
}

export async function hmacOpaqueValue({ pepper, purpose, value }) {
  const secret = String(pepper || "");
  if (encoder.encode(secret).byteLength < 32) {
    throw new ProtocolValueError("hash pepper must contain at least 32 UTF-8 bytes");
  }
  const boundedPurpose = canonicalLine(purpose, "hash purpose", /^[a-z0-9_-]+$/u);
  const opaqueValue = String(value || "");
  if (!opaqueValue || opaqueValue.includes("\n") || opaqueValue.length > 512) {
    throw new ProtocolValueError("opaque hash value is invalid");
  }
  const key = await crypto.subtle.importKey(
    "raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const payload = `${SECRET_HASH_DOMAIN}\n${boundedPurpose}\n${opaqueValue}`;
  return base64UrlEncode(new Uint8Array(await crypto.subtle.sign("HMAC", key, encoder.encode(payload))));
}

function derInteger(bytes) {
  let first = 0;
  while (first < bytes.length - 1 && bytes[first] === 0) first += 1;
  let value = bytes.slice(first);
  if ((value[0] & 0x80) !== 0) {
    const prefixed = new Uint8Array(value.length + 1);
    prefixed.set(value, 1);
    value = prefixed;
  }
  return Uint8Array.of(0x02, value.length, ...value);
}

export function p1363ToDer(signature) {
  const raw = typeof signature === "string"
    ? base64UrlDecode(signature, P256_P1363_BYTES)
    : new Uint8Array(signature);
  if (raw.length !== P256_P1363_BYTES) {
    throw new ProtocolValueError("P-256 P1363 signature must contain 64 bytes");
  }
  const r = derInteger(raw.slice(0, 32));
  const s = derInteger(raw.slice(32));
  return Uint8Array.of(0x30, r.length + s.length, ...r, ...s);
}

function readDerInteger(der, offset) {
  if (der[offset] !== 0x02 || offset + 2 > der.length) {
    throw new ProtocolValueError("invalid DER integer tag");
  }
  const length = der[offset + 1];
  const start = offset + 2;
  const end = start + length;
  if (length < 1 || length > 33 || end > der.length) {
    throw new ProtocolValueError("invalid DER integer length");
  }
  let value = der.slice(start, end);
  if ((value[0] & 0x80) !== 0) {
    throw new ProtocolValueError("negative DER integer is forbidden");
  }
  if (value.length > 1 && value[0] === 0) {
    if ((value[1] & 0x80) === 0) {
      throw new ProtocolValueError("non-minimal DER integer");
    }
    value = value.slice(1);
  }
  if (value.length > 32) {
    throw new ProtocolValueError("DER integer exceeds P-256 width");
  }
  const fixed = new Uint8Array(32);
  fixed.set(value, 32 - value.length);
  return { fixed, next: end };
}

export function derToP1363(signature) {
  const der = new Uint8Array(signature);
  if (der.length < 8 || der[0] !== 0x30 || der[1] !== der.length - 2 || der[1] >= 0x80) {
    throw new ProtocolValueError("invalid canonical short-form DER sequence");
  }
  const r = readDerInteger(der, 2);
  const s = readDerInteger(der, r.next);
  if (s.next !== der.length) {
    throw new ProtocolValueError("trailing DER signature data is forbidden");
  }
  return Uint8Array.of(...r.fixed, ...s.fixed);
}

export async function verifyP256Signature({ publicJwk, payload, signatureP1363 }) {
  canonicalPublicJwk(publicJwk);
  const signature = typeof signatureP1363 === "string"
    ? base64UrlDecode(signatureP1363, P256_P1363_BYTES)
    : new Uint8Array(signatureP1363);
  if (signature.length !== P256_P1363_BYTES) return false;
  const key = await crypto.subtle.importKey(
    "jwk", publicJwk, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
  return crypto.subtle.verify(
    { name: "ECDSA", hash: "SHA-256" }, key, signature, encoder.encode(String(payload)));
}
