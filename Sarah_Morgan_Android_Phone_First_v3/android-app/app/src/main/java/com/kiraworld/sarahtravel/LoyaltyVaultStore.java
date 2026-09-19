package com.kiraworld.sarahtravel;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

/** Stores program names and member identifiers, never passwords. */
public final class LoyaltyVaultStore {
    public static final class Entry {
        public final String id;
        public final String program;
        public final String kind;
        public final String memberId;
        public final String tier;
        public final String website;
        public final String notes;

        Entry(
                String id,
                String program,
                String kind,
                String memberId,
                String tier,
                String website,
                String notes) {
            this.id = clean(id);
            this.program = clean(program);
            this.kind = clean(kind);
            this.memberId = clean(memberId);
            this.tier = clean(tier);
            this.website = clean(website);
            this.notes = clean(notes);
        }
    }

    private static final String NAMESPACE = "loyalty";

    private LoyaltyVaultStore() { }

    public static List<Entry> list(Context context, String personId) {
        List<Entry> entries = new ArrayList<>();
        String raw = SecureProfileVault.get(context, NAMESPACE, personId);
        if (raw.isEmpty()) return entries;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                Entry entry = fromJson(item);
                if (!entry.program.isEmpty()) entries.add(entry);
            }
        } catch (Exception ignored) { }
        return entries;
    }

    public static void add(
            Context context,
            String personId,
            String program,
            String kind,
            String memberId,
            String tier,
            String website,
            String notes) {
        List<Entry> entries = list(context, personId);
        String id = String.valueOf(System.currentTimeMillis());
        entries.add(new Entry(id, program, kind, memberId, tier, website, notes));
        save(context, personId, entries);
    }

    public static void remove(Context context, String personId, String id) {
        List<Entry> kept = new ArrayList<>();
        for (Entry entry : list(context, personId)) {
            if (!entry.id.equals(id)) kept.add(entry);
        }
        save(context, personId, kept);
    }

    public static String summary(Context context, String personId) {
        StringBuilder out = new StringBuilder();
        for (Entry entry : list(context, personId)) {
            if (out.length() > 0) out.append("; ");
            out.append(entry.program);
            if (!entry.tier.isEmpty()) out.append(" (").append(entry.tier).append(")");
            if (out.length() > 300) break;
        }
        return out.toString();
    }

    public static boolean moveProfile(Context context, String oldPersonId, String newPersonId) {
        String priorRaw = SecureProfileVault.get(context, NAMESPACE, oldPersonId);
        String confirmedRaw = SecureProfileVault.get(context, NAMESPACE, newPersonId);
        List<Entry> prior = list(context, oldPersonId);
        if (prior.isEmpty()) return true;
        if (confirmedRaw.isEmpty()) {
            return SecureProfileVault.moveIfTargetEmpty(
                    context, NAMESPACE, oldPersonId, newPersonId);
        }
        if (priorRaw.equals(confirmedRaw)) {
            return SecureProfileVault.removeVerified(
                    context, NAMESPACE, oldPersonId);
        }
        if (!ProfileMigrationArchiveStore.preserveCollision(
                context,
                NAMESPACE,
                oldPersonId,
                newPersonId,
                priorRaw,
                confirmedRaw)) return false;

        List<Entry> merged = new ArrayList<>(list(context, newPersonId));
        Set<String> payloads = new LinkedHashSet<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Entry entry : merged) {
            payloads.add(payload(entry));
            ids.add(entry.id);
        }
        for (Entry entry : prior) {
            String payload = payload(entry);
            if (payloads.contains(payload)) continue;
            String id = entry.id;
            if (id.isEmpty() || ids.contains(id)) {
                id = ProfileMigrationPolicy.migratedRecordId(NAMESPACE, payload);
            }
            int collision = 1;
            String base = id;
            while (ids.contains(id)) id = base + "-" + collision++;
            Entry migrated = new Entry(
                    id,
                    entry.program,
                    entry.kind,
                    entry.memberId,
                    entry.tier,
                    entry.website,
                    entry.notes);
            merged.add(migrated);
            ids.add(id);
            payloads.add(payload);
        }
        String mergedRaw = serialize(merged);
        if (!SecureProfileVault.putVerified(
                context, NAMESPACE, newPersonId, mergedRaw)) return false;
        Set<String> verifiedPayloads = new LinkedHashSet<>();
        for (Entry entry : list(context, newPersonId)) verifiedPayloads.add(payload(entry));
        if (!verifiedPayloads.containsAll(payloads)) return false;
        if (!ProfileMigrationArchiveStore.containsExact(
                context,
                NAMESPACE,
                oldPersonId,
                newPersonId,
                priorRaw,
                confirmedRaw)) return false;
        return SecureProfileVault.removeVerified(
                context, NAMESPACE, oldPersonId);
    }

    private static void save(Context context, String personId, List<Entry> entries) {
        SecureProfileVault.put(context, NAMESPACE, personId, serialize(entries));
    }

    private static String serialize(List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", entry.id);
                item.put("program", entry.program);
                item.put("kind", entry.kind);
                item.put("member_id", entry.memberId);
                item.put("tier", entry.tier);
                item.put("website", entry.website);
                item.put("notes", entry.notes);
                array.put(item);
            } catch (Exception ignored) { }
        }
        return array.toString();
    }

    private static Entry fromJson(JSONObject item) {
        return new Entry(
                item.optString("id", ""),
                item.optString("program", ""),
                item.optString("kind", ""),
                item.optString("member_id", ""),
                item.optString("tier", ""),
                item.optString("website", ""),
                item.optString("notes", ""));
    }

    private static String identity(Entry entry) {
        return (entry.program + "|" + entry.kind + "|" + entry.memberId)
                .trim().toLowerCase(Locale.US);
    }

    private static String payload(Entry entry) {
        JSONObject item = new JSONObject();
        try {
            item.put("program", entry.program);
            item.put("kind", entry.kind);
            item.put("member_id", entry.memberId);
            item.put("tier", entry.tier);
            item.put("website", entry.website);
            item.put("notes", entry.notes);
        } catch (Exception ignored) { }
        return item.toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
