package com.kiraworld.sarahtravel;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Separate store for rail, air, bus, local-transit, driving, ferry, and mixed journeys. */
public final class MobilityWatchStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "sarah_mobility.db";
    private static final int DB_VERSION = 1;

    public MobilityWatchStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE journey_plans (id INTEGER PRIMARY KEY AUTOINCREMENT, origin TEXT NOT NULL, destination TEXT NOT NULL, event_name TEXT NOT NULL DEFAULT '', modes TEXT NOT NULL, notes TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'saved', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, UNIQUE(origin,destination,event_name,modes))");
        db.execSQL("CREATE TABLE mobility_watches (id INTEGER PRIMARY KEY AUTOINCREMENT, origin TEXT NOT NULL, destination TEXT NOT NULL, event_name TEXT NOT NULL DEFAULT '', modes TEXT NOT NULL, purpose TEXT NOT NULL DEFAULT 'options', active INTEGER NOT NULL DEFAULT 1, backend_status TEXT NOT NULL DEFAULT 'queued', last_checked_at INTEGER NOT NULL DEFAULT 0, last_summary TEXT NOT NULL DEFAULT '', last_source_note TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, UNIQUE(origin,destination,event_name,modes,purpose))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    public long saveJourneyPlan(
            String origin,
            String destination,
            String eventName,
            String modes,
            String notes) {
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("origin", value(origin, "Home area"));
        values.put("destination", value(destination, "Destination"));
        values.put("event_name", value(eventName, ""));
        values.put("modes", value(modes, "mixed"));
        values.put("notes", value(notes, ""));
        values.put("status", "saved");
        values.put("created_at", now);
        values.put("updated_at", now);
        getWritableDatabase().insertWithOnConflict(
                "journey_plans", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id FROM journey_plans WHERE lower(origin)=lower(?) AND lower(destination)=lower(?) AND lower(event_name)=lower(?) AND modes=? LIMIT 1",
                new String[]{value(origin, "Home area"), value(destination, "Destination"), value(eventName, ""), value(modes, "mixed")})) {
            return c.moveToFirst() ? c.getLong(0) : -1;
        }
    }

    public boolean createWatch(
            String origin,
            String destination,
            String eventName,
            String modes,
            String purpose) {
        ContentValues values = new ContentValues();
        values.put("origin", value(origin, "Home area"));
        values.put("destination", value(destination, "Destination"));
        values.put("event_name", value(eventName, ""));
        values.put("modes", value(modes, "air,rail,intercity_bus"));
        values.put("purpose", value(purpose, "options"));
        values.put("active", 1);
        values.put("backend_status", "queued");
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict(
                "mobility_watches", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public List<Map<String, String>> listJourneyPlans(int limit) {
        return plans("", limit);
    }

    public List<Map<String, String>> listActiveWatches(int limit) {
        return watches("WHERE active=1", limit);
    }

    public List<Map<String, String>> listWatches(int limit) {
        return watches("", limit);
    }

    public void updateWatch(
            long id,
            String status,
            long checkedAt,
            String summary,
            String sourceNote) {
        ContentValues values = new ContentValues();
        values.put("backend_status", value(status, "checked"));
        values.put("last_checked_at", checkedAt);
        values.put("last_summary", value(summary, ""));
        values.put("last_source_note", value(sourceNote, ""));
        getWritableDatabase().update("mobility_watches", values, "id=?", new String[]{String.valueOf(id)});
    }

    private List<Map<String, String>> plans(String where, int limit) {
        List<Map<String, String>> rows = new ArrayList<>();
        String sql = "SELECT id,origin,destination,event_name,modes,notes,status,created_at,updated_at FROM journey_plans "
                + where + " ORDER BY id DESC LIMIT ?";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(limit)})) {
            while (c.moveToNext()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", String.valueOf(c.getLong(0)));
                row.put("origin", c.getString(1));
                row.put("destination", c.getString(2));
                row.put("event_name", c.getString(3));
                row.put("modes", c.getString(4));
                row.put("notes", c.getString(5));
                row.put("status", c.getString(6));
                row.put("created_at", String.valueOf(c.getLong(7)));
                row.put("updated_at", String.valueOf(c.getLong(8)));
                rows.add(row);
            }
        }
        return rows;
    }

    private List<Map<String, String>> watches(String where, int limit) {
        List<Map<String, String>> rows = new ArrayList<>();
        String sql = "SELECT id,origin,destination,event_name,modes,purpose,active,backend_status,last_checked_at,last_summary,last_source_note FROM mobility_watches "
                + where + " ORDER BY id DESC LIMIT ?";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(limit)})) {
            while (c.moveToNext()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", String.valueOf(c.getLong(0)));
                row.put("origin", c.getString(1));
                row.put("destination", c.getString(2));
                row.put("event_name", c.getString(3));
                row.put("modes", c.getString(4));
                row.put("purpose", c.getString(5));
                row.put("active", String.valueOf(c.getInt(6)));
                row.put("backend_status", c.getString(7));
                row.put("last_checked_at", String.valueOf(c.getLong(8)));
                row.put("last_summary", c.getString(9));
                row.put("last_source_note", c.getString(10));
                rows.add(row);
            }
        }
        return rows;
    }

    private static String value(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
