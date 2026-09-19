# Setup

1. Copy this package into the host repository without copying Sarah runtime
   databases, preferences, caches, media, credentials, or build artifacts.
2. Compile `src/main/java` with Java 8 or newer, or translate the interfaces
   directly from `contracts/sarah-team-contract-v1.json` for another language.
3. Implement `HostServices.RenewableAccessBoundary`. It reports state and
   renews access, but does not return a bearer or endpoint to Sarah code.
4. Implement the conversation, current-source, and protected-voice gateways.
   Keep transport, retries, authentication, and credential storage in the host.
5. Bind profile, trip, calendar, wallet, and Workbench ports. Every stateful
   request must retain its `personScopeId`.
6. For the existing Android app, implement
   `CurrentSarahAndroidAdapter.NativePorts` using the mappings in
   `android/CURRENT_NATIVE_MAPPING.md`.
7. Run the unit tests, Java compile, deterministic build twice, hash comparison,
   and secret scan before distributing an artifact.

## Host runtime requirements

- Generate a unique request ID per operation.
- Keep timestamps in UTC epoch milliseconds at the integration boundary.
- Treat approximate area as short-lived and profile-scoped.
- Keep source receipts attached to the exact turn or search result.
- Preserve a typed error code, retryability, and human-readable factual truth.
- Renew host authentication before expiry. If renewal fails, return
  `AUTH_UNAVAILABLE` and allow the local/offline route to remain usable.
- Deliver text first. Voice is a separate cancellable handoff.

## Deliberately absent

There is no default service address, bearer, provider key, voice key, Gmail
identity, device key, owner profile, or sample booking. The package cannot make
a network call until a host supplies an implementation.
