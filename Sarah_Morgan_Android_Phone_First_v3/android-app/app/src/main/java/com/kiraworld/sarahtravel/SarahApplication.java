package com.kiraworld.sarahtravel;

import android.app.Application;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;

/** App-level initialization and narrowly scoped repair of known prototype mistakes. */
public final class SarahApplication extends Application {
    private static final String REPAIR_PREFS = "sarah_repairs";
    private static final String BELL_EVENT_REPAIR = "bell_county_event_repair_v1";

    @Override
    public void onCreate() {
        super.onCreate();
        repairBellCountyComicConMisclassification();
    }

    private void repairBellCountyComicConMisclassification() {
        SharedPreferences repairs = getSharedPreferences(REPAIR_PREFS, MODE_PRIVATE);
        if (repairs.getBoolean(BELL_EVENT_REPAIR, false)) return;

        boolean foundBadRecord = false;
        SarahDatabase db = new SarahDatabase(this);
        try {
            SQLiteDatabase writable = db.getWritableDatabase();
            String[] mistaken = {"bell country comic con", "bell county comic con"};
            for (String value : mistaken) {
                foundBadRecord |= writable.delete(
                        "wish_list", "lower(destination)=?", new String[]{value}) > 0;
                foundBadRecord |= writable.delete(
                        "destination_knowledge", "lower(destination)=?", new String[]{value}) > 0;
                foundBadRecord |= writable.delete(
                        "deal_watches", "lower(destination)=?", new String[]{value}) > 0;
                writable.delete(
                        "memories",
                        "lower(summary) LIKE ? OR lower(source_text) LIKE ?",
                        new String[]{"%" + value + "%", "%" + value + "%"});
            }
        } catch (Exception ignored) {
            // A later launch may retry if the repair marker is not written.
            db.close();
            return;
        } finally {
            try { db.close(); } catch (Exception ignored) { }
        }

        if (foundBadRecord) {
            EventTripStore eventStore = new EventTripStore(this);
            try {
                eventStore.upsertEventTrip("Bell County Comic Con", "Belton, Texas", true);
            } finally {
                eventStore.close();
            }
            EventMonitorScheduler.ensureScheduled(this);
            EventMonitorScheduler.runSoon(this);
        }
        repairs.edit().putBoolean(BELL_EVENT_REPAIR, true).apply();
    }
}
