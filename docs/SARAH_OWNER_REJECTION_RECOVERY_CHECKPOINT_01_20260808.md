# Sarah owner-rejection recovery checkpoint 01

Date boundary: 2026-08-07 America/New_York / 2026-08-08 UTC

This is an append-only checkpoint. It does not mark either app owner-accepted,
released, or ready for the hackathon.

## Owner evidence preserved

The physically installed Android build and the Windows build are both rejected.
The Android screenshots prove that:

- a phone with internet could still report `Online unavailable · offline mind ready`;
- the onboarding and later replies used the phone/offline voice rather than proving
  the approved ElevenLabs route;
- `I am travelling to new Zealand and the details is in my email` was incorrectly
  routed to a technology reply;
- the public PopCon lookup worked, but it was a narrow public-source tool result,
  not proof that the conversational online mind worked;
- no source-bound destination image appeared;
- the Travel Workbench and sponsor integrations were buried or disconnected.

The previously supplied Windows screenshots remain a separate owner rejection of
the legacy grey/tabbed/vector-avatar artifact.

Current classifications:

- `SARAH_ANDROID_OWNER_ACCEPTANCE_REJECTED_OFFLINE_WORKBENCH_MEDIA_REGRESSION`
- `SARAH_WINDOWS_OWNER_ACCEPTANCE_REJECTED_LEGACY_ARTIFACT_AND_MISSING_LIVE_ROUTES`

## Historical boundary

The last owner-described C-minus Android boundary is Git commit
`f52978be5138bbc4c25c623a8b56d13c8ecd9bfe` (`29b655b^`). The regression began
at `29b655b584cf10acce60af5cab0f0a55a17724e4` and continued at `6162da5`.
No wholesale revert or cherry-pick was used. The recovery is a forward port so
newer identity migration, lifecycle cancellation, safe insets, route truth,
Android-Keystore storage, voice receipts, location privacy, and secure-pairing
protections remain.

## Exact defects and bounded repairs

1. `DemoSarah` performed substring matching for the literal `ai`. Therefore
   `email`, `air`, and `train` could trigger the technology reply. The classifier
   now accepts `AI` only as a complete term and retains the longer technology
   phrases.
2. `DestinationParser` did not recognize `traveling to` / `travelling to` and did
   not contain the New Zealand/Aotearoa alias. Both forms are now supported.
3. The exact owner sentence is treated as a planned New Zealand trip. If it
   mentions email, Sarah states that she has not read the mailbox unless an exact
   read-only connection/import actually ran.
4. `ExploreButton`, `TravelHubButton`, `ProactiveDiscoveryButton`, and
   `TrustedSyncButton` were restored to the normal main layout.
5. `TravelHubActivity` no longer collapses every workbench section at startup.
   Its itinerary, hotels/Stay22, flights/Amtrak/bus, rides, experiences, road
   trips, events, offline flight companion, loyalty, accessibility, hotel stay,
   voice concierge, hotel operations, and explicit event-partner connection
   entries are visible.
6. The installed source-validation APK was offline by construction because it
   contained neither a protected backend URL/token pair nor a proven capability
   receipt. It must never again be described as an owner-test online APK.

## Verification at this checkpoint

- Android static package validation: `STATIC_PACKAGE_VALIDATION_PASS_R2`.
- Git diff whitespace check: pass (line-ending warnings only).
- Windows pytest suite: 100 passed in 6.52 seconds. An earlier invocation from
  the repository root failed test collection because the Windows module path was
  absent; rerunning from `windows-companion` was the correct test command.
- Full Android compile/APK and physical phone acceptance: not yet run.
- Protected online owner route: not yet physically accepted. The reusable bearer
  is intentionally not embedded in a public APK.
- Gmail read-only OAuth: implementation work exists on Windows but no real owner
  mailbox authorization/read/revocation has been accepted.
- Secure phone-to-Windows pairing: protocol work exists, but physical two-device
  acceptance is still pending.

## Stable repair hashes

- `DemoSarah.java`: `7993e572d70e0c6e9f0e0c729ae82c88ebafcc6d462c007dd3822f58bf94b581`
- `DestinationParser.java`: `9081de1f63205d5e7e8d5dc9e9d8b0bfb521ded015dbae82a1f6d62989467131`
- `AgenticTravelPlanner.java`: `965bea961ec16991a48ea8387c58d5b723cd0cf912a57f5ed3cefe2ef7bfb65f`
- `TravelPlanningConversationPolicy.java`: `ea6a83c3215f0ee69caa9bf4047bd51362e9534c3aa1530431758044b32d9ed8`
- `TravelHubActivity.java`: `d68e6c5f6af7017cdae14be106f8efcb6e45682bfe868e53a57d5c3b5c6b8c12`
- `Sarah25ConversationContextTest.java`: `7ffef23e2f535bd073c501ad53da565030068e9f1bd0ef619f8cf3aa5288e2ed`
- `validate_package.py`: `ead5768de0d634ef084b6ed8946b03e4683824a8fc5a84be5b47db404f2808bb`

## Rollback

All work is still uncommitted in the isolated PR audit worktree. Rollback is by
restoring only the listed changed files from Git commit
`e21b50a595a6aaa6bf34d2b14933acc6b3b23415`; do not reset the whole worktree,
because other bounded recovery files and owner evidence share it.
