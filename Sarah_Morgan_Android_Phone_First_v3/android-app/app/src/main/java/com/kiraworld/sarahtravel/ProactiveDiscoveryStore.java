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

public final class ProactiveDiscoveryStore extends SQLiteOpenHelper {
    public ProactiveDiscoveryStore(Context context) { super(context, "sarah_discoveries.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE discoveries (id INTEGER PRIMARY KEY AUTOINCREMENT, speaker TEXT NOT NULL, title TEXT NOT NULL, summary TEXT NOT NULL, url TEXT NOT NULL, query_text TEXT NOT NULL, category TEXT NOT NULL, source TEXT NOT NULL, source_time INTEGER NOT NULL, dismissed INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, UNIQUE(speaker,url))");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) { }
    public boolean add(String speaker, TavilyClient.Result result, String query, String category) {
        ContentValues v=new ContentValues(); v.put("speaker", speaker); v.put("title", result.title);
        v.put("summary", result.summary); v.put("url", result.url); v.put("query_text", query);
        v.put("category", category); v.put("source", "Tavily-connected public research");
        v.put("source_time", System.currentTimeMillis()); v.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict("discoveries", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }
    public List<Map<String,String>> list(String speaker,int limit) {
        List<Map<String,String>> rows=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,title,summary,url,query_text,category,source,source_time FROM discoveries WHERE lower(speaker)=lower(?) AND dismissed=0 ORDER BY id DESC LIMIT ?",new String[]{speaker,String.valueOf(limit)})) {
            while(c.moveToNext()) { Map<String,String> r=new LinkedHashMap<>();
                r.put("id",String.valueOf(c.getLong(0))); r.put("title",c.getString(1)); r.put("summary",c.getString(2));
                r.put("url",c.getString(3)); r.put("query",c.getString(4)); r.put("category",c.getString(5));
                r.put("source",c.getString(6)); r.put("source_time",String.valueOf(c.getLong(7))); rows.add(r); }
        } return rows;
    }
    public int count(String speaker) { try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM discoveries WHERE lower(speaker)=lower(?) AND dismissed=0",new String[]{speaker})) { return c.moveToFirst()?c.getInt(0):0; } }
}
