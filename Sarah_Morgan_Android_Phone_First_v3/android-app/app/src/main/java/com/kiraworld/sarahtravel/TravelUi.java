package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Small dependency-free visual system for Sarah's feature screens. */
public final class TravelUi {
    public static final int NAVY = Color.rgb(31, 52, 70);
    public static final int BLUE = Color.rgb(71, 126, 155);
    public static final int SKY = Color.rgb(222, 239, 247);
    public static final int CREAM = Color.rgb(255, 249, 239);
    public static final int INK = Color.rgb(30, 42, 52);
    public static final int LAVENDER = Color.rgb(221, 211, 244);
    public static final int MINT = Color.rgb(217, 242, 230);
    public static final int PEACH = Color.rgb(255, 226, 207);

    private TravelUi() { }

    public static LinearLayout page(Activity activity) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(CREAM);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 16), dp(activity, 18), dp(activity, 16), dp(activity, 30));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        activity.setContentView(scroll);
        SafeAreaInsets.apply(activity, scroll, null, scroll);
        return root;
    }

    public static TextView hero(
            Context context,
            String eyebrow,
            String title,
            String subtitle) {
        TextView view = new TextView(context);
        view.setText((eyebrow == null || eyebrow.isEmpty() ? "" : eyebrow.toUpperCase() + "\n")
                + title + (subtitle == null || subtitle.isEmpty() ? "" : "\n" + subtitle));
        view.setTextColor(Color.WHITE);
        view.setTextSize(18f);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setLineSpacing(0, 1.15f);
        view.setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 18));
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{NAVY, BLUE});
        background.setCornerRadius(dp(context, 24));
        view.setBackground(background);
        LinearLayout.LayoutParams params = matchWrap(context);
        params.setMargins(0, 0, 0, dp(context, 14));
        view.setLayoutParams(params);
        return view;
    }

    public static TextView section(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTag(text);
        view.setTextColor(NAVY);
        view.setTextSize(19f);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(dp(context, 2), dp(context, 18), dp(context, 2), dp(context, 8));
        return view;
    }

    /** Collapses direct-child section contents so a travel page is not one enormous tool list. */
    public static void makeSectionsCollapsible(LinearLayout root) {
        if (root == null) return;
        for (int index = 0; index < root.getChildCount(); index++) {
            View candidate = root.getChildAt(index);
            if (!(candidate instanceof TextView) || candidate.getTag() == null) continue;
            TextView header = (TextView) candidate;
            String title = String.valueOf(header.getTag());
            int next = index + 1;
            while (next < root.getChildCount()) {
                View following = root.getChildAt(next);
                if (following instanceof TextView && following.getTag() != null) break;
                following.setVisibility(View.GONE);
                next++;
            }
            final int from = index + 1;
            final int to = next;
            header.setText(title + "  ▸");
            header.setContentDescription(title + ", collapsed");
            header.setClickable(true);
            header.setFocusable(true);
            header.setOnClickListener(v -> {
                boolean open = from < to && root.getChildAt(from).getVisibility() != View.VISIBLE;
                for (int child = from; child < to; child++) {
                    root.getChildAt(child).setVisibility(open ? View.VISIBLE : View.GONE);
                }
                header.setText(title + (open ? "  ▾" : "  ▸"));
                header.setContentDescription(title + (open ? ", expanded" : ", collapsed"));
            });
        }
    }

    public static LinearLayout card(Context context, int backgroundColor) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 16), dp(context, 15), dp(context, 16), dp(context, 15));
        GradientDrawable background = new GradientDrawable();
        background.setColor(backgroundColor);
        background.setCornerRadius(dp(context, 20));
        background.setStroke(dp(context, 1), Color.argb(36, 31, 52, 70));
        card.setBackground(background);
        LinearLayout.LayoutParams params = matchWrap(context);
        params.setMargins(0, 0, 0, dp(context, 12));
        card.setLayoutParams(params);
        card.setElevation(dp(context, 2));
        return card;
    }

    public static TextView cardTitle(Context context, String icon, String title) {
        TextView view = new TextView(context);
        view.setText((icon == null || icon.isEmpty() ? "" : icon + "  ") + title);
        view.setTextColor(NAVY);
        view.setTextSize(18f);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    public static TextView body(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(INK);
        view.setTextSize(14.5f);
        view.setLineSpacing(0, 1.14f);
        LinearLayout.LayoutParams params = matchWrap(context);
        params.setMargins(0, dp(context, 7), 0, 0);
        view.setLayoutParams(params);
        return view;
    }

    public static Button primaryButton(Context context, String text, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14.5f);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(BLUE);
        background.setCornerRadius(dp(context, 16));
        button.setBackground(background);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = matchWrap(context);
        params.setMargins(0, dp(context, 10), 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    public static Button outlineButton(Context context, String text, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(text);
        button.setTextColor(NAVY);
        button.setTextSize(14f);
        button.setAllCaps(false);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(context, 16));
        background.setStroke(dp(context, 1), Color.argb(105, 71, 126, 155));
        button.setBackground(background);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = matchWrap(context);
        params.setMargins(0, dp(context, 8), 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    public static void open(Context context, String url) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(context, "No app could open that travel source.", Toast.LENGTH_LONG).show();
        }
    }

    public static void start(Context context, Class<?> activity) {
        context.startActivity(new Intent(context, activity));
    }

    public static LinearLayout.LayoutParams matchWrap(Context context) {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
