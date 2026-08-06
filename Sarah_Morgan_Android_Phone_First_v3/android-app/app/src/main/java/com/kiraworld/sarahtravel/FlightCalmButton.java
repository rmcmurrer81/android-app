package com.kiraworld.sarahtravel;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.TextView;

/** Header control that opens the fully offline flight companion. */
public final class FlightCalmButton extends TextView {
    public FlightCalmButton(Context context) {
        super(context);
        initialize();
    }

    public FlightCalmButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public FlightCalmButton(Context context, AttributeSet attrs, int style) {
        super(context, attrs, style);
        initialize();
    }

    private void initialize() {
        setText("✈");
        setTextColor(Color.WHITE);
        setTextSize(23f);
        setGravity(Gravity.CENTER);
        setContentDescription("Open the offline flight companion for takeoff, turbulence, landing, breathing, games, and children's sing-alongs");
        setClickable(true);
        setFocusable(true);
        setOnClickListener(v -> getContext().startActivity(new Intent(getContext(), FlightCalmActivity.class)));
    }
}
