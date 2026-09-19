package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owner-selected, profile-isolated ticket/pass images and source links. */
public final class TicketPassWalletActivity extends Activity {
    public static final String EXTRA_TITLE = "ticket_pass_title";
    public static final String EXTRA_DATE = "ticket_pass_date";
    public static final String EXTRA_OFFICIAL_URL = "ticket_pass_official_url";
    public static final String EXTRA_VERIFIED_EVENT_SOURCE = "ticket_pass_verified_event_source";
    public static final String EXTRA_AUTO_IMPORT = "ticket_pass_auto_import";

    private static final int PICK_PASS_IMAGE = 7402;
    private static final long SHARE_CACHE_MAX_AGE_MS = 30L * 60L * 1000L;

    private Map<String, String> person;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        clearExpiredShares();
        render();
        if (state == null && getIntent().getBooleanExtra(EXTRA_AUTO_IMPORT, false)) {
            choosePassImage();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_PASS_IMAGE || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        try {
            byte[] sanitized = sanitizeOwnerSelectedImage(data.getData());
            if (sanitized.length < 1
                    || sanitized.length > TicketPassPolicy.MAX_ENCRYPTED_IMAGE_BYTES) {
                throw new IllegalArgumentException("The sanitized ticket image is too large.");
            }
            showMetadataDialog(sanitized);
        } catch (Exception error) {
            Toast.makeText(
                    this,
                    "That ticket/pass image could not be imported safely: " + safeMessage(error),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void render() {
        PersonProfileStore people = new PersonProfileStore(this);
        try {
            person = people.getActiveProfile();
        } finally {
            people.close();
        }
        String personId = person.getOrDefault("person_id", "1");
        String name = person.getOrDefault("name", "Traveler");

        LinearLayout root = TravelUi.page(this);
        root.addView(TravelUi.hero(
                this,
                "Private ticket and pass wallet",
                name + "'s saved passes",
                "Owner-selected images are sanitized, encrypted with Android Keystore, and isolated to this active profile."));

        LinearLayout safety = TravelUi.card(this, TravelUi.PEACH);
        safety.addView(TravelUi.cardTitle(this, "🎟️", "Save the useful pass, not payment secrets"));
        safety.addView(TravelUi.body(this,
                "Import a ticket, badge or QR-code image you deliberately select. Add a title, date and exact official source when known. Never put passwords, payment-card details, recovery codes or security answers here. Saving an image does not claim a ticket was purchased, paid, valid or used."));
        safety.addView(TravelUi.primaryButton(
                this, "Import ticket/pass image", v -> choosePassImage()));
        root.addView(safety);

        root.addView(TravelUi.section(this, "Saved tickets and passes"));
        List<TicketPassVaultStore.Entry> entries;
        try {
            entries = TicketPassVaultStore.list(this, personId);
        } catch (IllegalStateException error) {
            LinearLayout blocked = TravelUi.card(this, TravelUi.PEACH);
            blocked.addView(TravelUi.cardTitle(this, "🔒", "Wallet locked to protect saved records"));
            blocked.addView(TravelUi.body(this,
                    "Sarah could not authenticate this profile's encrypted ticket wallet. Nothing was overwritten or removed. Restore a known-good device backup or review the exact failure before changing this wallet."));
            root.addView(blocked);
            return;
        }
        if (entries.isEmpty()) {
            LinearLayout empty = TravelUi.card(this, TravelUi.SKY);
            empty.addView(TravelUi.cardTitle(this, "📱", "No pass image saved yet"));
            empty.addView(TravelUi.body(this,
                    "The loyalty-program wallet remains separate. This wallet is only for owner-selected event tickets, admission passes, badges and QR-code images."));
            root.addView(empty);
            return;
        }

        for (TicketPassVaultStore.Entry entry : entries) {
            byte[] imageBytes = entry.imageBytes();
            LinearLayout card = TravelUi.card(this, TravelUi.LAVENDER);
            card.addView(TravelUi.cardTitle(this, "🎫", entry.title));
            String sourceTruth = TicketPassPolicy.isVerifiedEventSource(entry.sourceStatus)
                    ? "Source: exact verified event source carried from Sarah's event record"
                    : "Source: owner-provided link; not independently verified by this save action";
            card.addView(TravelUi.body(this,
                    (entry.eventDate.isEmpty() ? "Date: not entered" : "Date: " + entry.eventDate)
                            + "\n" + sourceTruth
                            + "\nStatus: saved image only; purchase, validity and admission use are not inferred."));
            ImageView preview = preview(imageBytes, TravelUi.dp(this, 260));
            preview.setContentDescription("Saved ticket or QR image for " + entry.title);
            preview.setOnClickListener(v -> showLargePreview(entry));
            card.addView(preview);
            card.addView(TravelUi.outlineButton(
                    this, "View ticket / QR code", v -> showLargePreview(entry)));
            if (!entry.officialUrl.isEmpty()) {
                String label = TicketPassPolicy.isVerifiedEventSource(entry.sourceStatus)
                        ? "Open verified official event / ticket source"
                        : "Open owner-provided source";
                card.addView(TravelUi.primaryButton(
                        this, label, v -> TravelUi.open(this, entry.officialUrl)));
                card.addView(TravelUi.body(this, "Exact source: " + entry.officialUrl));
            }
            card.addView(TravelUi.outlineButton(
                    this, "Share this pass image", v -> share(entry)));
            card.addView(TravelUi.outlineButton(
                    this, "Remove from this profile", v -> confirmRemove(personId, entry)));
            root.addView(card);
        }
    }

    private void choosePassImage() {
        final int currentCount;
        try {
            currentCount = TicketPassVaultStore.list(
                    this, person == null ? "1" : person.getOrDefault("person_id", "1")).size();
        } catch (IllegalStateException error) {
            Toast.makeText(
                    this,
                    "The encrypted wallet could not be authenticated. No pass was changed.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (currentCount >= TicketPassPolicy.MAX_PASSES_PER_PROFILE) {
            Toast.makeText(
                    this,
                    "This profile already has the bounded maximum of "
                            + TicketPassPolicy.MAX_PASSES_PER_PROFILE + " passes.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("image/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(picker, PICK_PASS_IMAGE);
    }

    private byte[] sanitizeOwnerSelectedImage(Uri uri) throws Exception {
        File root = new File(getNoBackupFilesDir(), "ticket_pass_import");
        File staging = new File(root, "staging");
        String mime = getContentResolver().getType(uri);
        try (PrivateContentSnapshot snapshot = PrivateContentSnapshot.capture(
                getContentResolver(),
                uri,
                root,
                staging,
                PrivateContentSnapshot.MAX_IMAGE_BYTES,
                "ticket_pass",
                mime)) {
            ImageSanitizer.Result result = ImageSanitizer.sanitize(
                    snapshot.file(), staging, snapshot.approvedMimeType());
            try {
                return result.jpeg;
            } finally {
                if (result.file.exists()) {
                    result.file.setWritable(true, true);
                    if (!result.file.delete() || result.file.exists()) {
                        throw new IllegalStateException("Sanitized ticket staging cleanup failed.");
                    }
                }
            }
        }
    }

    private void showMetadataDialog(byte[] sanitizedJpeg) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int padding = TravelUi.dp(this, 18);
        box.setPadding(padding, padding / 2, padding, 0);

        EditText title = field("Event or pass title", InputType.TYPE_CLASS_TEXT);
        EditText date = field("Date or date range (optional)", InputType.TYPE_CLASS_TEXT);
        EditText official = field(
                "Exact official website or ticket URL (optional)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        Intent launch = getIntent();
        title.setText(launch.getStringExtra(EXTRA_TITLE));
        date.setText(launch.getStringExtra(EXTRA_DATE));
        official.setText(launch.getStringExtra(EXTRA_OFFICIAL_URL));
        box.addView(title);
        box.addView(date);
        box.addView(official);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Save ticket or pass")
                .setMessage("The sanitized image and these fields are encrypted for the active profile. This does not verify a purchase or ticket validity.")
                .setView(box)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = title.getText().toString().trim();
                    String rawUrl = official.getText().toString().trim();
                    String exactUrl = TicketPassPolicy.exactHttpsUrl(rawUrl);
                    if (name.isEmpty()) {
                        title.setError("Enter the event or pass title.");
                        return;
                    }
                    if (!rawUrl.isEmpty() && exactUrl.isEmpty()) {
                        official.setError("Use an exact https:// official source URL.");
                        return;
                    }
                    String personId = person.getOrDefault("person_id", "1");
                    boolean verified = launch.getBooleanExtra(
                            EXTRA_VERIFIED_EVENT_SOURCE, false)
                            && exactUrl.equals(TicketPassPolicy.exactHttpsUrl(
                                    launch.getStringExtra(EXTRA_OFFICIAL_URL)));
                    boolean saved;
                    try {
                        saved = TicketPassVaultStore.add(
                                this,
                                personId,
                                name,
                                date.getText().toString(),
                                exactUrl,
                                verified,
                                sanitizedJpeg);
                    } catch (IllegalStateException error) {
                        Toast.makeText(
                                this,
                                "The encrypted wallet could not be authenticated. No pass was changed.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!saved) {
                        Toast.makeText(
                                this,
                                "The pass was not saved. Check the title, source and profile limit.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    dialog.dismiss();
                    render();
                }));
        dialog.show();
    }

    private void showLargePreview(TicketPassVaultStore.Entry entry) {
        ImageView image = preview(entry.imageBytes(), TravelUi.dp(this, 680));
        image.setPadding(
                TravelUi.dp(this, 10), TravelUi.dp(this, 10),
                TravelUi.dp(this, 10), TravelUi.dp(this, 10));
        new AlertDialog.Builder(this)
                .setTitle(entry.title)
                .setMessage(entry.eventDate.isEmpty() ? null : entry.eventDate)
                .setView(image)
                .setPositiveButton("Close", null)
                .show();
    }

    private ImageView preview(byte[] bytes, int maximumHeight) {
        ImageView image = new ImageView(this);
        Bitmap bitmap = bytes == null ? null : BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap != null) image.setImageBitmap(bitmap);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setMaxHeight(maximumHeight);
        image.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return image;
    }

    private void share(TicketPassVaultStore.Entry entry) {
        byte[] bytes = entry.imageBytes();
        if (bytes.length < 1) {
            Toast.makeText(this, "The saved pass image is unavailable.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            clearExpiredShares();
            File directory = new File(getCacheDir(), "ticket_pass_share").getCanonicalFile();
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IllegalStateException("The private share cache is unavailable.");
            }
            String token = UUID.randomUUID().toString().replace("-", "");
            File file = new File(directory, "ticket_pass_" + token + ".jpg").getCanonicalFile();
            if (file.getParentFile() == null || !file.getParentFile().equals(directory)) {
                throw new IllegalStateException("The ticket share path was rejected.");
            }
            try (FileOutputStream output = new FileOutputStream(file, false)) {
                output.write(bytes);
                output.flush();
                output.getFD().sync();
            }
            Uri uri = new Uri.Builder()
                    .scheme("content")
                    .authority(getPackageName() + ".ticketpasses")
                    .appendPath(TicketPassShareProvider.PATH)
                    .appendPath(file.getName())
                    .build();
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("image/jpeg");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, entry.title);
            send.setClipData(ClipData.newRawUri(entry.title, uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Share ticket/pass image"));
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> deleteExactShare(file), SHARE_CACHE_MAX_AGE_MS);
        } catch (Exception error) {
            Toast.makeText(
                    this, "The pass image could not be shared: " + safeMessage(error),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void confirmRemove(String personId, TicketPassVaultStore.Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + entry.title + "?")
                .setMessage("This removes the encrypted image and metadata from the active profile. It does not cancel a real ticket or account.")
                .setPositiveButton("Remove", (dialog, which) -> {
                    final boolean removed;
                    try {
                        removed = TicketPassVaultStore.remove(this, personId, entry.id);
                    } catch (IllegalStateException error) {
                        Toast.makeText(
                                this,
                                "The encrypted wallet could not be authenticated. No pass was changed.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!removed) {
                        Toast.makeText(this, "The encrypted pass could not be removed.", Toast.LENGTH_LONG).show();
                    }
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private EditText field(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setInputType(inputType | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }

    private void clearExpiredShares() {
        try {
            File directory = new File(getCacheDir(), "ticket_pass_share").getCanonicalFile();
            File[] files = directory.listFiles();
            if (files == null) return;
            long cutoff = System.currentTimeMillis() - SHARE_CACHE_MAX_AGE_MS;
            for (File file : files) {
                if (file.isFile()
                        && file.getName().matches("ticket_pass_[A-Za-z0-9_-]{8,80}\\.jpg")
                        && file.lastModified() < cutoff) deleteExactShare(file);
            }
        } catch (Exception ignored) { }
    }

    private void deleteExactShare(File file) {
        try {
            File directory = new File(getCacheDir(), "ticket_pass_share").getCanonicalFile();
            File exact = file.getCanonicalFile();
            if (exact.getParentFile() != null && exact.getParentFile().equals(directory)
                    && exact.getName().matches("ticket_pass_[A-Za-z0-9_-]{8,80}\\.jpg")) {
                exact.delete();
            }
        } catch (Exception ignored) { }
    }

    private static String safeMessage(Exception error) {
        String message = error == null ? "unknown error" : error.getMessage();
        return message == null || message.trim().isEmpty() ? "unknown error" : message.trim();
    }
}
