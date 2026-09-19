# Sarah full device authentication foundation

Status: **PHASE 1 FOUNDATION ONLY — NOT PRODUCTION-READY, NOT DEPLOYED, NOT OWNER-ACCEPTED**

This directory is an isolated implementation foundation for Sarah's durable
device authentication protocol. It deliberately has no `wrangler` deployment
configuration, no Worker entry-point router, no provider integration, and no
client credential. It does not import, change, extend, or retire the separate
72-hour event-candidate Worker.

Implemented here:

- versioned request/response JSON schemas;
- a public-only full-artifact configuration schema and bearer-rejection
  contract;
- deterministic public P-256 interoperability fixtures for Worker/Node,
  Windows/Python, and Android/Java verification;
- exact enrollment and renewable-auth signature payload construction;
- RFC 7638 public-JWK thumbprints, domain-separated HMAC hashing, and strict
  IEEE-P1363/ASN.1-DER conversion helpers;
- pure enrollment, challenge, device-lease, access-token, and rotation state
  helpers; and
- an isolated first D1 migration plus focused tests.

Not implemented here:

- a deployed Worker or D1 database;
- Cloudflare Access assertion validation or owner portal;
- enrollment, session, provider, search, or voice HTTP routes;
- production JWT signing or key rotation;
- Android Keystore or Windows DPAPI client integration;
- artifact construction, physical-device testing, 73-hour soak, or owner
  acceptance.

The fixture signatures are test evidence only. The fixture contains no private
key and cannot authenticate to any Sarah service.

## Focused verification

From this directory:

```powershell
npm.cmd test
py test/protocol_vectors_python_test.py
javac -d build/test-java test/ProtocolVectorVerifier.java
java -cp build/test-java ProtocolVectorVerifier fixtures/p256-v1.json
```

The Java verifier intentionally uses only JDK classes. The Python verifier
uses `cryptography` and independently converts the 64-byte wire signature into
DER before verification. All three language paths read the same checked-in
fixture.

## Security boundary

Full artifacts may bundle only the public configuration described by
`schema/full-artifact-config-v1.schema.json`. They must not contain an event
bearer, backend token, provider key, refresh bearer, JWT signing key, private
device key, Gmail token, or another reusable authorization secret. The helper
in `src/artifact_contract.js` is early contract scaffolding; final APK/DEX/EXE
binary scans remain a later mandatory release gate.
