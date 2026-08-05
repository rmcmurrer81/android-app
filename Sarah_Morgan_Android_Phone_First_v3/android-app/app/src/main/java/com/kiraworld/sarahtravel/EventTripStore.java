package com.kiraworld.sarahtravel;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Separate store for monitored events, event updates, and imported bookings. */
public final class EventTripStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "sarah_event_trips.db";
    private static final int DB_VERSION = 1;

    public EventTripStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE event_trips (id INTEGER PRIMARY KEY AUTOINCREMENT, event_key TEXT NOT NULL, event_name TEXT NOT NULL, destination TEXT NOT NULL, venue TEXT NOT NULL DEFAULT '', start_date TEXT NOT NULL DEFAULT '', end_date TEXT NOT NULL DEFAULT '', official_url TEXT NOT NULL DEFAULT '', monitor_status TEXT NOT NULL DEFAULT 'queued', updates_summary TEXT NOT NULL DEFAULT '', nearby_food TEXT NOT NULL DEFAULT '', nearby_places TEXT NOT NULL DEFAULT '', transport_notes TEXT NOT NULL DEFAULT '', source_note TEXT NOT NULL DEFAULT '', last_checked_at INTEGER NOT NULL DEFAULT 0, next_check_at INTEGER NOT NULL DEFAULT 0, active INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL, UNIQUE(event_key, destination))");
        db.execSQL("CREATE TABLE event_updates (id INTEGER PRIMARY KEY AUTOINCREMENT, event_trip_id INTEGER NOT NULL, update_key TEXT NOT NULL, category TEXT NOT NULL DEFAULT 'general', title TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', source_url TEXT NOT NULL DEFAULT '', published_at TEXT NOT NULL DEFAULT '', detected_at INTEGER NOT NULL, UNIQUE(event_trip_id, update_key))");
        db.execSQL("CREATE TABLE booking_imports (id INTEGER PRIMARY KEY AUTOINCREMENT, event_trip_id INTEGER, booking_type TEXT NOT NULL DEFAULT 'travel', provider TEXT NOT NULL DEFAULT 'Other', source_kind TEXT NOT NULL, source_url TEXT NOT NULL DEFAULT '', local_path TEXT NOT NULL DEFAULT '', raw_text TEXT NOT NULL DEFAULT '', extracted_summary TEXT NOT NULL DEFAULT '', confirmation_code TEXT NOT NULL DEFAULT '', start_date TEXT NOT NULL DEFAULT '', end_date TEXT NOT NULL DEFAULT '', address TEXT NOT NULL DEFAULT '', total REAL NOT NULL DEFAULT 0, currency TEXT NOT NULL DEFAULT 'USD', status TEXT NOT NULL DEFAULT 'pending_review', created_at INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    public long upsertEventTrip(String eventName, String destination, boolean monitor) {
        String cleanEvent = value(eventName).trim();
        String cleanDestination = value(destination).trim();
        if (cleanEvent.isEmpty() || cleanDestination.isEmpty()) return -1;
        ContentValues values = new ContentValues();
        values.put("event_key", eventKey(cleanEvent));
        values.put("event_name", cleanEvent);
        values.put("destination", cleanDestination);
        values.put("monitor_status", monitor ? "queued" : "saved");
        values.put("active", 1);
        values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(
                "event_trips", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id FROM event_trips WHERE event_key=? AND lower(destination)=lower(?) LIMIT 1",
                new String[]{eventKey(cleanEvent), cleanDestination})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }

    public void updateEventResearch(
            long id,
            String eventName,
            String destination,
            String venue,
            String startDate,
            String endDate,
            String officialUrl,
            String updatesSummary,
            String nearbyFood,
            String nearbyPlaces,
            String transportNotes,
            String sourceNote,
            long checkedAt,
            long nextCheckAt) {
        ContentValues values = new ContentValues();
        values.put("event_name", value(eventName));
        values.put("destination", value(destination));
        values.put("venue", value(venue));
        values.put("start_date", value(startDate));
        values.put("end_date", value(endDate));
        values.put("official_url", value(officialUrl));
        values.put("updates_summary", value(updatesSummary));
        values.put("nearby_food", value(nearbyFood));
        values.put("nearby_places", value(nearbyPlaces));
        values.put("transport_notes", value(transportNotes));
        values.put("source_note", value(sourceNote));
        values.put("monitor_status", "ready");
        values.put("last_checked_at", checkedAt);
        values.put("next_check_at", nextCheckAt);
        getWritableDatabase().update("event_trips", values, "id=?", new String[]{String.valueOf(id)});
    }

    public List<Map<String, String>> listActiveEventTrips(int limit) {
        return eventRows("WHERE active=1", limit);
    }

    public List<Map<String, String>> listDueEventTrips(int limit) {
        return eventRows("WHERE active=1 AND (next_check_at=0 OR next_check_at<=" + System.currentTimeMillis() + ")", limit);
    }

    private List<Map<String, String>> eventRows(String where, int limit) {
        List<Map<String, String>> rows = new ArrayList<>();
        String sql = "SELECT id,event_name,destination,venue,start_date,end_date,official_url,monitor_status,updates_summary,nearby_food,nearby_places,transport_notes,source_note,last_checked_at,next_check_at FROM event_trips "
                + where + " ORDER BY id DESC LIMIT ?";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(limit)})) {
            while (c.moveToNext()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", String.valueOf(c.getLong(0)));
                row.put("event_name", c.getString(1));
                row.put("destination", c.getString(2));
                row.put("venue", c.getString(3));
                row.put("start_date", c.getString(4));
                row.put("end_date", c.getString(5));
                row.put("official_url", c.getString(6));
                row.put("monitor_status", c.getString(7));
                row.put("updates_summary", c.getString(8));
                row.put("nearby_food", c.getString(9));
                row.put("nearby_places", c.getString(10));
                row.put("transport_notes", c.getString(11));
                row.put("source_note", c.getString(12));
                row.put("last_checked_at", String.valueOf(c.getLong(13)));
                row.put("next_check_at", String.valueOf(c.getLong(14)));
                rows.add(row);
            }
        }
        return rows;
    }

    public boolean addEventUpdate(
            long eventTripId,
            String updateKey,
            String category,
            String title,
            String detail,
            String sourceUrl,
            String publishedAt) {
        ContentValues values = new ContentValues();
        values.put("event_trip_id", eventTripId);
        values.put("update_key", value(updateKey));
        values.put("category", value(category));
        values.put("title", value(title));
        values.put("detail", value(detail));
        values.put("source_url", value(sourceUrl));
        values.put("published_at", value(publishedAt));
        values.put("detected_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict(
                "event_updates", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public long addBookingLink(String provider, String bookingType, String url, String rawText) {
        return addBooking(null, bookingType, provider, "link", url, "", rawText);
    }

    public long addBookingScreenshot(String localPath, String rawText) {
        return addBooking(null, "travel", "Unknown", "screenshot", "", localPath, rawText);
    }

    private long addBooking(
            Long eventTripId,
            String bookingType,
            String provider,
            String sourceKind,
            String sourceUrl,
            String localPath,
            String rawText) {
        ContentValues values = new ContentValues();
        if (eventTripId != null) values.put("event_trip_id", eventTripId);
        values.put("booking_type", value(bookingType));
        values.put("provider", value(provider));
        values.put("source_kind", value(sourceKind));
        values.put("source_url", value(sourceUrl));
        values.put("local_path", value(localPath));
        values.put("raw_text", value(rawText));
        values.put("status", "pending_review");
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("booking_imports", null, values);
    }

    public void updateBookingExtraction(
            long id,
            String bookingType,
            String provider,
            String summary,
            String confirmationCode,
            String startDate,
            String endDate,
            String address,
            double total,
            String currency,
            String status) {
        ContentValues values = new ContentValues();
        values.put("booking_type", value(bookingType));
        values.put("provider", value(provider));
        values.put("extracted_summary", value(summary));
        values.put("confirmation_code", value(confirmationCode));
        values.put("start_date", value(startDate));
        values.put("end_date", value(endDate));
        values.put("address", value(address));
        values.put("total", total);
        values.put("currency", value(currency).isEmpty() ? "USD" : currency.trim());
        values.put("status", value(status).isEmpty() ? "pending_review" : status);
        getWritableDatabase().update("booking_imports", values, "id=?", new String[]{String.valueOf(id)});
    }

    public List<Map<String, String>> listPendingBookings(int limit) {
        return bookingRows("WHERE status='pending_review'", limit);
    }

    public List<Map<String, String>> listBookings(int limit) {
        return bookingRows("", limit);
    }

    private List<Map<String, String>> bookingRows(String where, int limit) {
        List<Map<String, String>> rows = new ArrayList<>();
        String sql = "SELECT id,booking_type,provider,source_kind,source_url,local_path,raw_text,extracted_summary,confirmation_code,start_date,end_date,address,total,currency,status FROM booking_imports "
                + where + " ORDER BY id DESC LIMIT ?";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(limit)})) {
            while (c.moveToNext()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", String.valueOf(c.getLong(0)));
                row.put("booking_type", c.getString(1));
                row.put("provider", c.getString(2));
                row.put("source_kind", c.getString(3));
                row.put("source_url", c.getString(4));
                row.put("local_path", c.getString(5));
                row.put("raw_text", c.getString(6));
                row.put("extracted_summary", c.getString(7));
                row.put("confirmation_code", c.getString(8));
                row.put("start_date", c.getString(9));
                row.put("end_date", c.getString(10));
                row.put("address", c.getString(11));
                row.put("total", String.valueOf(c.getDouble(12)));
                row.put("currency", c.getString(13));
                row.put("status", c.getString(14));
                rows.add(row);
            }
        }
        return rows;
    }

    private static String eventKey(String eventName) {
        return value(eventName).toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    private static String value(String value) { return value == null ? "" : value; }
}
