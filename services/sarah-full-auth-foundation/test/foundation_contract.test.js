import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import test from "node:test";

import {
  ArtifactCredentialError,
  assertFullArtifactConfigNoBearer,
  assertNoEmbeddedBearer,
} from "../src/artifact_contract.js";

test("safe full artifact configuration contains public routing data only", async () => {
  const config = JSON.parse(await readFile(new URL("../fixtures/full-artifact-config-safe.json", import.meta.url), "utf8"));
  assert.equal(assertFullArtifactConfigNoBearer(config), true);
  assert.throws(
    () => assertFullArtifactConfigNoBearer({ ...config, backend_token: "extractable" }),
    ArtifactCredentialError,
  );
  assert.throws(() => assertNoEmbeddedBearer("Authorization: Bearer reusable-token-material"), ArtifactCredentialError);
  assert.throws(() => assertNoEmbeddedBearer("sevt1_extractable_event_bearer"), ArtifactCredentialError);
});
test("protocol schema fixes P-256, P1363, ten-minute access, and closed object shapes", async () => {
  const schema = JSON.parse(await readFile(new URL("../schema/protocol-v1.schema.json", import.meta.url), "utf8"));
  assert.equal(schema.$defs.PublicP256Jwk.additionalProperties, false);
  assert.equal(schema.$defs.PublicP256Jwk.properties.crv.const, "P-256");
  assert.equal(schema.$defs.P1363Signature.pattern, "^[A-Za-z0-9_-]{86}$");
  assert.equal(schema.$defs.AuthTokenResponse.properties.expires_in.const, 600);
});

test("isolated migration defines security tables and unique replay boundaries", async () => {
  const migration = await readFile(new URL("../migrations/0001_device_auth_v1.sql", import.meta.url), "utf8");
  for (const table of ["owners", "enrollments", "devices", "auth_challenges", "key_rotations", "audit_events"]) {
    assert.match(migration, new RegExp(`CREATE TABLE ${table}\\b`, "u"));
  }
  for (const phrase of [
    "device_code_hash TEXT NOT NULL UNIQUE",
    "user_code_hash TEXT NOT NULL UNIQUE",
    "nonce_hash TEXT NOT NULL UNIQUE",
    "CREATE UNIQUE INDEX devices_one_active_key",
    "CREATE UNIQUE INDEX devices_rotation_id",
  ]) assert.ok(migration.includes(phrase), phrase);
  assert.equal(migration.includes("SARAH_BACKEND_TOKEN"), false);
  assert.equal(migration.includes("provider_api_key"), false);
});

test("foundation has no deployment descriptor or event-worker import", async () => {
  const root = new URL("../", import.meta.url);
  const names = await readdir(root);
  assert.equal(names.includes("wrangler.toml"), false);
  assert.equal(names.includes("wrangler.jsonc"), false);
  const packageData = JSON.parse(await readFile(new URL("../package.json", import.meta.url), "utf8"));
  assert.equal("deploy" in packageData.scripts, false);
});

test("threat fixtures all prohibit provider calls", async () => {
  const cases = JSON.parse(await readFile(new URL("../fixtures/threat-cases-v1.json", import.meta.url), "utf8"));
  assert.ok(cases.cases.length >= 8);
  assert.equal(cases.cases.every((entry) => entry.provider_call_allowed === false), true);
});
