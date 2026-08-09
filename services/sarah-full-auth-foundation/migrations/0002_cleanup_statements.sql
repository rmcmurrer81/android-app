-- Scheduled cleanup statements for the isolated full-auth D1 database.
-- Bind each cutoff as a parameter in the future scheduled Worker. Never copy
-- content, tokens, codes, signatures, private keys, or provider data into the
-- audit table.

DELETE FROM auth_challenges
WHERE expires_at < ?1 AND consumed_at IS NOT NULL;

DELETE FROM auth_challenges
WHERE expires_at < ?2 AND consumed_at IS NULL;

DELETE FROM enrollments
WHERE state IN ('denied', 'expired', 'consumed') AND expires_at < ?3;

DELETE FROM audit_events
WHERE created_at < ?4;
