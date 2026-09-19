# D1 migration boundary

These migrations belong only to a future, separately named Sarah full-auth D1
database. They have not been applied anywhere. The 72-hour event candidate has
no D1 binding and must not be pointed at this schema.

All code, user, device-code, and nonce values stored by the eventual Worker
must first be HMAC-SHA-256 hashed with a server-only pepper and a distinct
purpose label. `public_jwk` and `key_thumbprint` are public key material. No
access token, signature, private key, provider credential, Gmail credential,
prompt, reply, or media content belongs in D1.

Security transitions must use conditional single-statement updates and verify
the affected-row count. Reading a row and later writing it without a conditional
state predicate is not an acceptable implementation of single-use enrollment,
challenge consumption, revocation, or rotation.
