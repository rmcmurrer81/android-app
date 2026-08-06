package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.Button;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Permanent chat control with a real public photo preview and travel-media tools. */
public final class ExploreButton extends Button {
    private static final ExecutorService MEDIA_EXECUTOR = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String loadedQuery = "";
    private String currentMessage = "";
    private Map<String, String> currentProfile = Map.of();
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refreshPreview();
            handler.postDelayed(this, 3000L);
        }
    };

    public ExploreButton(Context context) {
        super(context);
        initialize();
    }

    public ExploreButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public ExploreButton(Context context, AttributeSet attrs, int style) {
        super(context, attrs, style);
        initialize();
    }

    private void initialize() {
        setAllCaps(false);
        setGravity(Gravity.CENTER);
        setCompoundDrawablePadding(dp(8));
        setPadding(dp(12), dp(10), dp(12), dp(10));
        setMinHeight(dp(76));
        setText("Explore map • photos • videos • route");
        setOnClickListener(v -> openExplorer());
        setOnLongClickListener(v -> {
            refreshPreview();
            return true;
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(poll);
        super.onDetachedFromWindow();
    }

    private void refreshPreview() {
        Context context = getContext();
        SharedPreferences preferences = context.getSharedPreferences(
                SettingsActivity.PREFS,
                Context.MODE_PRIVATE);
        if (!preferences.getBoolean("inline_media_previews", true)) {
            setCompoundDrawables(null, null, null, null);
            setText("Explore map • photos • videos • route");
            return;
        }

        SarahDatabase db = new SarahDatabase(context.getApplicationContext());
        List<Map<String, String>> messages;
        Map<String, String> profile;
        try {
            messages = db.recentMessages(40);
            profile = db.getProfile();
        } finally {
            db.close();
        }
        String latest = latestUserMessage(messages);
        TravelMediaHelper.Tools tools = TravelMediaHelper.resolve(latest, profile, messages);
        if (!tools.available || tools.query.isEmpty()) return;

        currentMessage = tools.query;
        currentProfile = profile;
        String query = tools.query.trim();
        if (query.equalsIgnoreCase(loadedQuery)) return;
        loadedQuery = query;
        setText(query + "\nLoading a public photo…\nTap for map, more photos, videos, route, and official sources");

        String destination = tools.destination;
        KnownEventCatalog.Entry event = recentKnownEvent(messages, latest);
        String eventDestination = event == null ? "" : event.destination;
        MEDIA_EXECUTOR.submit(() -> {
            PublicMediaGateway.MediaItem item = PublicMediaGateway.findFirst(
                    query,
                    eventDestination,
                    destination,
                    destination.isEmpty() ? "" : destination + " landmarks");
            post(() -> applyMedia(query, item));
        });
    }

    private void applyMedia(String query, PublicMediaGateway.MediaItem item) {
        if (!query.equalsIgnoreCase(loadedQuery)) return;
        if (item == null || !item.found()) {
            setText(query + "\nTap for map, public photos, videos, route, and official sources");
            return;
        }
        Bitmap decoded = BitmapFactory.decodeByteArray(item.imageBytes, 0, item.imageBytes.length);
        if (decoded == null) return;
        int targetWidth = Math.max(dp(260), getResources().getDisplayMetrics().widthPixels - dp(56));
        int targetHeight = dp(150);
        float scale = Math.min((float) targetWidth / decoded.getWidth(), (float) targetHeight / decoded.getHeight());
        int width = Math.max(1, Math.round(decoded.getWidth() * scale));
        int height = Math.max(1, Math.round(decoded.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(decoded, width, height, true);
        if (scaled != decoded) decoded.recycle();
        BitmapDrawable drawable = new BitmapDrawable(getResources(), scaled);
        drawable.setBounds(0, 0, width, height);
        setCompoundDrawables(null, drawable, null, null);
        setText(query + "\nPublic photo preview • tap for map, more photos, videos, route, and sources");
        setContentDescription("Public photo preview for " + query + ". Tap for map, photos, videos, route, and official sources.");
    }

    private void openExplorer() {
        Context context = getContext();
        if (!(context instanceof Activity)) return;
        if (currentMessage.isEmpty()) refreshPreview();
        String query = currentMessage.isEmpty() ? "travel ideas" : currentMessage;
        Map<String, String> profile = currentProfile.isEmpty() ? loadProfile(context) : currentProfile;
        TravelSearchHelper.show((Activity) context, query, profile);
    }

    private static Map<String, String> loadProfile(Context context) {
        SarahDatabase db = new SarahDatabase(context.getApplicationContext());
        try {
            return db.getProfile();
        } finally {
            db.close();
        }
    }

    private static KnownEventCatalog.Entry recentKnownEvent(
            List<Map<String, String>> messages,
            String current) {
        KnownEventCatalog.Entry direct = KnownEventCatalog.find(current);
        if (direct != null) return direct;
        int inspected = 0;
        for (int i = messages.size() - 1; i >= 0 && inspected < 16; i--, inspected++) {
            String content = messages.get(i).getOrDefault("content", "");
            KnownEventCatalog.Entry entry = KnownEventCatalog.find(content);
            if (entry != null) return entry;
        }
        return null;
    }

    private static String latestUserMessage(List<Map<String, String>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, String> row = messages.get(i);
            if (!"user".equalsIgnoreCase(row.getOrDefault("role", ""))) continue;
            String content = row.getOrDefault("content", "").trim();
            if (!content.isEmpty()) return content;
        }
        return "";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
