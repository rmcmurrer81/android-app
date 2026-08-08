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

/** Source-bound discoveries isolated by durable person profile ID. */
public final class ProactiveDiscoveryStore extends SQLiteOpenHelper {
    private static final int DB_VERSION = 2;
    private final Context context;

    public ProactiveDiscoveryStore(Context context) {
        super(context, "sarah_discoveries.db", null, DB_VERSION);
        this.context = context.getApplicationContext();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE discoveries ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "profile_key TEXT NOT NULL,"
                + "speaker TEXT NOT NULL,"
                + "title TEXT NOT NULL,summary TEXT NOT NULL,url TEXT NOT NULL,"
                + "query_text TEXT NOT NULL,category TEXT NOT NULL,source TEXT NOT NULL,"
                + "source_time INTEGER NOT NULL,dismissed INTEGER NOT NULL DEFAULT 0,"
                + "created_at INTEGER NOT NULL,UNIQUE(profile_key,url))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion >= 2) return;
        db.beginTransaction();
        try {
            db.execSQL("ALTER TABLE discoveries RENAME TO discoveries_legacy_v1");
            onCreate(db);
            db.execSQL("INSERT OR IGNORE INTO discoveries("
                    + "profile_key,speaker,title,summary,url,query_text,category,source,"
                    + "source_time,dismissed,created_at) SELECT "
                    + "'legacy_name:' || lower(trim(speaker)),speaker,title,summary,url,"
                    + "query_text,category,source,source_time,dismissed,created_at "
                    + "FROM discoveries_legacy_v1");
            db.execSQL("DROP TABLE discoveries_legacy_v1");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public boolean add(
            String personId,
            String speaker,
            TavilyClient.Result result,
            String query,
            String category) {
        return addSourceBound(
                personId, speaker, result, query, category,
                "Tavily-connected public research", System.currentTimeMillis());
    }

    /** Preserves the origin receipt when an already source-bound row is trusted-synced. */
    public boolean addSynced(
            String personId,
            String speaker,
            TavilyClient.Result result,
            String query,
            String category,
            String source,
            long sourceTime) {
        String sourceLabel = clean(source);
        if (sourceLabel.isEmpty()) {
            sourceLabel = sourceTime > 0
                    ? "Trusted sync source receipt"
                    : "Trusted sync source receipt (origin time unavailable)";
        }
        return addSourceBound(
                personId, speaker, result, query, category,
                sourceLabel, Math.max(0L, sourceTime));
    }

    private boolean addSourceBound(
            String personId,
            String speaker,
            TavilyClient.Result result,
            String query,
            String category,
            String source,
            long sourceTime) {
        if (result == null || !clean(result.url).startsWith("https://")) return false;
        String profileKey = exactProfileKey(personId);
        if (profileKey.isEmpty()) return false;
        ContentValues values = new ContentValues();
        values.put("profile_key", profileKey);
        values.put("speaker", displayName(speaker));
        values.put("title", clean(result.title));
        values.put("summary", clean(result.summary));
        values.put("url", clean(result.url));
        values.put("query_text", clean(query));
        values.put("category", clean(category));
        values.put("source", clean(source));
        values.put("source_time", sourceTime);
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict(
                "discoveries", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    /** Claims exact-name v1 rows once an active durable profile is known. */
    public void claimLegacyProfile(String personId, String speaker) {
        String profileKey = exactProfileKey(personId);
        String legacyKey = legacyNameKey(speaker);
        if (profileKey.isEmpty() || legacyKey.equals("legacy_name:")) return;
        if (!moveProfileKeys(
                legacyKey,
                profileKey,
                displayName(speaker),
                legacyKey,
                personId)) {
            throw new IllegalStateException(
                    "Legacy discovery ownership could not be verified; source rows were retained.");
        }
    }

    public List<Map<String, String>> list(String personId, int limit) {
        List<Map<String, String>> rows = new ArrayList<>();
        String profileKey = exactProfileKey(personId);
        if (profileKey.isEmpty()) return rows;
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id,speaker,title,summary,url,query_text,category,source,source_time "
                        + "FROM discoveries WHERE profile_key=? AND dismissed=0 "
                        + "ORDER BY id DESC LIMIT ?",
                new String[]{profileKey, String.valueOf(Math.max(1, limit))})) {
            while (cursor.moveToNext()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", String.valueOf(cursor.getLong(0)));
                row.put("speaker", cursor.getString(1));
                row.put("title", cursor.getString(2));
                row.put("summary", cursor.getString(3));
                row.put("url", cursor.getString(4));
                row.put("query", cursor.getString(5));
                row.put("category", cursor.getString(6));
                row.put("source", cursor.getString(7));
                row.put("source_time", String.valueOf(cursor.getLong(8)));
                rows.add(row);
            }
        }
        return rows;
    }

    public int count(String personId) {
        String profileKey = exactProfileKey(personId);
        if (profileKey.isEmpty()) return 0;
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT count(*) FROM discoveries WHERE profile_key=? AND dismissed=0",
                new String[]{profileKey})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    /** Crash-resumable exact-profile move with encrypted collision preservation. */
    public boolean moveProfile(String oldPersonId, String newPersonId, String confirmedName) {
        String sourceKey = exactProfileKey(oldPersonId);
        String targetKey = exactProfileKey(newPersonId);
        if (sourceKey.isEmpty() || targetKey.isEmpty() || sourceKey.equals(targetKey)) return true;
        return moveProfileKeys(
                sourceKey, targetKey, confirmedName, oldPersonId, newPersonId);
    }

    private boolean moveProfileKeys(
            String sourceKey,
            String targetKey,
            String confirmedName,
            String archiveSourceId,
            String archiveTargetId) {
        List<Map<String, String>> sourceRows = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id,title,summary,url,query_text,category,source,source_time,dismissed,created_at "
                        + "FROM discoveries WHERE profile_key=? ORDER BY id",
                new String[]{sourceKey})) {
            while (cursor.moveToNext()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", String.valueOf(cursor.getLong(0)));
                row.put("title", cursor.getString(1));
                row.put("summary", cursor.getString(2));
                row.put("url", cursor.getString(3));
                row.put("query_text", cursor.getString(4));
                row.put("category", cursor.getString(5));
                row.put("source", cursor.getString(6));
                row.put("source_time", String.valueOf(cursor.getLong(7)));
                row.put("dismissed", String.valueOf(cursor.getInt(8)));
                row.put("created_at", String.valueOf(cursor.getLong(9)));
                sourceRows.add(row);
            }
        }
        for (Map<String, String> row : sourceRows) {
            String targetPayload = payloadFor(targetKey, row.getOrDefault("url", ""));
            String sourcePayload = payload(row);
            if (!targetPayload.isEmpty() && !targetPayload.equals(sourcePayload)
                    && !ProfileMigrationArchiveStore.preserveCollision(
                            context,
                            "sarah_discoveries",
                            archiveSourceId,
                            archiveTargetId,
                            sourcePayload,
                            targetPayload)) return false;
            if (targetPayload.isEmpty()) {
                ContentValues values = new ContentValues();
                values.put("profile_key", targetKey);
                values.put("speaker", displayName(confirmedName));
                values.put("title", row.get("title"));
                values.put("summary", row.get("summary"));
                values.put("url", row.get("url"));
                values.put("query_text", row.get("query_text"));
                values.put("category", row.get("category"));
                values.put("source", row.get("source"));
                values.put("source_time", Long.parseLong(row.get("source_time")));
                values.put("dismissed", Integer.parseInt(row.get("dismissed")));
                values.put("created_at", Long.parseLong(row.get("created_at")));
                if (getWritableDatabase().insertWithOnConflict(
                        "discoveries", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1
                        && payloadFor(targetKey, row.get("url")).isEmpty()) return false;
            }
            if (getWritableDatabase().delete(
                    "discoveries", "id=? AND profile_key=?",
                    new String[]{row.get("id"), sourceKey}) != 1) return false;
        }
        return true;
    }

    private String payloadFor(String profileKey, String url) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT title,summary,url,query_text,category,source,source_time,dismissed,created_at "
                        + "FROM discoveries WHERE profile_key=? AND url=? LIMIT 1",
                new String[]{profileKey, clean(url)})) {
            if (!cursor.moveToFirst()) return "";
            Map<String, String> row = new LinkedHashMap<>();
            row.put("title", cursor.getString(0));
            row.put("summary", cursor.getString(1));
            row.put("url", cursor.getString(2));
            row.put("query_text", cursor.getString(3));
            row.put("category", cursor.getString(4));
            row.put("source", cursor.getString(5));
            row.put("source_time", String.valueOf(cursor.getLong(6)));
            row.put("dismissed", String.valueOf(cursor.getInt(7)));
            row.put("created_at", String.valueOf(cursor.getLong(8)));
            return payload(row);
        }
    }

    private static String payload(Map<String, String> row) {
        return new org.json.JSONObject(row).toString();
    }

    public void mergePlaceholderSpeakers(String confirmedName, String confirmedPersonId) {
        if (!ProfileMigrationPolicy.isConfirmedDisplayName(confirmedName)) return;
        String target = exactProfileKey(confirmedPersonId);
        if (target.isEmpty()) return;
        List<String> sourceKeys = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT DISTINCT profile_key FROM discoveries "
                        + "WHERE lower(replace(trim(speaker),' ','')) "
                        + "IN ('phoneowner','thephoneowner') AND profile_key<>?",
                new String[]{target})) {
            while (cursor.moveToNext()) sourceKeys.add(cursor.getString(0));
        }
        for (String sourceKey : sourceKeys) {
            if (!moveProfileKeys(
                    sourceKey,
                    target,
                    confirmedName,
                    sourceKey,
                    confirmedPersonId)) {
                throw new IllegalStateException(
                        "Placeholder discovery collision could not be archived; source rows were retained.");
            }
        }
        ContentValues relabel = new ContentValues();
        relabel.put("speaker", confirmedName);
        getWritableDatabase().update(
                "discoveries", relabel, "profile_key=?", new String[]{target});
    }

    private static String exactProfileKey(String personId) {
        String value = clean(personId);
        return value.isEmpty() ? "" : "profile:" + CurrentLocationPolicy.profileKey(value);
    }

    private static String legacyNameKey(String speaker) {
        return "legacy_name:" + displayName(speaker).toLowerCase(Locale.US);
    }

    private static String displayName(String value) {
        String name = clean(value);
        return name.isEmpty() ? "Traveler" : name;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
