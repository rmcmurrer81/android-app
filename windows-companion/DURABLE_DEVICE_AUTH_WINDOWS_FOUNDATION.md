# Durable Windows device-auth foundation

Status: **STAGED / NOT CONNECTED / NOT DEPLOYED / NOT OWNER-ACCEPTED**

This isolated source layer prepares a later full Sarah Windows client for
renewable per-device access without compiling or persisting an indefinitely
reusable bearer. It is not imported by the current Sarah UI, event executable,
installer, model route, voice route, build specification, or workflow.

The staging module creates a versioned P-256 signing key in the current user's
Microsoft Software Key Storage Provider. It explicitly sets export policy to
zero, enables signing only, omits the machine-key flag, exports only the CNG
public-key blob, and emits the protocol's 64-byte IEEE-P1363 signatures. It also
implements public-only JWK and RFC 7638 thumbprint derivation plus the exact
enrollment and session challenge payloads.

The caller must eventually supply durable device-binding truth from a separate
reviewed state store. If that truth says a device is already bound but its CNG
key is absent or unreadable, the result is
`KEY_MISSING_REENROLL_REQUIRED`. The module never silently generates a new key
under the old device ID. Fresh key creation accepts only the explicit
`UNENROLLED` state.

Not implemented or claimed here:

- enrollment UI, owner approval, or device-state persistence;
- Worker/D1 connection, capability calls, or provider calls;
- in-memory access-JWT management or renewal;
- rotation, revocation, recovery, offline retry, or migration;
- EXE inclusion, installer integration, or physical 8 GB laptop acceptance.

Focused tests use a uniquely named `.Test.` CNG key, confirm the export policy
and real signature interoperability, delete only that exact test key, then
prove a bound missing key remains missing. The cleanup API refuses non-test key
names. No production credential is created, deleted, or changed.
