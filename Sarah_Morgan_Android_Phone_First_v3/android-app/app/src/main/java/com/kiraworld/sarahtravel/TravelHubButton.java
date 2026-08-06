package com.kiraworld.sarahtravel;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.Button;

/** One-tap entry from chat into Sarah's all-tracks travel command center. */
public final class TravelHubButton extends Button {
    public TravelHubButton(Context context) {
        super(context);
        initialize();
    }

    public TravelHubButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public TravelHubButton(Context context, AttributeSet attrs, int style) {
        super(context, attrs, style);
        initialize();
    }

    private void initialize() {
        setAllCaps(false);
        setGravity(Gravity.CENTER);
        setText("✨  Travel command center\nHotels • transport • food • rides • road trips • loyalty");
        setTextSize(14f);
        setTextColor(Color.WHITE);
        setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{TravelUi.NAVY, TravelUi.BLUE});
        background.setCornerRadius(dp(20));
        setBackground(background);
        setOnClickListener(v -> getContext().startActivity(
                new Intent(getContext(), TravelHubActivity.class)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
