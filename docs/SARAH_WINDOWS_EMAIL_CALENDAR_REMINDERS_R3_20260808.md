# Sarah Windows R3 - Gmail suggestions, owner calendar, and reminders

Status: `IMPLEMENTED_SOURCE_AND_AUTOMATED_TESTS_PASS_PHYSICAL_ACCEPTANCE_PENDING`

## Owner behavior

- A configured Windows build opens Google's browser consent from **Connect Gmail read-only**. It does not ask Robert for a developer file.
- Every Gmail result is first stored as a profile-isolated, encrypted, source-bound proposal.
- Sarah may ask, "I saw this in your email. Do you want me to remember it?"
- **Yes** opens a short title/date/place review before creating an item.
- **No** preserves an append-only rejection for that exact source item.
- **Cancel** leaves the proposal pending.
- A local reminder requires a separate owner choice and lead time.
- Calendar save, reminder delivery, attendance, journey completion, and personal memory remain separate truths.

## Source and privacy boundary

`windows-companion/sarah_calendar.py` binds proposals to the active person,
hashed Gmail account identity, opaque message/thread IDs, internal Gmail time,
sender, subject, date metadata, and a canonical SHA-256. Message bodies and
body-derived snippets are not requested or retained. The encrypted
payload is stored in Sarah's existing local database under the current device
crypto boundary. Rescanning an exact source does not duplicate it or reset an
earlier decision.

The Gmail connector remains read-only. It does not send, delete, label, mark
read, purchase, book, add a Google Calendar entry, or transmit a notification
to another device. Windows reminders are local notices while Sarah is running.

## Acceptance truth

Automated tests cover pending-only ingestion, stable source identity,
owner-confirmed creation, rejection history, profile isolation, explicit time
entry, opt-in reminders, and one-time reminder delivery. The packaged
self-test also exercises an encrypted proposal -> event -> reminder round trip.

Still pending:

- real Google Desktop-client repository variables;
- exact Google-configured owner-test EXE build and self-test (the separately
  labeled Gmail-unconfigured local engineering installer passed on
  2026-08-09 UTC; see `SARAH_WINDOWS_R3_REBUILT_SELF_TEST_20260809.md`);
- supervised Google consent and known-message read;
- visible local notification timing on Robert's Windows laptop;
- cross-device calendar synchronization (not implemented);
- Google Calendar cloud writes (not implemented or authorized).

Rollback: omit `sarah_calendar.py` and its UI bindings, restore the prior
workflow/build command, and preserve Gmail tokens/calendar evidence for owner
review rather than silently deleting them.
