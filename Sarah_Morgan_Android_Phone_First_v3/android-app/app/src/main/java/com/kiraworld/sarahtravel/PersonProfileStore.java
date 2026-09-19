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
    private static final int DB_VERSION = 2;

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
        createMemoryProvenanceTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createMemoryProvenanceTable(db);
    }

    /** Returns one durable, non-placeholder phone-owner profile, or none if ambiguous. */
    public Map<String, String> uniqueConfirmedOwnerCandidate() {
        Map<String, String> candidate = new LinkedHashMap<>();
        int count = 0;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,name,age,age_known,hometown,relationship,memory_consent,is_owner,active "
                        + "FROM people WHERE is_owner=1 OR lower(replace(relationship,' ','_')) IN ('phone_owner','phone_owner_candidate') "
                        + "ORDER BY is_owner DESC,updated_at DESC,id ASC",
                null)) {
            while (c.moveToNext()) {
                if (!ProfileMigrationPolicy.isConfirmedDisplayName(c.getString(1))) continue;
                candidate = profileRow(c);
                count++;
                if (count > 1) return new LinkedHashMap<>();
            }
        }
        return count == 1 ? candidate : new LinkedHashMap<>();
    }

    /** Exact placeholder-owner rows eligible for the explicit owner repair. */
    public List<String> placeholderProfileIds() {
        List<String> ids = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,name FROM people WHERE "
                        + "lower(replace(trim(name),' ','')) IN ('phoneowner','thephoneowner') "
                        + "OR (lower(replace(trim(name),' ',''))='traveler' AND "
                        + "(is_owner=1 OR lower(replace(relationship,' ','_')) "
                        + "IN ('phone_owner','phone_owner_candidate'))) ORDER BY id ASC",
                null)) {
            while (c.moveToNext()) {
                if (ProfileMigrationPolicy.isPlaceholderName(c.getString(1))) {
                    ids.add(String.valueOf(c.getLong(0)));
                }
            }
        }
        return ids;
    }

    /**
     * Save a trusted-device owner identity only as a correction candidate.
     * This deliberately does not activate it, mark it as owner, or merge the
     * old placeholder rows; OwnerIdentityCorrectionActivity performs those
     * mutations only after the phone user confirms the displayed name.
     */
    public Map<String, String> stageOwnerCandidate(Map<String, String> ownerProfile) {
        String name = clean(ownerProfile == null ? "" : ownerProfile.get("name"));
        if (!ProfileMigrationPolicy.isConfirmedDisplayName(name)) return new LinkedHashMap<>();
        boolean ageKnown = ProfileMigrationPolicy.ownerAgeKnown(ownerProfile);
        long now = System.currentTimeMillis();
        Map<String, String> existing = findByName(name);

        if (existing.isEmpty()) {
            ContentValues insert = new ContentValues();
            insert.put("name", name);
            insert.put("age", ageKnown ? ProfileMigrationPolicy.ownerAge(ownerProfile) : 0);
            insert.put("age_known", ageKnown ? 1 : 0);
            insert.put("hometown", clean(ownerProfile.getOrDefault("hometown", "")));
            insert.put("relationship", "phone_owner_candidate");
            insert.put("memory_consent", consentValue(ownerProfile.get("memory_consent"), -1));
            insert.put("is_owner", 0);
            insert.put("active", 0);
            insert.put("created_at", now);
            insert.put("updated_at", now);
            getWritableDatabase().insertWithOnConflict(
                    "people", null, insert, SQLiteDatabase.CONFLICT_IGNORE);
        } else {
            ContentValues update = new ContentValues();
            if (ageKnown && !"yes".equals(existing.get("age_known"))) {
                update.put("age", ProfileMigrationPolicy.ownerAge(ownerProfile));
                update.put("age_known", 1);
            }
            String hometown = clean(ownerProfile.getOrDefault("hometown", ""));
            if (!hometown.isEmpty() && clean(existing.get("hometown")).isEmpty()) {
                update.put("hometown", hometown);
            }
            int consent = consentValue(ownerProfile.get("memory_consent"), -1);
            if (consent >= 0 && "unknown".equals(existing.get("memory_consent"))) {
                update.put("memory_consent", consent);
            }
            if (!"yes".equals(existing.get("is_owner"))) {
                update.put("relationship", "phone_owner_candidate");
            }
            update.put("updated_at", now);
            getWritableDatabase().update(
                    "people", update, "lower(name)=lower(?)", new String[]{name});
        }
        return findByName(name);
    }

    public Map<String, String> ensureOwner(Map<String, String> ownerProfile) {
        String name = clean(ownerProfile.getOrDefault("name", "Phone owner"));
        if (name.isEmpty()) name = "Phone owner";
        if (ProfileMigrationPolicy.isPlaceholderName(name)) {
            Map<String, String> confirmed = uniqueConfirmedOwnerCandidate();
            if (!confirmed.isEmpty()) return confirmed;
        }
        boolean ageKnown = ProfileMigrationPolicy.ownerAgeKnown(ownerProfile);
        long now = System.currentTimeMillis();
        ContentValues insert = new ContentValues();
        insert.put("name", name);
        insert.put("age", ageKnown ? ProfileMigrationPolicy.ownerAge(ownerProfile) : 0);
        insert.put("age_known", ageKnown ? 1 : 0);
        insert.put("hometown", clean(ownerProfile.getOrDefault("hometown", "")));
        insert.put("relationship", "phone_owner");
        insert.put("memory_consent", "yes".equalsIgnoreCase(ownerProfile.getOrDefault("memory_consent", "yes")) ? 1 : 0);
        insert.put("is_owner", 1);
        insert.put("updated_at", now);
        insert.put("created_at", now);

        ContentValues update = new ContentValues(insert);
        update.remove("created_at");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.insertWithOnConflict("people", null, insert, SQLiteDatabase.CONFLICT_IGNORE);
            db.update("people", update, "lower(name)=lower(?)", new String[]{name});
            long ownerId = personId(db, name);

            boolean placeholderWasActive = false;
            List<Long> placeholderIds = new ArrayList<>();
            try (Cursor c = db.rawQuery(
                    "SELECT id,name,active FROM people WHERE lower(replace(trim(name),' ','')) "
                            + "IN ('phoneowner','thephoneowner') "
                            + "OR (lower(replace(trim(name),' ',''))='traveler' AND "
                            + "(is_owner=1 OR lower(replace(relationship,' ','_')) "
                            + "IN ('phone_owner','phone_owner_candidate'))) "
                            + "AND lower(name)<>lower(?) ORDER BY active DESC,id ASC",
                    new String[]{name})) {
                while (c.moveToNext()) {
                    if (ProfileMigrationPolicy.shouldMergePlaceholder(c.getString(1), name)) {
                        placeholderIds.add(c.getLong(0));
                        placeholderWasActive |= c.getInt(2) == 1;
                    }
                }
            }

            if (ownerId > 0) {
                for (long placeholderId : placeholderIds) {
                    preserveMemoryProvenance(db, ownerId, placeholderId, now);
                    db.execSQL(
                            "INSERT OR IGNORE INTO person_memories(person_id,category,summary,source_text,created_at) "
                                    + "SELECT ?,category,summary,source_text,created_at FROM person_memories WHERE person_id=?",
                            new Object[]{ownerId, placeholderId});
                    mergeTripParticipation(db, ownerId, placeholderId);
                    db.delete("person_memories", "person_id=?", new String[]{String.valueOf(placeholderId)});
                    db.delete("trip_participation", "person_id=?", new String[]{String.valueOf(placeholderId)});
                    db.delete("people", "id=?", new String[]{String.valueOf(placeholderId)});
                }
            }

            if (ownerId > 0) {
                ContentValues notOwner = new ContentValues();
                notOwner.put("is_owner", 0);
                db.update("people", notOwner, "id<>?", new String[]{String.valueOf(ownerId)});
                if (placeholderWasActive || !hasActive(db)) {
                    ContentValues off = new ContentValues();
                    off.put("active", 0);
                    db.update("people", off, null, null);
                    ContentValues on = new ContentValues();
                    on.put("active", 1);
                    on.put("updated_at", now);
                    db.update("people", on, "id=?", new String[]{String.valueOf(ownerId)});
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

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

    /** Returns only interest memories belonging to the exact named profile. */
    public String interestSummary(String name, int limit) {
        Map<String, String> person = findByName(name);
        if (person.isEmpty() || !"yes".equals(person.getOrDefault("memory_consent", "no"))) return "";
        StringBuilder out = new StringBuilder();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT summary FROM person_memories WHERE person_id=? "
                        + "AND lower(category) IN ('interest','profile_interest') "
                        + "ORDER BY id DESC LIMIT ?",
                new String[]{person.get("person_id"), String.valueOf(Math.max(1, limit))})) {
            while (c.moveToNext()) {
                String summary = clean(c.getString(0));
                if (summary.isEmpty()) continue;
                if (out.length() > 0) out.append("; ");
                out.append(summary);
            }
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

    private static boolean hasActive(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("SELECT 1 FROM people WHERE active=1 LIMIT 1", null)) {
            return c.moveToFirst();
        }
    }

    private static long personId(SQLiteDatabase db, String name) {
        try (Cursor c = db.rawQuery(
                "SELECT id FROM people WHERE lower(name)=lower(?) LIMIT 1",
                new String[]{name})) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    private static void createMemoryProvenanceTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS person_memory_provenance ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "target_person_id INTEGER NOT NULL,"
                + "source_person_id INTEGER NOT NULL,"
                + "category TEXT NOT NULL,"
                + "summary TEXT NOT NULL,"
                + "source_text TEXT NOT NULL DEFAULT '',"
                + "original_created_at INTEGER NOT NULL,"
                + "migration TEXT NOT NULL,"
                + "recorded_at INTEGER NOT NULL,"
                + "UNIQUE(target_person_id,source_person_id,category,summary,source_text,"
                + "original_created_at,migration))");
    }

    /** Preserve exact source text/time before an owner-memory UNIQUE collision can ignore it. */
    private static void preserveMemoryProvenance(
            SQLiteDatabase db,
            long ownerId,
            long placeholderId,
            long recordedAt) {
        db.execSQL(
                "INSERT OR IGNORE INTO person_memory_provenance("
                        + "target_person_id,source_person_id,category,summary,source_text,"
                        + "original_created_at,migration,recorded_at) "
                        + "SELECT ?,?,category,summary,source_text,created_at,"
                        + "'placeholder_owner_merge',? FROM person_memories WHERE person_id=?",
                new Object[]{ownerId, placeholderId, recordedAt, placeholderId});
    }

    /** Merge exact destinations by newest explicit state and preserve a conflict audit memory. */
    private static void mergeTripParticipation(
            SQLiteDatabase db,
            long ownerId,
            long placeholderId) {
        try (Cursor placeholder = db.rawQuery(
                "SELECT destination,status,created_at,updated_at FROM trip_participation WHERE person_id=?",
                new String[]{String.valueOf(placeholderId)})) {
            while (placeholder.moveToNext()) {
                String destination = placeholder.getString(0);
                String placeholderStatus = placeholder.getString(1);
                long placeholderCreated = placeholder.getLong(2);
                long placeholderUpdated = placeholder.getLong(3);
                try (Cursor confirmed = db.rawQuery(
                        "SELECT status,created_at,updated_at FROM trip_participation "
                                + "WHERE person_id=? AND lower(destination)=lower(?) LIMIT 1",
                        new String[]{String.valueOf(ownerId), destination})) {
                    if (!confirmed.moveToFirst()) {
                        ContentValues inserted = new ContentValues();
                        inserted.put("person_id", ownerId);
                        inserted.put("destination", destination);
                        inserted.put("status", placeholderStatus);
                        inserted.put("created_at", placeholderCreated);
                        inserted.put("updated_at", placeholderUpdated);
                        db.insertWithOnConflict(
                                "trip_participation", null, inserted, SQLiteDatabase.CONFLICT_IGNORE);
                        continue;
                    }

                    String confirmedStatus = confirmed.getString(0);
                    long confirmedUpdated = confirmed.getLong(2);
                    if (!clean(confirmedStatus).equals(clean(placeholderStatus))) {
                        ContentValues audit = new ContentValues();
                        audit.put("person_id", ownerId);
                        audit.put("category", "profile_merge_conflict");
                        audit.put(
                                "summary",
                                "Trip participation for " + clean(destination)
                                        + " had confirmed status " + clean(confirmedStatus)
                                        + " and prior placeholder status " + clean(placeholderStatus)
                                        + "; the newest timestamp was retained.");
                        audit.put("source_text", "Automatic placeholder-owner repair");
                        audit.put("created_at", System.currentTimeMillis());
                        db.insertWithOnConflict(
                                "person_memories", null, audit, SQLiteDatabase.CONFLICT_IGNORE);
                    }
                    if (placeholderUpdated > confirmedUpdated) {
                        ContentValues newer = new ContentValues();
                        newer.put("status", placeholderStatus);
                        newer.put("updated_at", placeholderUpdated);
                        db.update(
                                "trip_participation",
                                newer,
                                "person_id=? AND lower(destination)=lower(?)",
                                new String[]{String.valueOf(ownerId), destination});
                    }
                }
            }
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
        String clean = clean(value).replaceAll("[^\\p{L}\\p{M}'’ -]", "").replaceAll("\\s+", " ").trim();
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

    private static int consentValue(String value, int fallback) {
        String normalized = clean(value).toLowerCase(Locale.US);
        if (normalized.equals("yes") || normalized.equals("true") || normalized.equals("1")) return 1;
        if (normalized.equals("no") || normalized.equals("false") || normalized.equals("0")) return 0;
        return fallback;
    }
}
