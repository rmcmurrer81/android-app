const ALLOWED_KEYS = new Set([
  "contract",
  "api_origin",
  "owner_portal_origin",
  "service_id",
  "contract_major",
  "build_channel",
  "enrollment_enabled",
]);

const FORBIDDEN_KEY = /(authorization|bearer|token|secret|private.?key|credential|password|event.?auth|backend.?token|refresh)/iu;
const FORBIDDEN_VALUE = /(?:\bBearer\s+[A-Za-z0-9._~-]{12,}|\bsevt1_[A-Za-z0-9_-]+|\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,})/u;

export class ArtifactCredentialError extends Error {
  constructor(message) {
    super(message);
    this.name = "ArtifactCredentialError";
  }
}
function exactHttpsOrigin(value, label) {
  const text = String(value || "");
  let parsed;
  try {
    parsed = new URL(text);
  } catch {
    throw new ArtifactCredentialError(`${label} must be an HTTPS origin`);
  }
  if (parsed.protocol !== "https:" || parsed.origin !== text || parsed.pathname !== "/"
      || parsed.search || parsed.hash || parsed.username || parsed.password) {
    throw new ArtifactCredentialError(`${label} must be a canonical bare HTTPS origin`);
  }
}

export function assertFullArtifactConfigNoBearer(config) {
  if (!config || typeof config !== "object" || Array.isArray(config)) {
    throw new ArtifactCredentialError("full artifact configuration must be an object");
  }
  for (const key of Object.keys(config)) {
    if (FORBIDDEN_KEY.test(key)) {
      throw new ArtifactCredentialError(`credential-bearing field is forbidden: ${key}`);
    }
    if (!ALLOWED_KEYS.has(key)) {
      throw new ArtifactCredentialError(`unknown full artifact field: ${key}`);
    }
  }
  for (const required of ALLOWED_KEYS) {
    if (!(required in config)) throw new ArtifactCredentialError(`missing public field: ${required}`);
  }
  if (config.contract !== "sarah-full-artifact-config-v1" || config.contract_major !== 1) {
    throw new ArtifactCredentialError("unsupported full artifact contract");
  }
  if (!["full-staging", "full-release"].includes(config.build_channel)
      || typeof config.enrollment_enabled !== "boolean") {
    throw new ArtifactCredentialError("invalid full artifact channel or enrollment flag");
  }
  exactHttpsOrigin(config.api_origin, "api_origin");
  exactHttpsOrigin(config.owner_portal_origin, "owner_portal_origin");
  assertNoEmbeddedBearer(JSON.stringify(config));
  return true;
}

export function assertNoEmbeddedBearer(value) {
  const text = typeof value === "string" ? value : new TextDecoder().decode(value);
  if (FORBIDDEN_VALUE.test(text)) {
    throw new ArtifactCredentialError("reusable bearer-like material detected");
  }
  return true;
}
