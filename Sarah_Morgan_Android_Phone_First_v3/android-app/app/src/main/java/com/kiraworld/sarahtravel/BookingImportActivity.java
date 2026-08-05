package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.File;

/** Receives user-selected booking links or screenshots from Android's Share sheet. */
public final class BookingImportActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        handle(getIntent());
    }

    private void handle(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) {
            finishToSarah("Nothing was shared.");
            return;
        }
        String type = intent.getType() == null ? "" : intent.getType();
        if (type.startsWith("image/")) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri == null) {
                finishToSarah("The shared screenshot could not be opened.");
                return;
            }
            new Thread(() -> importScreenshot(uri), "SarahBookingImport").start();
            return;
        }
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        BookingLinkParser.BookingLink link = BookingLinkParser.parse(text);
        if (!link.found()) {
            finishToSarah("Sarah did not find a booking link in the shared text.");
            return;
        }
        EventTripStore store = new EventTripStore(this);
        try {
            store.addBookingLink(link.provider, link.bookingType, link.url, text == null ? link.url : text);
        } finally {
            store.close();
        }
        EventMonitorScheduler.ensureScheduled(this);
        EventMonitorScheduler.runSoon(this);
        finishToSarah(link.provider + " booking link saved for review.");
    }

    private void importScreenshot(Uri uri) {
        String message;
        EventTripStore store = new EventTripStore(getApplicationContext());
        try {
            File directory = new File(getFilesDir(), "booking_imports");
            ImageSanitizer.Result result = ImageSanitizer.sanitize(
                    getContentResolver(), uri, directory);
            store.addBookingScreenshot(
                    result.file.getAbsolutePath(),
                    "User-shared booking screenshot");
            EventMonitorScheduler.ensureScheduled(this);
            EventMonitorScheduler.runSoon(this);
            message = "Booking screenshot saved. Visible details will be extracted for review when Smart mode is connected.";
        } catch (Exception e) {
            message = "The booking screenshot could not be prepared: " + e.getMessage();
        } finally {
            store.close();
        }
        String finalMessage = message;
        runOnUiThread(() -> finishToSarah(finalMessage));
    }

    private void finishToSarah(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(open);
        finish();
    }
}
