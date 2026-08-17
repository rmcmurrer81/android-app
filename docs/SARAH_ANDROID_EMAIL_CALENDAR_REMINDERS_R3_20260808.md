# Sarah Android email proposals, owner calendar, and reminders — R3

Status: implemented in source and statically verified; device and signed-APK acceptance remain pending.

## Owner experience

- Gmail remains optional and uses only Google's `gmail.readonly` grant.
- A bounded check reads up to ten recent candidates using message metadata and a short Gmail preview. Sarah never requests the Gmail password and does not send, delete, modify, mark read, draft, purchase, or book anything.
- A newly observed candidate can produce a private local notification asking: “I saw this possible trip or event in your connected email. Do you want me to remember it?” A bounded check posts at most one proposal notification even if it records several new candidates, avoiding a notification burst.
- The Travel Workbench, Event Trip Center, and Gmail connection screen open **Sarah's calendar and email proposals**.
- Each candidate has explicit **Yes, remember this** and **No, do not remember this** actions.
- A saved item can retain a source-supported exact time or an exact time entered/corrected by the owner. Source-extracted and owner-entered time provenance remain distinct.
- The owner may separately request a local reminder one day or one hour before the saved start/departure. Removing a reminder does not remove the saved calendar item.
- The owner may remove a saved calendar item separately; this cancels its reminder but does not alter the Gmail message or erase the historical owner-decision fields from the encrypted record.

## Three separate truths

1. `EMAIL_CANDIDATE_PENDING_OWNER_DECISION` means Sarah observed a possible item. It is not memory, a calendar entry, attendance, or a booking.
2. `OWNER_APPROVED_CALENDAR_ITEM` exists only after the exact active phone owner presses Remember.
3. `OWNER_SCHEDULED_LOCAL_REMINDER` exists only after another per-item owner action and a future exact trigger time.

Repeated Gmail checks update source observation fields but preserve earlier owner decisions. Dismissed candidates do not reappear merely because the same Gmail message is seen again.

Disconnecting Gmail removes the access token, account binding, monitoring state, unsaved proposals, sender and preview details. It retains only the source-bound calendar items the owner separately chose to remember. A distinct full local-erasure method remains available for an explicit delete-all flow; disconnect is not treated as permission to erase the calendar.

## Date truth

The automated extractor accepts only labelled RFC3339 timestamps that include a real UTC offset. An unlabeled number, Gmail sent-date header, ambiguous phrase such as “August 12 at 3:30,” or contradictory end-before-start pair remains unknown. The owner may enter/correct a local date and time; it is stored as an exact UTC instant with `EXACT_TIME_ENTERED_BY_OWNER` provenance.

This first bounded implementation does not parse arbitrary airline HTML, PDF tickets, external attachments, or every natural-language date format. Those cases remain owner-reviewable proposals with “time not established” rather than fabricated dates.

## Reminder truth and limitations

Reminders use Android WorkManager one-time work, are local/profile-bound, require notification permission, and are opened into the exact proposal/calendar surface. Android battery optimization or device shutdown may deliver a reminder late; the UI says so. This is not an exact-alarm claim and does not add broad alarm privileges.

No Google Calendar cloud write/sync is implemented. The calendar is Sarah's encrypted local owner calendar, backed by the existing Android-Keystore Gmail envelope. Device acceptance must still prove Google OAuth certificate binding, notification permission, background delivery, reboot behavior, timezone behavior, and owner-visible wording.

## Verification

- Pure Java: `tests/EmailCalendarPolicyTest.java`
- Static contract: `tests/test_email_calendar_contract.py`
- Existing Gmail policy: `tests/GmailReadOnlyPolicyTest.java`

Gmail API basis: Google's official `users.messages.get` documentation defines the read-only GET route and the `gmail.readonly` scope, while the official Message resource identifies `snippet` as a short part of message text. The implementation requests `format=full` only with a partial-response field mask for IDs, timestamps, header metadata and that short preview; it does not request MIME bodies, raw messages or attachments.

- https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/get
- https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages

Suggested CI addition beside the existing Gmail policy compile/run step:

```text
javac .../EmailCalendarPolicy.java tests/EmailCalendarPolicyTest.java
java ... com.kiraworld.sarahtravel.EmailCalendarPolicyTest
python -m pytest -q tests/test_email_calendar_contract.py
```
