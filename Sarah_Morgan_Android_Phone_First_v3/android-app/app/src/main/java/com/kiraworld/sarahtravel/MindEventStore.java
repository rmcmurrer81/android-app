package com.kiraworld.sarahtravel;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/** Append-only three-channel event ledger. Private and factual fields are encrypted. */
public final class MindEventStore extends SQLiteOpenHelper {
    private static final String DB = "sarah_mind_events.db";
    public MindEventStore(Context context) { super(context, DB, null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE mind_events (event_id TEXT PRIMARY KEY, speaker TEXT NOT NULL, spoken TEXT NOT NULL, private_enc TEXT NOT NULL, factual_enc TEXT NOT NULL, classification TEXT NOT NULL, source TEXT NOT NULL, device_id TEXT NOT NULL, created_at INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    public static void record(Context context, String speaker, SarahChannelResponse response, String source) {
        if (context == null || response == null) return;
        MindEventStore store = new MindEventStore(context.getApplicationContext());
        try {
            ContentValues values = new ContentValues();
            values.put("event_id", UUID.randomUUID().toString());
            values.put("speaker", safe(speaker));
            values.put("spoken", safe(response.spoken));
            values.put("private_enc", MindCrypto.encrypt(response.privateMind));
            values.put("factual_enc", MindCrypto.encrypt(response.factualTruth));
            values.put("classification", safe(response.classification));
            values.put("source", safe(source));
            values.put("device_id", TrustedDeviceStore.localDeviceId(context));
            values.put("created_at", System.currentTimeMillis());
            store.getWritableDatabase().insertOrThrow("mind_events", null, values);
        } finally { store.close(); }
    }

    public static void recordLocal(Context context, String speaker, String spoken,
                                   String privateMind, String factualTruth, String classification) {
        record(context, speaker,
                new SarahChannelResponseFactory(spoken, privateMind, factualTruth, classification).response(),
                "local");
    }

    public JSONArray exportEncrypted(int limit) {
        JSONArray result = new JSONArray();
        String sql = "SELECT event_id,speaker,spoken,private_enc,factual_enc,classification,source,device_id,created_at FROM mind_events ORDER BY created_at DESC LIMIT ?";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(Math.max(1, limit))})) {
            while (c.moveToNext()) {
                JSONObject row = new JSONObject();
                try {
                    row.put("event_id", c.getString(0)); row.put("speaker", c.getString(1));
                    row.put("spoken", c.getString(2)); row.put("private_mind", MindCrypto.decrypt(c.getString(3)));
                    row.put("factual_truth", MindCrypto.decrypt(c.getString(4))); row.put("classification", c.getString(5));
                    row.put("source", c.getString(6)); row.put("device_id", c.getString(7));
                    row.put("created_at", c.getLong(8)); result.put(row);
                } catch (Exception ignored) { }
            }
        }
        return result;
    }

    private static String safe(String value) { return value == null ? "" : value; }

    /** Keeps SarahChannelResponse construction private while preserving its immutable API. */
    private static final class SarahChannelResponseFactory {
        private final String spoken, privateMind, factualTruth, classification;
        SarahChannelResponseFactory(String s, String p, String f, String c) {
            spoken=s; privateMind=p; factualTruth=f; classification=c;
        }
        SarahChannelResponse response() {
            String raw = "<SPOKEN>" + escape(spoken) + "</SPOKEN>"
                    + "<PRIVATE_MIND>" + escape(privateMind) + "</PRIVATE_MIND>"
                    + "<FACTUAL_TRUTH>" + escape(factualTruth) + "</FACTUAL_TRUTH>"
                    + "<CLASSIFICATION>" + escape(classification) + "</CLASSIFICATION>";
            return SarahChannelResponse.parse(raw);
        }
        private static String escape(String value) {
            return safe(value).replace("&", "and").replace("<", "[").replace(">", "]");
        }
    }
}
