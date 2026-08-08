package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ScrollView;

/** Applies status/cutout/navigation/keyboard insets without hiding the composer. */
public final class SafeAreaInsets {
    private SafeAreaInsets() { }

    public static void apply(Activity activity, View root, View bottomView, ScrollView scroll) {
        apply(activity, root, bottomView, scroll, null);
    }

    public static void apply(
            Activity activity,
            View root,
            View bottomView,
            ScrollView scroll,
            View hideWhileKeyboardVisible) {
        if (root == null) return;
        if (Build.VERSION.SDK_INT >= 30) activity.getWindow().setDecorFitsSystemWindows(false);

        int rootLeft = root.getPaddingLeft();
        int rootTop = root.getPaddingTop();
        int rootRight = root.getPaddingRight();
        int rootBottom = root.getPaddingBottom();
        int scrollBottom = scroll == null ? 0 : scroll.getPaddingBottom();
        int baseBottomMargin = 0;
        if (bottomView != null && bottomView.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            baseBottomMargin = ((ViewGroup.MarginLayoutParams) bottomView.getLayoutParams()).bottomMargin;
        }
        final int margin = baseBottomMargin;

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int preservedScrollY = scroll == null ? 0 : scroll.getScrollY();
            int left;
            int top;
            int right;
            int bottom;
            boolean keyboardVisible = false;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                keyboardVisible = insets.isVisible(WindowInsets.Type.ime());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = Math.max(bars.bottom, ime.bottom);
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            if (hideWhileKeyboardVisible != null) {
                hideWhileKeyboardVisible.setVisibility(
                        keyboardVisible ? View.GONE : View.VISIBLE);
            }
            view.setPadding(rootLeft + left, rootTop + top, rootRight + right,
                    bottomView == null ? rootBottom + bottom : rootBottom);
            if (bottomView != null && bottomView.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) bottomView.getLayoutParams();
                params.bottomMargin = margin + bottom;
                bottomView.setLayoutParams(params);
            }
            if (scroll != null) {
                scroll.setPadding(
                        scroll.getPaddingLeft(),
                        scroll.getPaddingTop(),
                        scroll.getPaddingRight(),
                        scrollBottom + (bottomView == null ? bottom : 0));
                scroll.post(() -> scroll.scrollTo(0, preservedScrollY));
            }
            return insets;
        });
        root.requestApplyInsets();
    }
}
