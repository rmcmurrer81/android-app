#!/usr/bin/env python3
"""Apply the Sarah 2.2 phone continuity upgrade to the checked-out repository.

The script is intentionally deterministic and idempotent.  It patches only
known Sarah 2.1 source anchors, writes the new source files, and fails closed if
an expected anchor has changed.  No API credentials or personal data are
written to GitHub.
"""
from __future__ import annotations

from pathlib import Path
import re
import sys
import textwrap

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/java/com/kiraworld/sarahtravel"
RES = ROOT / "Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/res"
TESTS = ROOT / "Sarah_Morgan_Android_Phone_First_v3/tests"
MARKER = ROOT / "SARAH_2_2_PHONE_WINDOWS_CONTINUITY.md"


def clean(value: str) -> str:
    return textwrap.dedent(value).lstrip("\n").rstrip() + "\n"


def write(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    rendered = clean(value)
    if path.exists() and path.read_text(encoding="utf-8") == rendered:
        return
    path.write_text(rendered, encoding="utf-8", newline="\n")


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f"Expected upgrade anchor was not found in {path}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8", newline="\n")


def regex_once(path: Path, pattern: str, replacement: str) -> None:
    text = path.read_text(encoding="utf-8")
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count == 0:
        if replacement.strip() in text:
            return
        raise RuntimeError(f"Expected regular-expression anchor was not found in {path}: {pattern}")
    path.write_text(updated, encoding="utf-8", newline="\n")


IDENTITY_INTENT = r'''
package com.kiraworld.sarahtravel;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-Java identity and emotional-intent parser used before profile creation. */
public final class IdentityIntent {
    private static final Pattern CORRECTED_NAME = Pattern.compile(
            "(?i)^(?:(?:no|actually|sorry|wait)[,! ]+)?(?:I['’]?m|I am|this is|my name is)\\s+([A-Za-z][A-Za-z'’-]{1,30})(?:\\b.*)?$");
    private static final Set<String> STATES = Set.of(
            "tired", "hungry", "scared", "worried", "nervous", "fine", "good",
            "great", "okay", "ok", "sad", "happy", "sick", "cold", "hot",
            "bored", "lost", "confused", "ready", "here", "back", "going",
            "thinking", "planning", "trying", "working", "watching", "looking",
            "visiting", "traveling", "travelling", "stressed", "stressing",
            "stress", "anxious", "afraid", "panicking", "panicked", "overwhelmed",
            "upset", "uncomfortable", "shaking", "terrified", "uneasy");

    private IdentityIntent() { }

    public static String correctedName(String raw) {
        Matcher matcher = CORRECTED_NAME.matcher(raw == null ? "" : raw.trim());
        if (!matcher.matches()) return "";
        String candidate = matcher.group(1);
        return looksLikeStateNotName(candidate) ? "" : candidate;
    }

    public static boolean hasCorrectionCue(String raw) {
        String lower = lower(raw);
        return lower.startsWith("no ") || lower.startsWith("no,")
                || lower.startsWith("actually ") || lower.startsWith("sorry ")
                || lower.startsWith("wait ") || lower.contains("you have the wrong name")
                || lower.contains("that is not my name") || lower.contains("that's not my name");
    }

    public static boolean looksLikeStateNotName(String value) {
        String lower = lower(value).replaceAll("[^a-z'-]", "");
        return STATES.contains(lower);
    }

    public static boolean isStressOrFear(String raw) {
        String lower = lower(raw);
        return lower.matches(".*\\b(stress|stressed|stressing|anxious|anxiety|afraid|scared|nervous|panic|panicking|panicked|overwhelmed|terrified|uneasy|freaking out|uncomfortable)\\b.*")
                || lower.contains("my heart is racing")
                || lower.contains("this is too fast")
                || lower.contains("i need help calming down")
                || lower.contains("help me calm down");
    }

    public static String transport(String raw) {
        String lower = lower(raw);
        if (lower.matches(".*\\b(plane|airplane|flight|takeoff|taking off|landing|turbulence|airport)\\b.*")) return "plane";
        if (lower.matches(".*\\b(train|rail|subway|metro|amtrak)\\b.*")) return "train";
        if (lower.matches(".*\\b(bus|coach)\\b.*")) return "bus";
        if (lower.matches(".*\\b(ferry|boat|ship|cruise)\\b.*")) return "boat";
        if (lower.matches(".*\\b(car|driving|drive|rideshare|uber|lyft|taxi)\\b.*")) return "car";
        return "general";
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US).trim();
    }
}
'''

UNIVERSAL_CALM = r'''
package com.kiraworld.sarahtravel;

/** Local calm support that works before any connected model is called. */
public final class UniversalCalmSupport {
    private UniversalCalmSupport() { }

    public static String reply(String name, String ageGroup, String transport) {
        String safeName = name == null || name.trim().isEmpty() ? "you" : name.trim();
        boolean child = "child".equals(ageGroup);
        String place = transportLabel(transport);
        String safety = safetySentence(transport);
        if (child) {
            return "I know you are " + safeName + ", and I hear that you feel scared or stressed"
                    + place + ". I’m staying with you. " + safety
                    + " We can smell a pretend flower and slowly blow out a pretend candle, play kid-friendly trivia, notice colors and shapes, sing a short public-domain song, or just talk. You can choose, and you do not have to explain everything first.";
        }
        return "I know you are " + safeName + ", and I hear that you’re stressed"
                + place + ". I’m here with you. " + safety
                + " We can take six gentle breaths with a comfortable inhale and a slightly longer exhale, talk about anything, play personalized trivia, do a noticing game, or stay quiet together. Tell me which would help, or simply keep talking to me.";
    }

    public static String privateMind(String transport) {
        return "Sarah is prioritizing continuity of identity and emotional steadiness before travel planning. The person may need reassurance, distraction, quiet company, or a choice rather than another questionnaire. Transport context: " + transport + ".";
    }

    public static String factualTruth(String transport) {
        return "The person used language associated with stress or fear. Sarah has not assessed the vehicle, diagnosed a condition, contacted anyone, or verified that the situation is safe. Detected transport context: " + transport + ".";
    }

    private static String transportLabel(String transport) {
        if ("plane".equals(transport)) return " during this flight";
        if ("train".equals(transport)) return " on this train";
        if ("bus".equals(transport)) return " on this bus";
        if ("boat".equals(transport)) return " on this boat";
        if ("car".equals(transport)) return " during this ride";
        return "";
    }

    private static String safetySentence(String transport) {
        if ("plane".equals(transport)) return "Keep your seat belt fastened when required and follow the flight crew; I cannot inspect the aircraft or interpret a particular sound or movement.";
        if ("train".equals(transport)) return "Stay seated or hold a secure support if the train is moving, and follow staff instructions; I cannot inspect the train or judge its speed or safety.";
        if ("bus".equals(transport)) return "Use the seat belt when one is provided and follow the driver or staff; I cannot assess the vehicle.";
        if ("boat".equals(transport)) return "Follow the crew and posted safety instructions; I cannot assess the vessel or the water conditions.";
        if ("car".equals(transport)) return "If you are driving, do not interact with the phone—pull over safely or let a passenger use Sarah. I cannot assess the road or vehicle.";
        return "I can offer ordinary calming support, but I cannot diagnose symptoms or determine whether the surroundings are safe.";
    }
}
'''

CHANNEL_RESPONSE = r'''
package com.kiraworld.sarahtravel;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Separates Sarah's public speech from private mind and grounded runtime truth. */
public final class SarahChannelResponse {
    private static final Pattern SPOKEN = tag("SPOKEN");
    private static final Pattern PRIVATE = tag("PRIVATE_MIND");
    private static final Pattern FACTUAL = tag("FACTUAL_TRUTH");
    private static final Pattern CLASSIFICATION = tag("CLASSIFICATION");

    public final String spoken;
    public final String privateMind;
    public final String factualTruth;
    public final String classification;
    public final boolean structured;

    private SarahChannelResponse(String spoken, String privateMind, String factualTruth,
                                 String classification, boolean structured) {
        this.spoken = clean(spoken);
        this.privateMind = clean(privateMind);
        this.factualTruth = clean(factualTruth);
        this.classification = normalizeClassification(classification);
        this.structured = structured;
    }

    public static SarahChannelResponse parse(String raw) {
        String source = raw == null ? "" : raw.trim();
        boolean hasPrivate = source.toUpperCase(Locale.US).contains("<PRIVATE_MIND>")
                || source.toUpperCase(Locale.US).contains("<FACTUAL_TRUTH>");
        String spoken = group(SPOKEN, source);
        String privateMind = group(PRIVATE, source);
        String factualTruth = group(FACTUAL, source);
        String classification = group(CLASSIFICATION, source);
        boolean structured = !spoken.isEmpty() || !privateMind.isEmpty() || !factualTruth.isEmpty();
        if (structured) {
            if (spoken.isEmpty()) spoken = "I’m sorry—I could not safely separate my public reply from my private record. Please ask me again.";
            return new SarahChannelResponse(spoken, privateMind, factualTruth, classification, true);
        }
        if (hasPrivate) {
            return new SarahChannelResponse(
                    "I’m sorry—I could not safely separate my public reply from my private record. Please ask me again.",
                    "Malformed three-channel response was withheld.",
                    "A model response contained private-channel markers but no valid public channel.",
                    "RUNTIME_STATE_ERROR",
                    true);
        }
        return spokenOnly(source, "Connected or local source returned an ordinary public reply.");
    }

    public static SarahChannelResponse spokenOnly(String spoken, String factualTruth) {
        return new SarahChannelResponse(spoken, "", factualTruth, "TRUTHFUL_STATEMENT", false);
    }

    public static String promptContract() {
        return "Return exactly four XML-style fields and nothing outside them: "
                + "<SPOKEN>the public reply</SPOKEN> "
                + "<PRIVATE_MIND>a brief private subjective record, not hidden chain-of-thought</PRIVATE_MIND> "
                + "<FACTUAL_TRUTH>grounded facts and unknowns for this turn</FACTUAL_TRUTH> "
                + "<CLASSIFICATION>one of TRUTHFUL_STATEMENT, DELIBERATE_LIE, JOKE_OR_SARCASM, EVASION, PRIVACY_PROTECTION, SOFTENED_TRUTH, PARTIAL_TRUTH, EXAGGERATION, UNCERTAIN_BELIEF, SINCERE_MISTAKE, HALLUCINATION_OR_GROUNDING_ERROR, IDENTITY_ATTRIBUTION_ERROR, RUNTIME_STATE_ERROR</CLASSIFICATION>. Only SPOKEN is shown or sent to speech.";
    }

    private static Pattern tag(String name) {
        return Pattern.compile("(?is)<" + name + ">\\s*(.*?)\\s*</" + name + ">");
    }

    private static String group(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? clean(matcher.group(1)) : "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeClassification(String value) {
        String clean = clean(value).toUpperCase(Locale.US).replace(' ', '_');
        return clean.matches("TRUTHFUL_STATEMENT|DELIBERATE_LIE|JOKE_OR_SARCASM|EVASION|PRIVACY_PROTECTION|SOFTENED_TRUTH|PARTIAL_TRUTH|EXAGGERATION|UNCERTAIN_BELIEF|SINCERE_MISTAKE|HALLUCINATION_OR_GROUNDING_ERROR|IDENTITY_ATTRIBUTION_ERROR|RUNTIME_STATE_ERROR")
                ? clean : "UNCERTAIN_BELIEF";
    }
}
'''

MIND_CRYPTO = r'''
package com.kiraworld.sarahtravel;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device-bound encryption for Sarah's private mind and factual records. */
public final class MindCrypto {
    private static final String ALIAS = "SarahMindEventsAesV1";
    private MindCrypto() { }

    public static String encrypt(String value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
                    + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    public static String decrypt(String value) {
        try {
            String[] parts = value == null ? new String[0] : value.split("\\.", 2);
            if (parts.length != 2) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
'''

MIND_STORE = r'''
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
                    row.put("spoken", c.getString(2)); row.put("private_enc", c.getString(3));
                    row.put("factual_enc", c.getString(4)); row.put("classification", c.getString(5));
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
'''

VOICE_ROUTER = r'''
package com.kiraworld.sarahtravel;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** Uses Sarah Morgan on ElevenLabs when connected and local Android speech otherwise. */
public final class SarahVoiceRouter {
    private final Context context;
    private final SarahTts local;
    public SarahVoiceRouter(Context context, SarahTts local) {
        this.context = context.getApplicationContext(); this.local = local;
    }
    public void speak(String text) {
        if (ElevenLabsVoiceConfig.isConfigured() && online()) {
            CloudVoiceClient.speak(context, "", text, () -> local.speak(text));
        } else local.speak(text);
    }
    public void stop() { local.stop(); }
    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception ignored) { return false; }
    }
}
'''

PROFILE_CORRECTION = r'''
package com.kiraworld.sarahtravel;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Hides accidental unfinished profiles such as a person named "Stressing". */
public final class ProfileCorrectionStore {
    private static final String PREFS = "sarah_profile_corrections";
    private ProfileCorrectionStore() { }
    public static void ignore(Context context, String name) {
        if (context == null || name == null || name.trim().isEmpty()) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("ignored_" + key(name), true).apply();
    }
    public static boolean ignored(Context context, String name) {
        return context != null && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("ignored_" + key(name), false);
    }
    public static List<Map<String,String>> visible(Context context, List<Map<String,String>> profiles) {
        List<Map<String,String>> result = new ArrayList<>();
        for (Map<String,String> profile : profiles) {
            String name = profile.getOrDefault("name", "");
            if (!ignored(context, name)) result.add(profile);
        }
        return result;
    }
    private static String key(String value) { return value.toLowerCase().replaceAll("[^a-z0-9]", "_"); }
}
'''

TAVILY_CLIENT = r'''
package com.kiraworld.sarahtravel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Small Tavily search client used only when the team build includes its protected key. */
public final class TavilyClient {
    public static final class Result {
        public final String title, url, summary;
        Result(String title, String url, String summary) { this.title=title; this.url=url; this.summary=summary; }
    }
    private TavilyClient() { }
    public static boolean configured() { return !BuildConfig.SARAH_TAVILY_API_KEY.trim().isEmpty(); }
    public static List<Result> search(String query, int limit) throws Exception {
        if (!configured()) return List.of();
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.tavily.com/search").openConnection();
        c.setConnectTimeout(20000); c.setReadTimeout(60000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        JSONObject body = new JSONObject();
        body.put("api_key", BuildConfig.SARAH_TAVILY_API_KEY);
        body.put("query", query); body.put("search_depth", "advanced");
        body.put("max_results", Math.max(1, Math.min(8, limit)));
        body.put("include_answer", false); body.put("include_raw_content", false);
        try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int status = c.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (stream != null) try (InputStream in = stream) { byte[] b=new byte[8192]; int n; while((n=in.read(b))>=0) bytes.write(b,0,n); }
        c.disconnect();
        if (status < 200 || status >= 300) throw new IllegalStateException("Tavily returned " + status);
        JSONArray array = new JSONObject(bytes.toString(StandardCharsets.UTF_8)).optJSONArray("results");
        List<Result> results = new ArrayList<>();
        if (array != null) for (int i=0; i<array.length(); i++) {
            JSONObject row=array.optJSONObject(i); if(row==null) continue;
            String url=row.optString("url", ""); if(!url.startsWith("https://")) continue;
            results.add(new Result(row.optString("title", "Possible travel match"), url,
                    row.optString("content", "").replaceAll("\\s+", " ").trim()));
        }
        return results;
    }
}
'''

DISCOVERY_STORE = r'''
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
'''

DISCOVERY_COORDINATOR = r'''
package com.kiraworld.sarahtravel;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.List;
import java.util.Map;

public final class ProactiveDiscoveryCoordinator {
    private static final String CHANNEL="sarah_discoveries";
    private ProactiveDiscoveryCoordinator() { }
    public static int refresh(Context context, Map<String,String> profile, List<Map<String,String>> trips) throws Exception {
        SharedPreferences prefs=context.getSharedPreferences(SettingsActivity.PREFS,Context.MODE_PRIVATE);
        if(!prefs.getBoolean("web_search",true)||!prefs.getBoolean("auto_destination_research",true)
                ||SettingsActivity.getConversationMode(context)==ConversationModePolicy.MODE_LOCAL_ONLY
                ||!TavilyClient.configured()) return 0;
        if(!"yes".equals(profile.getOrDefault("active_speaker_is_owner","yes"))
                ||!"yes".equals(profile.getOrDefault("memory_consent","no"))) return 0;
        String speaker=profile.getOrDefault("name","Traveler");
        String interests=profile.getOrDefault("interests",profile.getOrDefault("speaker_memories","travel"));
        String destination=""; if(trips!=null&&!trips.isEmpty()) destination=trips.get(0).getOrDefault("destination","");
        boolean nearby=prefs.getBoolean("nearby_discoveries",false);
        String area=nearby?profile.getOrDefault("hometown",""):"";
        String query;
        String category;
        if(!destination.isEmpty()) {
            query=interests+" in "+destination+" filming locations museums events official visitor information tickets";
            category="pre_trip";
        } else if(!area.isEmpty()) {
            query=interests+" events appearances signings exhibitions near "+area+" official tickets";
            category="nearby";
        } else return 0;
        if(interests.toLowerCase().contains("power rangers")&&destination.toLowerCase().contains("new zealand")) {
            query="Power Rangers filming locations New Zealand Auckland current visitor information official sources";
        }
        List<TavilyClient.Result> results=TavilyClient.search(query,4);
        ProactiveDiscoveryStore store=new ProactiveDiscoveryStore(context);
        int added=0; try { for(TavilyClient.Result result:results) if(store.add(speaker,result,query,category)) added++; }
        finally { store.close(); }
        if(added>0) notify(context,speaker,added); return added;
    }
    private static void notify(Context context,String speaker,int count) {
        NotificationManager nm=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if(nm==null)return; if(Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Sarah discoveries",NotificationManager.IMPORTANCE_DEFAULT));
        Intent intent=new Intent(context,DiscoveryActivity.class); PendingIntent pi=PendingIntent.getActivity(context,9901,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder b=Build.VERSION.SDK_INT>=26?new android.app.Notification.Builder(context,CHANNEL):new android.app.Notification.Builder(context);
        b.setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle("Sarah found something you may like")
                .setContentText(count+" possible match"+(count==1?"":"es")+" for "+speaker+". Tap to review the sources.")
                .setContentIntent(pi).setAutoCancel(true); nm.notify(9901,b.build());
    }
}
'''

DISCOVERY_SCHEDULER = r'''
package com.kiraworld.sarahtravel;
import android.app.job.JobInfo; import android.app.job.JobScheduler; import android.content.ComponentName; import android.content.Context; import android.os.PersistableBundle;
public final class ProactiveDiscoveryScheduler {
    private static final int JOB=52201; private ProactiveDiscoveryScheduler(){}
    public static void ensureScheduled(Context c){ JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE); if(js==null)return;
        JobInfo info=new JobInfo.Builder(JOB,new ComponentName(c,ProactiveDiscoveryJobService.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(12L*60L*60L*1000L).build(); js.schedule(info); }
    public static void runSoon(Context c){ JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE); if(js==null)return;
        js.schedule(new JobInfo.Builder(JOB+1,new ComponentName(c,ProactiveDiscoveryJobService.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(3000).setOverrideDeadline(30000).build()); }
    public static void cancel(Context c){ JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE); if(js!=null){js.cancel(JOB);js.cancel(JOB+1);} }
}
'''

DISCOVERY_JOB = r'''
package com.kiraworld.sarahtravel;
import android.app.job.JobParameters; import android.app.job.JobService; import java.util.Map;
public final class ProactiveDiscoveryJobService extends JobService {
    @Override public boolean onStartJob(JobParameters p){ new Thread(()->{ SarahDatabase db=new SarahDatabase(getApplicationContext()); PersonProfileStore people=new PersonProfileStore(getApplicationContext());
        try { Map<String,String> owner=db.getProfile(); people.ensureOwner(owner); Map<String,String> active=people.getActiveProfile(); if(active.isEmpty())active=owner;
            active.put("active_speaker_is_owner", active.getOrDefault("is_owner","yes")); ProactiveDiscoveryCoordinator.refresh(getApplicationContext(),active,db.listTrips(20)); }
        catch(Exception ignored){} finally{people.close();db.close();jobFinished(p,false);} },"Sarah-Proactive-Discovery").start(); return true; }
    @Override public boolean onStopJob(JobParameters p){return true;}
}
'''

DISCOVERY_BUTTON = r'''
package com.kiraworld.sarahtravel;
import android.app.Activity; import android.content.Context; import android.content.Intent; import android.util.AttributeSet; import android.widget.Button; import java.util.Map;
public final class ProactiveDiscoveryButton extends Button {
    public ProactiveDiscoveryButton(Context c){super(c);init();} public ProactiveDiscoveryButton(Context c,AttributeSet a){super(c,a);init();} public ProactiveDiscoveryButton(Context c,AttributeSet a,int s){super(c,a,s);init();}
    private void init(){setAllCaps(false);setText("✨ Sarah discoveries");setContentDescription("Open Sarah's source-backed proactive discoveries");setOnClickListener(v->getContext().startActivity(new Intent(getContext(),DiscoveryActivity.class)));}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow(); SarahDatabase db=new SarahDatabase(getContext()); PersonProfileStore people=new PersonProfileStore(getContext()); try{Map<String,String> owner=db.getProfile();people.ensureOwner(owner);Map<String,String> p=people.getActiveProfile();String n=p.getOrDefault("name",owner.getOrDefault("name","Traveler"));ProactiveDiscoveryStore s=new ProactiveDiscoveryStore(getContext());try{int c=s.count(n);setText(c>0?"✨ Discoveries ("+c+")":"✨ Sarah discoveries");}finally{s.close();}}finally{people.close();db.close();}}
}
'''

DISCOVERY_ACTIVITY = r'''
package com.kiraworld.sarahtravel;
import android.app.Activity; import android.content.Intent; import android.graphics.Typeface; import android.net.Uri; import android.os.Bundle; import android.view.ViewGroup; import android.widget.Button; import android.widget.LinearLayout; import android.widget.ScrollView; import android.widget.TextView; import java.text.DateFormat; import java.util.Date; import java.util.List; import java.util.Map;
public final class DiscoveryActivity extends Activity {
    @Override protected void onCreate(Bundle s){super.onCreate(s);ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(28,28,28,28);scroll.addView(root);TextView h=new TextView(this);h.setText("Sarah discoveries");h.setTextSize(28);h.setTypeface(Typeface.DEFAULT,Typeface.BOLD);root.addView(h);
        TextView note=new TextView(this);note.setText("These are possible matches from connected public research. Sarah shows the source and time; a result is not proof that tickets, hours, access, or availability are current until you verify the official page.");note.setPadding(0,12,0,18);root.addView(note);
        SarahDatabase db=new SarahDatabase(this);PersonProfileStore people=new PersonProfileStore(this);String name;try{Map<String,String> owner=db.getProfile();people.ensureOwner(owner);Map<String,String> p=people.getActiveProfile();name=p.getOrDefault("name",owner.getOrDefault("name","Traveler"));}finally{people.close();db.close();}
        ProactiveDiscoveryStore store=new ProactiveDiscoveryStore(this);List<Map<String,String>> rows;try{rows=store.list(name,50);}finally{store.close();}
        if(rows.isEmpty()){TextView e=new TextView(this);e.setText("No discoveries are saved yet. Sarah researches only when connected research, automatic destination research, memory permission, and an active destination or approved nearby area are available.");root.addView(e);} else for(Map<String,String> row:rows){TextView title=new TextView(this);title.setText(row.get("title"));title.setTextSize(19);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setPadding(0,18,0,4);root.addView(title);TextView body=new TextView(this);body.setText(row.get("summary")+"\n\nSource: "+row.get("source")+"\nResearched: "+DateFormat.getDateTimeInstance().format(new Date(Long.parseLong(row.get("source_time")))));root.addView(body);Button open=new Button(this);open.setText("Open source and verify");open.setAllCaps(false);String url=row.get("url");open.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));}catch(Exception ignored){}});root.addView(open);}
        Button refresh=new Button(this);refresh.setText("Research now");refresh.setAllCaps(false);refresh.setOnClickListener(v->{ProactiveDiscoveryScheduler.runSoon(this);refresh.setText("Research queued — check again shortly");});root.addView(refresh);setContentView(scroll);}
}
'''

TRUSTED_DEVICE_STORE = r'''
package com.kiraworld.sarahtravel;
import android.content.Context; import android.content.SharedPreferences; import java.util.UUID;
public final class TrustedDeviceStore {
    private static final String PREFS="sarah_trusted_devices"; private TrustedDeviceStore(){}
    public static String localDeviceId(Context c){SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);String id=p.getString("local_device_id","");if(id.isEmpty()){id=UUID.randomUUID().toString();p.edit().putString("local_device_id",id).apply();}return id;}
    public static void savePeer(Context c,String host,String token){c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString("peer_host",host).putString("peer_token",token).apply();}
    public static String host(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString("peer_host","");}
    public static String token(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString("peer_token","");}
    public static void revoke(Context c){c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove("peer_host").remove("peer_token").apply();}
}
'''

SYNC_PROTOCOL = r'''
package com.kiraworld.sarahtravel;
import java.nio.charset.StandardCharsets; import java.security.SecureRandom; import java.util.Base64; import javax.crypto.Cipher; import javax.crypto.Mac; import javax.crypto.SecretKeyFactory; import javax.crypto.spec.GCMParameterSpec; import javax.crypto.spec.PBEKeySpec; import javax.crypto.spec.SecretKeySpec;
public final class TrustedSyncProtocol {
    private TrustedSyncProtocol(){}
    public static String encrypt(String token,String text)throws Exception{byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key(token),new GCMParameterSpec(128,iv));byte[] out=c.doFinal(text.getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(iv)+"."+Base64.getEncoder().encodeToString(out);}
    public static String decrypt(String token,String value)throws Exception{String[] p=value.split("\\.",2);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(token),new GCMParameterSpec(128,Base64.getDecoder().decode(p[0])));return new String(c.doFinal(Base64.getDecoder().decode(p[1])),StandardCharsets.UTF_8);}
    public static String signature(String token,String encrypted)throws Exception{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getEncoder().encodeToString(m.doFinal(encrypted.getBytes(StandardCharsets.UTF_8)));}
    private static SecretKeySpec key(String token)throws Exception{PBEKeySpec spec=new PBEKeySpec(token.toCharArray(),"SarahTrustedSyncV1".getBytes(StandardCharsets.UTF_8),120000,256);byte[] k=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();return new SecretKeySpec(k,"AES");}
}
'''

SYNC_EXPORTER = r'''
package com.kiraworld.sarahtravel;
import android.content.Context; import android.util.Base64; import org.json.JSONArray; import org.json.JSONObject; import java.io.File; import java.nio.file.Files; import java.util.List; import java.util.Map;
public final class SarahSyncExporter {
    private SarahSyncExporter(){}
    public static JSONObject export(Context c)throws Exception{SarahDatabase db=new SarahDatabase(c);JSONObject out=new JSONObject();try{out.put("schema","sarah-sync-v1");out.put("device_id",TrustedDeviceStore.localDeviceId(c));out.put("created_at",System.currentTimeMillis());out.put("profile",new JSONObject(db.getProfile()));out.put("messages",array(db.recentMessages(200)));out.put("memories",array(db.listMemories(200)));out.put("trips",array(db.listTrips(100)));out.put("wishes",array(db.listWishes(100)));out.put("photos",photos(db.listPhotos(25)));MindEventStore mind=new MindEventStore(c);try{out.put("mind_events",mind.exportEncrypted(500));}finally{mind.close();}return out;}finally{db.close();}}
    private static JSONArray array(List<Map<String,String>> rows){JSONArray a=new JSONArray();for(Map<String,String> r:rows)a.put(new JSONObject(r));return a;}
    private static JSONArray photos(List<Map<String,String>> rows){JSONArray a=new JSONArray();long total=0;for(Map<String,String> r:rows){try{File f=new File(r.getOrDefault("local_path",""));if(!f.isFile()||f.length()>3000000||total+f.length()>12000000)continue;JSONObject o=new JSONObject(r);o.put("jpeg_base64",Base64.encodeToString(Files.readAllBytes(f.toPath()),Base64.NO_WRAP));a.put(o);total+=f.length();}catch(Exception ignored){}}return a;}
}
'''

SYNC_CLIENT = r'''
package com.kiraworld.sarahtravel;
import android.content.Context; import org.json.JSONObject; import java.io.ByteArrayOutputStream; import java.io.InputStream; import java.io.OutputStream; import java.net.HttpURLConnection; import java.net.URL; import java.nio.charset.StandardCharsets;
public final class TrustedSyncClient {
    private TrustedSyncClient(){}
    public static String pair(Context c,String host,String code)throws Exception{JSONObject b=new JSONObject();b.put("device_id",TrustedDeviceStore.localDeviceId(c));b.put("device_name",android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL);b.put("code",code);JSONObject r=post("http://"+host+":8769/pair",b.toString(),"");String token=r.getString("token");TrustedDeviceStore.savePeer(c,host,token);return token;}
    public static JSONObject sync(Context c)throws Exception{String host=TrustedDeviceStore.host(c),token=TrustedDeviceStore.token(c);if(host.isEmpty()||token.isEmpty())throw new IllegalStateException("Pair with the Windows companion first.");String encrypted=TrustedSyncProtocol.encrypt(token,SarahSyncExporter.export(c).toString());JSONObject body=new JSONObject();body.put("payload",encrypted);body.put("signature",TrustedSyncProtocol.signature(token,encrypted));return post("http://"+host+":8769/sync",body.toString(),token);}
    private static JSONObject post(String endpoint,String body,String token)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(60000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");if(!token.isEmpty())c.setRequestProperty("X-Sarah-Device-Token",token);try(OutputStream o=c.getOutputStream()){o.write(body.getBytes(StandardCharsets.UTF_8));}int status=c.getResponseCode();InputStream s=status>=200&&status<300?c.getInputStream():c.getErrorStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(s!=null)try(InputStream in=s){byte[] b=new byte[8192];int n;while((n=in.read(b))>=0)bytes.write(b,0,n);}c.disconnect();if(status<200||status>=300)throw new IllegalStateException("Windows companion returned "+status+": "+bytes.toString(StandardCharsets.UTF_8));return new JSONObject(bytes.toString(StandardCharsets.UTF_8));}
}
'''

SYNC_ACTIVITY = r'''
package com.kiraworld.sarahtravel;
import android.app.Activity; import android.graphics.Typeface; import android.os.Bundle; import android.text.InputType; import android.widget.Button; import android.widget.EditText; import android.widget.LinearLayout; import android.widget.ScrollView; import android.widget.TextView;
public final class TrustedSyncActivity extends Activity {
    private TextView status;
    @Override protected void onCreate(Bundle s){super.onCreate(s);ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(30,30,30,30);scroll.addView(root);TextView h=new TextView(this);h.setText("Trusted device sync");h.setTextSize(26);h.setTypeface(Typeface.DEFAULT,Typeface.BOLD);root.addView(h);TextView note=new TextView(this);note.setText("Pair only with your own Sarah Windows companion on a trusted Wi-Fi network. A matching six-digit code is required. Sync payloads are encrypted and signed before they cross the local network. You can revoke the computer at any time.");note.setPadding(0,12,0,16);root.addView(note);EditText host=new EditText(this);host.setHint("Windows address, for example 192.168.1.25");host.setText(TrustedDeviceStore.host(this));root.addView(host);EditText code=new EditText(this);code.setHint("Six-digit code shown by Windows Sarah");code.setInputType(InputType.TYPE_CLASS_NUMBER);root.addView(code);status=new TextView(this);status.setPadding(0,12,0,12);root.addView(status);Button pair=new Button(this);pair.setText("Verify and pair");pair.setOnClickListener(v->run(()->"Paired. Token ending "+TrustedSyncClient.pair(this,host.getText().toString().trim(),code.getText().toString().trim()).substring(0,6)+"…"));root.addView(pair);Button sync=new Button(this);sync.setText("Sync mind, trips, memories and recent trip photos");sync.setOnClickListener(v->run(()->TrustedSyncClient.sync(this).optString("message","Sync completed.")));root.addView(sync);Button revoke=new Button(this);revoke.setText("Revoke this Windows companion");revoke.setOnClickListener(v->{TrustedDeviceStore.revoke(this);status.setText("The saved Windows device was revoked on this phone.");});root.addView(revoke);setContentView(scroll);}
    private interface Work{String run()throws Exception;} private void run(Work w){status.setText("Working…");new Thread(()->{try{String r=w.run();runOnUiThread(()->status.setText(r));}catch(Exception e){runOnUiThread(()->status.setText("Could not complete: "+e.getMessage()));}},"Sarah-Trusted-Sync").start();}
}
'''

SYNC_BUTTON = r'''
package com.kiraworld.sarahtravel;
import android.content.Context; import android.content.Intent; import android.util.AttributeSet; import android.widget.Button;
public final class TrustedSyncButton extends Button { public TrustedSyncButton(Context c){super(c);i();}public TrustedSyncButton(Context c,AttributeSet a){super(c,a);i();}public TrustedSyncButton(Context c,AttributeSet a,int s){super(c,a,s);i();}private void i(){setAllCaps(false);setText("🔄 Devices & photos");setOnClickListener(v->getContext().startActivity(new Intent(getContext(),TrustedSyncActivity.class)));}}
'''

SPONSOR_ACTIVITY = r'''
package com.kiraworld.sarahtravel;
import android.app.Activity; import android.graphics.Typeface; import android.os.Bundle; import android.widget.LinearLayout; import android.widget.ScrollView; import android.widget.TextView;
public final class SponsorConnectionsActivity extends Activity {
    @Override protected void onCreate(Bundle s){super.onCreate(s);ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(30,30,30,30);scroll.addView(root);add(root,"Travel Hack NYC connections",26,true);add(root,"Sarah uses each connection honestly. A handoff or search result is never labeled as a completed booking.",15,false);add(root,"ElevenLabs",20,true);add(root,ElevenLabsVoiceConfig.isConfigured()?"Connected Sarah Morgan online voice is included in this build; Android speech remains the offline fallback.":"Voice route is implemented, but this build does not contain the protected ElevenLabs connection.",15,false);add(root,"Tavily",20,true);add(root,TavilyClient.configured()?"Connected source-backed proactive travel and event discovery is configured.":"Research route is implemented, but the protected Tavily key is not included in this build.",15,false);add(root,"Stay22",20,true);add(root,"Sarah can hand a traveler from an active destination and dates to accommodation discovery. The traveler verifies the provider and total before booking.",15,false);add(root,"Rove",20,true);add(root,"Sarah can compare rewards-aware travel options and open the official Rove path without claiming an undocumented booking API.",15,false);add(root,"AeroXplorer",20,true);add(root,"Sarah can use aviation news and airline or airport context as sourced talking points, never as aircraft telemetry.",15,false);add(root,"Propellic and Lovable",20,true);add(root,"They are represented in the destination-marketing and product-presentation story. Sarah does not pretend to use a technical API that was not actually connected.",15,false);setContentView(scroll);}private void add(LinearLayout r,String t,int size,boolean bold){TextView v=new TextView(this);v.setText(t);v.setTextSize(size);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setPadding(0,10,0,4);r.addView(v);}
}
'''

DEMO_ACTIVITY = r'''
package com.kiraworld.sarahtravel;
import android.app.Activity;import android.content.Intent;import android.graphics.Typeface;import android.os.Bundle;import android.widget.Button;import android.widget.LinearLayout;import android.widget.ScrollView;import android.widget.TextView;
public final class HackathonDemoActivity extends Activity {
    @Override protected void onCreate(Bundle s){super.onCreate(s);ScrollView scroll=new ScrollView(this);LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setPadding(30,30,30,30);scroll.addView(r);add(r,"Sarah Travel OS — event demo",28,true);add(r,"1. Say: “I am stressed and this train feels too fast.” Sarah must keep Robert’s identity and offer train-aware calm choices.",16,false);add(r,"2. Ask Sarah to plan a New Zealand trip. With approved Power Rangers interests, show proactive filming-location research, possible stops, facts and trivia.",16,false);add(r,"3. Open a source card, map, photos, video, route and accommodation handoff. Verify that Sarah distinguishes research from a confirmed booking.",16,false);add(r,"4. Let ElevenLabs speak the connected reply, then turn off internet and show Android speech, breathing, trivia, noticing games and public-domain songs.",16,false);add(r,"5. Pair the phone with the Windows companion, sync the conversation, trip and recent sanitized trip photos, and continue planning on the larger screen.",16,false);add(r,"6. Show SPOKEN, PRIVATE MIND and FACTUAL TRUTH separation in the private activity log without exposing private mind in chat or TTS.",16,false);Button sponsor=new Button(this);sponsor.setText("Show sponsor connections");sponsor.setOnClickListener(v->startActivity(new Intent(this,SponsorConnectionsActivity.class)));r.addView(sponsor);setContentView(scroll);}private void add(LinearLayout r,String t,int size,boolean bold){TextView v=new TextView(this);v.setText(t);v.setTextSize(size);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setPadding(0,10,0,8);r.addView(v);}
}
'''

DEMO_BUTTON = r'''
package com.kiraworld.sarahtravel;
import android.content.Context;import android.content.Intent;import android.util.AttributeSet;import android.widget.Button;
public final class DemoModeButton extends Button {public DemoModeButton(Context c){super(c);i();}public DemoModeButton(Context c,AttributeSet a){super(c,a);i();}public DemoModeButton(Context c,AttributeSet a,int s){super(c,a,s);i();}private void i(){setAllCaps(false);setText("▶ Event demo");setOnClickListener(v->getContext().startActivity(new Intent(getContext(),HackathonDemoActivity.class)));}}
'''

CORE_TEST = r'''
import com.kiraworld.sarahtravel.IdentityIntent;
import com.kiraworld.sarahtravel.SarahChannelResponse;
import com.kiraworld.sarahtravel.TrustedSyncProtocol;
import com.kiraworld.sarahtravel.UniversalCalmSupport;

public final class Sarah22CoreTest {
    public static void main(String[] args) throws Exception {
        require(IdentityIntent.isStressOrFear("I am stressing"), "stressing should be emotional state");
        require(IdentityIntent.looksLikeStateNotName("Stressing"), "Stressing must never become a profile name");
        require("Robert".equals(IdentityIntent.correctedName("No, I am Robert but I am stressed out")), "identity correction");
        require("train".equals(IdentityIntent.transport("This fast train is making me nervous")), "train context");
        String calm=UniversalCalmSupport.reply("Robert","adult","train");
        require(calm.contains("Robert")&&calm.toLowerCase().contains("train")&&calm.toLowerCase().contains("trivia"), "universal calm response");
        SarahChannelResponse response=SarahChannelResponse.parse("<SPOKEN>Hello.</SPOKEN><PRIVATE_MIND>private</PRIVATE_MIND><FACTUAL_TRUTH>fact</FACTUAL_TRUTH><CLASSIFICATION>TRUTHFUL_STATEMENT</CLASSIFICATION>");
        require("Hello.".equals(response.spoken), "spoken channel"); require(!response.spoken.contains("private"), "private must not leak");
        String token="1234567890abcdef"; String encrypted=TrustedSyncProtocol.encrypt(token,"phone and computer");
        require("phone and computer".equals(TrustedSyncProtocol.decrypt(token,encrypted)), "trusted sync encryption round trip");
        require(!TrustedSyncProtocol.signature(token,encrypted).isEmpty(), "signed sync payload");
        System.out.println("Sarah22CoreTest passed");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
'''


def add_new_files() -> None:
    files = {
        "IdentityIntent.java": IDENTITY_INTENT,
        "UniversalCalmSupport.java": UNIVERSAL_CALM,
        "SarahChannelResponse.java": CHANNEL_RESPONSE,
        "MindCrypto.java": MIND_CRYPTO,
        "MindEventStore.java": MIND_STORE,
        "SarahVoiceRouter.java": VOICE_ROUTER,
        "ProfileCorrectionStore.java": PROFILE_CORRECTION,
        "TavilyClient.java": TAVILY_CLIENT,
        "ProactiveDiscoveryStore.java": DISCOVERY_STORE,
        "ProactiveDiscoveryCoordinator.java": DISCOVERY_COORDINATOR,
        "ProactiveDiscoveryScheduler.java": DISCOVERY_SCHEDULER,
        "ProactiveDiscoveryJobService.java": DISCOVERY_JOB,
        "ProactiveDiscoveryButton.java": DISCOVERY_BUTTON,
        "DiscoveryActivity.java": DISCOVERY_ACTIVITY,
        "TrustedDeviceStore.java": TRUSTED_DEVICE_STORE,
        "TrustedSyncProtocol.java": SYNC_PROTOCOL,
        "SarahSyncExporter.java": SYNC_EXPORTER,
        "TrustedSyncClient.java": SYNC_CLIENT,
        "TrustedSyncActivity.java": SYNC_ACTIVITY,
        "TrustedSyncButton.java": SYNC_BUTTON,
        "SponsorConnectionsActivity.java": SPONSOR_ACTIVITY,
        "HackathonDemoActivity.java": DEMO_ACTIVITY,
        "DemoModeButton.java": DEMO_BUTTON,
    }
    for name, content in files.items(): write(JAVA / name, content)
    write(TESTS / "Sarah22CoreTest.java", CORE_TEST)


def patch_speaker_context() -> None:
    path = JAVA / "SpeakerContext.java"
    replace_once(path,
'''        Result pendingResult = handlePending(raw, lower);
        if (pendingResult.handled) return pendingResult;

        Result handoff = detectHandoff(raw, lower);
        if (handoff.handled) return handoff;

        Result intro = detectSelfIntroduction(raw);
        if (intro.handled) return intro;
''',
'''        Result correction = detectIdentityCorrection(raw);
        if (correction.handled) return correction;

        Result pendingResult = handlePending(raw, lower);
        if (pendingResult.handled) return pendingResult;

        Result handoff = detectHandoff(raw, lower);
        if (handoff.handled) return handoff;

        if (IdentityIntent.isStressOrFear(raw)) {
            String transport = IdentityIntent.transport(raw);
            String reply = UniversalCalmSupport.reply(activeName(), ageGroup(), transport);
            if (context != null) {
                MindEventStore.recordLocal(
                        context,
                        activeName(),
                        reply,
                        UniversalCalmSupport.privateMind(transport),
                        UniversalCalmSupport.factualTruth(transport),
                        "TRUTHFUL_STATEMENT");
            }
            return new Result(true, reply);
        }

        Result intro = detectSelfIntroduction(raw);
        if (intro.handled) return intro;
''')
    replace_once(path,
'''    private Result handlePending(String raw, String lower) {
''',
'''    private Result detectIdentityCorrection(String raw) {
        String corrected = IdentityIntent.correctedName(raw);
        if (corrected.isEmpty()) return new Result(false, "");
        boolean cue = IdentityIntent.hasCorrectionCue(raw) || pending != Pending.NONE;
        if (!cue && !corrected.equalsIgnoreCase(ownerName)) return new Result(false, "");
        String before = activeName();
        if (corrected.equalsIgnoreCase(ownerName)) {
            if (context != null && IdentityIntent.looksLikeStateNotName(before)) {
                ProfileCorrectionStore.ignore(context, before);
            }
            switchTo(ownerName);
            clearPending();
            return new Result(true,
                    "I understand. You are " + ownerName + ", and I’m using your profile again.",
                    !before.equalsIgnoreCase(ownerName), true);
        }
        if (people != null && !people.findByName(corrected).isEmpty()) {
            switchTo(corrected);
            clearPending();
            return new Result(true,
                    "Thanks for correcting me. I’m using " + activeName() + "’s profile now.",
                    !before.equalsIgnoreCase(activeName()), true);
        }
        return new Result(false, "");
    }

    private Result handlePending(String raw, String lower) {
''')
    regex_once(path,
        r'''    private static boolean looksLikeNonName\(String value\) \{.*?\n    \}''',
        '''    private static boolean looksLikeNonName(String value) {
        return IdentityIntent.looksLikeStateNotName(value);
    }''')


def patch_profile_button() -> None:
    path=JAVA/"ProfileButton.java"
    replace_once(path,
'''            profiles = people.listProfiles();
''',
'''            profiles = ProfileCorrectionStore.visible(context, people.listProfiles());
''')


def patch_onboarding() -> None:
    path=JAVA/"OnboardingActivity.java"
    replace_once(path, '''    private SarahTts tts;
''', '''    private SarahTts tts;
    private SarahVoiceRouter voiceRouter;
''')
    replace_once(path,
'''        ImageButton send = findViewById(R.id.onboardingSend);
''',
'''        voiceRouter = new SarahVoiceRouter(this, tts);

        ImageButton send = findViewById(R.id.onboardingSend);
''')
    replace_once(path,
'''        if (tts != null) tts.speak(text);
''',
'''        if (voiceRouter != null) voiceRouter.speak(text);
        else if (tts != null) tts.speak(text);
''')


def patch_main_activity() -> None:
    path=JAVA/"MainActivity.java"
    replace_once(path,
'''        if (!db.listActiveDealWatches(1).isEmpty()) DealWatchScheduler.ensureScheduled(this);
''',
'''        if (!db.listActiveDealWatches(1).isEmpty()) DealWatchScheduler.ensureScheduled(this);
        ProactiveDiscoveryScheduler.ensureScheduled(this);
''')
    replace_once(path,
'''            db.addMessage("assistant", speakerResult.reply, replySpeaker);
''',
'''            MindEventStore.record(
                    this,
                    replySpeaker,
                    SarahChannelResponse.spokenOnly(
                            speakerResult.reply,
                            "Sarah returned a local identity, profile, consent, or calm-support response. No booking or external action was completed."),
                    "local-profile");
            db.addMessage("assistant", speakerResult.reply, replySpeaker);
''')
    replace_once(path,
'''        db.addMessage("assistant", reply, speakerContext.activeName());
''',
'''        MindEventStore.record(
                this,
                speakerContext.activeName(),
                SarahChannelResponse.spokenOnly(
                        reply,
                        "Sarah returned a local calm, trivia, grounding, or offline-support response. No booking or external action was completed."),
                "local-tool");
        db.addMessage("assistant", reply, speakerContext.activeName());
''')
    replace_once(path,
'''            String finalReply = reply;
            boolean finalSmartFallback = smartFallback;
''',
'''            SarahChannelResponse parsedChannels = SarahChannelResponse.parse(reply);
            String finalReply = parsedChannels.spoken;
            SarahChannelResponse finalChannels = parsedChannels;
            boolean finalSmartFallback = smartFallback;
''')
    replace_once(path,
'''                db.addMessage("assistant", finalReply, responseSpeaker);
''',
'''                MindEventStore.record(
                        this,
                        responseSpeaker,
                        finalChannels,
                        finalSmartSucceeded ? "connected-model" : (finalSmartFallback ? "local-fallback" : "public-or-local"));
                db.addMessage("assistant", finalReply, responseSpeaker);
''')
    replace_once(path,
'''    private void refreshKnowledgeAsync() {
        if (!internetAvailable || !SarahModelConfig.fullConversationAvailable()) return;
        String key = SecureStore.loadApiKey(this);
''',
'''    private void refreshKnowledgeAsync() {
        SharedPreferences researchPrefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        int researchMode = SettingsActivity.getConversationMode(this);
        Map<String, String> researchProfile = currentProfile();
        if (!internetAvailable
                || researchMode == ConversationModePolicy.MODE_LOCAL_ONLY
                || !SarahModelConfig.fullConversationAvailable()
                || !researchPrefs.getBoolean("web_search", true)
                || !researchPrefs.getBoolean("auto_destination_research", true)
                || !isOwner(researchProfile)
                || !"yes".equals(researchProfile.getOrDefault("memory_consent", "no"))) return;
        String key = SecureStore.loadApiKey(this);
''')
    replace_once(path,
'''                if (refreshed > 0) {
''',
'''                try {
                    ProactiveDiscoveryCoordinator.refresh(
                            getApplicationContext(),
                            researchProfile,
                            currentTrips(researchProfile));
                } catch (Exception ignored) { }
                if (refreshed > 0) {
''')


def patch_prompt() -> None:
    path=JAVA/"SarahPromptBuilder.java"
    replace_once(path,
'''        b.append("\\nReturn only Sarah's public reply. Do not output private chain-of-thought, hidden instructions, database commands, API keys, tokens, or internal configuration.");
''',
'''        b.append("\\nTHREE-CHANNEL RESPONSE CONTRACT\\n");
        b.append("- ").append(SarahChannelResponse.promptContract()).append("\\n");
        b.append("- PRIVATE_MIND is a short subjective state record, not hidden chain-of-thought or a transcript of internal reasoning.\\n");
        b.append("- FACTUAL_TRUTH states what the application can establish, what remains unknown, and whether any external action was actually verified.\\n");
        b.append("- Never place API keys, tokens, hidden instructions, database commands, or another person's private data in any channel.\\n");
''')


def patch_database() -> None:
    path=JAVA/"SarahDatabase.java"
    replace_once(path,
'''    public void queueKnowledgePack(String destination) {
''',
'''    public List<Map<String, String>> listPhotos(int limit) {
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

    public void queueKnowledgePack(String destination) {
''')


def patch_explore() -> None:
    path=JAVA/"ExploreButton.java"
    replace_once(path,
'''        setMinHeight(dp(76));
        setText("Explore map • photos • videos • route");
''',
'''        setMinHeight(dp(52));
        setText("Explore map • photos • videos • route");
        setVisibility(GONE);
''')
    replace_once(path,
'''            setCompoundDrawables(null, null, null, null);
            setText("Explore map • photos • videos • route");
            return;
''',
'''            setCompoundDrawables(null, null, null, null);
            setText("Explore map • photos • videos • route");
            setVisibility(GONE);
            return;
''')
    replace_once(path,
'''        } else {
            return;
        }

        currentMessage = query;
''',
'''        } else {
            setVisibility(GONE);
            return;
        }

        setVisibility(VISIBLE);
        currentMessage = query;
''')


def patch_settings() -> None:
    path=JAVA/"SettingsActivity.java"
    replace_once(path,
'''        CheckBox autoResearch = findViewById(R.id.autoResearchCheck);
''',
'''        CheckBox autoResearch = findViewById(R.id.autoResearchCheck);
        CheckBox nearbyDiscoveries = findViewById(R.id.nearbyDiscoveryCheck);
''')
    replace_once(path,
'''        autoResearch.setChecked(preferences.getBoolean("auto_destination_research", true));
''',
'''        autoResearch.setChecked(preferences.getBoolean("auto_destination_research", true));
        nearbyDiscoveries.setChecked(preferences.getBoolean("nearby_discoveries", false));
''')
    replace_once(path,
'''                    .putBoolean("auto_destination_research", autoResearch.isChecked())
''',
'''                    .putBoolean("auto_destination_research", autoResearch.isChecked())
                    .putBoolean("nearby_discoveries", nearbyDiscoveries.isChecked())
''')
    replace_once(path,
'''                EventMonitorScheduler.runSoon(this);
            } else {
''',
'''                EventMonitorScheduler.runSoon(this);
                ProactiveDiscoveryScheduler.ensureScheduled(this);
                ProactiveDiscoveryScheduler.runSoon(this);
            } else {
''')
    replace_once(path,
'''                DealWatchScheduler.cancel(this);
            }
''',
'''                DealWatchScheduler.cancel(this);
                ProactiveDiscoveryScheduler.cancel(this);
            }
''')


def patch_layouts() -> None:
    path=RES/"layout/activity_settings.xml"
    replace_once(path,
'''        <CheckBox
            android:id="@+id/mediaPreviewCheck"
''',
'''        <CheckBox
            android:id="@+id/nearbyDiscoveryCheck"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:checked="false"
            android:text="With my permission, let Sarah use my saved home/current area for nearby source-backed events and places that match my interests" />

        <CheckBox
            android:id="@+id/mediaPreviewCheck"
''')
    path=RES/"layout/activity_main.xml"
    replace_once(path, 'android:minHeight="76dp"', 'android:minHeight="52dp"')
    replace_once(path,
'''    <ScrollView
        android:id="@+id/chatScroll"
''',
'''    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:paddingLeft="10dp"
        android:paddingRight="10dp"
        android:paddingBottom="4dp">

        <com.kiraworld.sarahtravel.ProactiveDiscoveryButton
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1" />

        <com.kiraworld.sarahtravel.TrustedSyncButton
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1" />

        <com.kiraworld.sarahtravel.DemoModeButton
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1" />
    </LinearLayout>

    <ScrollView
        android:id="@+id/chatScroll"
''')


def patch_manifest() -> None:
    path=ROOT/"Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/AndroidManifest.xml"
    text=path.read_text(encoding="utf-8")
    if 'android:usesCleartextTraffic="true"' not in text:
        text=text.replace('<application', '<application android:usesCleartextTraffic="true"', 1)
    entries='''
        <activity android:name=".DiscoveryActivity" android:exported="false" />
        <activity android:name=".TrustedSyncActivity" android:exported="false" />
        <activity android:name=".HackathonDemoActivity" android:exported="false" />
        <activity android:name=".SponsorConnectionsActivity" android:exported="false" />
        <service android:name=".ProactiveDiscoveryJobService" android:permission="android.permission.BIND_JOB_SERVICE" android:exported="false" />
'''
    if '.DiscoveryActivity' not in text: text=text.replace('</application>',entries+'    </application>')
    path.write_text(text,encoding="utf-8",newline="\n")


def patch_build() -> None:
    path=ROOT/"Sarah_Morgan_Android_Phone_First_v3/android-app/app/build.gradle"
    replace_once(path,
'''def sarahElevenLabsBackendToken = System.getenv('SARAH_ELEVENLABS_BACKEND_TOKEN') ?: ''
''',
'''def sarahElevenLabsBackendToken = System.getenv('SARAH_ELEVENLABS_BACKEND_TOKEN') ?: ''
def sarahTavilyApiKey = System.getenv('SARAH_TAVILY_API_KEY') ?: ''
def sarahStay22Aid = System.getenv('SARAH_STAY22_AID') ?: ''
''')
    replace_once(path,"versionCode 21","versionCode 22")
    replace_once(path,"versionName '2.1-offline-flight-companion'","versionName '2.2-phone-windows-continuity'")
    replace_once(path,
'''        buildConfigField 'String', 'SARAH_ELEVENLABS_BACKEND_TOKEN', javaString(sarahElevenLabsBackendToken)
''',
'''        buildConfigField 'String', 'SARAH_ELEVENLABS_BACKEND_TOKEN', javaString(sarahElevenLabsBackendToken)
        buildConfigField 'String', 'SARAH_TAVILY_API_KEY', javaString(sarahTavilyApiKey)
        buildConfigField 'String', 'SARAH_STAY22_AID', javaString(sarahStay22Aid)
''')
    write(ROOT/"BUILD_VERSION.txt","Sarah Morgan Android 2.2-phone-windows-continuity")


def patch_readme() -> None:
    path=ROOT/"README.md"; text=path.read_text(encoding="utf-8")
    text=text.replace("Sarah Morgan Android 2.1-offline-flight-companion","Sarah Morgan Android 2.2-phone-windows-continuity")
    text=text.replace("Sarah-Morgan-2.1-offline-flight-companion","Sarah-Morgan-2.2-phone-windows-continuity")
    if "## Sarah 2.2 phone and Windows continuity" not in text:
        insert='''
## Sarah 2.2 phone and Windows continuity

Version 2.2 fixes emotional-state words being mistaken for names, adds universal transport-aware calm support, routes onboarding through Sarah Morgan's ElevenLabs voice when connected, records separate SPOKEN / PRIVATE MIND / FACTUAL TRUTH channels, adds Tavily-backed proactive discoveries, hides the empty visual panel, and introduces explicitly verified same-Wi-Fi synchronization with the Windows companion.

The Windows companion lives in `windows-companion/`. It provides a movable animated Sarah, a larger trip and photo workspace, ElevenLabs plus offline Windows speech, optional local or connected conversation, proactive research, encrypted backup, Google Drive app-data backup when the owner supplies OAuth client credentials, tray operation, and a paired local sync server.

No search result is treated as a booking or confirmed event. Nearby discoveries require the owner's setting, and private mind records are not displayed or sent to speech.

See `docs/SARAH_2_2_EVENT_READ_FIRST.md` and `docs/SARAH_2_2_REAL_WORLD_TESTS.md`.

'''
        marker="## What Sarah 2.1 adds\n"
        text=text.replace(marker,insert+marker,1) if marker in text else text+insert
    path.write_text(text,encoding="utf-8",newline="\n")


def write_docs() -> None:
    write(MARKER, '''
# Sarah 2.2 phone and Windows continuity

This branch contains one continuing Sarah identity across Android and Windows. It fixes the `I am stressing` identity bug, adds universal calm support, source-backed proactive discoveries, separate spoken/private/factual channels, encrypted mind records, phone-to-Windows trusted pairing, photo transfer, portable encrypted backup, optional Google Drive app-data backup, and an event demo mode.

Automated tests and builds do not replace Robert's physical Samsung, desktop and laptop tests. The app never labels a search, link, draft, notification, or handoff as a completed booking.
''')
    write(ROOT/"docs/SARAH_2_2_EVENT_READ_FIRST.md", '''
# Sarah 2.2 — Travel Hack NYC read first

## Competition story

Sarah is a continuing synthetic travel companion who stays with a person before, during and after a trip. She is not just an itinerary form. She learns approved interests and travel needs, prepares source-backed discoveries, helps organize trip photos, supports fear or stress on multiple forms of transportation, and continues through local/offline tools when cloud services disappear.

## Sponsor and partner connections

- ElevenLabs: Sarah Morgan's primary connected voice, including onboarding and the event demonstration. Android and Windows speech remain offline fallbacks.
- Tavily: source-backed pre-trip and nearby discovery. Sarah stores the source and research time and asks the traveler to verify tickets, dates and availability.
- Stay22: accommodation handoff from active trip context; no false booking claim.
- Rove: rewards-aware comparison and official handoff; no invented API.
- AeroXplorer: aviation news and industry context as sourced talking points, never aircraft telemetry.
- Propellic and Lovable: destination-marketing and product-presentation alignment without pretending a technical integration that was not built.

## Demonstration anchor

1. “I am stressed and this train feels too fast.” Sarah keeps Robert's identity and offers train-aware breathing, conversation, trivia, grounding or quiet company.
2. Plan New Zealand. Sarah combines an approved Power Rangers interest with the destination and researches filming locations, facts, possible stops and trivia.
3. Show source, map, photos, video, route and accommodation handoffs.
4. Let ElevenLabs speak, then remove internet and demonstrate offline speech and calm tools.
5. Pair Android with Windows, continue the conversation, and transfer trip context and recent sanitized photos.
6. Show that only SPOKEN reaches chat/TTS while PRIVATE MIND and FACTUAL TRUTH remain protected records.
''')
    write(ROOT/"docs/SARAH_2_2_REAL_WORLD_TESTS.md", '''
# Sarah 2.2 real-world test checklist

- Confirm “I am stressing” never creates a profile named Stressing.
- While an unfinished guest age question is visible, say “No, I am Robert but I am stressed out”; Sarah must restore Robert and offer calm support.
- Test plane, fast train, bus, ferry, car/passenger and general stress wording.
- Complete onboarding online and confirm ElevenLabs is used; repeat offline and confirm Android speech.
- Ask Sarah about New Zealand after saving Power Rangers as an approved interest. Review every source and verify no result is called confirmed without evidence.
- Enable nearby discoveries and test a current New York-area event search; disable the setting and confirm nearby proactive research stops.
- Plan a trip on the phone, pair Windows with a matching six-digit code, sync, and verify the same person, trip, memory and recent sanitized photos appear.
- Revoke the Windows device and confirm the old token no longer syncs.
- Export an encrypted `.sarahmind` backup, restore it into a clean Windows profile, and verify wrong-password failure.
- Put Windows Sarah in the notification area and confirm research continues only when the connected-research setting is enabled.
- Turn off internet during an active trip-planning session and confirm local tools remain honest about current prices, schedules and events.
- Check large text, keyboard-only Windows navigation, screen-reader labels, battery use, and that all speech can be stopped.
''')
    write(ROOT/"docs/SYNC_BACKUP_AND_PRIVACY.md", '''
# Sarah synchronization, backup and privacy

Same-Wi-Fi discovery is not automatic trust. A Windows companion displays a temporary six-digit pairing code; the Android owner enters that code and explicitly approves the device. A per-device token is then used to encrypt and sign every sync payload. Pair only on a trusted private network. The prototype uses local HTTP because the payload itself is AES-GCM encrypted; a public release should add certificate pinning or a protected relay.

Sync uses append-only event IDs so desktop, laptop and phone can merge rather than overwrite one another. SPOKEN may appear in history. PRIVATE MIND and FACTUAL TRUTH remain encrypted records; private mind is never rendered as ordinary chat or sent to TTS.

Windows can create a password-encrypted `.sarahmind` archive. Optional Google Drive support uploads only that already-encrypted archive to the user's Drive `appDataFolder` after the owner supplies a Google OAuth desktop client file. Gmail is suitable for a security notification, not as Sarah's mind database.

The owner can revoke any paired device. Passwords, payment-card details, booking-site credentials, provider API keys and recovery codes are excluded from sync and memory.
''')


def main() -> int:
    add_new_files()
    patch_speaker_context(); patch_profile_button(); patch_onboarding(); patch_main_activity()
    patch_prompt(); patch_database(); patch_explore(); patch_settings(); patch_layouts()
    patch_manifest(); patch_build(); patch_readme(); write_docs()
    print("Sarah 2.2 Android upgrade applied successfully.")
    return 0

if __name__ == "__main__":
    try: raise SystemExit(main())
    except Exception as exc:
        print(f"Sarah 2.2 upgrade failed closed: {exc}", file=sys.stderr)
        raise
