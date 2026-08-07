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
