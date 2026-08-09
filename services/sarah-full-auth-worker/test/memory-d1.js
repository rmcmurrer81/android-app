function cloneMap(map) {
  return new Map([...map.entries()].map(([key, value]) => [key, structuredClone(value)]));
}

class MemoryStatement {
  constructor(database, sql) {
    this.database = database;
    this.sql = sql;
    this.values = [];
  }

  bind(...values) {
    this.values = values;
    return this;
  }

  op() {
    return /\/\* op:([a-z_]+) \*\//.exec(this.sql)?.[1] || "";
  }

  async first() {
    return this.database.first(this.op(), this.values);
  }

  async all() {
    return { results: this.database.all(this.op(), this.values) };
  }

  async run() {
    return { meta: { changes: this.database.run(this.op(), this.values) } };
  }
}

export class MemoryD1 {
  constructor() {
    this.owners = new Map();
    this.enrollments = new Map();
    this.devices = new Map();
    this.challenges = new Map();
    this.rotations = new Map();
    this.audits = new Map();
    this.available = true;
  }

  prepare(sql) {
    if (!this.available) throw new Error("D1 unavailable");
    return new MemoryStatement(this, sql);
  }

  async batch(statements) {
    if (!this.available) throw new Error("D1 unavailable");
    const snapshot = {
      owners: cloneMap(this.owners),
      enrollments: cloneMap(this.enrollments),
      devices: cloneMap(this.devices),
      challenges: cloneMap(this.challenges),
      rotations: cloneMap(this.rotations),
      audits: cloneMap(this.audits),
    };
    try {
      const results = [];
      for (const statement of statements) results.push(await statement.run());
      return results;
    } catch (error) {
      Object.assign(this, snapshot);
      throw error;
    }
  }

  first(op, values) {
    if (!this.available) throw new Error("D1 unavailable");
    if (op === "get_owner_by_subject") {
      const row = [...this.owners.values()].find((candidate) => (
        candidate.access_subject_hash === values[0]
      ));
      return row ? structuredClone(row) : null;
    }
    if (op === "get_owner_by_id") {
      const row = this.owners.get(values[0]);
      return row ? structuredClone(row) : null;
    }
    if (op === "get_enrollment") {
      const row = this.enrollments.get(values[0]);
      return row ? structuredClone(row) : null;
    }
    if (op === "claim_enrollment_by_user_code") {
      const [ownerId, userCodeHash, nowIso, repeatedOwnerId] = values;
      const row = [...this.enrollments.values()].find((candidate) => (
        candidate.user_code_hash === userCodeHash
        && candidate.state === "pending_owner"
        && candidate.expires_at > nowIso
        && (!candidate.owner_id || candidate.owner_id === repeatedOwnerId)
      ));
      if (row) row.owner_id = ownerId;
      return row ? structuredClone(row) : null;
    }
    if (op === "get_device") {
      const row = this.devices.get(values[0]);
      return row ? structuredClone(row) : null;
    }
    if (op === "get_challenge") {
      const row = this.challenges.get(values[0]);
      return row ? structuredClone(row) : null;
    }
    throw new Error(`unsupported first operation: ${op}`);
  }

  all(op, values) {
    if (!this.available) throw new Error("D1 unavailable");
    if (op === "list_pending_enrollments") {
      return [...this.enrollments.values()]
        .filter((row) => row.owner_id === values[0]
          && row.state === "pending_owner" && row.expires_at > values[1])
        .sort((left, right) => left.created_at.localeCompare(right.created_at))
        .slice(0, 100)
        .map((row) => ({ ...row }));
    }
    if (op === "list_devices") {
      return [...this.devices.values()]
        .filter((row) => row.owner_id === values[0])
        .sort((left, right) => left.created_at.localeCompare(right.created_at))
        .slice(0, 200)
        .map((row) => ({ ...row }));
    }
    throw new Error(`unsupported all operation: ${op}`);
  }

  run(op, values) {
    if (!this.available) throw new Error("D1 unavailable");
    if (op === "insert_enrollment") {
      const [
        enrollment_id,
        device_code_hash,
        user_code_hash,
        challenge_hash,
        public_jwk,
        key_thumbprint,
        platform,
        display_name,
        app_id,
        app_version,
        created_at,
        expires_at,
      ] = values;
      if (this.enrollments.has(enrollment_id)
          || [...this.enrollments.values()].some((row) => row.device_code_hash === device_code_hash
            || row.user_code_hash === user_code_hash || row.challenge_hash === challenge_hash)) {
        throw new Error("UNIQUE constraint failed");
      }
      this.enrollments.set(enrollment_id, {
        enrollment_id,
        device_code_hash,
        user_code_hash,
        challenge_hash,
        public_jwk,
        key_thumbprint,
        platform,
        display_name,
        app_id,
        app_version,
        state: "pending_owner",
        owner_id: null,
        created_at,
        expires_at,
        approved_at: null,
        consumed_at: null,
        last_polled_at: null,
      });
      return 1;
    }
    if (op === "approve_enrollment") {
      const [at, enrollmentId, ownerId, nowIso] = values;
      const row = this.enrollments.get(enrollmentId);
      if (!row || row.owner_id !== ownerId || row.state !== "pending_owner"
          || row.expires_at <= nowIso) return 0;
      row.state = "approved";
      row.approved_at = at;
      return 1;
    }
    if (op === "deny_enrollment") {
      const [enrollmentId, ownerId, nowIso] = values;
      const row = this.enrollments.get(enrollmentId);
      if (!row || row.owner_id !== ownerId || row.state !== "pending_owner"
          || row.expires_at <= nowIso) return 0;
      row.state = "denied";
      return 1;
    }
    if (op === "claim_enrollment_poll") {
      const [nowIso, enrollmentId, expiresAfterIso, allowedBeforeIso] = values;
      const row = this.enrollments.get(enrollmentId);
      if (!row || !new Set(["pending_owner", "approved"]).has(row.state)
          || row.expires_at <= expiresAfterIso
          || (row.last_polled_at && row.last_polled_at > allowedBeforeIso)) return 0;
      row.last_polled_at = nowIso;
      return 1;
    }
    if (op === "expire_enrollment") {
      const [enrollmentId, nowIso] = values;
      const row = this.enrollments.get(enrollmentId);
      if (!row || !new Set(["pending_owner", "approved"]).has(row.state)
          || row.expires_at > nowIso) return 0;
      row.state = "expired";
      return 1;
    }
    if (op === "insert_device") {
      const [
        device_id,
        owner_id,
        public_jwk,
        key_thumbprint,
        platform,
        display_name,
        app_id,
        lease_expires_at,
        created_at,
        last_seen_at,
      ] = values;
      if (this.devices.has(device_id)
          || [...this.devices.values()].some((row) => row.status === "active"
            && row.key_thumbprint === key_thumbprint)) {
        throw new Error("UNIQUE constraint failed");
      }
      this.devices.set(device_id, {
        device_id,
        owner_id,
        public_jwk,
        key_thumbprint,
        platform,
        display_name,
        app_id,
        key_version: 1,
        auth_epoch: 1,
        status: "active",
        lease_expires_at,
        created_at,
        last_seen_at,
        revoked_at: null,
        revoke_reason: null,
        last_rotation_id: null,
      });
      return 1;
    }
    if (op === "consume_enrollment") {
      const [consumedAt, enrollmentId, nowIso] = values;
      const row = this.enrollments.get(enrollmentId);
      if (!row || row.state !== "approved" || row.expires_at <= nowIso) return 0;
      row.state = "consumed";
      row.consumed_at = consumedAt;
      return 1;
    }
    if (op === "insert_challenge") {
      const [challenge_id, device_id, purpose, nonce_hash, created_at, expires_at] = values;
      if (this.challenges.has(challenge_id)
          || [...this.challenges.values()].some((row) => row.nonce_hash === nonce_hash)) {
        throw new Error("UNIQUE constraint failed");
      }
      this.challenges.set(challenge_id, {
        challenge_id,
        device_id,
        purpose,
        nonce_hash,
        created_at,
        expires_at,
        consumed_at: null,
      });
      return 1;
    }
    if (op === "consume_challenge") {
      const [consumedAt, challengeId, deviceId, purpose, nowIso] = values;
      const row = this.challenges.get(challengeId);
      if (!row || row.device_id !== deviceId || row.purpose !== purpose
          || row.consumed_at || row.expires_at <= nowIso) return 0;
      row.consumed_at = consumedAt;
      return 1;
    }
    if (op === "renew_lease") {
      const [leaseExpiresAt, lastSeenAt, deviceId, keyVersion, authEpoch] = values;
      const row = this.devices.get(deviceId);
      if (!row || row.status !== "active" || row.key_version !== keyVersion
          || row.auth_epoch !== authEpoch) return 0;
      row.lease_expires_at = leaseExpiresAt;
      row.last_seen_at = lastSeenAt;
      return 1;
    }
    if (op === "revoke_device") {
      const [revokedAt, reason, deviceId, ownerId, expectedEpoch] = values;
      const row = this.devices.get(deviceId);
      if (!row || row.owner_id !== ownerId || row.status !== "active"
          || row.auth_epoch !== expectedEpoch) return 0;
      row.status = "revoked";
      row.revoked_at = revokedAt;
      row.revoke_reason = reason;
      row.auth_epoch += 1;
      return 1;
    }
    if (op === "rotate_device") {
      const [
        publicJwk,
        thumbprint,
        rotationId,
        lastSeenAt,
        deviceId,
        ownerId,
        keyVersion,
        authEpoch,
        oldThumbprint,
      ] = values;
      const row = this.devices.get(deviceId);
      if (!row || row.owner_id !== ownerId || row.status !== "active"
          || row.key_version !== keyVersion || row.auth_epoch !== authEpoch
          || row.key_thumbprint !== oldThumbprint) return 0;
      if ([...this.devices.values()].some((candidate) => candidate.device_id !== deviceId
          && candidate.status === "active" && candidate.key_thumbprint === thumbprint)) {
        throw new Error("UNIQUE constraint failed");
      }
      if ([...this.devices.values()].some((candidate) => candidate.last_rotation_id === rotationId)) {
        throw new Error("UNIQUE constraint failed");
      }
      row.public_jwk = publicJwk;
      row.key_thumbprint = thumbprint;
      row.key_version += 1;
      row.auth_epoch += 1;
      row.last_rotation_id = rotationId;
      row.last_seen_at = lastSeenAt;
      return 1;
    }
    if (op === "insert_key_rotation") {
      const [
        rotationId,
        oldKeyVersion,
        newKeyVersion,
        oldThumbprint,
        newThumbprint,
        createdAt,
        completedAt,
        deviceId,
        ownerId,
        expectedRotationId,
        expectedKeyVersion,
        expectedThumbprint,
      ] = values;
      const device = this.devices.get(deviceId);
      if (!device || device.owner_id !== ownerId
          || device.last_rotation_id !== expectedRotationId
          || device.key_version !== expectedKeyVersion
          || device.key_thumbprint !== expectedThumbprint) return 0;
      if (this.rotations.has(rotationId)
          || [...this.rotations.values()].some((row) => (
            (row.device_id === deviceId && row.new_key_version === newKeyVersion)
            || row.new_thumbprint === newThumbprint
          ))) throw new Error("UNIQUE constraint failed");
      this.rotations.set(rotationId, {
        rotation_id: rotationId,
        device_id: deviceId,
        old_key_version: oldKeyVersion,
        new_key_version: newKeyVersion,
        old_thumbprint: oldThumbprint,
        new_thumbprint: newThumbprint,
        created_at: createdAt,
        completed_at: completedAt,
      });
      return 1;
    }
    if (op === "audit_event") {
      const [event_id, owner_id, device_id, event_type, created_at, bounded_metadata_json] = values;
      if (this.audits.has(event_id)) throw new Error("UNIQUE constraint failed");
      this.audits.set(event_id, {
        event_id,
        owner_id,
        device_id,
        event_type,
        created_at,
        bounded_metadata_json,
      });
      return 1;
    }
    if (op === "cleanup_enrollments") {
      let count = 0;
      for (const [key, row] of this.enrollments) {
        if (row.expires_at < values[0]
            && new Set(["denied", "expired", "consumed"]).has(row.state)) {
          this.enrollments.delete(key);
          count += 1;
        }
      }
      return count;
    }
    if (op === "cleanup_challenges") {
      let count = 0;
      for (const [key, row] of this.challenges) {
        if (row.expires_at < values[0]) {
          this.challenges.delete(key);
          count += 1;
        }
      }
      return count;
    }
    if (op === "cleanup_audit") {
      let count = 0;
      for (const [key, row] of this.audits) {
        if (row.created_at < values[0]) {
          this.audits.delete(key);
          count += 1;
        }
      }
      return count;
    }
    throw new Error(`unsupported run operation: ${op}`);
  }
}
