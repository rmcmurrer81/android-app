# Sarah email suggestions, calendar, and reminders — independent owner-requirement audit

Date: 2026-08-09

Source head before this audit: `202ec17d38f1e5e556b07ccc231c2f1de14bd2d1`

Status: `SOURCE_AND_OFFLINE_TEST_PARITY_PASS_EXTERNAL_OAUTH_ACCEPTANCE_PENDING`

## Exact bounded requirement

This audit checked Android and Windows for:

- optional Gmail setup after profile setup, with a later owner-facing connection control;
- bounded read-only discovery of likely travel and event messages;
- an exact-item question before Sarah remembers anything;
- owner-accepted events and flight/train/bus departure plus arrival in Sarah's local calendar;
- a separate per-item opt-in local reminder nearer the date;
- rejection or ignoring a suggestion creating no calendar item or reminder;
- no Gmail mutation, booking, ticket purchase, or attendance/completion claim.

No Gmail account, OAuth credential, access token, network, packaged build, or real notification was accessed during this audit.

## Android evidence

- `OnboardingActivity.java:209-218,253-260` offers Gmail only after the local profile, accepts yes/no/later, and points a later decision to Settings.
- `SettingsActivity.java:143-148,615-629` keeps the Gmail read-only management control available in Settings and truthfully shows disconnected/monitoring-off state.
- `GmailReadOnlyPolicy.java:17-20` uses a fixed one-year query containing travel, transport, ticket, event, concert, conference, convention, and festival terms while excluding spam/trash.
- `GmailReadOnlyClient.java:37-82` bounds the result count, performs GET-only list/get reads, and retains only IDs, timestamps, selected headers, and a bounded Gmail-generated preview. It derives candidate kind and only explicitly labelled, offset-bearing departure/start and arrival/end times.
- `GmailTokenVault.java:251-438` keeps pending email observation, a deduplicated foreground-conversation binding, defer state, owner calendar decision, owner-corrected times, and reminder request as separate transitions. A shared state lock serializes complete encrypted-state updates across UI, monitor, calendar, and reminder instances. A No decision leaves `CALENDAR_NOT_SAVED` and `REMINDER_NOT_SCHEDULED`.
- `MainActivity.java:355-370,577-713,783-843` resumes at most one exact pending candidate into the confirmed owner's visible conversation, does not call voice for that background finding, and applies only a conservative yes/no/later answer to the bound opaque message ID. It does not compete with an in-flight location turn. The exact subject stays in the ephemeral foreground bubble; the persisted conversation contains a source-redacted question. The binding owns only the immediate next owner turn: unrelated language is deferred before location or speaker routing, then continues through normal conversation while the proposal remains available for later Calendar review.
- `SpeakerContext.java:413-415` prevents the email question from competing with an already-pending identity, profile, consent, or trip question.
- `EmailConversationPromptPolicy.java` deliberately recognizes only bounded affirmative/rejection/defer phrases. It does not infer permission from a general conversational response, and **Not now** is not treated as permanent rejection.
- `TravelCalendarActivity.java:92-180` shows the exact source-bound item, provides **Yes, remember this** and **No, do not remember this**, exposes separate start/departure and end/arrival correction, and offers reminder/remove controls only for a saved calendar item.
- `TravelReminderScheduler.java:13-57` and `TravelReminderWorker.java:16-82` schedule and deliver only the exact explicitly requested local reminder. Android may defer delivery; this is not an exact-alarm claim.
- `GmailTravelMonitorWorker.java:75-90` can post one bounded proposal notice for new candidates but never approves a calendar item or schedules a reminder.

Android therefore meets the requested source behavior. Device behavior remains unaccepted until the exact signed APK completes Google authorization and physical notification tests.

## Windows evidence

- `sarah_event_ready.py:1948-1964` offers Gmail after the owner profile and leaves later setup available on the **Connections** page, which is the Windows owner settings/connection surface.
- `sarah_event_ready.py:1692-1704` exposes Connect, bounded manual check, independent automatic-monitoring control, Disconnect, and Calendar review without asking for a developer file in normal owner use.
- `sarah_gmail.py:25-32,494-536` uses a fixed travel/event query and Gmail list/get calls limited to metadata fields. It contains no Gmail send, modify, delete, label, draft, booking, or purchase path.
- `sarah_calendar.py:110-154,337-395` converts each exact account/message binding into only a `pending_owner_decision` proposal.
- `sarah_event_ready.py:1454-1550,1581-1600` asks about the exact suggestion. Yes requires a reviewed title and start/departure and, for flight/train/bus, separately reviewed arrival. No records rejection without creating an event; Cancel leaves the proposal pending.
- `sarah_calendar.py:418-510` creates an encrypted local event only after acceptance, preserves start/departure and end/arrival separately, rejects arrival before departure, and never claims attendance or journey completion.
- `sarah_calendar.py:533-584` and `sarah_event_ready.py:1507-1529,1552-1579,1610-1639` require a second explicit reminder choice and deliver the local notice once while Sarah is running.

Windows therefore meets the requested source behavior. The owner-visible page is named **Connections**, not **Settings**; it provides the requested later setup capability without adding another settings interface.

## Offline verification

Command environment:

```text
PYTHONDONTWRITEBYTECODE=1
PYTHONPATH=<repository>/windows-companion
py -B -m pytest -q -p no:cacheprovider \
  windows-companion/tests/test_sarah_calendar.py \
  windows-companion/tests/test_gmail_readonly.py \
  windows-companion/tests/test_event_ready_owner_surface.py \
  Sarah_Morgan_Android_Phone_First_v3/tests/test_email_calendar_contract.py
```

Result after the narrow conversation-parity and safety-hardening patch: `49 passed in 0.52s`.

The first repository-root invocation omitted the Windows module directory and failed collection with `ModuleNotFoundError`; adding the repository-local `windows-companion` directory to `PYTHONPATH` produced the passing result. This was a test invocation issue, not an application failure.

The focused tests cover pending-only ingestion, rejection without an event, stable exact-item identity, independent reminder opt-in, one-time delivery, impossible arrival rejection, profile isolation, bounded read-only Gmail behavior, Android owner actions, silent foreground-chat surfacing, exact-ID yes/no/later routing, one-turn deferral, prompt collision avoidance, source-redacted persisted chat, serialized vault updates, and Windows owner-surface wiring.

## Limits not promoted as acceptance

- Sarah's calendar is an encrypted local Sarah calendar. This patch does not add Google Calendar or Android system-calendar synchronization.
- Android's automatic time extraction remains intentionally narrow: it accepts only explicitly labelled RFC3339 start/departure and end/arrival instants. Natural-language dates, HTML itinerary bodies, PDFs, and attachments remain unknown until the owner enters or corrects the times.
- Android reminders still anchor to the start/departure and offer the existing one-day or one-hour choices. They are best-effort WorkManager notices, not exact alarms. Windows retains its existing owner-entered lead time.
- The foreground question is natural Sarah chat text, but it is not auto-spoken when the app resumes. A reply to the owner's explicit answer may use the normal foreground voice route.
- A No answer creates no calendar item, reminder, Gmail mutation, booking, or purchase. It intentionally preserves a local encrypted dismissal/audit state so the exact rejected message is not repeatedly proposed. **Not now** or an unrelated immediate next turn adds no decision and leaves the proposal pending for explicit review in Sarah's Calendar; the old prompt binding cannot capture a later unrelated yes/no.
- Static/offline tests do not replace signed-APK OAuth, Android instrumentation, physical notification, reboot/timezone, or owner-experience acceptance.

## Current external blockers

Android still requires all of the following outside this repository:

- a Google Cloud project with Gmail API enabled and an accurate OAuth consent screen;
- an Android OAuth client bound to package `com.kiraworld.sarahtravel` and the exact installed APK signing-certificate SHA-1;
- the physical test account listed as an OAuth test user while consent remains in testing;
- supervised acceptance on the exact signed APK, including the account shown, exact scope, known-message read, unchanged unread state, notification permission, background/deferred delivery, timezone, reboot, disconnect, and revocation evidence.

Windows still requires:

- the build-time `SARAH_GMAIL_DESKTOP_CLIENT_ID` and `SARAH_GMAIL_DESKTOP_CLIENT_SECRET` values from one Google Desktop-app registration so the build can package its public native-client identity;
- an allowed OAuth test user or completed consent/verification state as applicable;
- supervised system-browser/loopback-PKCE authorization, known-message metadata read, encrypted current-user token round trip, physical local notification, disconnect, and revocation evidence on the exact EXE.

When the platform-specific identity is absent, each platform must continue to fail closed. These are external configuration and physical acceptance blockers; no source patch can truthfully mark Gmail connected.

## Change and rollback truth

Windows implementation was audited without modification. Android received one narrow parity patch: encrypted exact-item prompt claiming/deduplication and cross-instance state serialization in `GmailTokenVault.java`, a conservative answer/defer classifier in `EmailConversationPromptPolicy.java`, existing-question detection in `SpeakerContext.java`, foreground text-only surfacing plus one-turn exact-ID answer handling and source-redacted history in `MainActivity.java`, and one focused static contract test. No Gmail, calendar-cloud, booking, purchase, background voice, or automatic reminder capability was added.

`GOOGLE_GMAIL_SETUP.md` was also corrected because its older metadata-only wording contradicted the already-tested Android bounded-preview implementation. The historical `docs/SARAH_ANDROID_GMAIL_READONLY_CHECKPOINT_20260808.md` remains unchanged as append-only evidence of the earlier metadata-only checkpoint; the later `docs/SARAH_ANDROID_EMAIL_CALENDAR_REMINDERS_R3_20260808.md` and current source supersede that old behavior description.

Hunk-level rollback is:

1. Remove only the `pendingEmailPrompt*`, `maybeSurfacePendingEmailPrompt`, `deferPendingEmailPrompt`, and `handlePendingEmailPromptAnswer` additions/call sites from `MainActivity.java`.
2. Remove only `claimPendingConversationPrompt`, `deferConversationPrompt`, their copy/state fields, and the shared state-lock wrappers from `GmailTokenVault.java`.
3. Remove only `hasPendingQuestion()` from `SpeakerContext.java`.
4. Remove `EmailConversationPromptPolicy.java` and its one static contract test.
5. Restore only the changed Android read/prompt paragraphs in `GOOGLE_GMAIL_SETUP.md` if the living-guide correction is rejected.

Do not overwrite whole source files or roll back the established Gmail/calendar/reminder implementation. Preserve this audit note as append-only evidence and mark it superseded rather than deleting it.
