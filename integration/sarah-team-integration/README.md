# Sarah Team Integration Source

Version: `1.0.0-draft`

This package is an additive, transport-neutral and UI-neutral integration
boundary for teams that want to place Sarah's current capabilities inside a
different application shell. It is not a replacement Sarah app, a backend,
an APK, an EXE, or a claim that every host feature is already connected.

The package intentionally contains no owner data, access bearer, provider key,
service endpoint, build credential, email credential, device enrollment
material, or event-only capability. A host application supplies its own
renewable authenticated gateways at runtime. Credentials remain inside the
host boundary and never appear in a Sarah request, result, log, backup, or
integration archive.

## What is included

- canonical operation and truth contracts for conversation, online/offline
  routing, approximate current area, current sources, profiles, trips,
  calendar, wallet, Travel Workbench navigation, and protected voice;
- Java 8-compatible neutral interfaces and data types;
- a current native Android adapter and a secret-free wiring example;
- mappings to the existing Sarah Android implementation;
- setup, migration, error/fallback, security, and licensing notes;
- a deterministic source-package builder and tests that inspect both source
  and the generated ZIP for credential-shaped or owner-data artifacts.

## Integration shape

```text
new UI / host application
        |
        v
SarahIntegration (neutral contract)
        |
        +-- current Android adapter --> existing Sarah native stores/routes
        |
        +-- host gateways -----------> renewable auth stays inside host
```

The host owns networking and authenticated session renewal. Sarah receives
only typed results and route receipts. The voice gateway accepts public spoken
text plus an approved, non-secret voice-profile identifier; it never accepts
an ElevenLabs provider credential.

## TemporaryAI Creator and expert-AI handoff

Robert's desktop TemporaryAI Creator prepares expert-person candidates for
private owner review. A creation request records an expert role, bounded
knowledge purpose, identity/profile manifest, and candidate status. Creation
does not activate, assign, publish, or represent the candidate as accepted.
Owner review and any later activation remain separate explicit stages.

Body and voice work are independent handoffs. The Creator may queue a body
request and an approved-voice request for the candidate, while retaining their
actual pending, failed, or completed state. A body or voice queue receipt is
not evidence that an accepted body or voice exists. A team-built app can read
the resulting owner-approved profile through the neutral profile and domain
interfaces in this package, then use the conversation, current-source, access,
and protected-voice contracts without importing desktop credentials or owner
data.

Current implementation truth:

- private, inactive expert-candidate records and their role/knowledge/profile
  manifests are the intended Creator boundary;
- there is no instant accepted body;
- the Qwen3-TTS voice forge remains rejected and under repair, and no model run
  is included or authorized by this package;
- no candidate is auto-activated;
- no body or voice is described as completed until its separate evidence and
  owner acceptance exist.

## Quick verification

From the repository root:

```text
python -B -m unittest discover -s integration/sarah-team-integration/tests -v
python -B integration/sarah-team-integration/tools/build_package.py
```

Compile the neutral Java sources and example with any Java 8+ compiler. The
package workflow performs this check before uploading the deterministic source
artifact.

Generated files:

```text
dist/Sarah-Team-Integration-Source.zip
dist/Sarah-Team-Integration-Source.zip.sha256
dist/Sarah-Team-Integration-Source.manifest.json
```

The ZIP is reproducible: files are sorted, text line endings are normalized,
timestamps and permissions are fixed, and generated output is excluded from
its own inputs.

## Non-negotiable truth boundaries

- `ONLINE_FAILED_FELL_BACK_OFFLINE` is not presented as an online answer.
- A current-source claim requires an applied source receipt.
- A location denial or missing area must produce an explicit unavailable
  result; the adapter never guesses the owner's location.
- Text completion never waits for voice completion.
- Protected voice reports its actual route or a truthful failure; a host must
  not relabel another voice as ElevenLabs.
- Opening Workbench, a trip, calendar, or wallet view does not claim an action,
  purchase, booking, reminder, or background monitor occurred.
- Person/profile scope is mandatory on stateful calls.

Read `SETUP.md`, `MIGRATION.md`, `SECURITY.md`, and
`android/CURRENT_NATIVE_MAPPING.md` before connecting a host.
