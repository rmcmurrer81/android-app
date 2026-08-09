-- Sarah full-version device authentication, Phase 1.
-- Isolated from the separate 72-hour event Worker and its global bearer.
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS owners (
  owner_id TEXT PRIMARY KEY NOT NULL,
  access_subject_hash TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL CHECK (status IN ('active', 'disabled')),
  created_at TEXT NOT NULL
) STRICT;

CREATE TABLE IF NOT EXISTS enrollments (
  enrollment_id TEXT PRIMARY KEY NOT NULL,
  device_code_hash TEXT NOT NULL UNIQUE,
  user_code_hash TEXT NOT NULL UNIQUE,
  challenge_hash TEXT NOT NULL UNIQUE,
  public_jwk TEXT NOT NULL,
  key_thumbprint TEXT NOT NULL,
  platform TEXT NOT NULL CHECK (platform IN ('android', 'windows')),
  display_name TEXT NOT NULL,
  app_id TEXT NOT NULL,
  app_version TEXT NOT NULL,
  state TEXT NOT NULL CHECK (
    state IN ('pending_owner', 'approved', 'consumed', 'denied', 'expired')
  ),
  owner_id TEXT REFERENCES owners(owner_id),
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  approved_at TEXT,
  consumed_at TEXT
) STRICT;

CREATE INDEX IF NOT EXISTS idx_enrollments_state_expires
  ON enrollments(state, expires_at);
CREATE INDEX IF NOT EXISTS idx_enrollments_owner
  ON enrollments(owner_id, created_at);

CREATE TABLE IF NOT EXISTS devices (
  device_id TEXT PRIMARY KEY NOT NULL,
  owner_id TEXT NOT NULL REFERENCES owners(owner_id),
  public_jwk TEXT NOT NULL,
  key_thumbprint TEXT NOT NULL,
  platform TEXT NOT NULL CHECK (platform IN ('android', 'windows')),
  display_name TEXT NOT NULL,
  app_id TEXT NOT NULL,
  key_version INTEGER NOT NULL CHECK (key_version >= 1),
  auth_epoch INTEGER NOT NULL CHECK (auth_epoch >= 1),
  status TEXT NOT NULL CHECK (status IN ('active', 'revoked')),
  lease_expires_at TEXT NOT NULL,
  created_at TEXT NOT NULL,
  last_seen_at TEXT NOT NULL,
  revoked_at TEXT,
  revoke_reason TEXT,
  last_rotation_id TEXT UNIQUE
) STRICT;

CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_active_key_thumbprint
  ON devices(key_thumbprint) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS idx_devices_owner_status
  ON devices(owner_id, status, created_at);

CREATE TABLE IF NOT EXISTS auth_challenges (
  challenge_id TEXT PRIMARY KEY NOT NULL,
  device_id TEXT NOT NULL REFERENCES devices(device_id),
  purpose TEXT NOT NULL CHECK (purpose IN ('session', 'self_revoke', 'key_rotation')),
  nonce_hash TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  consumed_at TEXT
) STRICT;

CREATE INDEX IF NOT EXISTS idx_auth_challenges_device
  ON auth_challenges(device_id, purpose, expires_at);

CREATE TABLE IF NOT EXISTS audit_events (
  event_id TEXT PRIMARY KEY NOT NULL,
  owner_id TEXT REFERENCES owners(owner_id),
  device_id TEXT REFERENCES devices(device_id),
  event_type TEXT NOT NULL,
  created_at TEXT NOT NULL,
  bounded_metadata_json TEXT NOT NULL CHECK (length(bounded_metadata_json) <= 2048)
) STRICT;

CREATE INDEX IF NOT EXISTS idx_audit_events_owner_time
  ON audit_events(owner_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_events_device_time
  ON audit_events(device_id, created_at);
