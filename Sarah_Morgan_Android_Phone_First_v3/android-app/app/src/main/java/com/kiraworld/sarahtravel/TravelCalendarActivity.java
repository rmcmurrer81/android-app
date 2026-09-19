package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Calendar;

/** Owner review and local calendar/reminder surface for Gmail-derived proposals. */
public final class TravelCalendarActivity extends Activity {
    public static final String EXTRA_MESSAGE_ID = "review_gmail_message_id";
    private static final int REQUEST_NOTIFICATIONS = 945;

    private GmailTokenVault vault;
    private String profileId = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        vault = new GmailTokenVault(this);
        profileId = EventTripStore.activePersonId(this);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (vault != null) render();
    }

    private void render() {
        LinearLayout root = TravelUi.page(this);
        root.addView(TravelUi.hero(
                this,
                "Sarah owner calendar",
                "Trips, tickets and events",
                "Email findings are proposals. Only you can remember an item, enter or accept its exact time, and schedule a local notification."));
        if (profileId.isEmpty() || !ConfirmedOwnerLease.isExactActiveOwner(this, profileId)) {
            LinearLayout card = TravelUi.card(this, TravelUi.PEACH);
            card.addView(TravelUi.cardTitle(this, "🔒", "Owner confirmation required"));
            card.addView(TravelUi.body(this,
                    "Return to Sarah and confirm the active phone owner before reviewing connected-email proposals."));
            root.addView(card);
            return;
        }

        JSONArray receipts = vault.receipts(profileId);
        int shown = 0;
        String requested = getIntent() == null ? ""
                : value(getIntent().getStringExtra(EXTRA_MESSAGE_ID));
        for (int pass = 0; pass < 2; pass++) {
            for (int index = receipts.length() - 1; index >= 0; index--) {
                JSONObject item = receipts.optJSONObject(index);
                if (item == null) continue;
                String messageId = value(item.optString("message_id", ""));
                if (messageId.isEmpty()) continue;
                boolean requestedItem = !requested.isEmpty() && requested.equals(messageId);
                if ((pass == 0) != requestedItem) continue;
                String emailState = item.optString(
                        "email_candidate_state", EmailCalendarPolicy.EMAIL_PENDING);
                if (EmailCalendarPolicy.EMAIL_DISMISSED.equals(emailState)) continue;
                if (EmailCalendarPolicy.CALENDAR_REMOVED.equals(
                        item.optString("calendar_item_state", ""))) continue;
                root.addView(candidateCard(item));
                shown++;
            }
        }
        if (shown == 0) {
            LinearLayout card = TravelUi.card(this, TravelUi.SKY);
            card.addView(TravelUi.cardTitle(this, "📅", "No proposals or saved items yet"));
            card.addView(TravelUi.body(this,
                    "After you optionally connect Gmail read-only, Sarah can show bounded travel/event candidates here. Nothing is saved to this calendar automatically."));
            root.addView(card);
        }
    }

    private LinearLayout candidateCard(JSONObject item) {
        String messageId = value(item.optString("message_id", ""));
        String subject = value(item.optString("subject", "(no subject)"));
        String from = value(item.optString("sender", ""));
        String snippet = value(item.optString("bounded_snippet", ""));
        if (snippet.length() > 280) snippet = snippet.substring(0, 280) + "…";
        String kind = value(item.optString("candidate_kind", "travel"));
        String emailState = item.optString(
                "email_candidate_state", EmailCalendarPolicy.EMAIL_PENDING);
        String calendarState = item.optString(
                "calendar_item_state", EmailCalendarPolicy.CALENDAR_NOT_SAVED);
        String reminderState = item.optString(
                "reminder_state", EmailCalendarPolicy.REMINDER_NOT_SCHEDULED);
        String start = EmailCalendarPolicy.normalizedInstant(
                item.optString("calendar_start_instant", ""));
        String end = EmailCalendarPolicy.normalizedInstant(
                item.optString("calendar_end_instant", ""));
        String timeSource = value(item.optString(
                "calendar_time_source", "TIME_NOT_PRESENT_OR_AMBIGUOUS"));

        LinearLayout card = TravelUi.card(this,
                EmailCalendarPolicy.CALENDAR_SAVED.equals(calendarState)
                        ? TravelUi.MINT : TravelUi.SKY);
        card.addView(TravelUi.cardTitle(this, "📨", subject));
        String truth = "Possible " + kind + " from connected Gmail"
                + (from.isEmpty() ? "" : "\nFrom: " + from)
                + (snippet.isEmpty() ? "" : "\nEmail preview: " + snippet)
                + "\nSource receipt: Gmail message " + messageId
                + "\nEmail candidate: " + emailState
                + "\nSaved calendar item: " + calendarState
                + "\nReminder: " + reminderState
                + (start.isEmpty() ? "\nStart/departure: not established"
                        : "\nStart/departure: " + localLabel(start))
                + (end.isEmpty() ? "\nEnd/arrival: not established"
                        : "\nEnd/arrival: " + localLabel(end))
                + "\nTime provenance: " + timeSource
                + "\nNo email was modified and no purchase was made.";
        card.addView(TravelUi.body(this, truth));

        if (EmailCalendarPolicy.EMAIL_PENDING.equals(emailState)) {
            card.addView(TravelUi.primaryButton(this,
                    "Yes, remember this",
                    v -> {
                        boolean saved = vault.decideCalendarCandidate(
                                profileId, messageId, true, System.currentTimeMillis());
                        toast(saved ? "Saved to Sarah's owner calendar." : "The item was not saved.");
                        render();
                    }));
            card.addView(TravelUi.outlineButton(this,
                    "No, do not remember this",
                    v -> {
                        TravelReminderScheduler.cancel(this, profileId, messageId);
                        boolean dismissed = vault.decideCalendarCandidate(
                                profileId, messageId, false, System.currentTimeMillis());
                        toast(dismissed ? "Dismissed. The Gmail message was unchanged."
                                : "The proposal was not changed.");
                        render();
                    }));
            return card;
        }

        if (EmailCalendarPolicy.CALENDAR_SAVED.equals(calendarState)) {
            card.addView(TravelUi.outlineButton(this,
                    start.isEmpty() ? "Add exact start or departure time"
                            : "Correct start or departure time",
                    v -> pickCalendarTime(messageId, false)));
            if (!start.isEmpty()) {
                card.addView(TravelUi.outlineButton(this,
                        end.isEmpty() ? "Add exact end or arrival time"
                                : "Correct end or arrival time",
                        v -> pickCalendarTime(messageId, true)));
                long anchor = Instant.parse(start).toEpochMilli();
                card.addView(TravelUi.primaryButton(this,
                        "Remind me 1 day before",
                        v -> schedule(messageId, anchor, 24L * 60L * 60L * 1000L)));
                card.addView(TravelUi.outlineButton(this,
                        "Remind me 1 hour before",
                        v -> schedule(messageId, anchor, 60L * 60L * 1000L)));
            }
            if (EmailCalendarPolicy.REMINDER_SCHEDULED.equals(reminderState)) {
                card.addView(TravelUi.outlineButton(this,
                        "Cancel this reminder",
                        v -> {
                            TravelReminderScheduler.cancel(this, profileId, messageId);
                            toast("This reminder was cancelled; the calendar item remains saved.");
                            render();
                        }));
            }
            card.addView(TravelUi.outlineButton(this,
                    "Remove this saved calendar item",
                    v -> {
                        TravelReminderScheduler.cancel(this, profileId, messageId);
                        boolean removed = vault.removeCalendarItem(
                                profileId, messageId, System.currentTimeMillis());
                        toast(removed ? "Removed from Sarah's calendar. The Gmail message was unchanged."
                                : "The calendar item was not removed.");
                        render();
                    }));
        }
        return card;
    }

    private void pickCalendarTime(String messageId, boolean endTime) {
        JSONObject item = vault.receipt(profileId, messageId);
        if (item == null) return;
        String existing = EmailCalendarPolicy.normalizedInstant(item.optString(
                endTime ? "calendar_end_instant" : "calendar_start_instant", ""));
        ZonedDateTime initial = existing.isEmpty()
                ? ZonedDateTime.now().plusDays(1)
                : Instant.parse(existing).atZone(ZoneId.systemDefault());
        DatePickerDialog date = new DatePickerDialog(
                this,
                (picker, year, month, day) -> new TimePickerDialog(
                        this,
                        (clock, hour, minute) -> {
                            String chosen = LocalDateTime.of(
                                    year, month + 1, day, hour, minute)
                                    .atZone(ZoneId.systemDefault()).toInstant().toString();
                            JSONObject current = vault.receipt(profileId, messageId);
                            if (current == null) return;
                            String start = EmailCalendarPolicy.normalizedInstant(
                                    current.optString("calendar_start_instant", ""));
                            String end = EmailCalendarPolicy.normalizedInstant(
                                    current.optString("calendar_end_instant", ""));
                            if (endTime) end = chosen; else start = chosen;
                            boolean saved = vault.setOwnerCalendarTimes(
                                    profileId, messageId, start, end, System.currentTimeMillis());
                            toast(saved
                                    ? "Exact owner-entered time saved. Any old reminder was cleared."
                                    : "That time could not be saved. The end/arrival cannot precede the start/departure.");
                            render();
                        },
                        initial.getHour(),
                        initial.getMinute(),
                        false).show(),
                initial.getYear(),
                initial.getMonthValue() - 1,
                initial.getDayOfMonth());
        date.show();
    }

    private void schedule(String messageId, long anchor, long lead) {
        if (!DealNotificationManager.canNotify(this)) {
            DealNotificationManager.requestPermissionIfNeeded(this, REQUEST_NOTIFICATIONS);
            toast("Allow notifications, then choose the reminder again.");
            return;
        }
        boolean scheduled = TravelReminderScheduler.schedule(
                this, profileId, messageId, anchor, lead);
        toast(scheduled
                ? "Local reminder scheduled. Android may deliver it late if the phone defers background work."
                : "The reminder was not scheduled. Its trigger may already have passed.");
        render();
    }

    private String localLabel(String instant) {
        try { return Instant.parse(instant).atZone(ZoneId.systemDefault()).toString(); }
        catch (Exception ignored) { return instant; }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private static String value(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
    }
}
