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
    private static final int DB_VERSION = 11;
    public static final String KNOWLEDGE_PENDING_NOT_SCHEDULED = KnowledgePackSchedulingPolicy.PENDING_NOT_SCHEDULED;
    public static final String KNOWLEDGE_PENDING_SCHEDULED = KnowledgePackSchedulingPolicy.PENDING_SCHEDULED;
    public static final String KNOWLEDGE_RUNNING = "RUNNING";
    public static final String KNOWLEDGE_READY = "READY";
    public static final String KNOWLEDGE_FAILED = "FAILED";

    public SarahDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE profile (id INTEGER PRIMARY KEY CHECK(id=1), name TEXT NOT NULL, hometown TEXT NOT NULL, age INTEGER NOT NULL DEFAULT 0, age_known INTEGER NOT NULL DEFAULT 0, first_flight INTEGER NOT NULL DEFAULT 0, interests TEXT NOT NULL DEFAULT '', worries TEXT NOT NULL DEFAULT '', memory_consent INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, content TEXT NOT NULL, speaker_name TEXT NOT NULL DEFAULT '', route TEXT NOT NULL DEFAULT 'UNKNOWN_LEGACY', created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL, summary TEXT NOT NULL, source_text TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, UNIQUE(category, summary))");
        db.execSQL("CREATE TABLE trips (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, destination TEXT NOT NULL, status TEXT NOT NULL, notes TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE wish_list (id INTEGER PRIMARY KEY AUTOINCREMENT, destination TEXT NOT NULL UNIQUE, notes TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE photos (id INTEGER PRIMARY KEY AUTOINCREMENT, local_path TEXT NOT NULL, caption TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)");
        createAgenticTables(db);
    }

    private static void createAgenticTables(SQLiteDatabase db) {
        createDestinationKnowledgeTables(db);
        db.execSQL("CREATE TABLE IF NOT EXISTS deal_watches (id INTEGER PRIMARY KEY AUTOINCREMENT, origin TEXT NOT NULL, destination TEXT NOT NULL, trip_type TEXT NOT NULL DEFAULT 'round_trip', travelers INTEGER NOT NULL DEFAULT 1, bag_mode TEXT NOT NULL DEFAULT 'carry_on', flexible_dates INTEGER NOT NULL DEFAULT 1, nearby_airports INTEGER NOT NULL DEFAULT 1, min_trip_days INTEGER NOT NULL DEFAULT 3, max_trip_days INTEGER NOT NULL DEFAULT 14, horizon_days INTEGER NOT NULL DEFAULT 365, active INTEGER NOT NULL DEFAULT 1, backend_status TEXT NOT NULL DEFAULT 'queued', last_checked_at INTEGER NOT NULL DEFAULT 0, last_notified_price REAL NOT NULL DEFAULT 0, currency TEXT NOT NULL DEFAULT 'USD', created_at INTEGER NOT NULL, UNIQUE(origin, destination))");
    }

    private static void createDestinationKnowledgeTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS destination_knowledge ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "person_key TEXT NOT NULL,"
                + "destination TEXT NOT NULL COLLATE NOCASE,"
                + "status TEXT NOT NULL DEFAULT 'PENDING_NOT_SCHEDULED',"
                + "overview TEXT NOT NULL DEFAULT '',"
                + "recommendations TEXT NOT NULL DEFAULT '',"
                + "transport TEXT NOT NULL DEFAULT '',"
                + "accessibility TEXT NOT NULL DEFAULT '',"
                + "seasonal TEXT NOT NULL DEFAULT '',"
                + "events TEXT NOT NULL DEFAULT '',"
                + "source_note TEXT NOT NULL DEFAULT '',"
                + "refreshed_at INTEGER NOT NULL DEFAULT 0,"
                + "expires_at INTEGER NOT NULL DEFAULT 0,"
                + "created_at INTEGER NOT NULL,"
                + "UNIQUE(person_key,destination))");
        db.execSQL("CREATE TABLE IF NOT EXISTS destination_knowledge_attempts ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "person_key TEXT NOT NULL,"
                + "destination TEXT NOT NULL,"
                + "status TEXT NOT NULL,"
                + "provider TEXT NOT NULL DEFAULT '',"
                + "source_count INTEGER NOT NULL DEFAULT 0,"
                + "source_receipt TEXT NOT NULL DEFAULT '',"
                + "failure_class TEXT NOT NULL DEFAULT '',"
                + "started_at INTEGER NOT NULL DEFAULT 0,"
                + "completed_at INTEGER NOT NULL DEFAULT 0,"
                + "created_at INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE profile ADD COLUMN memory_consent INTEGER NOT NULL DEFAULT 1"); } catch (Exception ignored) { }
        }
        if (oldVersion < 3) {
            try { db.execSQL("ALTER TABLE profile ADD COLUMN age INTEGER NOT NULL DEFAULT 0"); } catch (Exception ignored) { }
        }
        if (oldVersion < 4) repairEarlyTravelPreferenceBug(db);
        if (oldVersion < 5) createAgenticTables(db);
        if (oldVersion < 8) {
            try {
                db.execSQL("ALTER TABLE messages ADD COLUMN speaker_name TEXT NOT NULL DEFAULT ''");
            } catch (Exception ignored) { }
            try {
                db.execSQL("UPDATE messages SET speaker_name=COALESCE((SELECT name FROM profile WHERE id=1),'') WHERE speaker_name='' OR speaker_name IS NULL");
            } catch (Exception ignored) { }
        }
        if (oldVersion < 9) {
            try {
                db.execSQL("ALTER TABLE profile ADD COLUMN age_known INTEGER NOT NULL DEFAULT 0");
            } catch (Exception ignored) { }
            try {
                db.execSQL("UPDATE profile SET age_known=CASE "
                        + "WHEN age=18 AND EXISTS(SELECT 1 FROM memories WHERE lower(trim(summary))='age: 18') THEN 1 "
                        + "WHEN age BETWEEN 1 AND 120 AND age<>18 AND lower(replace(trim(name),' ','')) NOT IN ('phoneowner','thephoneowner','traveler') THEN 1 "
                        + "ELSE 0 END");
                db.execSQL("UPDATE profile SET age=0 WHERE age_known=0");
            } catch (Exception ignored) { }
        }
        if (oldVersion < 10) {
            try {
                db.execSQL("ALTER TABLE messages ADD COLUMN route TEXT NOT NULL DEFAULT 'UNKNOWN_LEGACY'");
            } catch (Exception ignored) { }
        }
        if (oldVersion < 11) migrateDestinationKnowledgeV11(db);
    }

    private static void migrateDestinationKnowledgeV11(SQLiteDatabase db) {
        if (!tableExists(db, "destination_knowledge")) {
            createDestinationKnowledgeTables(db);
            return;
        }
        if (hasColumn(db, "destination_knowledge", "person_key")) {
            createDestinationKnowledgeTables(db);
            normalizeKnowledgeStatuses(db);
            return;
        }

        db.beginTransaction();
        try {
            long legacyCount = tableCount(db, "destination_knowledge");
            db.execSQL("ALTER TABLE destination_knowledge RENAME TO destination_knowledge_legacy_v10");
            createDestinationKnowledgeTables(db);
            db.execSQL("INSERT INTO destination_knowledge ("
                    + "person_key,destination,status,overview,recommendations,transport,accessibility,seasonal,events,source_note,refreshed_at,expires_at,created_at) "
                    + "SELECT 'owner',destination,"
                    + "CASE lower(status) WHEN 'ready' THEN 'READY' WHEN 'failed' THEN 'FAILED' ELSE 'PENDING_NOT_SCHEDULED' END,"
                    + "overview,recommendations,transport,accessibility,seasonal,events,source_note,refreshed_at,expires_at,created_at "
                    + "FROM destination_knowledge_legacy_v10");
            if (tableCount(db, "destination_knowledge") != legacyCount) {
                throw new IllegalStateException("Destination knowledge migration count mismatch");
            }
            db.execSQL("DROP TABLE destination_knowledge_legacy_v10");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void normalizeKnowledgeStatuses(SQLiteDatabase db) {
        db.execSQL("UPDATE destination_knowledge SET status=CASE lower(status) "
                + "WHEN 'ready' THEN 'READY' WHEN 'running' THEN 'RUNNING' WHEN 'failed' THEN 'FAILED' "
                + "WHEN 'pending_scheduled' THEN 'PENDING_SCHEDULED' "
                + "ELSE 'PENDING_NOT_SCHEDULED' END");
    }

    private static boolean tableExists(SQLiteDatabase db, String table) {
        try (Cursor c = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                new String[]{table})) {
            return c.moveToFirst();
        }
    }

    private static boolean hasColumn(SQLiteDatabase db, String table, String column) {
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int nameIndex = c.getColumnIndex("name");
            while (c.moveToNext()) {
                if (nameIndex >= 0 && column.equalsIgnoreCase(c.getString(nameIndex))) return true;
            }
        }
        return false;
    }

    private static long tableCount(SQLiteDatabase db, String table) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
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
        saveProfile(name, hometown, age, age >= 1 && age <= 120, firstFlight, interests, worries, memoryConsent);
    }

    public void saveProfile(
            String name,
            String hometown,
            int age,
            boolean ageKnown,
            boolean firstFlight,
            String interests,
            String worries,
            boolean memoryConsent) {
        ContentValues values = new ContentValues();
        values.put("id", 1);
        values.put("name", name.trim());
        values.put("hometown", hometown.trim());
        values.put("age", ageKnown ? age : 0);
        values.put("age_known", ageKnown ? 1 : 0);
        values.put("first_flight", firstFlight ? 1 : 0);
        values.put("interests", interests.trim());
        values.put("worries", worries.trim());
        values.put("memory_consent", memoryConsent ? 1 : 0);
        values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("profile", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        ContentValues speaker = new ContentValues();
        speaker.put("speaker_name", name.trim());
        try {
            getWritableDatabase().update("messages", speaker, "speaker_name='' OR speaker_name IS NULL", null);
        } catch (Exception ignored) { }
    }

    public Map<String, String> getProfile() {
        Map<String, String> result = new LinkedHashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT name,hometown,age,age_known,first_flight,interests,worries,memory_consent FROM profile WHERE id=1", null)) {
            if (c.moveToFirst()) {
                result.put("name", c.getString(0));
                result.put("hometown", c.getString(1));
                int age = c.getInt(2);
                boolean ageKnown = c.getInt(3) == 1;
                result.put("age", ageKnown ? String.valueOf(age) : "unknown");
                result.put("age_known", ageKnown ? "yes" : "no");
                result.put("age_group", !ageKnown ? "unknown_use_child_safe_mode" : age < 13 ? "child" : age < 18 ? "teen" : "adult");
                result.put("first_flight", c.getInt(4) == 1 ? "yes" : "no");
                result.put("interests", c.getString(5));
                result.put("worries", c.getString(6));
                result.put("memory_consent", c.getInt(7) == 1 ? "yes" : "no");
            }
        }
        return result;
    }

    public boolean isPlaceholderOwner() {
        return hasProfile() && ProfileMigrationPolicy.isPlaceholderName(ownerName());
    }

    /**
     * Replaces an old relationship label used as the owner name with one
     * confirmed profile. Owner-bound rows remain in this database and message
     * labels move atomically; no trip, memory, wish, watch or photo is deleted.
     */
    public boolean reconcilePlaceholderOwner(Map<String, String> confirmedProfile) {
        if (!isPlaceholderOwner() || confirmedProfile == null) return false;
        String confirmedName = value(confirmedProfile.get("name")).trim();
        if (!ProfileMigrationPolicy.isConfirmedDisplayName(confirmedName)) return false;

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues profile = new ContentValues();
            profile.put("name", confirmedName);
            String hometown = value(confirmedProfile.get("hometown")).trim();
            if (!hometown.isEmpty()) profile.put("hometown", hometown);
            if (ProfileMigrationPolicy.ownerAgeKnown(confirmedProfile)) {
                profile.put("age", ProfileMigrationPolicy.ownerAge(confirmedProfile));
                profile.put("age_known", 1);
            } else if (!value(confirmedProfile.get("age_known")).trim().isEmpty()) {
                profile.put("age", 0);
                profile.put("age_known", 0);
            }
            String consent = value(confirmedProfile.get("memory_consent")).trim();
            if (consent.equalsIgnoreCase("yes") || consent.equals("1") || consent.equalsIgnoreCase("true")) {
                profile.put("memory_consent", 1);
            } else if (consent.equalsIgnoreCase("no") || consent.equals("0") || consent.equalsIgnoreCase("false")) {
                profile.put("memory_consent", 0);
            }
            db.update("profile", profile, "id=1", null);

            ContentValues speaker = new ContentValues();
            speaker.put("speaker_name", confirmedName);
            db.update(
                    "messages",
                    speaker,
                    "lower(replace(trim(speaker_name),' ','')) IN ('phoneowner','thephoneowner')",
                    null);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    /** Merge trusted-device owner facts only when they identify this exact owner. */
    public boolean mergeSyncedOwnerProfile(Map<String, String> incoming) {
        if (incoming == null || !hasProfile() || isPlaceholderOwner()) return false;
        Map<String, String> current = getProfile();
        String currentName = value(current.get("name")).trim();
        String incomingName = value(incoming.get("name")).trim();
        if (!currentName.equalsIgnoreCase(incomingName)) return false;

        ContentValues values = new ContentValues();
        if (!ProfileMigrationPolicy.ownerAgeKnown(current)
                && ProfileMigrationPolicy.ownerAgeKnown(incoming)) {
            values.put("age", ProfileMigrationPolicy.ownerAge(incoming));
            values.put("age_known", 1);
        }
        if (value(current.get("hometown")).trim().isEmpty()) {
            String hometown = value(incoming.get("hometown")).trim();
            if (!hometown.isEmpty()) values.put("hometown", hometown);
        }
        if (value(current.get("interests")).trim().isEmpty()) {
            String interests = value(incoming.get("interests")).trim();
            if (!interests.isEmpty()) values.put("interests", interests);
        }
        if (values.size() == 0) return true;
        getWritableDatabase().update("profile", values, "id=1", null);
        return true;
    }

    public void addMessage(String role, String content) {
        addMessage(role, content, ownerName());
    }

    public void addMessage(String role, String content, String speakerName) {
        addMessage(role, content, speakerName, TurnRoute.UNKNOWN_LEGACY);
    }

    public void addMessage(String role, String content, String speakerName, String route) {
        ContentValues values = new ContentValues();
        values.put("role", value(role));
        values.put("content", value(content));
        values.put("speaker_name", value(speakerName).trim());
        values.put("route", value(route).isEmpty() ? TurnRoute.UNKNOWN_LEGACY : route);
        values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insert("messages", null, values);
    }

    public List<Map<String, String>> recentMessages(int limit) {
        return recentMessagesForSpeaker(ownerName(), limit);
    }

    public List<Map<String, String>> recentMessagesForSpeaker(String speakerName, int limit) {
        String cleanName = value(speakerName).trim();
        String owner = ownerName();
        boolean includeUnassigned = cleanName.isEmpty() || cleanName.equalsIgnoreCase(owner);
        List<Map<String, String>> reversed = new ArrayList<>();
        String sql = includeUnassigned
                ? "SELECT id,role,content,speaker_name,route,created_at FROM messages WHERE lower(speaker_name)=lower(?) OR speaker_name='' ORDER BY id DESC LIMIT ?"
                : "SELECT id,role,content,speaker_name,route,created_at FROM messages WHERE lower(speaker_name)=lower(?) ORDER BY id DESC LIMIT ?";
        try (Cursor c = getReadableDatabase().rawQuery(
                sql,
                new String[]{cleanName.isEmpty() ? owner : cleanName, String.valueOf(Math.max(1, limit))})) {
            while (c.moveToNext()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", String.valueOf(c.getLong(0)));
                row.put("event_id", "android-message-" + c.getLong(0));
                row.put("role", c.getString(1));
                row.put("content", c.getString(2));
                row.put("speaker_name", c.getString(3));
                row.put("route", c.getString(4));
                row.put("created_at", String.valueOf(c.getLong(5)));
                reversed.add(row);
            }
        }
        List<Map<String, String>> normal = new ArrayList<>();
        for (int i = reversed.size() - 1; i >= 0; i--) normal.add(reversed.get(i));
        return normal;
    }

    private String ownerName() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT name FROM profile WHERE id=1", null)) {
            return c.moveToFirst() ? value(c.getString(0)).trim() : "";
        }
    }

    /** Repairs labels from the old placeholder owner without changing conversation content. */
    public void repairPlaceholderOwnerLabels() {
        String owner = ownerName();
        if (ProfileMigrationPolicy.isPlaceholderName(owner)) return;
        ContentValues values = new ContentValues();
        values.put("speaker_name", owner);
        getWritableDatabase().update(
                "messages",
                values,
                "lower(replace(trim(speaker_name),' ','')) IN ('phoneowner','thephoneowner')",
                null);
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

    public List<Map<String, String>> listPhotos(int limit) {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT local_path,caption,created_at FROM photos ORDER BY id DESC LIMIT ?",
                new String[]{String.valueOf(Math.max(1, limit))})) {
            while (c.moveToNext()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("local_path", c.getString(0));
                row.put("caption", c.getString(1));
                row.put("created_at", String.valueOf(c.getLong(2)));
                rows.add(row);
            }
        }
        return rows;
    }

    public boolean queueKnowledgePack(String destination) {
        return queueKnowledgePack(KnowledgeProfileKey.OWNER, destination, false);
    }

    public boolean queueKnowledgePack(String personKey, String destination, boolean scheduled) {
        String scope = value(personKey);
        String cleanDestination = value(destination);
        if (scope.isEmpty() || cleanDestination.isEmpty()) return false;
        String desired = KnowledgePackSchedulingPolicy.pendingState(scheduled);
        ContentValues values = new ContentValues();
        values.put("person_key", scope);
        values.put("destination", cleanDestination);
        values.put("status", desired);
        values.put("created_at", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        long inserted = db.insertWithOnConflict(
                "destination_knowledge", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (inserted != -1) return true;
        long now = System.currentTimeMillis();
        try (Cursor c = db.rawQuery(
                "SELECT status,expires_at FROM destination_knowledge "
                        + "WHERE person_key=? AND lower(destination)=lower(?) LIMIT 1",
                new String[]{scope, cleanDestination})) {
            if (!c.moveToFirst()) return false;
            String existing = c.getString(0);
            if (KNOWLEDGE_READY.equalsIgnoreCase(existing) && c.getLong(1) > now) return false;
            if (!scheduled && (KNOWLEDGE_PENDING_SCHEDULED.equalsIgnoreCase(existing)
                    || KNOWLEDGE_RUNNING.equalsIgnoreCase(existing))) return true;
        }
        ContentValues requeue = new ContentValues();
        requeue.put("status", desired);
        return db.update(
                "destination_knowledge", requeue,
                "person_key=? AND lower(destination)=lower(?)",
                new String[]{scope, cleanDestination}) > 0;
    }

    public boolean markKnowledgePackScheduled(String personKey, String destination) {
        ContentValues values = new ContentValues();
        values.put("status", KNOWLEDGE_PENDING_SCHEDULED);
        return getWritableDatabase().update(
                "destination_knowledge", values,
                "person_key=? AND lower(destination)=lower(?)",
                new String[]{value(personKey), value(destination)}) > 0;
    }

    public boolean markKnowledgePackNotScheduled(String personKey, String destination) {
        ContentValues values = new ContentValues();
        values.put("status", KNOWLEDGE_PENDING_NOT_SCHEDULED);
        return getWritableDatabase().update(
                "destination_knowledge", values,
                "person_key=? AND lower(destination)=lower(?) AND status='PENDING_SCHEDULED'",
                new String[]{value(personKey), value(destination)}) > 0;
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
        upsertKnowledgePack(
                KnowledgeProfileKey.OWNER,
                destination, overview, recommendations, transport, accessibility,
                seasonal, events, sourceNote, refreshedAt, expiresAt);
    }

    public void upsertKnowledgePack(
            String personKey,
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
        values.put("person_key", value(personKey));
        values.put("destination", value(destination));
        values.put("status", KNOWLEDGE_READY);
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
        return listKnowledgePacks(KnowledgeProfileKey.OWNER, limit);
    }

    public List<Map<String, String>> listKnowledgePacks(String personKey, int limit) {
        List<Map<String, String>> rows = new ArrayList<>();
        String sql = "SELECT destination,status,overview,recommendations,transport,accessibility,seasonal,events,source_note,refreshed_at,expires_at "
                + "FROM destination_knowledge WHERE person_key=? ORDER BY refreshed_at DESC, id DESC LIMIT ?";
        try (Cursor c = getReadableDatabase().rawQuery(
                sql, new String[]{value(personKey), String.valueOf(Math.max(1, limit))})) {
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
        return listPendingKnowledgeRequests(KnowledgeProfileKey.OWNER, limit);
    }

    public List<String> listPendingKnowledgeRequests(String personKey, int limit) {
        List<String> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT destination FROM destination_knowledge WHERE person_key=? AND ("
                        + "status IN ('PENDING_SCHEDULED','RUNNING','FAILED') "
                        + "OR (status='READY' AND expires_at>0 AND expires_at<?)) "
                        + "ORDER BY id ASC LIMIT ?",
                new String[]{value(personKey), String.valueOf(System.currentTimeMillis()),
                        String.valueOf(Math.max(1, limit))})) {
            while (c.moveToNext()) rows.add(c.getString(0));
        }
        return rows;
    }

    public void recordKnowledgeAttempt(
            String personKey,
            String destination,
            String status,
            String provider,
            int sourceCount,
            String sourceReceipt,
            String failureClass,
            long startedAt,
            long completedAt) {
        ContentValues attempt = new ContentValues();
        attempt.put("person_key", value(personKey));
        attempt.put("destination", value(destination));
        attempt.put("status", value(status));
        attempt.put("provider", value(provider));
        attempt.put("source_count", Math.max(0, sourceCount));
        attempt.put("source_receipt", value(sourceReceipt));
        attempt.put("failure_class", value(failureClass));
        attempt.put("started_at", Math.max(0, startedAt));
        attempt.put("completed_at", Math.max(0, completedAt));
        attempt.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertOrThrow("destination_knowledge_attempts", null, attempt);

        if (KNOWLEDGE_RUNNING.equals(status) || KNOWLEDGE_FAILED.equals(status)) {
            ContentValues pack = new ContentValues();
            pack.put("status", status);
            getWritableDatabase().update(
                    "destination_knowledge", pack,
                    "person_key=? AND lower(destination)=lower(?)",
                    new String[]{value(personKey), value(destination)});
        }
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
