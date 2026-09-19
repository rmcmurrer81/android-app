from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "android-app/app/src/main/java/com/kiraworld/sarahtravel"


def text(name: str) -> str:
    return (JAVA / name).read_text(encoding="utf-8")


def test_three_truths_and_explicit_owner_actions_are_separate():
    policy = text("EmailCalendarPolicy.java")
    vault = text("GmailTokenVault.java")
    assert "EMAIL_CANDIDATE_PENDING_OWNER_DECISION" in policy
    assert "OWNER_APPROVED_CALENDAR_ITEM" in policy
    assert "OWNER_SCHEDULED_LOCAL_REMINDER" in policy
    assert "explicitOwnerApproval" in policy
    assert "explicitOwnerRequest" in policy
    assert "decideCalendarCandidate" in vault
    assert "setOwnerCalendarTimes" in vault
    assert "setReminder" in vault
    assert "removeCalendarItem" in vault
    assert "disconnectGmailPreservingCalendar" in vault
    assert 'redacted.remove("bounded_snippet")' in vault


def test_gmail_remains_bounded_read_only_and_no_auto_save():
    client = text("GmailReadOnlyClient.java")
    gmail_policy = text("GmailReadOnlyPolicy.java")
    worker = text("GmailTravelMonitorWorker.java")
    assert 'setRequestMethod("GET")' in client
    assert "boundedSnippet" in client
    assert 'value.put("message_modified", false)' in client
    for forbidden in ("gmail.modify", "gmail.send", "mail.google.com/"):
        assert forbidden not in gmail_policy.split("SCOPE =", 1)[1].split(";", 1)[0]
    assert "decideCalendarCandidate" not in worker
    assert "setOwnerCalendarTimes" not in worker
    assert "setReminder(" not in worker
    assert "proposalNotificationPosted" in worker


def test_owner_calendar_and_local_reminder_are_connected():
    activity = text("TravelCalendarActivity.java")
    scheduler = text("TravelReminderScheduler.java")
    reminder = text("TravelReminderWorker.java")
    manifest = (ROOT / "android-app/app/src/main/AndroidManifest.xml").read_text(
        encoding="utf-8"
    )
    hub = text("TravelHubActivity.java")
    event_center = text("EventTripCenterActivity.java")
    assert "Yes, remember this" in activity
    assert "No, do not remember this" in activity
    assert "Remind me 1 day before" in activity
    assert "Remind me 1 hour before" in activity
    assert "Remove this saved calendar item" in activity
    assert "Add exact end or arrival time" in activity
    assert "calendar_end_instant" in activity
    assert "OneTimeWorkRequest" in scheduler
    assert "vault.setReminder" in scheduler
    assert "mayScheduleReminder" in text("GmailTokenVault.java")
    assert "REMINDER_SCHEDULED" in reminder
    assert '.TravelCalendarActivity' in manifest
    assert "Sarah's calendar and email proposals" in hub
    assert "Open Sarah's calendar and email proposals" in event_center


def test_ambiguous_dates_are_not_promoted_to_exact_times():
    policy = text("EmailCalendarPolicy.java")
    pure_test = (ROOT / "tests/EmailCalendarPolicyTest.java").read_text(encoding="utf-8")
    assert "LABELED_RFC3339" in policy
    assert "TIME_NOT_PRESENT_OR_AMBIGUOUS" in text("GmailReadOnlyClient.java")
    assert "ambiguous time must remain unknown" in pure_test
    assert "unlabelled/message date not event time" in pure_test


def test_one_exact_pending_email_item_can_surface_in_chat_without_background_voice():
    main = text("MainActivity.java")
    vault = text("GmailTokenVault.java")
    policy = text("EmailConversationPromptPolicy.java")
    speaker = text("SpeakerContext.java")
    surface = main.split("private void maybeSurfacePendingEmailPrompt()", 1)[1].split(
        "private boolean deferPendingEmailPrompt", 1
    )[0]
    defer = main.split("private boolean deferPendingEmailPrompt", 1)[1].split(
        "private boolean handlePendingEmailPromptAnswer", 1
    )[0]
    answer = main.split("private boolean handlePendingEmailPromptAnswer", 1)[1].split(
        "private void startTriviaGame", 1
    )[0]
    send = main.split("private void sendCurrent()", 1)[1].split(
        "private Map<String, String> currentProfile", 1
    )[0]

    assert "claimPendingConversationPrompt" in vault
    assert '"awaiting_owner_reply"' in vault
    assert "candidate = findReceipt(receipts, messageId)" in vault
    assert "ConfirmedOwnerLease.isExactActiveOwner(app, profileId)" in vault
    assert "conversation_prompt_newly_claimed" in vault
    assert "preserveOwnerTruth" in vault
    assert "private static final Object STATE_LOCK" in vault
    assert "synchronized (STATE_LOCK)" in vault
    assert "mainHandler.post(this::maybeSurfacePendingEmailPrompt)" in main
    assert "speakerContext.hasPendingQuestion()" in surface
    assert "!pendingLocationMessage.isEmpty()" in surface
    assert "pending != Pending.NONE" in speaker
    assert "I saw this possible trip or event in your connected email" in surface
    assert "persistedQuestion" in surface
    assert 'db.addMessage("assistant", persistedQuestion' in surface
    assert 'db.addMessage("assistant", question' not in surface
    persisted = surface.split("String persistedQuestion =", 1)[1].split(
        "lastTurnRoute =", 1
    )[0]
    assert "title" not in persisted
    assert "yes, no, or not now" in surface
    assert "speak(" not in surface
    assert "EmailConversationPromptPolicy.classify" in answer
    assert "pendingEmailPromptMessageId" in answer
    assert "decideCalendarCandidate" in answer
    assert "deferConversationPrompt" in defer
    assert "deferConversationPrompt" in answer
    assert "deferred_to_calendar_review" in vault
    assert "immediate next owner turn only" in answer
    assert "before a\n            // location request or speaker/profile switch" in send
    assert send.index("deferPendingEmailPrompt();") < send.index(
        "ensureApproximateAreaForTurn(text)"
    )
    assert "setReminder(" not in answer
    assert "TravelReminderScheduler" not in answer
    assert 'case "yes"' in policy and 'case "no"' in policy
    assert 'case "not now"' in policy and "return DEFER;" in policy
    assert "NOT_AN_ANSWER" in policy
