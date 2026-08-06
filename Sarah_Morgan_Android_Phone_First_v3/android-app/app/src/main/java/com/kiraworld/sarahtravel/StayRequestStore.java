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

/**
 * Local guest-request ledger. A saved request is not proof that a hotel received
 * or completed it.
 */
public final class StayRequestStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "sarah_stay_requests.db";
    private static final int DB_VERSION = 1;

    public StayRequestStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE stay_requests ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "person_id TEXT NOT NULL,"
                + "person_name TEXT NOT NULL,"
                + "trip_key TEXT NOT NULL DEFAULT '',"
                + "hotel_name TEXT NOT NULL DEFAULT '',"
                + "category TEXT NOT NULL,"
                + "title TEXT NOT NULL,"
                + "detail TEXT NOT NULL DEFAULT '',"
                + "priority TEXT NOT NULL DEFAULT 'normal',"
                + "status TEXT NOT NULL DEFAULT 'draft',"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    public long add(
            String personId,
            String personName,
            String tripKey,
            String hotelName,
            String category,
            String title,
            String detail,
            String priority) {
        ContentValues values = new ContentValues();
        values.put("person_id", clean(personId));
        values.put("person_name", clean(personName));
        values.put("trip_key", clean(tripKey));
        values.put("hotel_name", clean(hotelName));
        values.put("category", clean(category));
        values.put("title", clean(title));
        values.put("detail", clean(detail));
        values.put("priority", clean(priority).isEmpty() ? "normal" : priority.trim());
        values.put("status", "draft");
        values.put("created_at", System.currentTimeMillis());
        values.put("updated_at", System.currentTimeMillis());
        return getWritableDatabase().insert("stay_requests", null, values);
    }

    public List<Map<String, String>> listForPerson(String personId, int limit) {
        return rows("WHERE person_id=?", new String[]{clean(personId)}, limit);
    }

    public List<Map<String, String>> listAll(int limit) {
        return rows("", new String[0], limit);
    }

    public void updateStatus(long id, String status) {
        ContentValues values = new ContentValues();
        values.put("status", clean(status));
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("stay_requests", values, "id=?", new String[]{String.valueOf(id)});
    }

    public void delete(long id) {
        getWritableDatabase().delete("stay_requests", "id=?", new String[]{String.valueOf(id)});
    }

    private List<Map<String, String>> rows(String where, String[] args, int limit) {
        List<Map<String, String>> rows = new ArrayList<>();
        String sql = "SELECT id,person_id,person_name,trip_key,hotel_name,category,title,detail,priority,status,created_at,updated_at "
                + "FROM stay_requests " + where + " ORDER BY updated_at DESC,id DESC LIMIT ?";
        String[] allArgs = new String[args.length + 1];
        System.arraycopy(args, 0, allArgs, 0, args.length);
        allArgs[args.length] = String.valueOf(Math.max(1, limit));
        try (Cursor c = getReadableDatabase().rawQuery(sql, allArgs)) {
            while (c.moveToNext()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", String.valueOf(c.getLong(0)));
                row.put("person_id", c.getString(1));
                row.put("person_name", c.getString(2));
                row.put("trip_key", c.getString(3));
                row.put("hotel_name", c.getString(4));
                row.put("category", c.getString(5));
                row.put("title", c.getString(6));
                row.put("detail", c.getString(7));
                row.put("priority", c.getString(8));
                row.put("status", c.getString(9));
                row.put("created_at", String.valueOf(c.getLong(10)));
                row.put("updated_at", String.valueOf(c.getLong(11)));
                rows.add(row);
            }
        }
        return rows;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
