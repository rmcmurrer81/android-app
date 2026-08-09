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
