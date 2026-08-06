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

/**
 * Separate local identities for people who use the same phone.
 *
 * The original SarahDatabase owner profile remains intact for compatibility.
 * This store adds persistent speaker profiles, speaker-bound memories, and
 * explicit trip participation without merging one person's memories into
 * another person's profile.
 */
public final class PersonProfileStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "sarah_people.db";
    private static final int DB_VERSION = 1;

    public PersonProfileStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE people ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL COLLATE NOCASE UNIQUE,"
                + "age INTEGER NOT NULL DEFAULT 0,"
                + "age_known INTEGER NOT NULL DEFAULT 0,"
                + "hometown TEXT NOT NULL DEFAULT '',"
                + "relationship TEXT NOT NULL DEFAULT '',"
                + "memory_consent INTEGER NOT NULL DEFAULT -1,"
                + "is_owner INTEGER NOT NULL DEFAULT 0,"
                + "active INTEGER NOT NULL DEFAULT 0,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE person_memories ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "person_id INTEGER NOT NULL,"
                + "category TEXT NOT NULL,"
                + "summary TEXT NOT NULL,"
                + "source_text TEXT NOT NULL DEFAULT '',"
                + "created_at INTEGER NOT NULL,"
                + "UNIQUE(person_id,category,summary))");
        db.execSQL("CREATE TABLE trip_participation ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "person_id INTEGER NOT NULL,"
                + "destination TEXT NOT NULL COLLATE NOCASE,"
                + "status TEXT NOT NULL DEFAULT 'unknown',"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL,"
                + "UNIQUE(person_id,destination))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    public Map<String, String> ensureOwner(Map<String, String> ownerProfile) {
        String name = clean(ownerProfile.getOrDefault("name", "Phone owner"));
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("age", parseInt(ownerProfile.get("age"), 18));
        values.put("age_known", 1);
        values.put("hometown", clean(ownerProfile.getOrDefault("hometown", "")));
        values.put("relationship", "phone_owner");
        values.put("memory_consent", "yes".equalsIgnoreCase(ownerProfile.getOrDefault("memory_consent", "yes")) ? 1 : 0);
        values.put("is_owner", 1);
        values.put("updated_at", now);
        values.put("created_at", now);
        getWritableDatabase().insertWithOnConflict("people", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        getWritableDatabase().update("people", values, "lower(name)=lower(?)", new String[]{name});

        if (!hasActive()) setActiveByName(name);
        String interests = clean(ownerProfile.getOrDefault("interests", ""));
        if (!interests.isEmpty()) addMemory(name, "profile_interest", "Enjoys " + interests, "Initial owner profile");
        return findByName(name);
    }

    public Map<String, String> createOrGet(String name, String relationship) {
        String cleanName = cleanName(name);
        if (cleanName.isEmpty()) return new LinkedHashMap<>();
        Map<String, String> existing = findByName(cleanName);
        if (!existing.isEmpty()) return existing;
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("name", cleanName);
        values.put("relationship", clean(relationship));
        values.put("memory_consent", -1);
        values.put("created_at", now);
        values.put("updated_at", now);
        getWritableDatabase().insertWithOnConflict("people", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        return findByName(cleanName);
    }

    public Map<String, String> findByName(String name) {
        String cleanName = cleanName(name);
        if (cleanName.isEmpty()) return new LinkedHashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,name,age,age_known,hometown,relationship,memory_consent,is_owner,active "
                        + "FROM people WHERE lower(name)=lower(?) LIMIT 1",
                new String[]{cleanName})) {
            return c.moveToFirst() ? profileRow(c) : new LinkedHashMap<>();
        }
    }

    public Map<String, String> getActiveProfile() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,name,age,age_known,hometown,relationship,memory_consent,is_owner,active "
                        + "FROM people WHERE active=1 ORDER BY is_owner DESC,id ASC LIMIT 1",
                null)) {
            return c.moveToFirst() ? profileRow(c) : new LinkedHashMap<>();
        }
    }

    public List<Map<String, String>> listProfiles() {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,name,age,age_known,hometown,relationship,memory_consent,is_owner,active "
                        + "FROM people ORDER BY is_owner DESC,name COLLATE NOCASE ASC",
                null)) {
            while (c.moveToNext()) rows.add(profileRow(c));
        }
        return rows;
    }

    public void setActiveByName(String name) {
        Map<String, String> person = findByName(name);
        if (person.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues off = new ContentValues();
            off.put("active", 0);
            db.update("people", off, null, null);
            ContentValues on = new ContentValues();
            on.put("active", 1);
            on.put("updated_at", System.currentTimeMillis());
            db.update("people", on, "id=?", new String[]{person.get("person_id")});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void setAge(String name, int age) {
        if (age < 1 || age > 120) return;
        ContentValues values = new ContentValues();
        values.put("age", age);
        values.put("age_known", 1);
        if (age < 18) values.put("memory_consent", 0);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("people", values, "lower(name)=lower(?)", new String[]{cleanName(name)});
    }

    public void setMemoryConsent(String name, boolean allowed) {
        ContentValues values = new ContentValues();
        values.put("memory_consent", allowed ? 1 : 0);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("people", values, "lower(name)=lower(?)", new String[]{cleanName(name)});
    }

    public boolean addMemory(String name, String category, String summary, String sourceText) {
        Map<String, String> person = findByName(name);
        if (person.isEmpty() || !"yes".equals(person.get("memory_consent"))) return false;
        String cleanSummary = clean(summary);
        if (cleanSummary.isEmpty()) return false;
        ContentValues values = new ContentValues();
        values.put("person_id", parseLong(person.get("person_id"), 0));
        values.put("category", clean(category));
        values.put("summary", cleanSummary);
        values.put("source_text", clean(sourceText));
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict(
                "person_memories", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public List<Map<String, String>> listMemories(String name, int limit) {
        List<Map<String, String>> rows = new ArrayList<>();
        Map<String, String> person = findByName(name);
        if (person.isEmpty()) return rows;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT category,summary,source_text FROM person_memories "
                        + "WHERE person_id=? ORDER BY id DESC LIMIT ?",
                new String[]{person.get("person_id"), String.valueOf(Math.max(1, limit))})) {
            while (c.moveToNext()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("category", c.getString(0));
                row.put("summary", c.getString(1));
                row.put("source_text", c.getString(2));
                rows.add(row);
            }
        }
        return rows;
    }

    public String memorySummary(String name, int limit) {
        List<Map<String, String>> rows = listMemories(name, limit);
        StringBuilder out = new StringBuilder();
        for (Map<String, String> row : rows) {
            String summary = clean(row.get("summary"));
            if (summary.isEmpty()) continue;
            if (out.length() > 0) out.append("; ");
            out.append(summary);
        }
        return out.toString();
    }

    public void setTripParticipation(String name, String destination, String status) {
        Map<String, String> person = findByName(name);
        String cleanDestination = clean(destination);
        if (person.isEmpty() || cleanDestination.isEmpty()) return;
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("person_id", parseLong(person.get("person_id"), 0));
        values.put("destination", cleanDestination);
        values.put("status", normalizeParticipation(status));
        values.put("updated_at", now);
        values.put("created_at", now);
        getWritableDatabase().insertWithOnConflict(
                "trip_participation", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String getTripParticipation(String name, String destination) {
        Map<String, String> person = findByName(name);
        if (person.isEmpty() || clean(destination).isEmpty()) return "unknown";
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT status FROM trip_participation WHERE person_id=? AND lower(destination)=lower(?) LIMIT 1",
                new String[]{person.get("person_id"), clean(destination)})) {
            return c.moveToFirst() ? c.getString(0) : "unknown";
        }
    }

    private boolean hasActive() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM people WHERE active=1 LIMIT 1", null)) {
            return c.moveToFirst();
        }
    }

    private static Map<String, String> profileRow(Cursor c) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("person_id", String.valueOf(c.getLong(0)));
        row.put("name", c.getString(1));
        int age = c.getInt(2);
        boolean ageKnown = c.getInt(3) == 1;
        row.put("age", ageKnown ? String.valueOf(age) : "unknown");
        row.put("age_known", ageKnown ? "yes" : "no");
        row.put("age_group", !ageKnown ? "unknown_use_child_safe_mode" : age < 13 ? "child" : age < 18 ? "teen" : "adult");
        row.put("hometown", c.getString(4));
        row.put("relationship", c.getString(5));
        int consent = c.getInt(6);
        row.put("memory_consent", consent > 0 ? "yes" : consent == 0 ? "no" : "unknown");
        row.put("is_owner", c.getInt(7) == 1 ? "yes" : "no");
        row.put("active", c.getInt(8) == 1 ? "yes" : "no");
        return row;
    }

    private static String normalizeParticipation(String value) {
        String lower = clean(value).toLowerCase(Locale.US);
        if (lower.equals("yes") || lower.equals("going") || lower.equals("included")) return "going";
        if (lower.equals("no") || lower.equals("not_going") || lower.equals("not going")) return "not_going";
        return "unknown";
    }

    private static String cleanName(String value) {
        String clean = clean(value).replaceAll("[^A-Za-z'’ -]", "").replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) return "";
        String[] parts = clean.split(" ");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) out.append(part.substring(1));
        }
        return out.toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(clean(value)); }
        catch (Exception ignored) { return fallback; }
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(clean(value)); }
        catch (Exception ignored) { return fallback; }
    }
}
