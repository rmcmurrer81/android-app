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

/** Per-profile trip plan ledger. Model suggestions are not confirmed items until saved here. */
public final class TripPlanStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "sarah_trip_plan.db";
    private static final int DB_VERSION = 1;

    public TripPlanStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE itinerary_items ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "person_id TEXT NOT NULL,"
                + "destination TEXT NOT NULL,"
                + "date_text TEXT NOT NULL DEFAULT '',"
                + "time_text TEXT NOT NULL DEFAULT '',"
                + "title TEXT NOT NULL,"
                + "location TEXT NOT NULL DEFAULT '',"
                + "category TEXT NOT NULL DEFAULT 'activity',"
                + "cost REAL NOT NULL DEFAULT 0,"
                + "currency TEXT NOT NULL DEFAULT 'USD',"
                + "status TEXT NOT NULL DEFAULT 'idea',"
                + "notes TEXT NOT NULL DEFAULT '',"
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE budget_items ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "person_id TEXT NOT NULL,"
                + "destination TEXT NOT NULL,"
                + "category TEXT NOT NULL,"
                + "planned REAL NOT NULL DEFAULT 0,"
                + "actual REAL NOT NULL DEFAULT 0,"
                + "currency TEXT NOT NULL DEFAULT 'USD',"
                + "notes TEXT NOT NULL DEFAULT '',"
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE packing_items ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "person_id TEXT NOT NULL,"
                + "destination TEXT NOT NULL,"
                + "category TEXT NOT NULL DEFAULT 'general',"
                + "label TEXT NOT NULL,"
                + "packed INTEGER NOT NULL DEFAULT 0,"
                + "created_at INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    public long addItinerary(
            String personId,
            String destination,
            String date,
            String time,
            String title,
            String location,
            String category,
            double cost,
            String notes) {
        ContentValues values = new ContentValues();
        values.put("person_id", clean(personId));
        values.put("destination", clean(destination));
        values.put("date_text", clean(date));
        values.put("time_text", clean(time));
        values.put("title", clean(title));
        values.put("location", clean(location));
        values.put("category", clean(category).isEmpty() ? "activity" : category.trim());
        values.put("cost", Math.max(0, cost));
        values.put("currency", "USD");
        values.put("status", "idea");
        values.put("notes", clean(notes));
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("itinerary_items", null, values);
    }

    public long addBudget(
            String personId,
            String destination,
            String category,
            double planned,
            double actual,
            String notes) {
        ContentValues values = new ContentValues();
        values.put("person_id", clean(personId));
        values.put("destination", clean(destination));
        values.put("category", clean(category));
        values.put("planned", Math.max(0, planned));
        values.put("actual", Math.max(0, actual));
        values.put("currency", "USD");
        values.put("notes", clean(notes));
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("budget_items", null, values);
    }

    public long addPacking(String personId, String destination, String category, String label) {
        ContentValues values = new ContentValues();
        values.put("person_id", clean(personId));
        values.put("destination", clean(destination));
        values.put("category", clean(category).isEmpty() ? "general" : category.trim());
        values.put("label", clean(label));
        values.put("packed", 0);
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("packing_items", null, values);
    }

    public List<Map<String, String>> itinerary(String personId, String destination, int limit) {
        return rows(
                "SELECT id,date_text,time_text,title,location,category,cost,currency,status,notes FROM itinerary_items "
                        + "WHERE person_id=? AND lower(destination)=lower(?) "
                        + "ORDER BY date_text ASC,time_text ASC,id ASC LIMIT ?",
                new String[]{clean(personId), clean(destination), String.valueOf(limit)},
                new String[]{"id","date","time","title","location","category","cost","currency","status","notes"});
    }

    public List<Map<String, String>> budget(String personId, String destination, int limit) {
        return rows(
                "SELECT id,category,planned,actual,currency,notes FROM budget_items "
                        + "WHERE person_id=? AND lower(destination)=lower(?) ORDER BY id ASC LIMIT ?",
                new String[]{clean(personId), clean(destination), String.valueOf(limit)},
                new String[]{"id","category","planned","actual","currency","notes"});
    }

    public List<Map<String, String>> packing(String personId, String destination, int limit) {
        return rows(
                "SELECT id,category,label,packed FROM packing_items "
                        + "WHERE person_id=? AND lower(destination)=lower(?) ORDER BY packed ASC,id ASC LIMIT ?",
                new String[]{clean(personId), clean(destination), String.valueOf(limit)},
                new String[]{"id","category","label","packed"});
    }

    public void togglePacked(long id, boolean packed) {
        ContentValues values = new ContentValues();
        values.put("packed", packed ? 1 : 0);
        getWritableDatabase().update("packing_items", values, "id=?", new String[]{String.valueOf(id)});
    }

    public void remove(String table, long id) {
        if (!"itinerary_items".equals(table)
                && !"budget_items".equals(table)
                && !"packing_items".equals(table)) return;
        getWritableDatabase().delete(table, "id=?", new String[]{String.valueOf(id)});
    }

    private List<Map<String, String>> rows(String sql, String[] args, String[] keys) {
        List<Map<String, String>> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < keys.length; i++) row.put(keys[i], c.getString(i));
                result.add(row);
            }
        }
        return result;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
