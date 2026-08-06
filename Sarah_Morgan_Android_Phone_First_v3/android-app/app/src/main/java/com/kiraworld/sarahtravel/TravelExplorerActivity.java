package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URLEncoder;

/** Displays maps, public photos, videos, and route searches inside Sarah. */
public final class TravelExplorerActivity extends Activity {
    public static final String EXTRA_KIND = "kind";
    public static final String EXTRA_QUERY = "query";
    public static final String EXTRA_ORIGIN = "origin";
    public static final String EXTRA_DESTINATION = "destination";
    public static final String EXTRA_MODE = "mode";

    private WebView webView;
    private String currentUrl;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String kind = getIntent().getStringExtra(EXTRA_KIND);
        String query = getIntent().getStringExtra(EXTRA_QUERY);
        String origin = getIntent().getStringExtra(EXTRA_ORIGIN);
        String destination = getIntent().getStringExtra(EXTRA_DESTINATION);
        String mode = getIntent().getStringExtra(EXTRA_MODE);
        currentUrl = buildUrl(kind, query, origin, destination, mode);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(250, 247, 240));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView title = new TextView(this);
        title.setText(title(kind, query));
        title.setTextSize(19f);
        title.setTextColor(Color.rgb(34, 57, 72));
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button external = new Button(this);
        external.setText("Open outside Sarah");
        external.setAllCaps(false);
        external.setOnClickListener(v -> openExternal());
        top.addView(title);
        top.addView(external);
        root.addView(top);

        webView = new WebView(this);
        webView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null) return false;
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { }
                return true;
            }
        });
        root.addView(webView);
        setContentView(root);
        webView.loadUrl(currentUrl);
    }

    private void openExternal() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)));
        } catch (Exception e) {
            Toast.makeText(this, "No browser could open this source.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    private static String buildUrl(
            String kind,
            String query,
            String origin,
            String destination,
            String mode) {
        String safeKind = kind == null ? "map" : kind;
        String q = encode(query == null ? "travel destination" : query);
        if ("photos".equals(safeKind)) {
            return "https://commons.wikimedia.org/w/index.php?search=" + q
                    + "&title=Special:MediaSearch&type=image";
        }
        if ("videos".equals(safeKind)) {
            return "https://www.youtube.com/results?search_query=" + q + "+travel+guide";
        }
        if ("route".equals(safeKind)) {
            String travelMode = "driving";
            if (JourneyIntentParser.BIKE.equals(mode)) travelMode = "bicycling";
            else if (JourneyIntentParser.WALK.equals(mode)) travelMode = "walking";
            else if (!JourneyIntentParser.DRIVE.equals(mode)) travelMode = "transit";
            return "https://www.google.com/maps/dir/?api=1&origin="
                    + encode(origin == null ? "" : origin)
                    + "&destination=" + encode(destination == null ? "" : destination)
                    + "&travelmode=" + travelMode;
        }
        return "https://www.openstreetmap.org/search?query=" + q;
    }

    private static String title(String kind, String query) {
        String label = "map";
        if ("photos".equals(kind)) label = "photos";
        else if ("videos".equals(kind)) label = "videos";
        else if ("route".equals(kind)) label = "route";
        return "Sarah: " + label + " for "
                + (query == null || query.trim().isEmpty() ? "this trip" : query.trim());
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception ignored) {
            return Uri.encode(value == null ? "" : value);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
