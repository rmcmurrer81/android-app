# Sarah Windows R3 rebuilt-package checkpoint

Status: `SOURCE_AND_LOCAL_PACKAGE_SELF_TEST_PASS_OWNER_ACCEPTANCE_PENDING`

On 2026-08-09 UTC, the Windows R3 candidate was rebuilt after the owner
calendar, profile isolation, Gmail proposal, reminder lease, wallet, pairing,
and synchronization changes.

Verified locally:

- Windows source tests: `146 passed`;
- `py sarah_event_ready.py --self-test`: `SARAH_EVENT_READY_SELF_TEST_OK`;
- exact one-file installer `--self-test`: exit code `0`;
- installer bytes: `66,314,784`;
- installer SHA-256:
  `3c95692afaab7807c7404db661cf5d96c3cde047756f5432565c03f7144c96f8`;
- bundled application bytes: `55,317,042`;
- bundled application SHA-256:
  `67be3cab261505933b63bdd215d6ba21bcfea4d98db23562a7d56806e12077d1`;
- both files remained byte-identical across the installed self-test;
- the installer has a valid Windows PE header.

The self-test exercises the exact approved portrait and continuous animation,
encrypted Gmail-token vault logic, pending email proposal, explicit owner
calendar save, separate reminder opt-in, encrypted wallet, two-device pairing,
and loopback synchronization contracts.

This local artifact deliberately has no Google Desktop OAuth identity because
none is configured on this computer. It is engineering evidence, not the
owner-test download. It must truthfully show that Gmail setup is unavailable
instead of opening a developer-file picker. The gated online-judge workflow
must receive and validate the repository's public Desktop-client variables,
bundle that identity, pass its packaged self-test, and produce a fresh exact
artifact before supervised Gmail acceptance.

No real Gmail account was opened, no message was read, no event was saved from
Robert's mailbox, no notification was physically observed, and no owner
acceptance is claimed by this checkpoint.

Rollback: retain the previous installer and source commits. Do not delete
calendar, Gmail, wallet, pairing, or prior artifact evidence merely to roll
back the executable selected for a physical test.
