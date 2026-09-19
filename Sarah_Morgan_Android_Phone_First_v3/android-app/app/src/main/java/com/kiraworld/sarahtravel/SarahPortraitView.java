package com.kiraworld.sarahtravel;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * Lightweight live presentation of Sarah's approved portrait.
 *
 * <p>The source portrait is decoded once and never regenerated. Blink, gaze,
 * head drift, and speech-envelope drawing use a single UI-thread frame loop
 * capped at 20 FPS. Speech animation starts only when a voice backend reports
 * real playback. It is intentionally not described as phoneme-accurate lip
 * sync.</p>
 */
public final class SarahPortraitView extends View {
    public static final String ANIMATION_EVIDENCE =
            "PLAYBACK_BOUND_SPEECH_ENVELOPE_PHONEME_ACCURACY_PENDING";
    static final long FRAME_INTERVAL_MS = 50L;

    private static final float[] LEFT_EYE = {0.405f, 0.301f, 0.468f, 0.340f};
    private static final float[] RIGHT_EYE = {0.532f, 0.301f, 0.595f, 0.340f};
    private static final float IRIS_RADIUS = 0.0135f;

    private final Handler animationHandler = new Handler(Looper.getMainLooper());
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint eyePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint skinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mouthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect sourceRect = new Rect();
    private final Rect irisSource = new Rect();
    private final RectF imageRect = new RectF();
    private final RectF eyeRect = new RectF();
    private final RectF irisDestination = new RectF();
    private final RectF mouthRect = new RectF();
    private final Path clipPath = new Path();
    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!animationActive || !isShown()) return;
            invalidate();
            animationHandler.postDelayed(this, FRAME_INTERVAL_MS);
        }
    };

    private Bitmap portrait;
    private boolean animationActive;
    private long animationEpochMs;
    private long speechStartedWallMs;
    private String spokenText = "";

    public SarahPortraitView(Context context) {
        super(context);
        initialize();
    }

    public SarahPortraitView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public SarahPortraitView(Context context, AttributeSet attrs, int style) {
        super(context, attrs, style);
        initialize();
    }

    private void initialize() {
        portrait = BitmapFactory.decodeResource(
                getResources(), R.drawable.sarah_adult_portrait_r2_runtime_512);
        if (portrait != null) {
            sourceRect.set(0, 0, portrait.getWidth(), portrait.getHeight());
        }
        eyePaint.setColor(Color.rgb(235, 224, 209));
        skinPaint.setColor(Color.rgb(201, 132, 88));
        mouthPaint.setColor(Color.rgb(80, 32, 34));
        lipPaint.setColor(Color.rgb(188, 91, 85));
        lipPaint.setStyle(Paint.Style.STROKE);
        lipPaint.setStrokeCap(Paint.Cap.ROUND);
        animationEpochMs = SystemClock.uptimeMillis();
    }

    /** Enables frames only while the owning activity is in the foreground. */
    public void setAnimationActive(boolean active) {
        animationActive = active;
        animationHandler.removeCallbacks(frame);
        if (active && isShown()) animationHandler.post(frame);
        else invalidate();
    }

    /** Starts mouth motion at the backend's exact reported playback time. */
    public void beginSpeechEnvelope(String text, long playbackStartedAt) {
        spokenText = text == null ? "" : text;
        speechStartedWallMs = playbackStartedAt > 0L
                ? playbackStartedAt : System.currentTimeMillis();
        invalidate();
    }

    public void endSpeechEnvelope() {
        speechStartedWallMs = 0L;
        spokenText = "";
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animationActive) setAnimationActive(true);
    }

    @Override protected void onDetachedFromWindow() {
        animationHandler.removeCallbacks(frame);
        endSpeechEnvelope();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (portrait == null || portrait.isRecycled()) return;
        float side = Math.min(getWidth(), getHeight());
        float left = (getWidth() - side) * 0.5f;
        float top = (getHeight() - side) * 0.5f;
        imageRect.set(left, top, left + side, top + side);

        long elapsed = Math.max(0L, SystemClock.uptimeMillis() - animationEpochMs);
        float headX = (float) Math.sin(elapsed / 2900.0) * side * 0.0035f;
        float headY = (float) Math.sin(elapsed / 3600.0) * side * 0.0025f;
        float headAngle = (float) Math.sin(elapsed / 4200.0) * 0.32f;

        int save = canvas.save();
        canvas.translate(headX, headY);
        canvas.rotate(headAngle, getWidth() * 0.5f, getHeight() * 0.5f);
        canvas.drawBitmap(portrait, sourceRect, imageRect, bitmapPaint);

        float gazeX = (float) Math.sin(elapsed / 2300.0) * side * 0.0036f;
        float gazeY = (float) Math.sin(elapsed / 3100.0) * side * 0.0018f;
        drawEyeGaze(canvas, LEFT_EYE, gazeX, gazeY);
        drawEyeGaze(canvas, RIGHT_EYE, gazeX, gazeY);

        float blink = blinkAmount(elapsed);
        if (blink > 0f) {
            drawBlink(canvas, LEFT_EYE, blink);
            drawBlink(canvas, RIGHT_EYE, blink);
        }
        drawSpeakingMouth(canvas, side);
        canvas.restoreToCount(save);
    }

    private void drawEyeGaze(Canvas canvas, float[] bounds, float gazeX, float gazeY) {
        normalizedRect(bounds, eyeRect);
        float cx = eyeRect.centerX();
        float cy = eyeRect.centerY();
        float radius = imageRect.width() * IRIS_RADIUS;

        clipPath.reset();
        clipPath.addOval(eyeRect, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(clipPath);
        canvas.drawOval(eyeRect, eyePaint);

        int sourceCx = Math.round(((cx - imageRect.left) / imageRect.width()) * portrait.getWidth());
        int sourceCy = Math.round(((cy - imageRect.top) / imageRect.height()) * portrait.getHeight());
        int sourceRadius = Math.max(1, Math.round(IRIS_RADIUS * portrait.getWidth()));
        irisSource.set(
                sourceCx - sourceRadius,
                sourceCy - sourceRadius,
                sourceCx + sourceRadius,
                sourceCy + sourceRadius);
        irisDestination.set(
                cx - radius + gazeX,
                cy - radius + gazeY,
                cx + radius + gazeX,
                cy + radius + gazeY);
        clipPath.reset();
        clipPath.addOval(irisDestination, Path.Direction.CW);
        canvas.clipPath(clipPath);
        canvas.drawBitmap(portrait, irisSource, irisDestination, bitmapPaint);
        canvas.restoreToCount(save);
    }

    private void drawBlink(Canvas canvas, float[] bounds, float amount) {
        normalizedRect(bounds, eyeRect);
        float half = eyeRect.height() * 0.5f * amount;
        int save = canvas.save();
        clipPath.reset();
        clipPath.addOval(eyeRect, Path.Direction.CW);
        canvas.clipPath(clipPath);
        canvas.drawRect(eyeRect.left, eyeRect.top, eyeRect.right,
                eyeRect.top + half, skinPaint);
        canvas.drawRect(eyeRect.left, eyeRect.bottom - half, eyeRect.right,
                eyeRect.bottom, skinPaint);
        if (amount > 0.72f) {
            lipPaint.setColor(Color.rgb(87, 54, 43));
            lipPaint.setStrokeWidth(Math.max(1f, imageRect.width() * 0.003f));
            canvas.drawLine(eyeRect.left, eyeRect.centerY(), eyeRect.right,
                    eyeRect.centerY(), lipPaint);
        }
        canvas.restoreToCount(save);
    }

    private void drawSpeakingMouth(Canvas canvas, float side) {
        float amount = currentSpeechAmount();
        if (amount <= 0.015f) return;
        float cx = imageRect.left + imageRect.width() * 0.501f;
        float cy = imageRect.top + imageRect.height() * 0.444f;
        float halfWidth = imageRect.width() * (0.034f + amount * 0.004f);
        float halfHeight = imageRect.height() * (0.003f + amount * 0.010f);
        mouthRect.set(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight);
        canvas.drawOval(mouthRect, mouthPaint);
        lipPaint.setColor(Color.rgb(200, 105, 99));
        lipPaint.setStrokeWidth(Math.max(1f, side * 0.004f));
        canvas.drawArc(mouthRect, 190f, 160f, false, lipPaint);
        canvas.drawArc(mouthRect, 10f, 160f, false, lipPaint);
    }

    private float currentSpeechAmount() {
        if (speechStartedWallMs <= 0L || spokenText.isEmpty()) return 0f;
        long elapsed = Math.max(0L, System.currentTimeMillis() - speechStartedWallMs);
        int position = (int) (elapsed / 82L);
        if (position >= spokenText.length() + 8) {
            endSpeechEnvelope();
            return 0f;
        }
        char current = position < spokenText.length()
                ? Character.toLowerCase(spokenText.charAt(position)) : ' ';
        float base;
        if ("aeiouy".indexOf(current) >= 0) base = 0.86f;
        else if (Character.isLetterOrDigit(current)) base = 0.48f;
        else if (current == ',' || current == ';' || current == ':' || current == '.') base = 0.06f;
        else base = 0.18f;
        float pulse = 0.66f + 0.34f * (float) Math.sin((elapsed % 164L) * Math.PI / 164.0);
        return Math.max(0f, Math.min(1f, base * pulse));
    }

    private static float blinkAmount(long elapsed) {
        long phase = elapsed % 4700L;
        if (phase > 190L) return 0f;
        if (phase <= 90L) return phase / 90f;
        return Math.max(0f, (190L - phase) / 100f);
    }

    private void normalizedRect(float[] bounds, RectF out) {
        out.set(
                imageRect.left + imageRect.width() * bounds[0],
                imageRect.top + imageRect.height() * bounds[1],
                imageRect.left + imageRect.width() * bounds[2],
                imageRect.top + imageRect.height() * bounds[3]);
    }
}
