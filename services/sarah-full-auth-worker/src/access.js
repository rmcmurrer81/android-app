import {
  constantTimeStringEqual,
  decodeBase64Url,
  decodeUtf8,
  encodeBase64Url,
  randomBase64Url,
  sha256Base64Url,
  utf8,
} from "./crypto.js";
import { HttpError, OWNER_CSRF_TTL_SECONDS } from "./protocol.js";

const jwksCache = new Map();

function parseJwtJson(value) {
  try {
    return JSON.parse(decodeUtf8(decodeBase64Url(value)));
  } catch {
    throw new HttpError(401, "owner_access_invalid");
  }
}

function accessConfiguration(env) {
  const issuer = String(env.CF_ACCESS_ISSUER || "").trim().replace(/\/$/, "");
  const audience = String(env.CF_ACCESS_AUDIENCE || "").trim();
  let parsed;
  try {
    parsed = new URL(issuer);
  } catch {
    throw new HttpError(503, "owner_access_unavailable");
  }
  if (parsed.protocol !== "https:" || parsed.origin !== issuer || parsed.pathname !== "/"
      || !audience) {
    throw new HttpError(503, "owner_access_unavailable");
  }
  return { issuer, audience };
}

async function fetchJwks(issuer, fetchImpl, nowMs) {
  const cached = jwksCache.get(issuer);
  if (cached && cached.expiresAt > nowMs) return cached.keys;
  let response;
  try {
    response = await fetchImpl(`${issuer}/cdn-cgi/access/certs`, {
      headers: { Accept: "application/json" },
    });
  } catch {
    throw new HttpError(503, "owner_access_unavailable");
  }
  if (!response.ok) throw new HttpError(503, "owner_access_unavailable");
  let body;
  try {
    body = await response.json();
  } catch {
    throw new HttpError(503, "owner_access_unavailable");
  }
  const keys = Array.isArray(body?.keys) ? body.keys : [];
  if (!keys.length) throw new HttpError(503, "owner_access_unavailable");
  jwksCache.set(issuer, { keys, expiresAt: nowMs + 5 * 60 * 1000 });
  return keys;
}

export async function verifyOwnerAccess(request, env, store, nowMs, fetchImpl) {
  const assertion = String(request.headers.get("Cf-Access-Jwt-Assertion") || "").trim();
  if (!assertion) throw new HttpError(401, "owner_access_invalid");
  const parts = assertion.split(".");
  if (parts.length !== 3) throw new HttpError(401, "owner_access_invalid");
  const header = parseJwtJson(parts[0]);
  const claims = parseJwtJson(parts[1]);
  if (header?.alg !== "RS256" || typeof header.kid !== "string" || !header.kid) {
    throw new HttpError(401, "owner_access_invalid");
  }
  const { issuer, audience } = accessConfiguration(env);
  const keys = await fetchJwks(issuer, fetchImpl, nowMs);
  const jwk = keys.find((candidate) => candidate?.kid === header.kid
    && candidate.kty === "RSA" && (!candidate.alg || candidate.alg === "RS256"));
  if (!jwk) throw new HttpError(401, "owner_access_invalid");
  let signature;
  try {
    signature = decodeBase64Url(parts[2]);
  } catch {
    throw new HttpError(401, "owner_access_invalid");
  }
  let verified = false;
  try {
    const key = await crypto.subtle.importKey(
      "jwk", jwk, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"],
    );
    verified = await crypto.subtle.verify(
      "RSASSA-PKCS1-v1_5", key, signature, utf8(`${parts[0]}.${parts[1]}`),
    );
  } catch {
    verified = false;
  }
  const nowSeconds = Math.floor(nowMs / 1000);
  const audiences = Array.isArray(claims?.aud) ? claims.aud : [claims?.aud];
  if (!verified || claims?.iss !== issuer || !audiences.includes(audience)
      || typeof claims?.sub !== "string" || !claims.sub
      || !Number.isInteger(claims.exp) || claims.exp <= nowSeconds - 5
      || (claims.nbf !== undefined && (!Number.isInteger(claims.nbf) || claims.nbf > nowSeconds + 5))) {
    throw new HttpError(401, "owner_access_invalid");
  }
  const subjectHash = await sha256Base64Url(claims.sub);
  const owner = await store.getOwnerBySubjectHash(subjectHash);
  if (!owner || owner.status !== "active") throw new HttpError(403, "owner_access_denied");
  return { ownerId: owner.owner_id, subjectHash };
}

export function issueOwnerCsrf(randomBytes) {
  const token = randomBase64Url(32, randomBytes);
  const cookie = `__Host-sarah_owner_csrf=${token}; Path=/; Max-Age=${OWNER_CSRF_TTL_SECONDS}; Secure; SameSite=Strict`;
  return { token, cookie };
}

function cookieValue(request, name) {
  const raw = String(request.headers.get("Cookie") || "");
  for (const item of raw.split(";")) {
    const separator = item.indexOf("=");
    if (separator < 0) continue;
    if (item.slice(0, separator).trim() === name) return item.slice(separator + 1).trim();
  }
  return "";
}

export function verifyOwnerCsrf(request, env) {
  const expectedOrigin = String(env.OWNER_PORTAL_ORIGIN || "").trim().replace(/\/$/, "");
  let parsed;
  try {
    parsed = new URL(expectedOrigin);
  } catch {
    throw new HttpError(503, "owner_portal_not_configured");
  }
  if (parsed.protocol !== "https:" || parsed.origin !== expectedOrigin || parsed.pathname !== "/") {
    throw new HttpError(503, "owner_portal_not_configured");
  }
  if (request.headers.get("Origin") !== expectedOrigin) {
    throw new HttpError(403, "csrf_rejected");
  }
  const header = String(request.headers.get("X-Sarah-Owner-CSRF") || "").trim();
  const cookie = cookieValue(request, "__Host-sarah_owner_csrf");
  if (header.length < 32 || cookie.length < 32 || !constantTimeStringEqual(header, cookie)) {
    throw new HttpError(403, "csrf_rejected");
  }
}

export function clearAccessJwksCacheForTests() {
  jwksCache.clear();
}
