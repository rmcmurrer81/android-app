package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.widget.Button;

public final class TrustedSyncButton extends Button {
    public TrustedSyncButton(Context context) { super(context); initialize(); }
    public TrustedSyncButton(Context context, AttributeSet attrs) { super(context, attrs); initialize(); }
    public TrustedSyncButton(Context context, AttributeSet attrs, int style) { super(context, attrs, style); initialize(); }

    private void initialize() {
        setAllCaps(false);
        setText("◉  Devices & sync");
        setContentDescription("Discover, approve, synchronize, or revoke your Sarah devices and trip photos");
        setOnClickListener(v -> getContext().startActivity(new Intent(getContext(), TrustedSyncActivity.class)));
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Context context = getContext();
        if (context instanceof Activity) SarahAutoPairCoordinator.maybePrompt((Activity) context);
    }
}
