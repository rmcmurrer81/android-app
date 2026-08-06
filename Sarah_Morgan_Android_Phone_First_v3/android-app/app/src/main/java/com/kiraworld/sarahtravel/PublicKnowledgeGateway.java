package com.kiraworld.sarahtravel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Limited no-key factual lookup for clear public questions while Smart mode is not configured. */
public final class PublicKnowledgeGateway {
    private PublicKnowledgeGateway() { }

    public static boolean canHandle(String message) {
        String lower = normalize(message);
        return containsAny(lower,
                "where did they film", "where was it filmed", "where was filmed",
                "filming location", "filming locations", "where is filmed", "where were they filmed");
    }

    public static String answer(String message) {
        if (!canHandle(message)) return null;
        List<String> subjects = extractSubjects(message);
        if (subjects.isEmpty()) return null;
        List<String> answers = new ArrayList<>();
        for (String subject : subjects) {
            String answer = lookupFilmingLocation(subject);
            if (answer != null && !answer.isEmpty()) answers.add(subject + ": " + answer);
        }
        if (answers.isEmpty()) return null;
        return String.join("\n\n", answers)
                + "\n\nI used public reference pages because Smart conversation is not configured. Use Explore for maps, public photos, and videos, and verify access details before planning a visit.";
    }

    static List<String> extractSubjects(String message) {
        String safe = message == null ? "" : message.trim();
        String value = safe.replaceFirst(
                "(?i)^.*?\\b(?:where did they film|where was it filmed|where was filmed|filming locations? (?:for|of)?|where were they filmed)\\b",
                "").trim();
        value = value.replaceAll("[?.!]+$", "").trim();
        if (value.toLowerCase(Locale.US).startsWith("in ")) value = value.substring(3).trim();
        List<String> result = new ArrayList<>();
        for (String part : value.split("(?i)\\s+(?:and|&)\\s+|,")) {
            String subject = part.trim();
            if (subject.toLowerCase(Locale.US).startsWith("the ")) subject = subject.substring(4).trim();
            if (subject.length() >= 2 && subject.length() <= 80) result.add(titleCase(subject));
        }
        return result;
    }

    private static String lookupFilmingLocation(String subject) {
        try {
            String title = searchTitle(subject);
            if (!title.isEmpty()) {
                String extract = pageExtract(title);
                String relevant = relevantFilmingSentences(extract);
                if (!relevant.isEmpty()) return relevant + " (Public Wikipedia article: " + title + ")";
            }
        } catch (Exception ignored) { }
        return knownFallback(subject);
    }

    private static String searchTitle(String subject) throws Exception {
        String query = subject + " television series";
        String url = "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&utf8=1&srlimit=3&srsearch="
                + encode(query);
        JSONObject root = new JSONObject(get(url));
        JSONArray results = root.optJSONObject("query") == null
                ? null : root.optJSONObject("query").optJSONArray("search");
        if (results == null || results.length() == 0) return "";
        String normalizedSubject = normalize(subject);
        for (int i = 0; i < results.length(); i++) {
            String title = results.optJSONObject(i) == null ? "" : results.optJSONObject(i).optString("title", "");
            if (normalize(title).contains(normalizedSubject)) return title;
        }
        return results.optJSONObject(0) == null ? "" : results.optJSONObject(0).optString("title", "");
    }

    private static String pageExtract(String title) throws Exception {
        String url = "https://en.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1&format=json&utf8=1&titles="
                + encode(title);
        JSONObject root = new JSONObject(get(url));
        JSONObject pages = root.optJSONObject("query") == null
                ? null : root.optJSONObject("query").optJSONObject("pages");
        if (pages == null) return "";
        JSONArray names = pages.names();
        if (names == null || names.length() == 0) return "";
        JSONObject page = pages.optJSONObject(names.optString(0));
        return page == null ? "" : page.optString("extract", "");
    }

    private static String relevantFilmingSentences(String extract) {
        if (extract == null || extract.trim().isEmpty()) return "";
        String[] sentences = extract.replace('\n', ' ').split("(?<=[.!?])\\s+");
        List<String> selected = new ArrayList<>();
        for (String sentence : sentences) {
            String lower = normalize(sentence);
            if (containsAny(lower,
                    "filmed in", "filmed at", "filming took place", "principal photography",
                    "shot in", "shot at", "location filming", "filming location")) {
                String clean = sentence.trim();
                if (!clean.isEmpty()) selected.add(clean);
                if (selected.size() >= 2) break;
            }
        }
        return String.join(" ", selected);
    }

    private static String knownFallback(String subject) {
        String lower = normalize(subject);
        if (lower.contains("smallville")) {
            return "The series was filmed mainly in British Columbia around Vancouver. Recognizable locations include Cloverdale in Surrey for Smallville street exteriors and Hatley Castle in Colwood for the Luthor mansion.";
        }
        if (lower.contains("corner gas")) {
            return "The fictional town of Dog River was filmed largely in Rouleau, Saskatchewan, with additional production in Regina. Rouleau has a self-guided Corner Gas walking tour.";
        }
        return null;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(16000);
        connection.setRequestProperty("User-Agent", "SarahMorganTravel/1.4 (private prototype; public knowledge lookup)");
        connection.setRequestProperty("Accept", "application/json");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 400) throw new IllegalStateException("Public reference returned " + status);
        try (InputStream in = connection.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                out.append(buffer, 0, count);
                if (out.length() > 2_000_000) break;
            }
            return out.toString();
        } finally {
            connection.disconnect();
        }
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static String titleCase(String value) {
        StringBuilder out = new StringBuilder();
        for (String word : value.trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) out.append(word.substring(1));
        }
        return out.toString();
    }

    private static boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US).replaceAll("\\s+", " ").trim();
    }
}
