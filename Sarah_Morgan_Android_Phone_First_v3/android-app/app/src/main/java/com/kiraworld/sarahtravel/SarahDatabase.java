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
    private static final int DB_VERSION = 4;

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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE profile ADD COLUMN memory_consent INTEGER NOT NULL DEFAULT 1"); } catch (Exception ignored) { }
        }
        if (oldVersion < 3) {
            try { db.execSQL("ALTER TABLE profile ADD COLUMN age INTEGER NOT NULL DEFAULT 18"); } catch (Exception ignored) { }
        }
        if (oldVersion < 4) {
            repairEarlyTravelPreferenceBug(db);
        }
    }

    private void repairEarlyTravelPreferenceBug(SQLiteDatabase db) {
        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
            db.delete(
                    "memories",
                    "lower(summary) LIKE ? OR lower(summary) LIKE ?",
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
        ContentValues v = new ContentValues();
        v.put("role", role);
        v.put("content", content);
        v.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insert("messages", null, v);
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
        ContentValues v = new ContentValues();
        v.put("category", category);
        v.put("summary", summary);
        v.put("source_text", sourceText);
        v.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict("memories", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public List<Map<String, String>> listMemories(int limit) {
        return queryTwoText("SELECT category,summary FROM memories ORDER BY id DESC LIMIT ?", new String[]{String.valueOf(limit)}, "category", "summary");
    }

    public void addWish(String destination, String notes) {
        ContentValues v = new ContentValues();
        v.put("destination", destination.trim());
        v.put("notes", notes.trim());
        v.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("wish_list", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        addMemory("wish_list", "Wants to visit " + destination.trim(), destination + ": " + notes);
    }

    public void addTrip(String title, String destination, String status, String notes) {
        ContentValues v = new ContentValues();
        v.put("title", title.trim());
        v.put("destination", destination.trim());
        v.put("status", status.trim());
        v.put("notes", notes.trim());
        v.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insert("trips", null, v);
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
        ContentValues v = new ContentValues();
        v.put("local_path", localPath);
        v.put("caption", caption);
        v.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("photos", null, v);
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
}
