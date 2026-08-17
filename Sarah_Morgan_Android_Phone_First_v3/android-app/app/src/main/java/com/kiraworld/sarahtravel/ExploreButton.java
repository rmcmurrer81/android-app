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

import java.util.Collections;
import java.util.LinkedHashMap;
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
    private Map<String, String> currentProfile = Collections.emptyMap();
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
        setMinHeight(dp(52));
        setText("Explore map • photos • videos • route");
        setVisibility(GONE);
        setOnClickListener(v -> openExplorer());
        setOnLongClickListener(v -> {
            loadedQuery = "";
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
            setVisibility(GONE);
            return;
        }

        SarahDatabase db = new SarahDatabase(context.getApplicationContext());
        List<Map<String, String>> messages;
        Map<String, String> ownerProfile;
        Map<String, String> profile;
        try {
            ownerProfile = db.getProfile();
            profile = activeProfile(context, ownerProfile);
            messages = db.recentMessagesForSpeaker(
                    profile.getOrDefault("name", ownerProfile.getOrDefault("name", "")),
                    40);
        } finally {
            db.close();
        }

        String latest = latestUserMessage(messages);
        KnownEventCatalog.Entry knownEvent = recentKnownEvent(messages, latest);
        String unfamiliarEvent = GenericEventReference.recentEvent(messages, latest);
        Map<String, String> storedEvent = unfamiliarEvent.isEmpty()
                ? Collections.emptyMap() : findStoredEvent(context, unfamiliarEvent);
        TravelMediaHelper.Tools tools = TravelMediaHelper.resolve(latest, profile, messages);

        String query;
        String destination;
        if (knownEvent != null) {
            query = knownEvent.eventName + " " + knownEvent.destination;
            destination = knownEvent.destination;
        } else if (!unfamiliarEvent.isEmpty()) {
            destination = storedEvent.getOrDefault("destination", "");
            query = unfamiliarEvent + (destination.isEmpty() ? "" : " " + destination);
        } else if (tools.available && !tools.query.isEmpty()) {
            query = tools.query.trim();
            destination = tools.destination;
        } else {
            setVisibility(GONE);
            return;
        }

        setVisibility(VISIBLE);
        currentMessage = query;
        currentProfile = profile;
        if (query.equalsIgnoreCase(loadedQuery)) return;
        loadedQuery = query;
        setCompoundDrawables(null, null, null, null);
        setText(query + "\nLoading a public photo…\nTap for map, more photos, videos, route, and official sources");

        String knownDestination = knownEvent == null ? "" : knownEvent.destination;
        MEDIA_EXECUTOR.submit(() -> {
            PublicMediaGateway.MediaItem item = PublicMediaGateway.findFirst(
                    query,
                    knownDestination,
                    destination,
                    destination == null || destination.isEmpty() ? "" : destination + " landmarks");
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
        int targetHeight = dp(170);
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
        Map<String, String> profile = currentProfile.isEmpty() ? loadOwnerProfile(context) : currentProfile;
        TravelSearchHelper.show((Activity) context, query, profile);
    }

    private static Map<String, String> activeProfile(
            Context context,
            Map<String, String> ownerProfile) {
        Map<String, String> result = new LinkedHashMap<>(ownerProfile);
        PersonProfileStore people = new PersonProfileStore(context.getApplicationContext());
        try {
            people.ensureOwner(ownerProfile);
            Map<String, String> active = people.getActiveProfile();
            if (!active.isEmpty()) {
                boolean owner = "yes".equals(active.get("is_owner"));
                if (!owner) result.clear();
                result.putAll(active);
                result.put("active_speaker", active.get("name"));
                result.put("active_speaker_is_owner", owner ? "yes" : "no");
            }
        } finally {
            people.close();
        }
        if (result.getOrDefault("hometown", "").isEmpty()) {
            result.put("hometown", ownerProfile.getOrDefault("hometown", ""));
        }
        return result;
    }

    private static Map<String, String> loadOwnerProfile(Context context) {
        SarahDatabase db = new SarahDatabase(context.getApplicationContext());
        try {
            return db.getProfile();
        } finally {
            db.close();
        }
    }

    private static Map<String, String> findStoredEvent(Context context, String eventName) {
        EventTripStore store = new EventTripStore(context.getApplicationContext());
        try {
            for (Map<String, String> event : store.listActiveEventTrips(50)) {
                if (eventName.equalsIgnoreCase(event.getOrDefault("event_name", ""))) return event;
            }
        } finally {
            store.close();
        }
        return Collections.emptyMap();
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
