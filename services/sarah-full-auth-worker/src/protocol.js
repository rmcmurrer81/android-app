export const SERVICE_NAME = "sarah-full-auth-worker";
export const CONTRACT_MAJOR = 1;
export const CONTRACT_VERSION = "sarah-full-device-auth-v1";

export const ENROLLMENT_TTL_SECONDS = 10 * 60;
export const ENROLLMENT_POLL_INTERVAL_SECONDS = 5;
export const CHALLENGE_TTL_SECONDS = 2 * 60;
export const ACCESS_TOKEN_TTL_SECONDS = 10 * 60;
export const DEVICE_LEASE_SECONDS = 90 * 24 * 60 * 60;
export const OWNER_CSRF_TTL_SECONDS = 10 * 60;

export const DEFAULT_SCOPES = Object.freeze([
  "capabilities:read",
  "chat:write",
  "search:write",
  "voice:write",
  "device:read",
  "device:write",
]);

export class HttpError extends Error {
  constructor(status, code, details = undefined, headers = undefined) {
    super(code);
    this.name = "HttpError";
    this.status = status;
    this.code = code;
    this.details = details;
    this.headers = headers;
  }
}

export function json(payload, status = 200, headers = undefined) {
  const responseHeaders = new Headers(headers || {});
  responseHeaders.set("Content-Type", "application/json; charset=utf-8");
  responseHeaders.set("Cache-Control", "no-store");
  responseHeaders.set("X-Content-Type-Options", "nosniff");
  return new Response(JSON.stringify(payload), { status, headers: responseHeaders });
}

export async function readJson(request, maxBytes = 64 * 1024) {
  const declared = Number(request.headers.get("Content-Length") || 0);
  if (Number.isFinite(declared) && declared > maxBytes) {
    throw new HttpError(413, "request_too_large");
  }
  let text;
  try {
    text = await request.text();
  } catch {
    throw new HttpError(400, "request_read_failed");
  }
  if (new TextEncoder().encode(text).byteLength > maxBytes) {
    throw new HttpError(413, "request_too_large");
  }
  try {
    const value = JSON.parse(text);
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      throw new Error("object required");
    }
    return value;
  } catch {
    throw new HttpError(400, "invalid_json");
  }
}

export function boundedString(value, maxLength, field, { required = true } = {}) {
  if (typeof value !== "string") {
    if (!required && (value === undefined || value === null)) return "";
    throw new HttpError(400, `${field}_required`);
  }
  const clean = value.trim();
  if (!clean && required) throw new HttpError(400, `${field}_required`);
  if (clean.length > maxLength) throw new HttpError(400, `${field}_invalid`);
  return clean;
}

export function assertExactFields(value, allowed, required = allowed) {
  const actual = Object.keys(value).sort();
  const allowedSet = new Set(allowed);
  if (actual.some((field) => !allowedSet.has(field))
      || required.some((field) => !Object.hasOwn(value, field))) {
    throw new HttpError(400, "request_fields_invalid");
  }
  return value;
}

export function isoTime(ms) {
  return new Date(ms).toISOString();
}

export function parseIsoTime(value) {
  if (typeof value !== "string") return NaN;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : NaN;
}

export function canonicalApiOrigin(env, request) {
  const configured = String(env.SARAH_FULL_API_ORIGIN || "").trim().replace(/\/$/, "");
  let parsed;
  try {
    parsed = new URL(configured);
  } catch {
    throw new HttpError(503, "api_origin_not_configured");
  }
  if (parsed.protocol !== "https:" || parsed.origin !== configured || parsed.pathname !== "/") {
    throw new HttpError(503, "api_origin_not_configured");
  }
  if (new URL(request.url).origin !== configured) {
    throw new HttpError(421, "wrong_api_origin");
  }
  return configured;
}

export function publicErrorResponse(error) {
  if (error instanceof HttpError) {
    const payload = { error: error.code };
    if (error.details && typeof error.details === "object") {
      Object.assign(payload, error.details);
    }
    return json(payload, error.status, error.headers);
  }
  return json({ error: "internal_error" }, 500);
}
