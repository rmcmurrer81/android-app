-- Sarah durable device authentication v1.
-- ISOLATED FOUNDATION ONLY: do not apply to the 72-hour event Worker/database.
PRAGMA foreign_keys = ON;

CREATE TABLE owners (
  owner_id TEXT PRIMARY KEY NOT NULL,
  access_subject_hash TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL CHECK (status IN ('active', 'disabled')),
  created_at TEXT NOT NULL
);

CREATE TABLE enrollments (
  enrollment_id TEXT PRIMARY KEY NOT NULL,
  device_code_hash TEXT NOT NULL UNIQUE,
  user_code_hash TEXT NOT NULL UNIQUE,
  challenge_hash TEXT NOT NULL UNIQUE,
  public_jwk TEXT NOT NULL,
  key_thumbprint TEXT NOT NULL,
  platform TEXT NOT NULL CHECK (platform IN ('android', 'windows')),
  display_name TEXT NOT NULL CHECK (length(display_name) BETWEEN 1 AND 80),
  app_id TEXT NOT NULL,
  app_version TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('pending_owner', 'approved', 'consumed', 'denied', 'expired')),
  owner_id TEXT REFERENCES owners(owner_id),
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  approved_at TEXT,
  consumed_at TEXT,
  last_polled_at TEXT,
  CHECK (expires_at > created_at),
  CHECK ((state <> 'approved') OR (owner_id IS NOT NULL AND approved_at IS NOT NULL)),
  CHECK ((state <> 'consumed') OR (owner_id IS NOT NULL AND approved_at IS NOT NULL AND consumed_at IS NOT NULL)),
  CHECK ((state <> 'denied') OR owner_id IS NOT NULL),
  CHECK (last_polled_at IS NULL OR last_polled_at >= created_at)
);

CREATE UNIQUE INDEX enrollments_one_live_key
  ON enrollments(key_thumbprint)
  WHERE state IN ('pending_owner', 'approved');
CREATE INDEX enrollments_expiry ON enrollments(state, expires_at);
CREATE INDEX enrollments_owner_state ON enrollments(owner_id, state, created_at);

CREATE TABLE devices (
  device_id TEXT PRIMARY KEY NOT NULL,
  owner_id TEXT NOT NULL REFERENCES owners(owner_id),
  public_jwk TEXT NOT NULL,
  key_thumbprint TEXT NOT NULL,
  platform TEXT NOT NULL CHECK (platform IN ('android', 'windows')),
  display_name TEXT NOT NULL CHECK (length(display_name) BETWEEN 1 AND 80),
  app_id TEXT NOT NULL,
  key_version INTEGER NOT NULL DEFAULT 1 CHECK (key_version >= 1),
  auth_epoch INTEGER NOT NULL DEFAULT 1 CHECK (auth_epoch >= 1),
  status TEXT NOT NULL CHECK (status IN ('active', 'revoked')),
  lease_expires_at TEXT NOT NULL,
  created_at TEXT NOT NULL,
  last_seen_at TEXT NOT NULL,
  revoked_at TEXT,
  revoke_reason TEXT CHECK (revoke_reason IS NULL OR length(revoke_reason) <= 160),
  last_rotation_id TEXT,
  CHECK ((status <> 'revoked') OR revoked_at IS NOT NULL)
);

CREATE UNIQUE INDEX devices_one_active_key
  ON devices(key_thumbprint)
  WHERE status = 'active';
CREATE UNIQUE INDEX devices_rotation_id
  ON devices(last_rotation_id)
  WHERE last_rotation_id IS NOT NULL;
CREATE INDEX devices_owner_state ON devices(owner_id, status);
CREATE INDEX devices_lease_expiry ON devices(status, lease_expires_at);

CREATE TABLE auth_challenges (
  challenge_id TEXT PRIMARY KEY NOT NULL,
  device_id TEXT NOT NULL REFERENCES devices(device_id),
  purpose TEXT NOT NULL CHECK (purpose IN ('access_token', 'key_rotation', 'self_revoke')),
  nonce_hash TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  consumed_at TEXT,
  CHECK (expires_at > created_at)
);

CREATE INDEX auth_challenges_device_state
  ON auth_challenges(device_id, purpose, consumed_at, expires_at);

CREATE TABLE key_rotations (
  rotation_id TEXT PRIMARY KEY NOT NULL,
  device_id TEXT NOT NULL REFERENCES devices(device_id),
  old_key_version INTEGER NOT NULL CHECK (old_key_version >= 1),
  new_key_version INTEGER NOT NULL CHECK (new_key_version = old_key_version + 1),
  old_thumbprint TEXT NOT NULL,
  new_thumbprint TEXT NOT NULL,
  created_at TEXT NOT NULL,
  completed_at TEXT NOT NULL,
  UNIQUE(device_id, new_key_version),
  UNIQUE(new_thumbprint)
);

CREATE TABLE audit_events (
  event_id TEXT PRIMARY KEY NOT NULL,
  owner_id TEXT REFERENCES owners(owner_id),
  device_id TEXT REFERENCES devices(device_id),
  event_type TEXT NOT NULL,
  created_at TEXT NOT NULL,
  bounded_metadata_json TEXT NOT NULL DEFAULT '{}'
    CHECK (length(bounded_metadata_json) <= 4096)
);

CREATE INDEX audit_events_owner_time ON audit_events(owner_id, created_at);
CREATE INDEX audit_events_device_time ON audit_events(device_id, created_at);
