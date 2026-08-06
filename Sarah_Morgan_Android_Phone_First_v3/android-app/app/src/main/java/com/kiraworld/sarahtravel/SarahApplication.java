package com.kiraworld.sarahtravel;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;

/** App-level initialization and narrowly scoped repair of known prototype mistakes. */
public final class SarahApplication extends Application {
    private static final String REPAIR_PREFS = "sarah_repairs";
    private static final String BELL_EVENT_REPAIR = "bell_county_event_repair_v1";
    private static final String INDY_POPCON_REPAIR = "indy_popcon_event_repair_v1";
    private static SarahApplication instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        repairEventMisclassification(
                BELL_EVENT_REPAIR,
                "Bell County Comic Con",
                "Belton, Texas",
                new String[]{"bell country comic con", "bell county comic con"});
        repairEventMisclassification(
                INDY_POPCON_REPAIR,
                "PopCon Indy",
                "Indianapolis, Indiana",
                new String[]{"indy pop con", "indy popcon", "popcon indy", "indianapolis popcon"});
    }

    public static Context appContext() {
        return instance == null ? null : instance.getApplicationContext();
    }

    private void repairEventMisclassification(
            String marker,
            String eventName,
            String destination,
            String[] mistakenDestinations) {
        SharedPreferences repairs = getSharedPreferences(REPAIR_PREFS, MODE_PRIVATE);
        if (repairs.getBoolean(marker, false)) return;

        boolean foundBadRecord = false;
        SarahDatabase db = new SarahDatabase(this);
        try {
            SQLiteDatabase writable = db.getWritableDatabase();
            for (String value : mistakenDestinations) {
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
            try { db.close(); } catch (Exception ignoredAgain) { }
            return;
        } finally {
            try { db.close(); } catch (Exception ignored) { }
        }

        if (foundBadRecord) {
            EventTripStore eventStore = new EventTripStore(this);
            try {
                eventStore.upsertEventTrip(eventName, destination, true);
            } finally {
                eventStore.close();
            }
            EventMonitorScheduler.ensureScheduled(this);
            EventMonitorScheduler.runSoon(this);
        }
        repairs.edit().putBoolean(marker, true).apply();
    }
}
