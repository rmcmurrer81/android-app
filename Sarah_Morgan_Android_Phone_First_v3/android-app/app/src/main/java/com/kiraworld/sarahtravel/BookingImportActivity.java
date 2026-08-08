package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** Owner-selected booking import and truthful fail-closed Gmail connection surface. */
public final class BookingImportActivity extends Activity {
    private static final int REQUEST_DOCUMENT = 801;
    private boolean launchedFromShare;
    private EditText sharedText;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        launchedFromShare = intent != null && Intent.ACTION_SEND.equals(intent.getAction());
        if (launchedFromShare) handleShared(intent);
        else showConnectionsUi();
    }

    private void showConnectionsUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        add(root, "Gmail and booking imports", 24);
        add(root, GmailTravelConnection.status(), 16);
        add(root, "Gmail OAuth is not installed in this build. Sarah cannot read, send, delete, search, or monitor mailbox messages. You can still share only the booking material you choose.", 14);

        Button connect = new Button(this);
        connect.setText("Connect Gmail (setup required)");
        connect.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Gmail is not connected")
                .setMessage("A maintainer must complete the Google OAuth steps in GOOGLE_GMAIL_SETUP.md and pass a supervised read-only test. Sarah will never ask for your Gmail password.")
                .setPositiveButton("OK", null).show());
        root.addView(connect);

        CheckBox monitoring = new CheckBox(this);
        monitoring.setText("Background travel-email monitoring (unavailable and off)");
        monitoring.setChecked(false);
        monitoring.setEnabled(false);
        root.addView(monitoring);
        add(root, "Last Gmail sync: never", 14);

        Button choose = new Button(this);
        choose.setText("Choose a booking screenshot or PDF");
        choose.setOnClickListener(v -> chooseDocument());
        root.addView(choose);

        sharedText = new EditText(this);
        sharedText.setHint("Paste a booking link or booking-email text you chose to share");
        sharedText.setMinLines(3);
        root.addView(sharedText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button importText = new Button(this);
        importText.setText("Save shared text for review");
        importText.setOnClickListener(v -> importSharedText(
                sharedText.getText().toString(), false));
        root.addView(importText);

        Button clear = new Button(this);
        clear.setText("Clear imported booking data");
        clear.setOnClickListener(v -> confirmClearImports());
        root.addView(clear);
        Button disconnect = new Button(this);
        disconnect.setText("Disconnect Gmail (already disconnected)");
        disconnect.setEnabled(false);
        root.addView(disconnect);
        Button close = new Button(this);
        close.setText("Back to Sarah");
        close.setOnClickListener(v -> finish());
        root.addView(close);

        setContentView(scroll);
        SafeAreaInsets.apply(this, scroll, null, scroll);
    }

    private void chooseDocument() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
        startActivityForResult(pick, REQUEST_DOCUMENT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_DOCUMENT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        String type = getContentResolver().getType(uri);
        new Thread(() -> {
            if ("application/pdf".equalsIgnoreCase(type)) importPdf(uri);
            else importScreenshot(uri);
        }, "SarahBookingDocumentImport").start();
    }

    private void handleShared(Intent intent) {
        String type = intent.getType() == null ? "" : intent.getType();
        if (type.startsWith("image/") || "application/pdf".equalsIgnoreCase(type)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri == null) {
                finishWithMessage("The shared booking file could not be opened.");
                return;
            }
            new Thread(() -> {
                if ("application/pdf".equalsIgnoreCase(type)) importPdf(uri);
                else importScreenshot(uri);
            }, "SarahBookingImport").start();
            return;
        }
        CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        reviewExternallySharedText(shared == null ? "" : shared.toString());
    }

    private void reviewExternallySharedText(String text) {
        String clean = BookingImportTextPolicy.clean(text);
        if (clean.isEmpty()) {
            finishWithMessage("No booking text or link was supplied.");
            return;
        }
        if (!BookingImportTextPolicy.accepted(clean)) {
            finishWithMessage("The shared booking text was not imported because it exceeds the "
                    + BookingImportTextPolicy.MAX_CHARS + " character or "
                    + BookingImportTextPolicy.MAX_UTF8_BYTES + " UTF-8 byte limit.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Review shared booking text")
                .setMessage("Another app shared the text below. Nothing has been saved or scheduled. "
                        + "Choose Import only if you want Sarah to hold it for booking review.\n\n"
                        + clean)
                .setPositiveButton("Import for review", (dialog, which) ->
                        importSharedText(clean, true))
                .setNegativeButton("Cancel", (dialog, which) ->
                        finishWithMessage("Shared booking text was not imported or scheduled."))
                .setOnCancelListener(dialog ->
                        finishWithMessage("Shared booking text was not imported or scheduled."))
                .show();
    }

    private void importSharedText(String text, boolean externalShareReviewed) {
        String clean = BookingImportTextPolicy.clean(text);
        if (clean.isEmpty()) {
            finishWithMessage("No booking text or link was supplied.");
            return;
        }
        if (!BookingImportTextPolicy.accepted(clean)) {
            finishWithMessage("The booking text was not imported because it exceeds the exact size limit.");
            return;
        }
        if (launchedFromShare && !externalShareReviewed) {
            finishWithMessage("Externally shared booking text requires your review before import.");
            return;
        }
        BookingLinkParser.BookingLink link = BookingLinkParser.parse(clean);
        EventTripStore store = new EventTripStore(this);
        try {
            if (link.found()) store.addBookingLink(link.provider, link.bookingType, link.url, clean);
            else store.addBookingText(clean);
        } finally { store.close(); }
        EventMonitorScheduler.ensureScheduled(this);
        EventMonitorScheduler.runSoon(this);
        if (sharedText != null) sharedText.setText("");
        finishWithMessage("Shared booking material saved as pending review. No trip was changed.");
    }

    private void importScreenshot(Uri uri) {
        String message;
        EventTripStore store = new EventTripStore(getApplicationContext());
        try {
            File directory = new File(getFilesDir(), "booking_imports");
            ImageSanitizer.Result result = ImageSanitizer.sanitize(
                    getContentResolver(), uri, directory);
            store.addBookingScreenshot(result.file.getAbsolutePath(), "User-shared booking screenshot");
            EventMonitorScheduler.ensureScheduled(this);
            EventMonitorScheduler.runSoon(this);
            message = "Booking screenshot saved as pending review. No trip was changed.";
        } catch (Exception e) {
            message = "The booking screenshot could not be prepared: " + e.getMessage();
        } finally { store.close(); }
        String finalMessage = message;
        runOnUiThread(() -> finishWithMessage(finalMessage));
    }

    private void importPdf(Uri uri) {
        String message;
        File target = null;
        EventTripStore store = new EventTripStore(getApplicationContext());
        try {
            File directory = new File(getFilesDir(), "booking_imports");
            if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Import folder unavailable");
            target = new File(directory, "booking_" + System.currentTimeMillis() + ".pdf");
            int total = 0;
            byte[] buffer = new byte[8192];
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(target)) {
                if (in == null) throw new IllegalArgumentException("The selected PDF could not be read");
                int count;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > 12_000_000) throw new IllegalArgumentException("The selected PDF exceeds 12 MB");
                    out.write(buffer, 0, count);
                }
            }
            if (total < 5) throw new IllegalArgumentException("The selected PDF is empty");
            store.addBookingDocument(target.getAbsolutePath(), "User-shared booking PDF");
            EventMonitorScheduler.ensureScheduled(this);
            EventMonitorScheduler.runSoon(this);
            message = "Booking PDF saved as pending review. No trip was changed.";
        } catch (Exception e) {
            if (target != null && target.isFile()) target.delete();
            message = "The booking PDF could not be prepared: " + e.getMessage();
        } finally { store.close(); }
        String finalMessage = message;
        runOnUiThread(() -> finishWithMessage(finalMessage));
    }

    private void confirmClearImports() {
        new AlertDialog.Builder(this)
                .setTitle("Clear imported booking data?")
                .setMessage("This removes only booking links, shared text, screenshots, and PDFs imported into Sarah. It does not delete Gmail messages or other phone files.")
                .setPositiveButton("Clear imports", (dialog, which) -> clearImports())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearImports() {
        EventTripStore store = new EventTripStore(this);
        try {
            List<Map<String, String>> imports = store.listBookings(1000);
            File allowed = new File(getFilesDir(), "booking_imports").getCanonicalFile();
            String allowedPath = allowed.getCanonicalPath() + File.separator;
            for (Map<String, String> row : imports) {
                String path = row.getOrDefault("local_path", "");
                if (path.isEmpty()) continue;
                File candidate = new File(path).getCanonicalFile();
                if (candidate.isFile() && candidate.getCanonicalPath().startsWith(allowedPath)) {
                    candidate.delete();
                }
            }
            int count = store.clearBookingImports();
            Toast.makeText(this, "Cleared " + count + " imported booking record(s).", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "Imported booking data could not be cleared safely: "
                    + error.getMessage(), Toast.LENGTH_LONG).show();
        } finally { store.close(); }
    }

    private void finishWithMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        if (!launchedFromShare) return;
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(open);
        finish();
    }

    private void add(LinearLayout root, String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setPadding(0, 8, 0, 8);
        root.addView(view);
    }
}
