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

public final class SarahDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "sarah.db";
    private static final int DB_VERSION = 7;

    public SarahDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE profile (id INTEGER PRIMARY KEY CHECK(id=1), name TEXT NOT NULL, hometown TEXT NOT NULL, age INTEGER NOT NULL DEFAULT 18, first_flight INTEGER NOT NULL DEFAULT 0, interests TEXT NOT NULL DEFAULT '', worries TEXT NOT NULL DEFAULT '', memory_consent INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL, summary TEXT NOT NULL, source_text TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, UNIQUE(category, summary))");
        db.execSQL("CREATE TABLE trips (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, destination TEXT NOT NULL, status TEXT NOT NULL, notes TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE wish_list (id INTEGER PRIMARY KEY AUTOINCREMENT, destination TEXT NOT NULL UNIQUE, notes TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE photos (id INTEGER PRIMARY KEY AUTOINCREMENT, local_path TEXT NOT NULL, caption TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)");
        createAgenticTables(db);
    }

    private static void createAgenticTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS destination_knowledge (id INTEGER PRIMARY KEY AUTOINCREMENT, destination TEXT NOT NULL UNIQUE, status TEXT NOT NULL DEFAULT 'pending', overview TEXT NOT NULL DEFAULT '', recommendations TEXT NOT NULL DEFAULT '', transport TEXT NOT NULL DEFAULT '', accessibility TEXT NOT NULL DEFAULT '', seasonal TEXT NOT NULL DEFAULT '', events TEXT NOT NULL DEFAULT '', source_note TEXT NOT NULL DEFAULT '', refreshed_at INTEGER NOT NULL DEFAULT 0, expires_at INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS deal_watches (id INTEGER PRIMARY KEY AUTOINCREMENT, origin TEXT NOT NULL, destination TEXT NOT NULL, trip_type TEXT NOT NULL DEFAULT 'round_trip', travelers INTEGER NOT NULL DEFAULT 1, bag_mode TEXT NOT NULL DEFAULT 'carry_on', flexible_dates INTEGER NOT NULL DEFAULT 1, nearby_airports INTEGER NOT NULL DEFAULT 1, min_trip_days INTEGER NOT NULL DEFAULT 3, max_trip_days INTEGER NOT NULL DEFAULT 14, horizon_days INTEGER NOT NULL DEFAULT 365, active INTEGER NOT NULL DEFAULT 1, backend_status TEXT NOT NULL DEFAULT 'queued', last_checked_at INTEGER NOT NULL DEFAULT 0, last_notified_price REAL NOT NULL DEFAULT 0, currency TEXT NOT NULL DEFAULT 'USD', created_at INTEGER NOT NULL, UNIQUE(origin, destination))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE profile ADD COLUMN memory_consent INTEGER NOT NULL DEFAULT 1"); } catch (Exception ignored) { }
        }
        if (oldVersion < 3) {
            try { db.execSQL("ALTER TABLE profile ADD COLUMN age INTEGER NOT NULL DEFAULT 18"); } catch (Exception ignored) { }
        }
        if (oldVersion < 4) repairEarlyTravelPreferenceBug(db);
        if (oldVersion < 5) createAgenticTables(db);
    }

    private void repairEarlyTravelPreferenceBug(SQLiteDatabase db) {
        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
            db.delete("memories", "lower(summary) LIKE ? OR lower(summary) LIKE ?",
                    new String[]{"dislikes or avoids%care of dates%", "dislikes or avoids%travel light%"});
            ContentValues flexible = new ContentValues();
            flexible.put("category", "travel_preference");
            flexible.put("summary", "Travel dates are flexible");
            flexible.put("source_text", "Corrected from an early parser mistake");
            flexible.put("created_at", now);
            db.insertWithOnConflict("memories", null, flexible, SQLiteDatabase.CONFLICT_IGNORE);
            ContentValues light = new ContentValues();
            light.put("category", "travel_preference");
            light.put("summary", "Usually travels light and prefers little or no checked luggage");
            light.put("source_text", "Corrected from an early parser mistake");
            light.put("created_at", now);
            db.insertWithOnConflict("memories", null, light, SQLiteDatabase.CONFLICT_IGNORE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public boolean hasProfile() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM profile WHERE id=1", null)) {
            return c.moveToFirst();
        }
    }

    public void saveProfile(String name, String hometown, int age, boolean firstFlight, String interests, String worries, boolean memoryConsent) {
        ContentValues values = new ContentValues();
        values.put("id", 1);
        values.put("name", name.trim());
        values.put("hometown", hometown.trim());
        values.put("age", age);
        values.put("first_flight", firstFlight ? 1 : 0);
        values.put("interests", interests.trim());
        values.put("worries", worries.trim());
        values.put("memory_consent", memoryConsent ? 1 : 0);
        values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("profile", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public Map<String, String> getProfile() {
        Map<String, String> result = new LinkedHashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT name,hometown,age,first_flight,interests,worries,memory_consent FROM profile WHERE id=1", null)) {
            if (c.moveToFirst()) {
                result.put("name", c.getString(0));
                result.put("hometown", c.getString(1));
                int age = c.getInt(2);
                result.put("age", String.valueOf(age));
                result.put("age_group", age < 13 ? "child" : age < 18 ? "teen" : "adult");
                result.put("first_flight", c.getInt(3) == 1 ? "yes" : "no");
                result.put("interests", c.getString(4));
                result.put("worries", c.getString(5));
                result.put("memory_consent", c.getInt(6) == 1 ? "yes" : "no");
            }
        }
        return result;
    }

    public void addMessage(String role, String content) {
        ContentValues values = new ContentValues();
        values.put("role", role);
        values.put("content", content);
        values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insert("messages", null, values);
    }

    public List<Map<String, String>> recentMessages(int limit) {
        List<Map<String, String>> reversed = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT role,content FROM messages ORDER BY id DESC LIMIT ?", new String[]{String.valueOf(limit)})) {
            while (c.moveToNext()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("role", c.getString(0));
                row.put("content", c.getString(1));
                reversed.add(row);
            }
        }
        List<Map<String, String>> normal = new ArrayList<>();
        for (int i = reversed.size() - 1; i >= 0; i--) normal.add(reversed.get(i));
        return normal;
    }

    public boolean addMemory(String category, String summary, String sourceText) {
        ContentValues values = new ContentValues();
        values.put("category", category);
        values.put("summary", summary);
        values.put("source_text", sourceText);
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict("memories", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public List<Map<String, String>> listMemories(int limit) {
        return queryTwoText("SELECT category,summary FROM memories ORDER BY id DESC LIMIT ?", new String[]{String.valueOf(limit)}, "category", "summary");
    }

    public void addWish(String destination, String notes) {
        ContentValues values = new ContentValues();
        values.put("destination", destination.trim());
        values.put("notes", notes.trim());
        values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("wish_list", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        addMemory("wish_list", "Wants to visit " + destination.trim(), destination + ": " + notes);
    }

    public void addTrip(String title, String destination, String status, String notes) {
        ContentValues values = new ContentValues();
        values.put("title", title.trim());
        values.put("destination", destination.trim());
        values.put("status", status.trim());
        values.put("notes", notes.trim());
        values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insert("trips", null, values);
        addMemory("trip", status + " trip: " + destination.trim(), title + ": " + notes);
    }

    public List<Map<String, String>> listTrips(int limit) {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT title,destination,status,notes FROM trips ORDER BY id DESC LIMIT ?", new String[]{String.valueOf(limit)})) {
            while (c.moveToNext()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("title", c.getString(0));
                row.put("destination", c.getString(1));
                row.put("status", c.getString(2));
                row.put("notes", c.getString(3));
                rows.add(row);
            }
        }
        return rows;
    }

    public List<Map<String, String>> listWishes(int limit) {
        return queryTwoText("SELECT destination,notes FROM wish_list ORDER BY id DESC LIMIT ?", new String[]{String.valueOf(limit)}, "destination", "notes");
    }

    public long addPhoto(String localPath, String caption) {
        ContentValues values = new ContentValues();
        values.put("local_path", localPath);
        values.put("caption", caption);
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("photos", null, values);
    }

    public void queueKnowledgePack(String destination) {
        if (destination == null || destination.trim().isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("destination", destination.trim());
        values.put("status", "pending");
        values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("destination_knowledge", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void upsertKnowledgePack(
            String destination,
            String overview,
            String recommendations,
            String transport,
            String accessibility,
            String seasonal,
            String events,
            String sourceNote,
            long refreshedAt,
            long expiresAt) {
        ContentValues values = new ContentValues();
        values.put("destination", destination.trim());
        values.put("status", "ready");
        values.put("overview", value(overview));
        values.put("recommendations", value(recommendations));
        values.put("transport", value(transport));
        values.put("accessibility", value(accessibility));
        values.put("seasonal", value(seasonal));
        values.put("events", value(events));
        values.put("source_note", value(sourceNote));
        values.put("refreshed_at", refreshedAt);
        values.put("expires_at", expiresAt);
        values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("destination_knowledge", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public List<Map<String, String>> listKnowledgePacks(int limit) {
        List<Map<String, String>> rows = new ArrayList<>();
        String sql = "SELECT destination,status,overview,recommendations,transport,accessibility,seasonal,events,source_note,refreshed_at,expires_at FROM destination_knowledge ORDER BY refreshed_at DESC, id DESC LIMIT ?";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(limit)})) {
            while (c.moveToNext()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("destination", c.getString(0));
                row.put("status", c.getString(1));
                row.put("overview", c.getString(2));
                row.put("recommendations", c.getString(3));
                row.put("transport", c.getString(4));
                row.put("accessibility", c.getString(5));
                row.put("seasonal", c.getString(6));
                row.put("events", c.getString(7));
                row.put("source_note", c.getString(8));
                row.put("refreshed_at", String.valueOf(c.getLong(9)));
                row.put("expires_at", String.valueOf(c.getLong(10)));
                rows.add(row);
            }
        }
        return rows;
    }

    public List<String> listPendingKnowledgeRequests(int limit) {
        List<String> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT destination FROM destination_knowledge WHERE status='pending' OR expires_at<? ORDER BY id ASC LIMIT ?",
                new String[]{String.valueOf(System.currentTimeMillis()), String.valueOf(limit)})) {
            while (c.moveToNext()) rows.add(c.getString(0));
        }
        return rows;
    }

    public boolean createDefaultDealWatch(String origin, String destination) {
        if (destination == null || destination.trim().isEmpty()) return false;
        ContentValues values = new ContentValues();
        values.put("origin", value(origin).isEmpty() ? "Home area" : origin.trim());
        values.put("destination", destination.trim());
        values.put("trip_type", "round_trip");
        values.put("travelers", 1);
        values.put("bag_mode", "carry_on");
        values.put("flexible_dates", 1);
        values.put("nearby_airports", 1);
        values.put("min_trip_days", 3);
        values.put("max_trip_days", 14);
        values.put("horizon_days", 365);
        values.put("active", 1);
        values.put("backend_status", "queued");
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict("deal_watches", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public void markDealWatchesFlexible(List<String> destinations) {
        if (destinations == null || destinations.isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("flexible_dates", 1);
        for (String destination : destinations) {
            getWritableDatabase().update("deal_watches", values, "lower(destination)=lower(?)", new String[]{destination});
        }
    }

    public List<Map<String, String>> listDealWatches(int limit) {
        List<Map<String, String>> rows = new ArrayList<>();
        String sql = "SELECT id,origin,destination,trip_type,travelers,bag_mode,flexible_dates,nearby_airports,min_trip_days,max_trip_days,horizon_days,active,backend_status,last_checked_at,last_notified_price,currency FROM deal_watches ORDER BY id DESC LIMIT ?";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(limit)})) {
            while (c.moveToNext()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", String.valueOf(c.getLong(0)));
                row.put("origin", c.getString(1));
                row.put("destination", c.getString(2));
                row.put("trip_type", c.getString(3));
                row.put("travelers", String.valueOf(c.getInt(4)));
                row.put("bag_mode", c.getString(5));
                row.put("flexible_dates", String.valueOf(c.getInt(6)));
                row.put("nearby_airports", String.valueOf(c.getInt(7)));
                row.put("min_trip_days", String.valueOf(c.getInt(8)));
                row.put("max_trip_days", String.valueOf(c.getInt(9)));
                row.put("horizon_days", String.valueOf(c.getInt(10)));
                row.put("active", String.valueOf(c.getInt(11)));
                row.put("backend_status", c.getString(12));
                row.put("last_checked_at", String.valueOf(c.getLong(13)));
                row.put("last_notified_price", String.valueOf(c.getDouble(14)));
                row.put("currency", c.getString(15));
                rows.add(row);
            }
        }
        return rows;
    }

    public List<Map<String, String>> listActiveDealWatches(int limit) {
        List<Map<String, String>> all = listDealWatches(limit);
        List<Map<String, String>> active = new ArrayList<>();
        for (Map<String, String> row : all) {
            if ("1".equals(row.get("active"))) active.add(row);
        }
        return active;
    }

    public void updateDealWatchCheck(long id, String backendStatus, long checkedAt, double notifiedPrice, String currency) {
        ContentValues values = new ContentValues();
        values.put("backend_status", value(backendStatus));
        values.put("last_checked_at", checkedAt);
        if (notifiedPrice > 0) values.put("last_notified_price", notifiedPrice);
        if (currency != null && !currency.trim().isEmpty()) values.put("currency", currency.trim());
        getWritableDatabase().update("deal_watches", values, "id=?", new String[]{String.valueOf(id)});
    }

    private List<Map<String, String>> queryTwoText(String sql, String[] args, String key1, String key2) {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put(key1, c.getString(0));
                row.put(key2, c.getString(1));
                rows.add(row);
            }
        }
        return rows;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
