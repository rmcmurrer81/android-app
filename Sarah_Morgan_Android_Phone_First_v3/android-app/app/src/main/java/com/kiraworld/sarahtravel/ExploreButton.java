package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.Button;

import java.util.List;
import java.util.Map;

/** Permanent chat control for maps, public photos, videos, routes, and live sources. */
public final class ExploreButton extends Button {
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
        setOnClickListener(v -> openExplorer());
    }

    private void openExplorer() {
        Context context = getContext();
        if (!(context instanceof Activity)) return;
        SarahDatabase db = new SarahDatabase(context.getApplicationContext());
        try {
            String query = latestUserMessage(db.recentMessages(40));
            if (query.isEmpty()) query = db.getProfile().getOrDefault("hometown", "travel ideas");
            TravelSearchHelper.show((Activity) context, query, db.getProfile());
        } finally {
            db.close();
        }
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
}
