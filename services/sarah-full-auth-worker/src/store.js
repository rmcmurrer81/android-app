import { HttpError } from "./protocol.js";

function changes(result) {
  const value = Number(result?.meta?.changes ?? result?.changes ?? 0);
  return Number.isFinite(value) ? value : 0;
}

export class AuthStore {
  constructor(binding) {
    if (!binding || typeof binding.prepare !== "function") {
      throw new HttpError(503, "auth_store_unavailable");
    }
    this.binding = binding;
  }

  statement(sql, values = []) {
    try {
      return this.binding.prepare(sql).bind(...values);
    } catch {
      throw new HttpError(503, "auth_store_unavailable");
    }
  }

  async first(sql, values = []) {
    try {
      return await this.statement(sql, values).first();
    } catch (error) {
      if (error instanceof HttpError) throw error;
      throw new HttpError(503, "auth_store_unavailable");
    }
  }

  async all(sql, values = []) {
    try {
      const result = await this.statement(sql, values).all();
      return Array.isArray(result) ? result : (result?.results || []);
    } catch (error) {
      if (error instanceof HttpError) throw error;
      throw new HttpError(503, "auth_store_unavailable");
    }
  }

  async run(sql, values = []) {
    try {
      const result = await this.statement(sql, values).run();
      return { raw: result, changes: changes(result) };
    } catch (error) {
      if (error instanceof HttpError) throw error;
      throw new HttpError(503, "auth_store_unavailable");
    }
  }

  async batch(statements) {
    if (typeof this.binding.batch !== "function") {
      throw new HttpError(503, "auth_store_unavailable");
    }
    try {
      const prepared = statements.map(({ sql, values = [] }) => this.statement(sql, values));
      const result = await this.binding.batch(prepared);
      return result.map((entry) => ({ raw: entry, changes: changes(entry) }));
    } catch (error) {
      if (error instanceof HttpError) throw error;
      throw new HttpError(503, "auth_store_unavailable");
    }
  }

  async getOwnerBySubjectHash(subjectHash) {
    return this.first(
      `/* op:get_owner_by_subject */
       SELECT owner_id, status FROM owners WHERE access_subject_hash = ? LIMIT 1`,
      [subjectHash],
    );
  }

  async getOwnerById(ownerId) {
    return this.first(
      `/* op:get_owner_by_id */
       SELECT owner_id, status FROM owners WHERE owner_id = ? LIMIT 1`,
      [ownerId],
    );
  }

  async insertEnrollment(row) {
    return this.run(
      `/* op:insert_enrollment */
       INSERT INTO enrollments (
         enrollment_id, device_code_hash, user_code_hash, challenge_hash,
         public_jwk, key_thumbprint, platform, display_name, app_id, app_version,
         state, owner_id, created_at, expires_at, approved_at, consumed_at, last_polled_at
       ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending_owner', NULL, ?, ?, NULL, NULL, NULL)`,
      [
        row.enrollment_id,
        row.device_code_hash,
        row.user_code_hash,
        row.challenge_hash,
        row.public_jwk,
        row.key_thumbprint,
        row.platform,
        row.display_name,
        row.app_id,
        row.app_version,
        row.created_at,
        row.expires_at,
      ],
    );
  }

  async getEnrollment(enrollmentId) {
    return this.first(
      `/* op:get_enrollment */ SELECT * FROM enrollments WHERE enrollment_id = ? LIMIT 1`,
      [enrollmentId],
    );
  }

  async claimEnrollmentByUserCodeHash(userCodeHash, ownerId, nowIso) {
    return this.first(
      `/* op:claim_enrollment_by_user_code */
       UPDATE enrollments SET owner_id = ?
       WHERE user_code_hash = ? AND state = 'pending_owner' AND expires_at > ?
         AND (owner_id IS NULL OR owner_id = ?)
       RETURNING enrollment_id, key_thumbprint, platform, display_name, app_id,
                 app_version, state, owner_id, created_at, expires_at`,
      [ownerId, userCodeHash, nowIso, ownerId],
    );
  }

  async listPendingEnrollments(ownerId, nowIso) {
    return this.all(
      `/* op:list_pending_enrollments */
       SELECT enrollment_id, key_thumbprint, platform, display_name, app_id,
              app_version, state, created_at, expires_at
       FROM enrollments
       WHERE owner_id = ? AND state = 'pending_owner' AND expires_at > ?
       ORDER BY created_at ASC LIMIT 100`,
      [ownerId, nowIso],
    );
  }

  async approveEnrollment(enrollmentId, ownerId, nowIso) {
    return this.run(
      `/* op:approve_enrollment */
       UPDATE enrollments
       SET state = 'approved', approved_at = ?
       WHERE enrollment_id = ? AND owner_id = ?
         AND state = 'pending_owner' AND expires_at > ?`,
      [nowIso, enrollmentId, ownerId, nowIso],
    );
  }

  async denyEnrollment(enrollmentId, ownerId, nowIso) {
    return this.run(
      `/* op:deny_enrollment */
       UPDATE enrollments
       SET state = 'denied'
       WHERE enrollment_id = ? AND owner_id = ?
         AND state = 'pending_owner' AND expires_at > ?`,
      [enrollmentId, ownerId, nowIso],
    );
  }

  async claimEnrollmentPoll(enrollmentId, nowIso, allowedBeforeIso) {
    return this.run(
      `/* op:claim_enrollment_poll */
       UPDATE enrollments SET last_polled_at = ?
       WHERE enrollment_id = ? AND state IN ('pending_owner', 'approved')
         AND expires_at > ?
         AND (last_polled_at IS NULL OR last_polled_at <= ?)`,
      [nowIso, enrollmentId, nowIso, allowedBeforeIso],
    );
  }

  async markEnrollmentExpired(enrollmentId, nowIso) {
    return this.run(
      `/* op:expire_enrollment */
       UPDATE enrollments SET state = 'expired'
       WHERE enrollment_id = ? AND state IN ('pending_owner', 'approved') AND expires_at <= ?`,
      [enrollmentId, nowIso],
    );
  }

  async consumeEnrollmentAndCreateDevice(enrollment, device, nowIso) {
    return this.batch([
      {
        sql: `/* op:insert_device */
          INSERT INTO devices (
            device_id, owner_id, public_jwk, key_thumbprint, platform, display_name,
            app_id, key_version, auth_epoch, status, lease_expires_at, created_at,
            last_seen_at, revoked_at, revoke_reason, last_rotation_id
          ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1, 'active', ?, ?, ?, NULL, NULL, NULL)`,
        values: [
          device.device_id,
          enrollment.owner_id,
          enrollment.public_jwk,
          enrollment.key_thumbprint,
          enrollment.platform,
          enrollment.display_name,
          enrollment.app_id,
          device.lease_expires_at,
          nowIso,
          nowIso,
        ],
      },
      {
        sql: `/* op:consume_enrollment */
          UPDATE enrollments SET state = 'consumed', consumed_at = ?
          WHERE enrollment_id = ? AND state = 'approved' AND expires_at > ?`,
        values: [nowIso, enrollment.enrollment_id, nowIso],
      },
      {
        sql: `/* op:audit_event */
          INSERT INTO audit_events (
            event_id, owner_id, device_id, event_type, created_at, bounded_metadata_json
          ) VALUES (?, ?, ?, ?, ?, ?)`,
        values: [
          device.audit_event_id,
          enrollment.owner_id,
          device.device_id,
          "device_enrolled",
          nowIso,
          JSON.stringify({ platform: enrollment.platform, app_id: enrollment.app_id }),
        ],
      },
    ]);
  }

  async getDevice(deviceId) {
    return this.first(
      `/* op:get_device */ SELECT * FROM devices WHERE device_id = ? LIMIT 1`,
      [deviceId],
    );
  }

  async listDevices(ownerId) {
    return this.all(
      `/* op:list_devices */
       SELECT device_id, key_thumbprint, platform, display_name, app_id, key_version,
              auth_epoch, status, lease_expires_at, created_at, last_seen_at,
              revoked_at, revoke_reason
       FROM devices WHERE owner_id = ? ORDER BY created_at ASC LIMIT 200`,
      [ownerId],
    );
  }

  async insertChallenge(row) {
    return this.run(
      `/* op:insert_challenge */
       INSERT INTO auth_challenges (
         challenge_id, device_id, purpose, nonce_hash, created_at, expires_at, consumed_at
       ) VALUES (?, ?, ?, ?, ?, ?, NULL)`,
      [
        row.challenge_id,
        row.device_id,
        row.purpose,
        row.nonce_hash,
        row.created_at,
        row.expires_at,
      ],
    );
  }

  async getChallenge(challengeId) {
    return this.first(
      `/* op:get_challenge */ SELECT * FROM auth_challenges WHERE challenge_id = ? LIMIT 1`,
      [challengeId],
    );
  }

  async consumeChallenge(challengeId, deviceId, purpose, nowIso) {
    return this.run(
      `/* op:consume_challenge */
       UPDATE auth_challenges SET consumed_at = ?
       WHERE challenge_id = ? AND device_id = ? AND purpose = ?
         AND consumed_at IS NULL AND expires_at > ?`,
      [nowIso, challengeId, deviceId, purpose, nowIso],
    );
  }

  async renewLease(device, nowIso, leaseExpiresIso) {
    return this.run(
      `/* op:renew_lease */
       UPDATE devices SET lease_expires_at = ?, last_seen_at = ?
       WHERE device_id = ? AND status = 'active' AND key_version = ? AND auth_epoch = ?`,
      [leaseExpiresIso, nowIso, device.device_id, device.key_version, device.auth_epoch],
    );
  }

  async revokeDevice({ deviceId, ownerId, expectedEpoch, nowIso, reason }) {
    return this.run(
      `/* op:revoke_device */
       UPDATE devices
       SET status = 'revoked', revoked_at = ?, revoke_reason = ?, auth_epoch = auth_epoch + 1
       WHERE device_id = ? AND owner_id = ? AND status = 'active' AND auth_epoch = ?`,
      [nowIso, reason, deviceId, ownerId, expectedEpoch],
    );
  }

  async rotateDeviceKey({
    deviceId,
    ownerId,
    expectedKeyVersion,
    expectedAuthEpoch,
    newPublicJwk,
    newThumbprint,
    rotationId,
    oldThumbprint,
    nowIso,
  }) {
    const results = await this.batch([
      {
        sql: `/* op:rotate_device */
          UPDATE devices
          SET public_jwk = ?, key_thumbprint = ?, key_version = key_version + 1,
              auth_epoch = auth_epoch + 1, last_rotation_id = ?, last_seen_at = ?
          WHERE device_id = ? AND owner_id = ? AND status = 'active'
            AND key_version = ? AND auth_epoch = ? AND key_thumbprint = ?`,
        values: [
          newPublicJwk,
          newThumbprint,
          rotationId,
          nowIso,
          deviceId,
          ownerId,
          expectedKeyVersion,
          expectedAuthEpoch,
          oldThumbprint,
        ],
      },
      {
        sql: `/* op:insert_key_rotation */
          INSERT INTO key_rotations (
            rotation_id, device_id, old_key_version, new_key_version,
            old_thumbprint, new_thumbprint, created_at, completed_at
          )
          SELECT ?, device_id, ?, ?, ?, ?, ?, ? FROM devices
          WHERE device_id = ? AND owner_id = ? AND last_rotation_id = ?
            AND key_version = ? AND key_thumbprint = ?`,
        values: [
          rotationId,
          expectedKeyVersion,
          expectedKeyVersion + 1,
          oldThumbprint,
          newThumbprint,
          nowIso,
          nowIso,
          deviceId,
          ownerId,
          rotationId,
          expectedKeyVersion + 1,
          newThumbprint,
        ],
      },
    ]);
    return {
      changes: results[0]?.changes === 1 && results[1]?.changes === 1 ? 1 : 0,
      results,
    };
  }

  async insertAudit({ eventId, ownerId, deviceId, eventType, nowIso, metadata = {} }) {
    const bounded = JSON.stringify(metadata);
    if (bounded.length > 4096) throw new HttpError(500, "audit_metadata_too_large");
    return this.run(
      `/* op:audit_event */
       INSERT INTO audit_events (
         event_id, owner_id, device_id, event_type, created_at, bounded_metadata_json
       ) VALUES (?, ?, ?, ?, ?, ?)`,
      [eventId, ownerId || null, deviceId || null, eventType, nowIso, bounded],
    );
  }

  async cleanup(nowIso, auditBeforeIso) {
    return this.batch([
      {
        sql: `/* op:cleanup_enrollments */
          DELETE FROM enrollments
          WHERE expires_at < ? AND state IN ('denied', 'expired', 'consumed')`,
        values: [nowIso],
      },
      {
        sql: `/* op:cleanup_challenges */
          DELETE FROM auth_challenges WHERE expires_at < ?`,
        values: [nowIso],
      },
      {
        sql: `/* op:cleanup_audit */
          DELETE FROM audit_events WHERE created_at < ?`,
        values: [auditBeforeIso],
      },
    ]);
  }
}

export function requireAuthStore(env) {
  const binding = env.AUTH_DB;
  if (!binding || typeof binding.prepare !== "function") {
    throw new HttpError(503, "auth_store_unavailable");
  }
  // Security-state reads must not be served from a lagging replica. D1's
  // first-primary session routes the first read to the primary and preserves
  // sequential consistency for the rest of this request.
  if (typeof binding.withSession === "function") {
    try {
      return new AuthStore(binding.withSession("first-primary"));
    } catch {
      throw new HttpError(503, "auth_store_unavailable");
    }
  }
  return new AuthStore(binding);
}
