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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owner-selected booking import and truthful fail-closed Gmail connection surface. */
public final class BookingImportActivity extends Activity {
    private static final int REQUEST_DOCUMENT = 801;
    private static final int REQUEST_GMAIL_CONNECTION = 802;
    private static final int MAX_SHARED_FILE_BYTES = 12_000_000;
    private boolean launchedFromShare;
    private EditText sharedText;
    private String boundPersonId = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        EventTripPreUpgradeBackupGate.Result upgradeState =
                SarahApplication.eventTripUpgradeState();
        if (upgradeState == null) {
            upgradeState = EventTripPreUpgradeBackupGate.ensure(this);
        }
        if (!upgradeState.mayOpenV2) {
            Toast.makeText(
                    this,
                    "Booking imports are unavailable because Sarah could not verify the protected event-trip backup. Status: "
                            + upgradeState.status,
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Intent intent = getIntent();
        launchedFromShare = intent != null && Intent.ACTION_SEND.equals(intent.getAction());
        boundPersonId = EventTripStore.activePersonId(this);
        if (launchedFromShare && !hasConfirmedActiveProfileLease()) {
            finishWithMessage("Choose and confirm the active phone profile before importing booking material. Nothing was copied, saved, sent, or scheduled.");
            return;
        }
        EventTripStore recoveryStore = new EventTripStore(this, boundPersonId);
        try {
            EventTripStore.BookingRecoveryResult recovery =
                    recoveryStore.reconcileBookingClearOperations();
            if (!recovery.ready) {
                finishWithMessage("Booking imports are paused because a prior private clear needs review. Status: "
                        + recovery.status + ". Residual path(s): "
                        + joinPaths(recovery.residualPaths));
                return;
            }
        } finally {
            recoveryStore.close();
        }
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

        String gmailProfileId = EventTripStore.activePersonId(this);
        GmailTokenVault gmailVault = new GmailTokenVault(this);
        boolean gmailConnected = !gmailProfileId.isEmpty()
                && gmailVault.hasAuthorizedGrant(gmailProfileId);
        boolean gmailReady = gmailConnected && !gmailVault.reauthorizationRequired();
        add(root, "Gmail and booking imports", 24);
        add(root, gmailConnected
                ? "Gmail read-only: " + gmailVault.accountEmail(gmailProfileId)
                    + " · monitoring " + (gmailVault.monitoringEnabled(gmailProfileId) ? "on" : "off")
                : "Gmail not connected · monitoring off", 16);
        add(root, "Google handles account selection and consent. Sarah never asks for your Gmail password and cannot send, delete, modify, mark read, draft, or change settings. Owner-selected text, images and PDFs remain available without Gmail.", 14);

        Button connect = new Button(this);
        connect.setText(gmailConnected
                ? "Open Gmail connection and receipts"
                : "Connect Gmail read-only");
        connect.setOnClickListener(v -> startActivityForResult(
                new Intent(this, GmailAuthorizationActivity.class),
                REQUEST_GMAIL_CONNECTION));
        root.addView(connect);

        CheckBox monitoring = new CheckBox(this);
        monitoring.setText("Bounded travel-message checks about every 6 hours");
        monitoring.setChecked(gmailReady
                && gmailVault.monitoringEnabled(gmailProfileId));
        monitoring.setEnabled(gmailReady);
        monitoring.setOnCheckedChangeListener((button, enabled) -> {
            try {
                GmailMonitorScheduler.setEnabled(
                        this, gmailProfileId, enabled);
            } catch (Exception error) {
                monitoring.setChecked(false);
                Toast.makeText(this,
                        "Gmail monitoring was not changed: " + error.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        });
        root.addView(monitoring);
        long lastGmailSync = gmailVault.lastSyncAt();
        add(root, "Last Gmail check: " + (lastGmailSync == 0L
                ? "never"
                : java.time.Instant.ofEpochMilli(lastGmailSync))
                + " · metadata first · messages unchanged", 14);

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
        Button manageGmail = new Button(this);
        manageGmail.setText("Review or disconnect Gmail");
        manageGmail.setEnabled(gmailConnected);
        manageGmail.setOnClickListener(v -> startActivityForResult(
                new Intent(this, GmailAuthorizationActivity.class),
                REQUEST_GMAIL_CONNECTION));
        root.addView(manageGmail);
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
        if (requestCode == REQUEST_GMAIL_CONNECTION) {
            showConnectionsUi();
            return;
        }
        if (requestCode != REQUEST_DOCUMENT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        String type = approvedMime(uri, "");
        if (type.isEmpty()) {
            finishWithMessage("Choose a PNG, JPEG, WebP screenshot, or PDF no larger than 12 MB.");
            return;
        }
        new Thread(() -> {
            if ("application/pdf".equals(type)) importPdf(uri, true);
            else importScreenshot(uri, type, true);
        }, "SarahBookingDocumentImport").start();
    }

    private void handleShared(Intent intent) {
        String declaredType = intent.getType() == null ? "" : intent.getType();
        if (declaredType.startsWith("image/")
                || "application/pdf".equalsIgnoreCase(declaredType)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri == null) {
                finishWithMessage("The shared booking file could not be opened.");
                return;
            }
            if (!"content".equalsIgnoreCase(uri.getScheme())) {
                finishWithMessage("The external share was rejected. Sarah accepts only a content URI containing a PNG, JPEG, WebP screenshot, or PDF no larger than 12 MB.");
                return;
            }
            reviewExternallySharedFile(uri, declaredType);
            return;
        }
        CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        reviewExternallySharedText(shared == null ? "" : shared.toString());
    }

    private void reviewExternallySharedFile(Uri uri, String declaredType) {
        String kind = "application/pdf".equalsIgnoreCase(declaredType) ? "PDF" : "image";
        new AlertDialog.Builder(this)
                .setTitle("Review shared booking " + kind)
                .setMessage("Another app shared a booking " + kind
                        + ". Nothing has been copied, saved, sent to a service, or scheduled. "
                        + "Choose Import only if you want Sarah to sanitize and hold this exact item for private booking review.")
                .setPositiveButton("Import for review", (dialog, which) ->
                        new Thread(() -> {
                            String approvedType = approvedMime(uri, declaredType);
                            if (approvedType.isEmpty()) {
                                runOnUiThread(() -> finishWithMessage(
                                        "The confirmed external share was rejected because its resolved content type is not PNG, JPEG, WebP, or PDF."));
                                return;
                            }
                            if ("application/pdf".equals(approvedType)) {
                                importPdf(uri, true);
                            } else {
                                importScreenshot(uri, approvedType, true);
                            }
                        }, "SarahConfirmedBookingImport").start())
                .setNegativeButton("Cancel", (dialog, which) ->
                        finishWithMessage("Shared booking file was not copied, saved, sent, or scheduled."))
                .setOnCancelListener(dialog ->
                        finishWithMessage("Shared booking file was not copied, saved, sent, or scheduled."))
                .show();
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
        if (!hasConfirmedActiveProfileLease()) {
            finishWithMessage("Choose and confirm the active phone profile before importing booking text. Nothing was saved or scheduled.");
            return;
        }
        BookingLinkParser.BookingLink link = BookingLinkParser.parse(clean);
        EventTripStore store = new EventTripStore(this, boundPersonId);
        long saved;
        try {
            saved = link.found()
                    ? store.addBookingLink(link.provider, link.bookingType, link.url, clean)
                    : store.addBookingText(clean);
        } finally { store.close(); }
        if (saved < 0) {
            finishWithMessage("Choose the active phone profile and try again. Nothing was saved or scheduled.");
            return;
        }
        boolean refreshQueued = queueExactOwnerEventRefresh();
        if (sharedText != null) sharedText.setText("");
        finishWithMessage("Shared booking material saved as pending review. No trip was changed. "
                + (refreshQueued
                        ? "One immediate review refresh was queued."
                        : "No immediate review refresh was queued."));
    }

    private void importScreenshot(
            Uri uri,
            String approvedResolverMimeType,
            boolean ownerConfirmed) {
        String message;
        EventTripStore store = new EventTripStore(
                getApplicationContext(), boundPersonId);
        File derivative = null;
        File stagingDirectory = null;
        boolean rowSaved = false;
        try {
            requireConfirmedImportLease(ownerConfirmed);
            requireBookingRecoveryReady(store);
            File directory = profileImportDirectory(store);
            stagingDirectory = createPrivateImageStagingDirectory(directory);
            File importRoot = new File(getFilesDir(), "booking_imports").getCanonicalFile();
            ImageSanitizer.Result result;
            try (PrivateContentSnapshot snapshot = PrivateContentSnapshot.capture(
                    getContentResolver(),
                    uri,
                    importRoot,
                    stagingDirectory,
                    MAX_SHARED_FILE_BYTES,
                    "booking_image",
                    approvedResolverMimeType)) {
                result = ImageSanitizer.sanitize(
                        snapshot.file(),
                        stagingDirectory,
                        snapshot.approvedMimeType());
            }
            derivative = createPrivateImageTarget(directory);
            promoteSanitizedImage(
                    directory, stagingDirectory, result.file, derivative);
            removeEmptyImageStaging(stagingDirectory);
            stagingDirectory = null;
            requireConfirmedImportLease(ownerConfirmed);
            if (store.addBookingScreenshot(
                    derivative.getAbsolutePath(), "User-shared booking screenshot") < 0) {
                throw new IllegalStateException(
                        "the confirmed active profile changed before the import was saved");
            }
            rowSaved = true;
            boolean refreshQueued = queueExactOwnerEventRefresh();
            message = "Booking screenshot saved as pending review. No trip was changed. "
                    + (refreshQueued
                            ? "One immediate review refresh was queued."
                            : "No immediate review refresh was queued.");
        } catch (Exception e) {
            if (rowSaved) {
                message = "Booking screenshot was saved as pending review, but the immediate review refresh failed: "
                        + e.getMessage() + ". The private derivative was preserved with its row.";
            } else {
                String residual = cleanupImportArtifacts(derivative, stagingDirectory);
                message = "The booking screenshot could not be prepared: " + e.getMessage()
                        + residualMessage(residual);
            }
        } finally { store.close(); }
        String finalMessage = message;
        runOnUiThread(() -> finishWithMessage(finalMessage));
    }

    private void importPdf(Uri uri, boolean ownerConfirmed) {
        String message;
        File target = null;
        boolean rowSaved = false;
        EventTripStore store = new EventTripStore(
                getApplicationContext(), boundPersonId);
        try {
            requireConfirmedImportLease(ownerConfirmed);
            requireBookingRecoveryReady(store);
            File directory = profileImportDirectory(store);
            target = new File(
                    directory, "booking_" + UUID.randomUUID() + ".pdf").getCanonicalFile();
            if (target.getParentFile() == null
                    || !target.getParentFile().getCanonicalFile()
                            .equals(directory.getCanonicalFile())
                    || target.exists()) {
                throw new IllegalStateException("Private PDF target was rejected");
            }
            int total = 0;
            byte[] signature = new byte[5];
            int signatureCount = 0;
            byte[] buffer = new byte[8192];
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(target)) {
                if (in == null) throw new IllegalArgumentException("The selected PDF could not be read");
                int count;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_SHARED_FILE_BYTES) throw new IllegalArgumentException("The selected PDF exceeds 12 MB");
                    for (int i = 0; i < count && signatureCount < signature.length; i++) {
                        signature[signatureCount++] = buffer[i];
                    }
                    out.write(buffer, 0, count);
                }
                out.flush();
                out.getFD().sync();
            }
            if (signatureCount != 5
                    || signature[0] != '%'
                    || signature[1] != 'P'
                    || signature[2] != 'D'
                    || signature[3] != 'F'
                    || signature[4] != '-') {
                throw new IllegalArgumentException("The selected file does not contain a PDF signature");
            }
            requireConfirmedImportLease(ownerConfirmed);
            if (store.addBookingDocument(
                    target.getAbsolutePath(), "User-shared booking PDF") < 0) {
                throw new IllegalStateException(
                        "the confirmed active profile changed before the import was saved");
            }
            rowSaved = true;
            boolean refreshQueued = queueExactOwnerEventRefresh();
            message = "Booking PDF saved as pending review. No trip was changed. "
                    + (refreshQueued
                            ? "One immediate review refresh was queued."
                            : "No immediate review refresh was queued.");
        } catch (Exception e) {
            if (rowSaved) {
                message = "Booking PDF was saved as pending review, but the immediate review refresh failed: "
                        + e.getMessage() + ". The private derivative was preserved with its row.";
            } else {
                String residual = cleanupPrivateDerivative(target);
                message = "The booking PDF could not be prepared: " + e.getMessage()
                        + residualMessage(residual);
            }
        } finally { store.close(); }
        String finalMessage = message;
        runOnUiThread(() -> finishWithMessage(finalMessage));
    }

    private String approvedMime(Uri uri, String declaredType) {
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())) return "";
        String declared = declaredType == null ? "" : declaredType.trim().toLowerCase();
        String resolved = getContentResolver().getType(uri);
        resolved = resolved == null ? "" : resolved.trim().toLowerCase();
        boolean ownerSelected = declared.isEmpty();
        if ((ownerSelected || "application/pdf".equals(declared))
                && "application/pdf".equals(resolved)) return resolved;
        if ((ownerSelected || declared.startsWith("image/") || "image/*".equals(declared))
                && ("image/jpeg".equals(resolved)
                        || "image/png".equals(resolved)
                        || "image/webp".equals(resolved))) {
            return resolved;
        }
        return "";
    }

    private boolean hasConfirmedActiveProfileLease() {
        if (boundPersonId == null || boundPersonId.trim().isEmpty()) return false;
        PersonProfileStore people = new PersonProfileStore(getApplicationContext());
        try {
            Map<String, String> active = people.getActiveProfile();
            return boundPersonId.equals(active.getOrDefault("person_id", "").trim())
                    && "yes".equals(active.getOrDefault("active", ""))
                    && ProfileMigrationPolicy.isConfirmedDisplayName(
                            active.getOrDefault("name", ""));
        } finally {
            people.close();
        }
    }

    private boolean queueExactOwnerEventRefresh() {
        ConfirmedOwnerLease ownerLease = ConfirmedOwnerLease.capture(this);
        if (ownerLease == null
                || boundPersonId == null
                || !boundPersonId.equals(ownerLease.personId())) {
            return false;
        }
        try {
            ownerLease.requireActive();
        } catch (IllegalStateException e) {
            return false;
        }
        return EventMonitorScheduler.runSoon(this);
    }

    private void requireConfirmedImportLease(boolean ownerConfirmed) {
        if (!ownerConfirmed) {
            throw new IllegalStateException(
                    "Visible owner confirmation is required before this external share is copied");
        }
        if (!hasConfirmedActiveProfileLease()) {
            throw new IllegalStateException(
                    "The confirmed active profile changed before the import began");
        }
    }

    private static void requireBookingRecoveryReady(EventTripStore store) {
        EventTripStore.BookingRecoveryResult recovery =
                store.reconcileBookingClearOperations();
        if (!recovery.ready) {
            throw new IllegalStateException(
                    "a prior private clear needs review (" + recovery.status + ") at "
                            + joinPaths(recovery.residualPaths));
        }
    }

    private String cleanupPrivateDerivative(File derivative) {
        if (derivative == null || !derivative.exists()) return "";
        try {
            File exact = derivative.getCanonicalFile();
            File root = new File(getFilesDir(), "booking_imports").getCanonicalFile();
            if (!exact.getCanonicalPath().startsWith(
                        root.getCanonicalPath() + File.separator)
                    || !exact.isFile()
                    || !exact.delete()
                    || exact.exists()) {
                return exact.getCanonicalPath();
            }
            return "";
        } catch (Exception ignored) {
            return derivative.getAbsolutePath();
        }
    }

    private File createPrivateImageStagingDirectory(File profileDirectory) throws Exception {
        File exactProfile = profileDirectory.getCanonicalFile();
        File staging = new File(
                exactProfile, ".pending_image_" + UUID.randomUUID()).getCanonicalFile();
        if (staging.getParentFile() == null
                || !staging.getParentFile().getCanonicalFile().equals(exactProfile)
                || !staging.mkdir()
                || !staging.isDirectory()) {
            throw new IllegalStateException("Private image staging directory unavailable");
        }
        return staging;
    }

    private File createPrivateImageTarget(File profileDirectory) throws Exception {
        File exactProfile = profileDirectory.getCanonicalFile();
        File target = new File(
                exactProfile,
                "booking_screenshot_" + UUID.randomUUID() + ".jpg").getCanonicalFile();
        if (target.getParentFile() == null
                || !target.getParentFile().getCanonicalFile().equals(exactProfile)
                || target.exists()) {
            throw new IllegalStateException("Private image target was rejected");
        }
        return target;
    }

    private void promoteSanitizedImage(
            File profileDirectory,
            File stagingDirectory,
            File stagedFile,
            File target) throws Exception {
        File exactProfile = profileDirectory.getCanonicalFile();
        File exactStaging = stagingDirectory.getCanonicalFile();
        File exactStaged = stagedFile.getCanonicalFile();
        File exactTarget = target.getCanonicalFile();
        if (exactStaging.getParentFile() == null
                || !exactStaging.getParentFile().getCanonicalFile().equals(exactProfile)
                || exactStaged.getParentFile() == null
                || !exactStaged.getParentFile().getCanonicalFile().equals(exactStaging)
                || !exactStaged.isFile()
                || exactTarget.getParentFile() == null
                || !exactTarget.getParentFile().getCanonicalFile().equals(exactProfile)
                || exactTarget.exists()) {
            throw new IllegalStateException("Sanitized image staging scope was rejected");
        }
        if (!exactStaged.renameTo(exactTarget)
                || exactStaged.exists()
                || !exactTarget.isFile()
                || exactTarget.length() < 1) {
            throw new IllegalStateException("Sanitized booking image could not be promoted");
        }
    }

    private static void removeEmptyImageStaging(File stagingDirectory) throws Exception {
        File exactStaging = stagingDirectory.getCanonicalFile();
        File[] remaining = exactStaging.listFiles();
        if (remaining == null
                || remaining.length != 0
                || !exactStaging.delete()
                || exactStaging.exists()) {
            throw new IllegalStateException(
                    "Private image staging cleanup failed at "
                            + exactStaging.getCanonicalPath());
        }
    }

    private String cleanupImportArtifacts(File derivative, File stagingDirectory) {
        List<String> residuals = new ArrayList<>();
        try {
            File exactStaging = stagingDirectory == null
                    ? null
                    : stagingDirectory.getCanonicalFile();
            File exactDerivative = derivative == null
                    ? null
                    : derivative.getCanonicalFile();
            boolean derivativeInsideStaging = exactStaging != null
                    && exactDerivative != null
                    && exactDerivative.getParentFile() != null
                    && exactDerivative.getParentFile().getCanonicalFile().equals(exactStaging);
            if (!derivativeInsideStaging) {
                String derivativeResidual = cleanupPrivateDerivative(exactDerivative);
                if (!derivativeResidual.isEmpty()) residuals.add(derivativeResidual);
            }
            if (exactStaging != null && exactStaging.exists()) {
                File[] children = exactStaging.listFiles();
                if (children == null || children.length > 8) {
                    residuals.add(exactStaging.getCanonicalPath());
                } else {
                    for (File child : children) {
                        String childResidual = cleanupPrivateDerivative(child);
                        if (!childResidual.isEmpty()) residuals.add(childResidual);
                    }
                    File[] after = exactStaging.listFiles();
                    if (after == null
                            || after.length != 0
                            || !exactStaging.delete()
                            || exactStaging.exists()) {
                        residuals.add(exactStaging.getCanonicalPath());
                    }
                }
            }
        } catch (Exception failure) {
            if (stagingDirectory != null) residuals.add(stagingDirectory.getAbsolutePath());
            else if (derivative != null) residuals.add(derivative.getAbsolutePath());
        }
        StringBuilder out = new StringBuilder();
        for (String path : residuals) {
            if (path == null || path.trim().isEmpty()) continue;
            if (out.length() > 0) out.append(", ");
            out.append(path);
        }
        return out.toString();
    }

    private static String residualMessage(String residual) {
        return residual == null || residual.trim().isEmpty()
                ? ""
                : ". Exact private residual requiring review: " + residual;
    }

    private static String joinPaths(java.util.List<String> paths) {
        if (paths == null || paths.isEmpty()) return "none recorded";
        StringBuilder out = new StringBuilder();
        for (String path : paths) {
            if (out.length() > 0) out.append(", ");
            out.append(path);
        }
        return out.toString();
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
        EventTripStore store = null;
        try {
            store = new EventTripStore(this, boundPersonId);
            EventTripStore.BookingClearResult result =
                    store.clearBookingImportsAndDerivatives();
            String message;
            if (result.success) {
                message = "Cleared " + result.deletedCount
                        + " imported booking record(s) and their private derivative file(s).";
            } else if (result.rowsCleared) {
                message = "The booking records were cleared, but "
                        + result.residualPaths.size()
                        + " private quarantine item(s) remain for review. Status: "
                        + result.status;
            } else if (result.rollbackComplete) {
                message = "Nothing was cleared. Sarah restored the imported booking files "
                        + "and records. Status: " + result.status;
            } else {
                message = "The clear did not complete and needs review. "
                        + result.residualPaths.size()
                        + " private item(s) remain unresolved. Status: " + result.status;
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "Imported booking data could not be cleared safely: "
                    + error.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            if (store != null) store.close();
        }
    }

    private File profileImportDirectory(EventTripStore store) throws Exception {
        String child = store.profileDirectoryName();
        if (child.isEmpty() || !store.isActiveProfile()) {
            throw new IllegalStateException("Choose the active phone profile before importing a booking");
        }
        File root = new File(getFilesDir(), "booking_imports").getCanonicalFile();
        File directory = new File(root, child).getCanonicalFile();
        String rootPrefix = root.getCanonicalPath() + File.separator;
        if (!directory.getCanonicalPath().startsWith(rootPrefix)) {
            throw new IllegalStateException("Import profile folder was rejected");
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Import folder unavailable");
        }
        return directory;
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
