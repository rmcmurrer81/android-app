package com.kiraworld.sarahtravel;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Profile-isolated event trips, event updates, and owner-reviewed booking imports.
 *
 * Version 1 had no person identity. Its rows are preserved under one hidden
 * legacy sentinel during upgrade. They become visible only after the explicit
 * placeholder-owner correction claims them for the confirmed owner.
 */
public final class EventTripStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "sarah_event_trips.db";
    private static final int DB_VERSION = 2;
    private static final int MAX_BOOKING_CLEAR_ROWS = 4096;
    private static final int MAX_BOOKING_RECOVERY_OPERATIONS = 128;
    private static final int MAX_BOOKING_JOURNAL_BYTES = 24_000_000;
    private static final String BOOKING_IMPORT_ROOT = "booking_imports";
    private static final String BOOKING_QUARANTINE_ROOT = "booking_import_quarantine";
    private static final String BOOKING_CLEAR_TOMBSTONE = "TOMBSTONE.json";
    private static final String BOOKING_CLEAR_TOMBSTONE_PENDING = "TOMBSTONE.pending";
    private static final String BOOKING_CLEAR_JOURNAL = "JOURNAL.jsonl";

    private final Context appContext;
    private final String profileKey;

    /** Exact, non-binary result of one profile-scoped booking clear attempt. */
    public static final class BookingClearResult {
        public final boolean success;
        public final boolean rowsCleared;
        public final boolean derivativesCleared;
        public final boolean rollbackComplete;
        public final int deletedCount;
        public final String status;
        public final List<String> residualPaths;

        private BookingClearResult(
                boolean success,
                boolean rowsCleared,
                boolean derivativesCleared,
                boolean rollbackComplete,
                int deletedCount,
                String status,
                List<String> residualPaths) {
            this.success = success;
            this.rowsCleared = rowsCleared;
            this.derivativesCleared = derivativesCleared;
            this.rollbackComplete = rollbackComplete;
            this.deletedCount = deletedCount;
            this.status = value(status);
            this.residualPaths = Collections.unmodifiableList(
                    new ArrayList<>(residualPaths));
        }

        private static BookingClearResult failure(
                String status,
                boolean rollbackComplete,
                List<String> residualPaths) {
            return new BookingClearResult(
                    false,
                    false,
                    false,
                    rollbackComplete,
                    0,
                    status,
                    residualPaths);
        }
    }

    /** Bounded startup/read-boundary reconciliation result; residuals are never erased here. */
    public static final class BookingRecoveryResult {
        public final boolean ready;
        public final String status;
        public final List<String> residualPaths;

        private BookingRecoveryResult(
                boolean ready,
                String status,
                List<String> residualPaths) {
            this.ready = ready;
            this.status = value(status);
            this.residualPaths = Collections.unmodifiableList(
                    new ArrayList<>(residualPaths));
        }
    }

    /** Exact outcome of resolving one natural-language monitor cancellation. */
    public static final class MonitorDisableResult {
        public final boolean disabled;
        public final boolean ambiguous;
        public final String eventName;
        public final String destination;

        private MonitorDisableResult(
                boolean disabled,
                boolean ambiguous,
                String eventName,
                String destination) {
            this.disabled = disabled;
            this.ambiguous = ambiguous;
            this.eventName = value(eventName);
            this.destination = value(destination);
        }
    }

    private static final class QuarantinedDerivative {
        final long bookingId;
        final File original;
        File quarantined;
        final long expectedBytes;
        boolean moved;

        QuarantinedDerivative(long bookingId, File original) {
            this.bookingId = bookingId;
            this.original = original;
            this.expectedBytes = original.isFile() ? original.length() : -1L;
        }

        QuarantinedDerivative(long bookingId, File original, long expectedBytes) {
            this.bookingId = bookingId;
            this.original = original;
            this.expectedBytes = expectedBytes;
        }
    }

    private static final class BookingRowSnapshot {
        final long bookingId;
        final String localPath;

        BookingRowSnapshot(long bookingId, String localPath) {
            this.bookingId = bookingId;
            this.localPath = value(localPath);
        }
    }

    public EventTripStore(Context context, String personId) {
        this(context, EventTripProfilePolicy.profileKey(personId), true);
    }

    private EventTripStore(Context context, String key, boolean alreadyNormalized) {
        super(requireApplicationContext(context), DB_NAME, null, DB_VERSION);
        appContext = requireApplicationContext(context);
        EventTripPreUpgradeBackupGate.Result backupGate =
                EventTripPreUpgradeBackupGate.ensure(appContext);
        if (!backupGate.mayOpenV2) {
            throw new IllegalStateException(
                    "EVENT_TRIP_DATABASE_OPEN_BLOCKED_" + backupGate.status);
        }
        profileKey = alreadyNormalized ? value(key) : EventTripProfilePolicy.profileKey(key);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createTables(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) migrateV1Losslessly(db);
    }

    public String profileKey() {
        return profileKey;
    }

    /** Safe child-directory name for new profile-owned imported derivatives. */
    public String profileDirectoryName() {
        return EventTripProfilePolicy.isVisibleProfileKey(profileKey) ? profileKey : "";
    }

    public boolean isActiveProfile() {
        return mayAccessActiveProfile();
    }

    /** Read the exact active PersonProfileStore ID without creating or changing a profile. */
    public static String activePersonId(Context context) {
        if (context == null) return "";
        PersonProfileStore people = new PersonProfileStore(context.getApplicationContext());
        try {
            String personId = people.getActiveProfile().getOrDefault("person_id", "");
            return EventTripProfilePolicy.profileKey(personId).isEmpty() ? "" : personId.trim();
        } finally {
            people.close();
        }
    }

    /** Move already profile-bound rows during an explicit, verified profile correction. */
    public static boolean moveProfile(Context context, String oldPersonId, String newPersonId) {
        String oldKey = EventTripProfilePolicy.profileKey(oldPersonId);
        String newKey = EventTripProfilePolicy.profileKey(newPersonId);
        if (oldKey.isEmpty() || newKey.isEmpty()) return false;
        if (oldKey.equals(newKey)) return true;
        return moveProfileKey(context, oldKey, newKey);
    }

    /**
     * Claim v1 owner data only from the explicit owner-correction flow. Normal
     * constructors and reads can never select the hidden sentinel.
     */
    public static boolean claimLegacyOwnerData(Context context, String confirmedOwnerPersonId) {
        String newKey = EventTripProfilePolicy.profileKey(confirmedOwnerPersonId);
        if (newKey.isEmpty()) return false;
        return moveProfileKey(context, EventTripProfilePolicy.LEGACY_OWNER_UNASSIGNED, newKey);
    }

    public long upsertEventTrip(String eventName, String destination, boolean monitor) {
        if (!mayAccessActiveProfile()) return -1;
        String cleanEvent = value(eventName).trim();
        String cleanDestination = value(destination).trim();
        if (cleanEvent.isEmpty() || cleanDestination.isEmpty()) return -1;
        ContentValues values = new ContentValues();
        values.put("person_profile_key", profileKey);
        values.put("event_key", eventKey(cleanEvent));
        values.put("event_name", cleanEvent);
        values.put("destination", cleanDestination);
        values.put("monitor_status", monitor ? "queued" : "saved");
        values.put("monitor_enabled", monitor ? 1 : 0);
        values.put("active", 1);
        values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(
                "event_trips", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id FROM event_trips WHERE person_profile_key=? "
                        + "AND event_key=? AND lower(destination)=lower(?) LIMIT 1",
                new String[]{profileKey, eventKey(cleanEvent), cleanDestination})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }

    /** Create or explicitly reactivate one runnable monitor owned by this profile. */
    public boolean ensureRunnableEventMonitor(String eventName, String destination) {
        long id = upsertEventTrip(eventName, destination, true);
        if (id < 0 || !mayAccessActiveProfile()) return false;
        ContentValues values = new ContentValues();
        values.put("monitor_status", "queued");
        values.put("monitor_enabled", 1);
        values.put("active", 1);
        values.put("next_check_at", 0);
        if (getWritableDatabase().update(
                "event_trips", values, "id=? AND person_profile_key=?",
                new String[]{String.valueOf(id), profileKey}) <= 0) return false;
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT active,monitor_status,monitor_enabled FROM event_trips "
                        + "WHERE id=? AND person_profile_key=? LIMIT 1",
                new String[]{String.valueOf(id), profileKey})) {
            return cursor.moveToFirst()
                    && cursor.getInt(0) == 1
                    && "queued".equals(cursor.getString(1))
                    && cursor.getInt(2) == 1;
        }
    }

    /** Exact current row truth; a later save-only mention never implies off. */
    public boolean eventMonitorEnabled(String eventName, String destination) {
        if (!mayAccessActiveProfile()) return false;
        String cleanEvent = value(eventName).trim();
        String cleanDestination = value(destination).trim();
        if (cleanEvent.isEmpty() || cleanDestination.isEmpty()) return false;
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT monitor_enabled FROM event_trips WHERE person_profile_key=? "
                        + "AND event_key=? AND lower(destination)=lower(?) AND active=1 LIMIT 1",
                new String[]{profileKey, eventKey(cleanEvent), cleanDestination})) {
            return cursor.moveToFirst() && cursor.getInt(0) == 1;
        }
    }

    /** Disable exactly one active-profile event monitor and verify the row. */
    public boolean disableEventMonitor(String eventName, String destination) {
        if (!mayAccessActiveProfile()) return false;
        String cleanEvent = value(eventName).trim();
        String cleanDestination = value(destination).trim();
        if (cleanEvent.isEmpty() || cleanDestination.isEmpty()) return false;
        ContentValues values = new ContentValues();
        values.put("monitor_enabled", 0);
        values.put("monitor_status", "stopped_by_owner");
        values.put("next_check_at", 0);
        int updated = getWritableDatabase().update(
                "event_trips",
                values,
                "person_profile_key=? AND event_key=? "
                        + "AND lower(destination)=lower(?) AND active=1",
                new String[]{profileKey, eventKey(cleanEvent), cleanDestination});
        if (updated != 1) return false;
        return !eventMonitorEnabled(cleanEvent, cleanDestination);
    }

    /**
     * Resolve an omitted destination only when one enabled active-profile row
     * has the exact normalized event name. Never guess between destinations.
     */
    public MonitorDisableResult resolveAndDisableEventMonitor(
            String eventName,
            String destination) {
        if (!mayAccessActiveProfile()) {
            return new MonitorDisableResult(false, false, eventName, destination);
        }
        String cleanEvent = value(eventName).trim();
        String cleanDestination = value(destination).trim();
        if (cleanEvent.isEmpty()) {
            return new MonitorDisableResult(false, false, cleanEvent, cleanDestination);
        }
        if (!cleanDestination.isEmpty()) {
            boolean disabled = disableEventMonitor(cleanEvent, cleanDestination);
            return new MonitorDisableResult(
                    disabled, false, cleanEvent, cleanDestination);
        }

        List<Map<String, String>> matches = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT event_name,destination FROM event_trips "
                        + "WHERE person_profile_key=? AND event_key=? "
                        + "AND active=1 AND monitor_enabled=1 ORDER BY id DESC LIMIT 2",
                new String[]{profileKey, eventKey(cleanEvent)})) {
            while (cursor.moveToNext()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("event_name", cursor.getString(0));
                row.put("destination", cursor.getString(1));
                matches.add(row);
            }
        }
        if (matches.size() != 1) {
            return new MonitorDisableResult(
                    false, matches.size() > 1, cleanEvent, "");
        }
        String resolvedEvent = matches.get(0).getOrDefault("event_name", cleanEvent);
        String resolvedDestination = matches.get(0).getOrDefault("destination", "");
        boolean disabled = disableEventMonitor(resolvedEvent, resolvedDestination);
        return new MonitorDisableResult(
                disabled, false, resolvedEvent, resolvedDestination);
    }

    public boolean hasEnabledEventMonitors() {
        if (!mayAccessActiveProfile()) return false;
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT 1 FROM event_trips WHERE person_profile_key=? "
                        + "AND active=1 AND monitor_enabled=1 LIMIT 1",
                new String[]{profileKey})) {
            return cursor.moveToFirst();
        }
    }

    public boolean updateEventResearch(
            long id,
            String eventName,
            String destination,
            String venue,
            String startDate,
            String endDate,
            String officialUrl,
            String updatesSummary,
            String nearbyFood,
            String nearbyPlaces,
            String transportNotes,
            String sourceNote,
            long checkedAt,
            long nextCheckAt) {
        if (!mayAccessActiveProfile()) return false;
        ContentValues values = new ContentValues();
        values.put("event_name", value(eventName));
        values.put("destination", value(destination));
        values.put("venue", value(venue));
        values.put("start_date", value(startDate));
        values.put("end_date", value(endDate));
        values.put("official_url", value(officialUrl));
        values.put("updates_summary", value(updatesSummary));
        values.put("nearby_food", value(nearbyFood));
        values.put("nearby_places", value(nearbyPlaces));
        values.put("transport_notes", value(transportNotes));
        values.put("source_note", value(sourceNote));
        values.put("monitor_status", "ready");
        values.put("last_checked_at", checkedAt);
        values.put("next_check_at", nextCheckAt);
        return getWritableDatabase().update(
                "event_trips", values, "id=? AND person_profile_key=?",
                new String[]{String.valueOf(id), profileKey}) == 1;
    }

    public List<Map<String, String>> listActiveEventTrips(int limit) {
        if (!mayAccessActiveProfile()) return new ArrayList<>();
        return eventRows("active=1", null, limit);
    }

    public List<Map<String, String>> listDueEventTrips(int limit) {
        if (!mayAccessActiveProfile()) return new ArrayList<>();
        return eventRows(
                "active=1 AND monitor_enabled=1 AND (next_check_at=0 OR next_check_at<=?)",
                new String[]{String.valueOf(System.currentTimeMillis())},
                limit);
    }

    private List<Map<String, String>> eventRows(
            String condition,
            String[] conditionArgs,
            int limit) {
        List<Map<String, String>> rows = new ArrayList<>();
        String sql = "SELECT id,event_name,destination,venue,start_date,end_date,official_url,"
                + "monitor_status,updates_summary,nearby_food,nearby_places,transport_notes,"
                + "source_note,last_checked_at,next_check_at,monitor_enabled FROM event_trips "
                + "WHERE person_profile_key=? AND " + condition + " ORDER BY id DESC LIMIT ?";
        List<String> args = new ArrayList<>();
        args.add(profileKey);
        if (conditionArgs != null) {
            for (String arg : conditionArgs) args.add(arg);
        }
        args.add(String.valueOf(Math.max(1, limit)));
        try (Cursor c = getReadableDatabase().rawQuery(sql, args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", String.valueOf(c.getLong(0)));
                row.put("event_name", c.getString(1));
                row.put("destination", c.getString(2));
                row.put("venue", c.getString(3));
                row.put("start_date", c.getString(4));
                row.put("end_date", c.getString(5));
                row.put("official_url", c.getString(6));
                row.put("monitor_status", c.getString(7));
                row.put("updates_summary", c.getString(8));
                row.put("nearby_food", c.getString(9));
                row.put("nearby_places", c.getString(10));
                row.put("transport_notes", c.getString(11));
                row.put("source_note", c.getString(12));
                row.put("last_checked_at", String.valueOf(c.getLong(13)));
                row.put("next_check_at", String.valueOf(c.getLong(14)));
                row.put("monitor_enabled", c.getInt(15) == 1 ? "yes" : "no");
                row.put("person_profile_key", profileKey);
                rows.add(row);
            }
        }
        return rows;
    }

    public boolean addEventUpdate(
            long eventTripId,
            String updateKey,
            String category,
            String title,
            String detail,
            String sourceUrl,
            String publishedAt) {
        if (!mayAccessActiveProfile() || !ownsEvent(eventTripId)) return false;
        ContentValues values = new ContentValues();
        values.put("person_profile_key", profileKey);
        values.put("event_trip_id", eventTripId);
        values.put("update_key", value(updateKey));
        values.put("category", value(category));
        values.put("title", value(title));
        values.put("detail", value(detail));
        values.put("source_url", value(sourceUrl));
        values.put("published_at", value(publishedAt));
        values.put("detected_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict(
                "event_updates", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public long addBookingLink(String provider, String bookingType, String url, String rawText) {
        return addBooking(null, bookingType, provider, "link", url, "", rawText);
    }

    public long addBookingScreenshot(String localPath, String rawText) {
        return addBooking(null, "travel", "Unknown", "screenshot", "", localPath, rawText);
    }

    public long addBookingDocument(String localPath, String rawText) {
        return addBooking(null, "travel", "Unknown", "pdf", "", localPath, rawText);
    }

    public long addBookingText(String rawText) {
        return addBooking(null, "travel", "Unknown", "shared_text", "", "", rawText);
    }

    /**
     * Reconcile interrupted booking-clear operations before any booking read or
     * write. Recovery may restore a quarantine file only when its exact scoped
     * database row still exists and every recorded path/size check succeeds.
     * It never deletes a private residual. A committed residual, mixed row
     * state, malformed record, or unexpected child fails closed for review.
     */
    public BookingRecoveryResult reconcileBookingClearOperations() {
        List<String> residuals = new ArrayList<>();
        if (!mayAccessActiveProfile()) {
            return new BookingRecoveryResult(
                    false, "ACTIVE_PROFILE_LEASE_UNAVAILABLE", residuals);
        }
        try {
            File profileDirectory = bookingQuarantineProfileDirectory(false);
            if (profileDirectory == null || !profileDirectory.exists()) {
                return new BookingRecoveryResult(
                        true, "NO_INTERRUPTED_BOOKING_CLEAR", residuals);
            }
            File[] children = profileDirectory.listFiles();
            if (children == null) {
                addResidual(residuals, profileDirectory);
                return new BookingRecoveryResult(
                        false, "BOOKING_RECOVERY_DIRECTORY_UNREADABLE", residuals);
            }
            if (children.length > MAX_BOOKING_RECOVERY_OPERATIONS) {
                addResidual(residuals, profileDirectory);
                return new BookingRecoveryResult(
                        false, "BOOKING_RECOVERY_OPERATION_LIMIT_EXCEEDED", residuals);
            }
            java.util.Arrays.sort(children, (left, right) ->
                    left.getName().compareTo(right.getName()));
            int reconciled = 0;
            for (File child : children) {
                File operation = child.getCanonicalFile();
                if (!child.getAbsoluteFile().getPath().equals(operation.getPath())
                        || !operation.isDirectory()
                        || operation.getParentFile() == null
                        || !operation.getParentFile().getCanonicalFile()
                                .equals(profileDirectory)
                        || !operation.getName().startsWith("clear_")) {
                    addResidual(residuals, child);
                    return new BookingRecoveryResult(
                            false, "BOOKING_RECOVERY_UNEXPECTED_PRIVATE_ITEM", residuals);
                }
                BookingRecoveryResult result = reconcileBookingClearOperation(operation);
                if (!result.ready) {
                    residuals.addAll(result.residualPaths);
                    return new BookingRecoveryResult(false, result.status, residuals);
                }
                reconciled++;
            }
            return new BookingRecoveryResult(
                    true,
                    reconciled == 0
                            ? "NO_INTERRUPTED_BOOKING_CLEAR"
                            : "BOOKING_CLEAR_RECOVERY_VERIFIED",
                    residuals);
        } catch (Exception failure) {
            try {
                addResidual(
                        residuals,
                        new File(
                                new File(appContext.getFilesDir(), BOOKING_QUARANTINE_ROOT),
                                profileKey));
            } catch (Exception ignored) { }
            return new BookingRecoveryResult(
                    false, boundedRecoveryStatus(failure), residuals);
        }
    }

    private BookingRecoveryResult reconcileBookingClearOperation(File operation)
            throws Exception {
        List<String> residuals = new ArrayList<>();
        File tombstone = exactOperationChild(operation, BOOKING_CLEAR_TOMBSTONE);
        if (!tombstone.isFile()) {
            File pending = exactOperationChild(
                    operation, BOOKING_CLEAR_TOMBSTONE_PENDING);
            File journalOnly = exactOperationChild(operation, BOOKING_CLEAR_JOURNAL);
            File[] initialChildren = operation.listFiles();
            if (initialChildren != null && initialChildren.length == 0) {
                return new BookingRecoveryResult(
                        true, "BOOKING_RECOVERY_EMPTY_OPERATION_NO_PRIVATE_RESIDUAL", residuals);
            }
            if (pending.isFile()
                    && initialChildren != null
                    && initialChildren.length == 1) {
                JSONObject pendingRecord = new JSONObject(readBoundedUtf8(pending));
                if ("sarah-booking-clear-private-quarantine-v1".equals(
                                pendingRecord.optString("contract"))
                        && profileKey.equals(pendingRecord.optString("profile_key"))
                        && pending.renameTo(tombstone)
                        && !pending.exists()
                        && tombstone.isFile()) {
                    // Continue using the fully fsynced manifest; no private file changed.
                } else {
                    addResidual(residuals, pending);
                    return new BookingRecoveryResult(
                            false, "BOOKING_RECOVERY_PENDING_TOMBSTONE_REJECTED", residuals);
                }
            } else if (journalOnly.isFile()
                    && initialChildren != null
                    && initialChildren.length == 1
                    && "CLEAR_COMPLETE_NO_PRIVATE_RESIDUAL".equals(
                            validateBookingClearJournal(operation, journalOnly))) {
                return new BookingRecoveryResult(
                        true,
                        "BOOKING_RECOVERY_COMPLETION_JOURNAL_NO_PRIVATE_RESIDUAL",
                        residuals);
            }
        }
        if (!tombstone.isFile()) {
            addResidual(residuals, operation);
            return new BookingRecoveryResult(
                    false, "BOOKING_RECOVERY_TOMBSTONE_MISSING", residuals);
        }
        JSONObject record = new JSONObject(readBoundedUtf8(tombstone));
        if (!"sarah-booking-clear-private-quarantine-v1".equals(
                    record.optString("contract"))
                || !profileKey.equals(record.optString("profile_key"))) {
            addResidual(residuals, tombstone);
            return new BookingRecoveryResult(
                    false, "BOOKING_RECOVERY_TOMBSTONE_SCOPE_REJECTED", residuals);
        }
        int expectedRows = record.optInt("expected_database_rows", -1);
        JSONArray items = record.optJSONArray("derivatives");
        JSONArray recordedRows = record.optJSONArray("booking_rows");
        if (expectedRows < 0
                || expectedRows > MAX_BOOKING_CLEAR_ROWS
                || items == null
                || items.length() > MAX_BOOKING_CLEAR_ROWS) {
            addResidual(residuals, tombstone);
            return new BookingRecoveryResult(
                    false, "BOOKING_RECOVERY_TOMBSTONE_BOUNDS_REJECTED", residuals);
        }

        File journal = exactOperationChild(operation, BOOKING_CLEAR_JOURNAL);
        String lastJournalEvent = validateBookingClearJournal(operation, journal);
        List<QuarantinedDerivative> derivatives = new ArrayList<>();
        Set<String> originalPaths = new HashSet<>();
        Set<String> quarantinePaths = new HashSet<>();
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            long bookingId = item.optLong("booking_id", -1L);
            long expectedBytes = item.optLong("expected_bytes", Long.MIN_VALUE);
            File original = exactRecordedBookingPath(item.optString("original_path"));
            File quarantined = exactRecordedQuarantinePath(
                    operation, item.optString("quarantine_path"));
            if (bookingId <= 0
                    || expectedBytes < -1L
                    || !originalPaths.add(original.getCanonicalPath())
                    || !quarantinePaths.add(quarantined.getCanonicalPath())) {
                addResidual(residuals, tombstone);
                return new BookingRecoveryResult(
                        false, "BOOKING_RECOVERY_DERIVATIVE_RECORD_REJECTED", residuals);
            }
            QuarantinedDerivative derivative =
                    new QuarantinedDerivative(bookingId, original, expectedBytes);
            derivative.quarantined = quarantined;
            derivative.moved = item.optBoolean("moved", false);
            derivatives.add(derivative);
        }
        List<BookingRowSnapshot> bookingRows = new ArrayList<>();
        Set<Long> bookingIds = new HashSet<>();
        if (recordedRows != null) {
            if (recordedRows.length() != expectedRows) {
                addResidual(residuals, tombstone);
                return new BookingRecoveryResult(
                        false, "BOOKING_RECOVERY_ROW_SNAPSHOT_COUNT_REJECTED", residuals);
            }
            for (int index = 0; index < recordedRows.length(); index++) {
                JSONObject row = recordedRows.getJSONObject(index);
                long bookingId = row.optLong("booking_id", -1L);
                String localPath = value(row.optString("local_path"));
                if (bookingId <= 0 || !bookingIds.add(bookingId)) {
                    addResidual(residuals, tombstone);
                    return new BookingRecoveryResult(
                            false, "BOOKING_RECOVERY_ROW_SNAPSHOT_REJECTED", residuals);
                }
                if (!localPath.trim().isEmpty()) {
                    File exact = exactRecordedBookingPath(localPath);
                    if (!exact.getCanonicalPath().equals(localPath)) {
                        addResidual(residuals, tombstone);
                        return new BookingRecoveryResult(
                                false,
                                "BOOKING_RECOVERY_ROW_SNAPSHOT_PATH_REJECTED",
                                residuals);
                    }
                }
                bookingRows.add(new BookingRowSnapshot(bookingId, localPath));
            }
        } else {
            if (expectedRows != derivatives.size()) {
                addResidual(residuals, tombstone);
                return new BookingRecoveryResult(
                        false,
                        "BOOKING_RECOVERY_LEGACY_ROW_IDENTITY_INCOMPLETE_REVIEW_REQUIRED",
                        residuals);
            }
            for (QuarantinedDerivative derivative : derivatives) {
                if (!bookingIds.add(derivative.bookingId)) {
                    addResidual(residuals, tombstone);
                    return new BookingRecoveryResult(
                            false, "BOOKING_RECOVERY_LEGACY_ROW_ID_DUPLICATE", residuals);
                }
                bookingRows.add(new BookingRowSnapshot(
                        derivative.bookingId,
                        derivative.original.getCanonicalPath()));
            }
        }
        for (QuarantinedDerivative derivative : derivatives) {
            boolean matched = false;
            for (BookingRowSnapshot row : bookingRows) {
                if (row.bookingId == derivative.bookingId
                        && row.localPath.equals(
                                derivative.original.getCanonicalPath())) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                addResidual(residuals, tombstone);
                return new BookingRecoveryResult(
                        false, "BOOKING_RECOVERY_DERIVATIVE_ROW_SNAPSHOT_MISMATCH", residuals);
            }
        }
        Set<String> allowedChildren = new HashSet<>();
        allowedChildren.add(tombstone.getCanonicalPath());
        if (journal.exists()) allowedChildren.add(journal.getCanonicalPath());
        for (QuarantinedDerivative derivative : derivatives) {
            allowedChildren.add(derivative.quarantined.getCanonicalPath());
        }
        File[] operationChildren = operation.listFiles();
        if (operationChildren == null) {
            addResidual(residuals, operation);
            return new BookingRecoveryResult(
                    false, "BOOKING_RECOVERY_OPERATION_UNREADABLE", residuals);
        }
        for (File child : operationChildren) {
            if (!allowedChildren.contains(child.getCanonicalPath())) {
                addResidual(residuals, child);
            }
        }
        if (!residuals.isEmpty()) {
            return new BookingRecoveryResult(
                    false, "BOOKING_RECOVERY_UNEXPECTED_OPERATION_CHILD", residuals);
        }

        boolean terminalPrecommit =
                "RECOVERY_PRECOMMIT_RESTORED_OR_CONFIRMED".equals(lastJournalEvent);
        boolean terminalCommitted =
                "RECOVERY_DATABASE_COMMITTED_NO_PRIVATE_RESIDUAL"
                        .equals(lastJournalEvent);
        if (terminalPrecommit || terminalCommitted) {
            for (QuarantinedDerivative derivative : derivatives) {
                if (derivative.quarantined.exists()) {
                    addResidual(residuals, derivative.quarantined);
                }
                if (terminalCommitted && derivative.original.exists()) {
                    addResidual(residuals, derivative.original);
                }
            }
            if (!residuals.isEmpty()) {
                return new BookingRecoveryResult(
                        false,
                        "BOOKING_RECOVERY_TERMINAL_RECORD_HAS_PRIVATE_RESIDUAL",
                        residuals);
            }
            return new BookingRecoveryResult(
                    true, "BOOKING_RECOVERY_TERMINAL_RECORD_VERIFIED", residuals);
        }

        SQLiteDatabase db = getWritableDatabase();
        long currentRows = countRows(
                db,
                "booking_imports",
                "person_profile_key=?",
                new String[]{profileKey});
        if (currentRows != expectedRows && currentRows != 0) {
            addResidual(residuals, operation);
            return new BookingRecoveryResult(
                    false, "BOOKING_RECOVERY_MIXED_DATABASE_STATE_REVIEW_REQUIRED", residuals);
        }

        if (currentRows == expectedRows) {
            if (!bookingRowsMatchSnapshot(db, bookingRows)) {
                addResidual(residuals, operation);
                return new BookingRecoveryResult(
                        false, "BOOKING_RECOVERY_ROW_SNAPSHOT_MISMATCH", residuals);
            }
            for (QuarantinedDerivative derivative : derivatives) {
                if (!bookingRowMatches(
                        db,
                        derivative.bookingId,
                        derivative.original.getCanonicalPath())) {
                    addResidual(residuals, derivative.original);
                    addResidual(residuals, derivative.quarantined);
                    return new BookingRecoveryResult(
                            false, "BOOKING_RECOVERY_ROW_OWNERSHIP_AMBIGUOUS", residuals);
                }
                boolean originalPresent = derivative.original.exists();
                boolean quarantinePresent = derivative.quarantined.exists();
                if (derivative.expectedBytes < 0
                        && !originalPresent
                        && !quarantinePresent) {
                    continue;
                }
                if (originalPresent
                        && derivative.original.isFile()
                        && derivative.original.length() == derivative.expectedBytes
                        && !quarantinePresent) {
                    continue;
                }
                if (!originalPresent
                        && quarantinePresent
                        && derivative.quarantined.isFile()
                        && derivative.quarantined.length() == derivative.expectedBytes) {
                    appendBookingClearJournal(
                            operation,
                            "RECOVERY_FILE_RESTORE_INTENT",
                            derivative,
                            expectedRows);
                    boolean restored = derivative.quarantined.renameTo(derivative.original);
                    if (!restored
                            || !derivative.original.isFile()
                            || derivative.original.length() != derivative.expectedBytes
                            || derivative.quarantined.exists()) {
                        addResidual(residuals, derivative.original);
                        addResidual(residuals, derivative.quarantined);
                        return new BookingRecoveryResult(
                                false, "BOOKING_RECOVERY_FILE_RESTORE_FAILED", residuals);
                    }
                    appendBookingClearJournal(
                            operation,
                            "RECOVERY_FILE_RESTORED",
                            derivative,
                            expectedRows);
                    continue;
                }
                addResidual(residuals, derivative.original);
                addResidual(residuals, derivative.quarantined);
                return new BookingRecoveryResult(
                        false, "BOOKING_RECOVERY_PRECOMMIT_FILE_STATE_AMBIGUOUS", residuals);
            }
            if (!"RECOVERY_PRECOMMIT_RESTORED_OR_CONFIRMED".equals(lastJournalEvent)) {
                appendBookingClearJournal(
                        operation,
                        "RECOVERY_PRECOMMIT_RESTORED_OR_CONFIRMED",
                        null,
                        expectedRows);
            }
            return new BookingRecoveryResult(
                    true, "BOOKING_RECOVERY_PRECOMMIT_RESTORED", residuals);
        }

        for (QuarantinedDerivative derivative : derivatives) {
            if (bookingRowMatches(
                    db,
                    derivative.bookingId,
                    derivative.original.getCanonicalPath())) {
                addResidual(residuals, operation);
                return new BookingRecoveryResult(
                        false, "BOOKING_RECOVERY_COMMITTED_ROW_REAPPEARED", residuals);
            }
            if (derivative.original.exists()) addResidual(residuals, derivative.original);
            if (derivative.quarantined.exists()) {
                addResidual(residuals, derivative.quarantined);
            }
        }
        if (!residuals.isEmpty()) {
            if (!"RECOVERY_DATABASE_COMMITTED_PRIVATE_RESIDUAL_REVIEW_REQUIRED"
                    .equals(lastJournalEvent)) {
                appendBookingClearJournal(
                        operation,
                        "RECOVERY_DATABASE_COMMITTED_PRIVATE_RESIDUAL_REVIEW_REQUIRED",
                        null,
                        expectedRows);
            }
            return new BookingRecoveryResult(
                    false,
                    "BOOKING_RECOVERY_COMMITTED_PRIVATE_RESIDUAL_REVIEW_REQUIRED",
                    residuals);
        }
        if (!"RECOVERY_DATABASE_COMMITTED_NO_PRIVATE_RESIDUAL".equals(lastJournalEvent)) {
            appendBookingClearJournal(
                    operation,
                    "RECOVERY_DATABASE_COMMITTED_NO_PRIVATE_RESIDUAL",
                    null,
                    expectedRows);
        }
        return new BookingRecoveryResult(
                true, "BOOKING_RECOVERY_COMMITTED_NO_PRIVATE_RESIDUAL", residuals);
    }

    /**
     * Clear exact current-profile rows and private derivatives as one bounded
     * operation. Files move to a same-volume private quarantine before the SQL
     * delete. A pre-commit failure rolls SQL back and restores every moved file.
     * After commit, every checked deletion must be verified before success is
     * reported; a residual quarantine is returned honestly instead.
     */
    public BookingClearResult clearBookingImportsAndDerivatives() {
        if (!mayAccessActiveProfile()) {
            return BookingClearResult.failure(
                    "ACTIVE_PROFILE_LEASE_UNAVAILABLE",
                    true,
                    Collections.emptyList());
        }
        BookingRecoveryResult recovery = reconcileBookingClearOperations();
        if (!recovery.ready) {
            return BookingClearResult.failure(
                    recovery.status,
                    false,
                    recovery.residualPaths);
        }
        SQLiteDatabase db = getWritableDatabase();
        List<QuarantinedDerivative> derivatives = new ArrayList<>();
        List<BookingRowSnapshot> bookingRows = new ArrayList<>();
        File operationDirectory = null;
        int expectedRows = 0;
        int deletedRows = 0;
        boolean transactionStarted = false;
        boolean commitRequested = false;
        boolean transactionEnded = false;
        String failureStatus = "BOOKING_CLEAR_PRECOMMIT_FAILED";
        try {
            db.beginTransaction();
            transactionStarted = true;
            if (!mayAccessActiveProfile()) {
                throw new IllegalStateException("ACTIVE_PROFILE_CHANGED_BEFORE_CLEAR");
            }

            Set<String> seenPaths = new HashSet<>();
            try (Cursor cursor = db.rawQuery(
                    "SELECT id,local_path FROM booking_imports "
                            + "WHERE person_profile_key=? ORDER BY id",
                    new String[]{profileKey})) {
                while (cursor.moveToNext()) {
                    expectedRows++;
                    if (expectedRows > MAX_BOOKING_CLEAR_ROWS) {
                        throw new IllegalStateException("BOOKING_CLEAR_ROW_LIMIT_EXCEEDED");
                    }
                    long bookingId = cursor.getLong(0);
                    String storedPath = value(cursor.getString(1)).trim();
                    bookingRows.add(new BookingRowSnapshot(bookingId, storedPath));
                    if (storedPath.isEmpty()) continue;
                    File exact = exactRowOwnedBookingImportFile(bookingId, storedPath);
                    String canonical = exact.getCanonicalPath();
                    if (seenPaths.add(canonical)) {
                        derivatives.add(new QuarantinedDerivative(bookingId, exact));
                    }
                }
            }

            operationDirectory = createBookingQuarantineOperation();
            assignQuarantineTargets(operationDirectory, derivatives);
            writeBookingClearTombstone(
                    operationDirectory,
                    derivatives,
                    bookingRows,
                    expectedRows,
                    "PLANNED_BEFORE_FILE_MOVE");
            appendBookingClearJournal(
                    operationDirectory,
                    "PLANNED_BEFORE_FILE_MOVE",
                    null,
                    expectedRows);
            for (QuarantinedDerivative derivative : derivatives) {
                if (!derivative.original.exists()) continue;
                if (!derivative.original.isFile()) {
                    throw new IllegalStateException("BOOKING_DERIVATIVE_IS_NOT_A_FILE");
                }
                appendBookingClearJournal(
                        operationDirectory,
                        "FILE_MOVE_INTENT",
                        derivative,
                        expectedRows);
                checkedMoveToQuarantine(derivative);
                appendBookingClearJournal(
                        operationDirectory,
                        "FILE_QUARANTINED",
                        derivative,
                        expectedRows);
            }
            writeBookingClearTombstone(
                    operationDirectory,
                    derivatives,
                    bookingRows,
                    expectedRows,
                    "FILES_QUARANTINED_DATABASE_NOT_COMMITTED");

            if (!mayAccessActiveProfile()) {
                throw new IllegalStateException("ACTIVE_PROFILE_CHANGED_AFTER_QUARANTINE");
            }
            deletedRows = db.delete(
                    "booking_imports", "person_profile_key=?", new String[]{profileKey});
            if (deletedRows != expectedRows
                    || countRows(
                            db,
                            "booking_imports",
                            "person_profile_key=?",
                            new String[]{profileKey}) != 0
                    || !mayAccessActiveProfile()) {
                throw new IllegalStateException("BOOKING_ROW_CLEAR_VERIFICATION_FAILED");
            }
            db.setTransactionSuccessful();
            commitRequested = true;
        } catch (Exception failure) {
            failureStatus = boundedFailureStatus(failure);
        } finally {
            if (transactionStarted) {
                try {
                    db.endTransaction();
                    transactionEnded = true;
                } catch (Exception endFailure) {
                    failureStatus = "BOOKING_CLEAR_TRANSACTION_END_FAILED";
                }
            }
        }

        long remainingRows;
        try {
            remainingRows = countRows(
                    db,
                    "booking_imports",
                    "person_profile_key=?",
                    new String[]{profileKey});
        } catch (Exception countFailure) {
            remainingRows = -1;
            failureStatus = "BOOKING_CLEAR_POST_TRANSACTION_VERIFY_FAILED";
        }
        boolean rowsCleared = commitRequested && transactionEnded && remainingRows == 0;
        if (!rowsCleared) {
            List<String> residuals = restoreQuarantinedDerivatives(derivatives);
            long restoredRows;
            try {
                restoredRows = countRows(
                        db,
                        "booking_imports",
                        "person_profile_key=?",
                        new String[]{profileKey});
            } catch (Exception countFailure) {
                restoredRows = -1;
            }
            boolean rollbackComplete = restoredRows == expectedRows
                    && bookingRowsMatchSnapshot(db, bookingRows)
                    && residuals.isEmpty();
            if (rollbackComplete) {
                residuals.addAll(removeRestoredQuarantine(operationDirectory, derivatives));
                rollbackComplete = residuals.isEmpty();
            } else if (operationDirectory != null) {
                try {
                    writeBookingClearTombstone(
                            operationDirectory,
                            derivatives,
                            bookingRows,
                            expectedRows,
                            "PRECOMMIT_ROLLBACK_INCOMPLETE_REVIEW_REQUIRED");
                } catch (Exception ignored) {
                    addResidual(residuals, operationDirectory);
                }
            }
            return BookingClearResult.failure(
                    rollbackComplete
                            ? failureStatus + "_ROLLED_BACK"
                            : failureStatus + "_ROLLBACK_INCOMPLETE",
                    rollbackComplete,
                    residuals);
        }

        try {
            writeBookingClearTombstone(
                    operationDirectory,
                    derivatives,
                    bookingRows,
                    expectedRows,
                    "DATABASE_ROWS_COMMITTED_PENDING_QUARANTINE_DELETE");
            appendBookingClearJournal(
                    operationDirectory,
                    "DATABASE_ROWS_COMMITTED_PENDING_QUARANTINE_DELETE",
                    null,
                    expectedRows);
        } catch (Exception ignored) {
            // The pre-commit tombstone remains. Deletion truth below is still exact.
        }
        List<String> residuals = deleteCommittedQuarantine(
                operationDirectory, derivatives, expectedRows);
        boolean derivativesCleared = residuals.isEmpty();
        return new BookingClearResult(
                derivativesCleared,
                true,
                derivativesCleared,
                true,
                deletedRows,
                derivativesCleared
                        ? "BOOKING_ROWS_AND_DERIVATIVES_CLEARED"
                        : "BOOKING_ROWS_CLEARED_RESIDUAL_PRIVATE_QUARANTINE",
                residuals);
    }

    /** Compatibility count succeeds only when rows and derivatives are both gone. */
    public int clearBookingImports() {
        BookingClearResult result = clearBookingImportsAndDerivatives();
        return result.success ? result.deletedCount : 0;
    }

    /**
     * Only the exact current profile directory, or a direct v1 legacy file
     * claimed with that profile's row, may be read or removed.
     */
    public boolean isOwnedBookingFilePath(String localPath) {
        if (!EventTripProfilePolicy.isVisibleProfileKey(profileKey)
                || value(localPath).trim().isEmpty()) return false;
        try {
            File root = new File(appContext.getFilesDir(), "booking_imports").getCanonicalFile();
            File candidate = new File(localPath).getCanonicalFile();
            File ownDirectory = new File(root, profileKey).getCanonicalFile();
            String candidatePath = candidate.getCanonicalPath();
            String ownPrefix = ownDirectory.getCanonicalPath() + File.separator;
            if (candidatePath.startsWith(ownPrefix)) return true;
            File parent = candidate.getParentFile();
            return parent != null && parent.getCanonicalFile().equals(root);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Row-bound validation also permits an older profile directory after an
     * explicit profile correction. The scoped row is the durable ownership
     * proof; the path must still remain inside Sarah's private import root.
     */
    public boolean isOwnedBookingFilePath(long bookingId, String localPath) {
        if (!mayAccessActiveProfile() || bookingId <= 0 || !isBookingLibraryPath(localPath)) {
            return false;
        }
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT 1 FROM booking_imports WHERE id=? AND person_profile_key=? "
                        + "AND local_path=? LIMIT 1",
                new String[]{String.valueOf(bookingId), profileKey, value(localPath)})) {
            return cursor.moveToFirst();
        }
    }

    private boolean isBookingLibraryPath(String localPath) {
        if (value(localPath).trim().isEmpty()) return false;
        try {
            File root = new File(appContext.getFilesDir(), BOOKING_IMPORT_ROOT).getCanonicalFile();
            File candidate = new File(localPath).getCanonicalFile();
            return candidate.getCanonicalPath().startsWith(
                    root.getCanonicalPath() + File.separator);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Resolve only a current-profile private derivative or a direct legacy-v1
     * derivative. Canonical containment prevents a stored path from escaping
     * Sarah's private import root or selecting another profile's directory.
     */
    private File exactRowOwnedBookingImportFile(long bookingId, String localPath)
            throws Exception {
        if (!EventTripProfilePolicy.isVisibleProfileKey(profileKey)
                || bookingId <= 0
                || value(localPath).trim().isEmpty()) {
            throw new IllegalStateException("BOOKING_DERIVATIVE_PATH_UNAVAILABLE");
        }
        File supplied = new File(localPath);
        if (!supplied.isAbsolute()) {
            throw new IllegalStateException("BOOKING_DERIVATIVE_PATH_NOT_ABSOLUTE");
        }
        File privateRoot = appContext.getFilesDir().getCanonicalFile();
        File root = new File(privateRoot, BOOKING_IMPORT_ROOT).getCanonicalFile();
        File ownDirectory = new File(root, profileKey).getCanonicalFile();
        File candidate = supplied.getCanonicalFile();
        if (root.getParentFile() == null
                || !root.getParentFile().getCanonicalFile().equals(privateRoot)) {
            throw new IllegalStateException("BOOKING_IMPORT_ROOT_REJECTED");
        }
        if (!supplied.getAbsoluteFile().getPath().equals(candidate.getPath())) {
            throw new IllegalStateException("BOOKING_DERIVATIVE_PATH_NOT_CANONICAL");
        }
        if (ownDirectory.getParentFile() == null
                || !ownDirectory.getParentFile().getCanonicalFile().equals(root)) {
            throw new IllegalStateException("BOOKING_PROFILE_IMPORT_ROOT_REJECTED");
        }
        File parent = candidate.getParentFile();
        boolean directLegacyFile = parent != null
                && parent.getCanonicalFile().equals(root);
        boolean directCurrentProfileFile = parent != null
                && parent.getCanonicalFile().equals(ownDirectory);
        boolean directHistoricalProfileFile = parent != null
                && parent.getParentFile() != null
                && parent.getParentFile().getCanonicalFile().equals(root)
                && EventTripProfilePolicy.isVisibleProfileKey(parent.getName());
        if (!directCurrentProfileFile
                && !directLegacyFile
                && !directHistoricalProfileFile) {
            throw new IllegalStateException("BOOKING_DERIVATIVE_OUTSIDE_ROW_OWNED_PROFILE");
        }
        try (Cursor owner = getReadableDatabase().rawQuery(
                "SELECT 1 FROM booking_imports WHERE id=? AND person_profile_key=? "
                        + "AND local_path=? LIMIT 1",
                new String[]{String.valueOf(bookingId), profileKey, value(localPath)})) {
            if (!owner.moveToFirst()) {
                throw new IllegalStateException("BOOKING_DERIVATIVE_ROW_OWNERSHIP_REJECTED");
            }
        }
        return candidate;
    }

    /** Create one exact operation directory; never reuse or recursively clear it. */
    private File createBookingQuarantineOperation() throws Exception {
        File profileDirectory = bookingQuarantineProfileDirectory(true);
        File operationDirectory = new File(
                profileDirectory, "clear_" + UUID.randomUUID()).getCanonicalFile();
        if (operationDirectory.getParentFile() == null
                || !operationDirectory.getParentFile().getCanonicalFile()
                        .equals(profileDirectory)
                || !operationDirectory.mkdir()) {
            throw new IllegalStateException("BOOKING_QUARANTINE_OPERATION_UNAVAILABLE");
        }
        return operationDirectory;
    }

    private File bookingQuarantineProfileDirectory(boolean create) throws Exception {
        if (!EventTripProfilePolicy.isVisibleProfileKey(profileKey)) {
            throw new IllegalStateException("BOOKING_QUARANTINE_PROFILE_REJECTED");
        }
        File privateRoot = appContext.getFilesDir().getCanonicalFile();
        File root = new File(privateRoot, BOOKING_QUARANTINE_ROOT).getCanonicalFile();
        if (root.getParentFile() == null
                || !root.getParentFile().getCanonicalFile().equals(privateRoot)) {
            throw new IllegalStateException("BOOKING_QUARANTINE_ROOT_REJECTED");
        }
        if (!root.exists()) {
            if (!create) return null;
            if (!root.mkdirs()) {
                throw new IllegalStateException("BOOKING_QUARANTINE_ROOT_UNAVAILABLE");
            }
        }
        if (!root.isDirectory()) {
            throw new IllegalStateException("BOOKING_QUARANTINE_ROOT_NOT_DIRECTORY");
        }
        File profileDirectory = new File(root, profileKey).getCanonicalFile();
        if (profileDirectory.getParentFile() == null
                || !profileDirectory.getParentFile().getCanonicalFile().equals(root)) {
            throw new IllegalStateException("BOOKING_QUARANTINE_PROFILE_OUTSIDE_ROOT");
        }
        if (!profileDirectory.exists()) {
            if (!create) return null;
            if (!profileDirectory.mkdir()) {
                throw new IllegalStateException("BOOKING_QUARANTINE_PROFILE_UNAVAILABLE");
            }
        }
        if (!profileDirectory.isDirectory()) {
            throw new IllegalStateException("BOOKING_QUARANTINE_PROFILE_NOT_DIRECTORY");
        }
        return profileDirectory;
    }

    private static void assignQuarantineTargets(
            File operationDirectory,
            List<QuarantinedDerivative> derivatives) throws Exception {
        File exactOperation = operationDirectory.getCanonicalFile();
        for (int index = 0; index < derivatives.size(); index++) {
            QuarantinedDerivative derivative = derivatives.get(index);
            File target = new File(
                    exactOperation,
                    String.format(
                            Locale.US,
                            "derivative_%04d_booking_%d.private",
                            index,
                            derivative.bookingId)).getCanonicalFile();
            if (target.getParentFile() == null
                    || !target.getParentFile().getCanonicalFile().equals(exactOperation)) {
                throw new IllegalStateException("BOOKING_QUARANTINE_TARGET_REJECTED");
            }
            derivative.quarantined = target;
        }
    }

    /** Persist one immutable rollback manifest; later boundaries use the append-only journal. */
    private void writeBookingClearTombstone(
            File operationDirectory,
            List<QuarantinedDerivative> derivatives,
            List<BookingRowSnapshot> bookingRows,
            int expectedRows,
            String operationStatus) throws Exception {
        File exactOperation = operationDirectory.getCanonicalFile();
        File profileDirectory = exactOperation.getParentFile();
        File quarantineRoot = profileDirectory == null ? null : profileDirectory.getParentFile();
        File expectedRoot = new File(
                appContext.getFilesDir(), BOOKING_QUARANTINE_ROOT).getCanonicalFile();
        if (profileDirectory == null
                || quarantineRoot == null
                || !quarantineRoot.getCanonicalFile().equals(expectedRoot)
                || !profileKey.equals(profileDirectory.getName())
                || !exactOperation.isDirectory()) {
            throw new IllegalStateException("BOOKING_TOMBSTONE_SCOPE_REJECTED");
        }

        JSONObject root = new JSONObject();
        root.put("contract", "sarah-booking-clear-private-quarantine-v1");
        root.put("profile_key", profileKey);
        root.put("operation_status", value(operationStatus));
        root.put("expected_database_rows", expectedRows);
        root.put("updated_at_epoch_ms", System.currentTimeMillis());
        JSONArray rowItems = new JSONArray();
        for (BookingRowSnapshot row : bookingRows) {
            JSONObject item = new JSONObject();
            item.put("booking_id", row.bookingId);
            item.put("local_path", row.localPath);
            rowItems.put(item);
        }
        root.put("booking_rows", rowItems);
        JSONArray items = new JSONArray();
        for (QuarantinedDerivative derivative : derivatives) {
            JSONObject item = new JSONObject();
            item.put("booking_id", derivative.bookingId);
            item.put("original_path", derivative.original.getCanonicalPath());
            item.put(
                    "quarantine_path",
                    derivative.quarantined == null
                            ? ""
                            : derivative.quarantined.getCanonicalPath());
            item.put("expected_bytes", derivative.expectedBytes);
            item.put("moved", derivative.moved);
            item.put("original_present", derivative.original.exists());
            item.put(
                    "quarantine_present",
                    derivative.quarantined != null && derivative.quarantined.exists());
            items.put(item);
        }
        root.put("derivatives", items);

        File tombstone = new File(
                exactOperation, BOOKING_CLEAR_TOMBSTONE).getCanonicalFile();
        if (tombstone.getParentFile() == null
                || !tombstone.getParentFile().getCanonicalFile().equals(exactOperation)) {
            throw new IllegalStateException("BOOKING_TOMBSTONE_PATH_REJECTED");
        }
        if (tombstone.exists()) {
            if (!tombstone.isFile()
                    || tombstone.length() < 1
                    || tombstone.length() > MAX_BOOKING_JOURNAL_BYTES) {
                throw new IllegalStateException("BOOKING_TOMBSTONE_EXISTING_REJECTED");
            }
            return;
        }
        File pending = exactOperationChild(
                exactOperation, BOOKING_CLEAR_TOMBSTONE_PENDING);
        if (pending.exists()) {
            throw new IllegalStateException("BOOKING_TOMBSTONE_PENDING_EXISTS");
        }
        byte[] encoded = root.toString(2).getBytes(StandardCharsets.UTF_8);
        if (encoded.length < 1 || encoded.length > MAX_BOOKING_JOURNAL_BYTES) {
            throw new IllegalStateException("BOOKING_TOMBSTONE_SIZE_REJECTED");
        }
        try (FileOutputStream output = new FileOutputStream(pending, false)) {
            output.write(encoded);
            output.flush();
            output.getFD().sync();
        }
        if (!pending.isFile() || pending.length() != encoded.length
                || !pending.renameTo(tombstone)
                || pending.exists()
                || !tombstone.isFile()
                || tombstone.length() != encoded.length) {
            throw new IllegalStateException("BOOKING_TOMBSTONE_WRITE_NOT_VERIFIED");
        }
    }

    /** Append and fsync one exact operation boundary; existing journal bytes are never rewritten. */
    private void appendBookingClearJournal(
            File operationDirectory,
            String event,
            QuarantinedDerivative derivative,
            int expectedRows) throws Exception {
        File exactOperation = operationDirectory.getCanonicalFile();
        File profileDirectory = exactOperation.getParentFile();
        File expectedProfile = bookingQuarantineProfileDirectory(false);
        if (expectedProfile == null
                || profileDirectory == null
                || !profileDirectory.getCanonicalFile().equals(expectedProfile)
                || !exactOperation.isDirectory()) {
            throw new IllegalStateException("BOOKING_JOURNAL_SCOPE_REJECTED");
        }
        JSONObject line = new JSONObject();
        line.put("contract", "sarah-booking-clear-journal-v2");
        line.put("profile_key", profileKey);
        line.put("operation_id", exactOperation.getName());
        line.put("event", value(event));
        line.put("expected_database_rows", expectedRows);
        line.put("recorded_at_epoch_ms", System.currentTimeMillis());
        if (derivative != null) {
            line.put("booking_id", derivative.bookingId);
            line.put("original_path", derivative.original.getCanonicalPath());
            line.put(
                    "quarantine_path",
                    derivative.quarantined == null
                            ? ""
                            : derivative.quarantined.getCanonicalPath());
            line.put("expected_bytes", derivative.expectedBytes);
            line.put("original_present", derivative.original.exists());
            line.put(
                    "quarantine_present",
                    derivative.quarantined != null && derivative.quarantined.exists());
        }
        byte[] encoded = (line.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        File journal = exactOperationChild(exactOperation, BOOKING_CLEAR_JOURNAL);
        long existing = journal.exists() ? journal.length() : 0L;
        if ((journal.exists() && !journal.isFile())
                || existing < 0
                || existing + encoded.length > MAX_BOOKING_JOURNAL_BYTES) {
            throw new IllegalStateException("BOOKING_JOURNAL_SIZE_REJECTED");
        }
        try (FileOutputStream output = new FileOutputStream(journal, true)) {
            output.write(encoded);
            output.flush();
            output.getFD().sync();
        }
        if (!journal.isFile() || journal.length() != existing + encoded.length) {
            throw new IllegalStateException("BOOKING_JOURNAL_APPEND_NOT_VERIFIED");
        }
    }

    /** Validate every existing append-only journal record; missing means a legacy v1 attempt. */
    private String validateBookingClearJournal(File operation, File journal) throws Exception {
        if (!journal.exists()) return "LEGACY_V1_TOMBSTONE_WITHOUT_JOURNAL";
        if (!journal.isFile()
                || journal.length() < 1
                || journal.length() > MAX_BOOKING_JOURNAL_BYTES) {
            throw new IllegalStateException("BOOKING_RECOVERY_JOURNAL_SIZE_REJECTED");
        }
        String lastEvent = "";
        int records = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(journal), StandardCharsets.UTF_8))) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                if (raw.trim().isEmpty()) {
                    throw new IllegalStateException("BOOKING_RECOVERY_JOURNAL_EMPTY_RECORD");
                }
                JSONObject line = new JSONObject(raw);
                if (!"sarah-booking-clear-journal-v2".equals(line.optString("contract"))
                        || !profileKey.equals(line.optString("profile_key"))
                        || !operation.getName().equals(line.optString("operation_id"))) {
                    throw new IllegalStateException("BOOKING_RECOVERY_JOURNAL_SCOPE_REJECTED");
                }
                lastEvent = line.optString("event");
                if (lastEvent.trim().isEmpty()) {
                    throw new IllegalStateException("BOOKING_RECOVERY_JOURNAL_EVENT_MISSING");
                }
                records++;
                if (records > MAX_BOOKING_CLEAR_ROWS * 8 + 32) {
                    throw new IllegalStateException("BOOKING_RECOVERY_JOURNAL_RECORD_LIMIT");
                }
            }
        }
        if (records == 0) {
            throw new IllegalStateException("BOOKING_RECOVERY_JOURNAL_EMPTY");
        }
        return lastEvent;
    }

    private File exactOperationChild(File operation, String name) throws Exception {
        File exactOperation = operation.getCanonicalFile();
        File child = new File(exactOperation, name).getCanonicalFile();
        if (child.getParentFile() == null
                || !child.getParentFile().getCanonicalFile().equals(exactOperation)) {
            throw new IllegalStateException("BOOKING_RECOVERY_OPERATION_CHILD_REJECTED");
        }
        return child;
    }

    private File exactRecordedBookingPath(String path) throws Exception {
        String clean = value(path).trim();
        File supplied = new File(clean);
        File candidate = supplied.getCanonicalFile();
        File root = new File(
                appContext.getFilesDir(), BOOKING_IMPORT_ROOT).getCanonicalFile();
        File parent = candidate.getParentFile();
        boolean directLegacy = parent != null
                && parent.getCanonicalFile().equals(root);
        boolean directProfile = parent != null
                && parent.getParentFile() != null
                && parent.getParentFile().getCanonicalFile().equals(root)
                && EventTripProfilePolicy.isVisibleProfileKey(parent.getName());
        if (clean.isEmpty()
                || !supplied.isAbsolute()
                || !supplied.getAbsoluteFile().getPath().equals(candidate.getPath())
                || !candidate.getCanonicalPath().startsWith(
                        root.getCanonicalPath() + File.separator)
                || (!directLegacy && !directProfile)) {
            throw new IllegalStateException("BOOKING_RECOVERY_ORIGINAL_PATH_REJECTED");
        }
        return candidate;
    }

    private static File exactRecordedQuarantinePath(File operation, String path)
            throws Exception {
        String clean = value(path).trim();
        File supplied = new File(clean);
        File candidate = supplied.getCanonicalFile();
        File exactOperation = operation.getCanonicalFile();
        if (clean.isEmpty()
                || !supplied.isAbsolute()
                || !supplied.getAbsoluteFile().getPath().equals(candidate.getPath())
                || candidate.getParentFile() == null
                || !candidate.getParentFile().getCanonicalFile().equals(exactOperation)) {
            throw new IllegalStateException("BOOKING_RECOVERY_QUARANTINE_PATH_REJECTED");
        }
        return candidate;
    }

    private static String readBoundedUtf8(File file) throws Exception {
        if (!file.isFile() || file.length() < 1 || file.length() > MAX_BOOKING_JOURNAL_BYTES) {
            throw new IllegalStateException("BOOKING_RECOVERY_RECORD_SIZE_REJECTED");
        }
        StringBuilder out = new StringBuilder((int) Math.min(file.length(), 65536L));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                out.append(buffer, 0, count);
                if (out.length() > MAX_BOOKING_JOURNAL_BYTES) {
                    throw new IllegalStateException("BOOKING_RECOVERY_RECORD_SIZE_REJECTED");
                }
            }
        }
        return out.toString();
    }

    private boolean bookingRowMatches(SQLiteDatabase db, long bookingId, String originalPath) {
        try (Cursor cursor = db.rawQuery(
                "SELECT 1 FROM booking_imports WHERE id=? AND person_profile_key=? "
                        + "AND local_path=? LIMIT 1",
                new String[]{String.valueOf(bookingId), profileKey, value(originalPath)})) {
            return cursor.moveToFirst();
        }
    }

    private boolean bookingRowsMatchSnapshot(
            SQLiteDatabase db,
            List<BookingRowSnapshot> bookingRows) {
        try {
            for (BookingRowSnapshot row : bookingRows) {
                try (Cursor cursor = db.rawQuery(
                        "SELECT 1 FROM booking_imports WHERE id=? AND person_profile_key=? "
                                + "AND local_path=? LIMIT 1",
                        new String[]{
                            String.valueOf(row.bookingId), profileKey, row.localPath
                        })) {
                    if (!cursor.moveToFirst()) return false;
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Same-private-volume move with explicit before/after state verification. */
    private static void checkedMoveToQuarantine(
            QuarantinedDerivative derivative) throws Exception {
        if (derivative.quarantined == null || derivative.quarantined.exists()) {
            throw new IllegalStateException("BOOKING_QUARANTINE_TARGET_ALREADY_EXISTS");
        }
        boolean renamed = derivative.original.renameTo(derivative.quarantined);
        derivative.moved = !derivative.original.exists() && derivative.quarantined.isFile();
        if (!renamed
                || !derivative.moved
                || derivative.quarantined.length() != derivative.expectedBytes) {
            throw new IllegalStateException("BOOKING_DERIVATIVE_QUARANTINE_MOVE_FAILED");
        }
    }

    /** Restore in reverse move order. Returned paths are exact unresolved residuals. */
    private static List<String> restoreQuarantinedDerivatives(
            List<QuarantinedDerivative> derivatives) {
        List<String> residuals = new ArrayList<>();
        for (int index = derivatives.size() - 1; index >= 0; index--) {
            QuarantinedDerivative derivative = derivatives.get(index);
            try {
                if (!derivative.moved) {
                    if (derivative.expectedBytes >= 0
                            && (!derivative.original.isFile()
                                    || derivative.original.length()
                                            != derivative.expectedBytes)) {
                        addResidual(residuals, derivative.original);
                    }
                    continue;
                }
                if (derivative.original.exists()) {
                    if (!derivative.original.isFile()
                            || derivative.original.length() != derivative.expectedBytes
                            || (derivative.quarantined != null
                                    && derivative.quarantined.exists())) {
                        addResidual(residuals, derivative.original);
                        addResidual(residuals, derivative.quarantined);
                    }
                    continue;
                }
                if (derivative.quarantined == null
                        || !derivative.quarantined.isFile()
                        || derivative.quarantined.length() != derivative.expectedBytes) {
                    addResidual(residuals, derivative.quarantined);
                    continue;
                }
                boolean restored = derivative.quarantined.renameTo(derivative.original);
                if (!restored
                        || !derivative.original.isFile()
                        || derivative.original.length() != derivative.expectedBytes
                        || derivative.quarantined.exists()) {
                    addResidual(residuals, derivative.original);
                    addResidual(residuals, derivative.quarantined);
                }
            } catch (Exception ignored) {
                addResidual(residuals, derivative.original);
                addResidual(residuals, derivative.quarantined);
            }
        }
        return residuals;
    }

    /** Remove only the exact restored operation's own tombstone and empty directory. */
    private static List<String> removeRestoredQuarantine(
            File operationDirectory,
            List<QuarantinedDerivative> derivatives) {
        List<String> residuals = new ArrayList<>();
        if (operationDirectory == null) return residuals;
        checkedDeleteExactFile(
                new File(operationDirectory, BOOKING_CLEAR_JOURNAL), residuals);
        File tombstone = new File(operationDirectory, BOOKING_CLEAR_TOMBSTONE);
        checkedDeleteExactFile(tombstone, residuals);
        collectUnexpectedOperationChildren(operationDirectory, residuals);
        checkedDeleteEmptyDirectory(operationDirectory, residuals);
        return residuals;
    }

    /**
     * After the row commit, erase only enumerated quarantine files and the one
     * tombstone. Any failed deletion or unexpected child is returned and makes
     * the public result a truthful partial failure.
     */
    private List<String> deleteCommittedQuarantine(
            File operationDirectory,
            List<QuarantinedDerivative> derivatives,
            int expectedRows) {
        List<String> residuals = new ArrayList<>();
        for (QuarantinedDerivative derivative : derivatives) {
            try {
                if (derivative.original.exists()) {
                    addResidual(residuals, derivative.original);
                }
                if (derivative.quarantined != null
                        && derivative.quarantined.exists()) {
                    if (!derivative.quarantined.isFile()
                            || (derivative.expectedBytes >= 0
                                    && derivative.quarantined.length()
                                            != derivative.expectedBytes)) {
                        addResidual(residuals, derivative.quarantined);
                    } else {
                        appendBookingClearJournal(
                                operationDirectory,
                                "PRIVATE_QUARANTINE_DELETE_INTENT",
                                derivative,
                                expectedRows);
                        checkedDeleteExactFile(derivative.quarantined, residuals);
                        if (!derivative.quarantined.exists()) {
                            appendBookingClearJournal(
                                    operationDirectory,
                                    "PRIVATE_QUARANTINE_DELETED",
                                    derivative,
                                    expectedRows);
                        }
                    }
                }
            } catch (Exception ignored) {
                addResidual(residuals, derivative.original);
                addResidual(residuals, derivative.quarantined);
            }
        }
        if (operationDirectory == null) {
            addResidual(residuals, null);
            return residuals;
        }
        try {
            appendBookingClearJournal(
                    operationDirectory,
                    residuals.isEmpty()
                            ? "CLEAR_COMPLETE_NO_PRIVATE_RESIDUAL"
                            : "CLEAR_PARTIAL_PRIVATE_RESIDUAL_REVIEW_REQUIRED",
                    null,
                    expectedRows);
        } catch (Exception journalFailure) {
            addResidual(residuals, new File(operationDirectory, BOOKING_CLEAR_JOURNAL));
        }
        if (!residuals.isEmpty()) return residuals;
        checkedDeleteExactFile(
                new File(operationDirectory, BOOKING_CLEAR_JOURNAL), residuals);
        checkedDeleteExactFile(
                new File(operationDirectory, BOOKING_CLEAR_TOMBSTONE), residuals);
        collectUnexpectedOperationChildren(operationDirectory, residuals);
        checkedDeleteEmptyDirectory(operationDirectory, residuals);
        return residuals;
    }

    private static void checkedDeleteExactFile(File file, List<String> residuals) {
        if (file == null || !file.exists()) return;
        if (!file.isFile() || !file.delete() || file.exists()) {
            addResidual(residuals, file);
        }
    }

    private static void collectUnexpectedOperationChildren(
            File operationDirectory,
            List<String> residuals) {
        if (operationDirectory == null || !operationDirectory.exists()) return;
        File[] children = operationDirectory.listFiles();
        if (children == null) {
            addResidual(residuals, operationDirectory);
            return;
        }
        for (File child : children) addResidual(residuals, child);
    }

    private static void checkedDeleteEmptyDirectory(
            File operationDirectory,
            List<String> residuals) {
        if (operationDirectory == null || !operationDirectory.exists()) return;
        File[] children = operationDirectory.listFiles();
        if (children == null || children.length != 0
                || !operationDirectory.delete() || operationDirectory.exists()) {
            addResidual(residuals, operationDirectory);
        }
    }

    private static void addResidual(List<String> residuals, File residual) {
        String path;
        try {
            path = residual == null
                    ? "UNKNOWN_PRIVATE_QUARANTINE_RESIDUAL"
                    : residual.getCanonicalPath();
        } catch (Exception ignored) {
            path = residual == null
                    ? "UNKNOWN_PRIVATE_QUARANTINE_RESIDUAL"
                    : residual.getAbsolutePath();
        }
        if (!residuals.contains(path)) residuals.add(path);
    }

    private static String boundedFailureStatus(Exception failure) {
        String message = value(failure == null ? "" : failure.getMessage()).trim();
        if (message.matches("[A-Z0-9_]{3,120}")) return message;
        String kind = failure == null
                ? "UNKNOWN"
                : failure.getClass().getSimpleName().toUpperCase(Locale.US)
                        .replaceAll("[^A-Z0-9]+", "_");
        return "BOOKING_CLEAR_" + (kind.isEmpty() ? "FAILED" : kind);
    }

    private static String boundedRecoveryStatus(Exception failure) {
        String message = value(failure == null ? "" : failure.getMessage()).trim();
        if (message.matches("[A-Z0-9_]{3,120}")) return message;
        String kind = failure == null
                ? "UNKNOWN"
                : failure.getClass().getSimpleName().toUpperCase(Locale.US)
                        .replaceAll("[^A-Z0-9]+", "_");
        return "BOOKING_RECOVERY_" + (kind.isEmpty() ? "FAILED" : kind);
    }

    private static Context requireApplicationContext(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("EVENT_TRIP_CONTEXT_REQUIRED");
        }
        Context application = context.getApplicationContext();
        return application == null ? context : application;
    }

    private long addBooking(
            Long eventTripId,
            String bookingType,
            String provider,
            String sourceKind,
            String sourceUrl,
            String localPath,
            String rawText) {
        if (!mayAccessActiveProfile()) return -1;
        BookingRecoveryResult recovery = reconcileBookingClearOperations();
        if (!recovery.ready) return -1;
        if (eventTripId != null && !ownsEvent(eventTripId)) return -1;
        ContentValues values = new ContentValues();
        values.put("person_profile_key", profileKey);
        if (eventTripId != null) values.put("event_trip_id", eventTripId);
        values.put("booking_type", value(bookingType));
        values.put("provider", value(provider));
        values.put("source_kind", value(sourceKind));
        values.put("source_url", value(sourceUrl));
        values.put("local_path", value(localPath));
        values.put("raw_text", value(rawText));
        values.put("status", "pending_review");
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("booking_imports", null, values);
    }

    public boolean updateBookingExtraction(
            long id,
            String bookingType,
            String provider,
            String summary,
            String confirmationCode,
            String startDate,
            String endDate,
            String address,
            double total,
            String currency,
            String status) {
        if (!mayAccessActiveProfile()) return false;
        BookingRecoveryResult recovery = reconcileBookingClearOperations();
        if (!recovery.ready) return false;
        ContentValues values = new ContentValues();
        values.put("booking_type", value(bookingType));
        values.put("provider", value(provider));
        values.put("extracted_summary", value(summary));
        values.put("confirmation_code", value(confirmationCode));
        values.put("start_date", value(startDate));
        values.put("end_date", value(endDate));
        values.put("address", value(address));
        values.put("total", total);
        values.put("currency", value(currency).isEmpty() ? "USD" : currency.trim());
        values.put("status", value(status).isEmpty() ? "pending_review" : status);
        return getWritableDatabase().update(
                "booking_imports", values, "id=? AND person_profile_key=?",
                new String[]{String.valueOf(id), profileKey}) == 1;
    }

    public List<Map<String, String>> listPendingBookings(int limit) {
        return bookingRows("status='pending_review'", limit);
    }

    public List<Map<String, String>> listBookings(int limit) {
        return bookingRows("1=1", limit);
    }

    private List<Map<String, String>> bookingRows(String condition, int limit) {
        List<Map<String, String>> rows = new ArrayList<>();
        if (!mayAccessActiveProfile()) return rows;
        BookingRecoveryResult recovery = reconcileBookingClearOperations();
        if (!recovery.ready) return rows;
        String sql = "SELECT id,booking_type,provider,source_kind,source_url,local_path,"
                + "raw_text,extracted_summary,confirmation_code,start_date,end_date,address,"
                + "total,currency,status FROM booking_imports WHERE person_profile_key=? AND "
                + condition + " ORDER BY id DESC LIMIT ?";
        try (Cursor c = getReadableDatabase().rawQuery(
                sql, new String[]{profileKey, String.valueOf(Math.max(1, limit))})) {
            while (c.moveToNext()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", String.valueOf(c.getLong(0)));
                row.put("booking_type", c.getString(1));
                row.put("provider", c.getString(2));
                row.put("source_kind", c.getString(3));
                row.put("source_url", c.getString(4));
                row.put("local_path", c.getString(5));
                row.put("raw_text", c.getString(6));
                row.put("extracted_summary", c.getString(7));
                row.put("confirmation_code", c.getString(8));
                row.put("start_date", c.getString(9));
                row.put("end_date", c.getString(10));
                row.put("address", c.getString(11));
                row.put("total", String.valueOf(c.getDouble(12)));
                row.put("currency", c.getString(13));
                row.put("status", c.getString(14));
                row.put("person_profile_key", profileKey);
                rows.add(row);
            }
        }
        return rows;
    }

    private boolean ownsEvent(long eventTripId) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT 1 FROM event_trips WHERE id=? AND person_profile_key=? LIMIT 1",
                new String[]{String.valueOf(eventTripId), profileKey})) {
            return cursor.moveToFirst();
        }
    }

    /** Re-check the active durable person before every read/write to block stale UI/job work. */
    private boolean mayAccessActiveProfile() {
        if (!EventTripProfilePolicy.isVisibleProfileKey(profileKey)) return false;
        return EventTripProfilePolicy.sameProfile(profileKey, activePersonId(appContext));
    }

    private static void createTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE event_trips ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "person_profile_key TEXT NOT NULL,"
                + "event_key TEXT NOT NULL,"
                + "event_name TEXT NOT NULL,"
                + "destination TEXT NOT NULL COLLATE NOCASE,"
                + "venue TEXT NOT NULL DEFAULT '',"
                + "start_date TEXT NOT NULL DEFAULT '',"
                + "end_date TEXT NOT NULL DEFAULT '',"
                + "official_url TEXT NOT NULL DEFAULT '',"
                + "monitor_status TEXT NOT NULL DEFAULT 'queued',"
                + "updates_summary TEXT NOT NULL DEFAULT '',"
                + "nearby_food TEXT NOT NULL DEFAULT '',"
                + "nearby_places TEXT NOT NULL DEFAULT '',"
                + "transport_notes TEXT NOT NULL DEFAULT '',"
                + "source_note TEXT NOT NULL DEFAULT '',"
                + "last_checked_at INTEGER NOT NULL DEFAULT 0,"
                + "next_check_at INTEGER NOT NULL DEFAULT 0,"
                + "monitor_enabled INTEGER NOT NULL DEFAULT 0,"
                + "active INTEGER NOT NULL DEFAULT 1,"
                + "created_at INTEGER NOT NULL,"
                + "UNIQUE(person_profile_key,event_key,destination))");
        db.execSQL("CREATE TABLE event_updates ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "person_profile_key TEXT NOT NULL,"
                + "event_trip_id INTEGER NOT NULL,"
                + "update_key TEXT NOT NULL,"
                + "category TEXT NOT NULL DEFAULT 'general',"
                + "title TEXT NOT NULL,"
                + "detail TEXT NOT NULL DEFAULT '',"
                + "source_url TEXT NOT NULL DEFAULT '',"
                + "published_at TEXT NOT NULL DEFAULT '',"
                + "detected_at INTEGER NOT NULL,"
                + "UNIQUE(person_profile_key,event_trip_id,update_key))");
        db.execSQL("CREATE TABLE booking_imports ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "person_profile_key TEXT NOT NULL,"
                + "event_trip_id INTEGER,"
                + "booking_type TEXT NOT NULL DEFAULT 'travel',"
                + "provider TEXT NOT NULL DEFAULT 'Other',"
                + "source_kind TEXT NOT NULL,"
                + "source_url TEXT NOT NULL DEFAULT '',"
                + "local_path TEXT NOT NULL DEFAULT '',"
                + "raw_text TEXT NOT NULL DEFAULT '',"
                + "extracted_summary TEXT NOT NULL DEFAULT '',"
                + "confirmation_code TEXT NOT NULL DEFAULT '',"
                + "start_date TEXT NOT NULL DEFAULT '',"
                + "end_date TEXT NOT NULL DEFAULT '',"
                + "address TEXT NOT NULL DEFAULT '',"
                + "total REAL NOT NULL DEFAULT 0,"
                + "currency TEXT NOT NULL DEFAULT 'USD',"
                + "status TEXT NOT NULL DEFAULT 'pending_review',"
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX event_trips_profile_active_idx "
                + "ON event_trips(person_profile_key,active,monitor_enabled,next_check_at)");
        db.execSQL("CREATE INDEX event_updates_profile_event_idx "
                + "ON event_updates(person_profile_key,event_trip_id)");
        db.execSQL("CREATE INDEX booking_imports_profile_status_idx "
                + "ON booking_imports(person_profile_key,status)");
    }

    /** Copy every v1 row with the same ID, verify counts, then remove only verified temp tables. */
    private static void migrateV1Losslessly(SQLiteDatabase db) {
        long eventCount = countRows(db, "event_trips", "", null);
        long updateCount = countRows(db, "event_updates", "", null);
        long bookingCount = countRows(db, "booking_imports", "", null);

        db.execSQL("ALTER TABLE event_trips RENAME TO event_trips_v1_preserved");
        db.execSQL("ALTER TABLE event_updates RENAME TO event_updates_v1_preserved");
        db.execSQL("ALTER TABLE booking_imports RENAME TO booking_imports_v1_preserved");
        createTables(db);

        String legacy = EventTripProfilePolicy.LEGACY_OWNER_UNASSIGNED;
        db.execSQL("INSERT INTO event_trips(id,person_profile_key,event_key,event_name,destination,"
                        + "venue,start_date,end_date,official_url,monitor_status,updates_summary,"
                        + "nearby_food,nearby_places,transport_notes,source_note,last_checked_at,"
                        + "next_check_at,monitor_enabled,active,created_at) SELECT id,?,event_key,event_name,destination,"
                        + "venue,start_date,end_date,official_url,monitor_status,updates_summary,"
                        + "nearby_food,nearby_places,transport_notes,source_note,last_checked_at,"
                        + "next_check_at,0,active,created_at FROM event_trips_v1_preserved",
                new Object[]{legacy});
        db.execSQL("INSERT INTO event_updates(id,person_profile_key,event_trip_id,update_key,category,"
                        + "title,detail,source_url,published_at,detected_at) SELECT id,?,event_trip_id,"
                        + "update_key,category,title,detail,source_url,published_at,detected_at "
                        + "FROM event_updates_v1_preserved",
                new Object[]{legacy});
        db.execSQL("INSERT INTO booking_imports(id,person_profile_key,event_trip_id,booking_type,"
                        + "provider,source_kind,source_url,local_path,raw_text,extracted_summary,"
                        + "confirmation_code,start_date,end_date,address,total,currency,status,created_at) "
                        + "SELECT id,?,event_trip_id,booking_type,provider,source_kind,source_url,"
                        + "local_path,raw_text,extracted_summary,confirmation_code,start_date,end_date,"
                        + "address,total,currency,status,created_at FROM booking_imports_v1_preserved",
                new Object[]{legacy});

        String eventColumns = "id,event_key,event_name,destination,venue,start_date,end_date,"
                + "official_url,monitor_status,updates_summary,nearby_food,nearby_places,"
                + "transport_notes,source_note,last_checked_at,next_check_at,active,created_at";
        String updateColumns = "id,event_trip_id,update_key,category,title,detail,source_url,"
                + "published_at,detected_at";
        String bookingColumns = "id,event_trip_id,booking_type,provider,source_kind,source_url,"
                + "local_path,raw_text,extracted_summary,confirmation_code,start_date,end_date,"
                + "address,total,currency,status,created_at";
        if (eventCount != countRows(db, "event_trips", "person_profile_key=?", new String[]{legacy})
                || updateCount != countRows(db, "event_updates", "person_profile_key=?", new String[]{legacy})
                || bookingCount != countRows(db, "booking_imports", "person_profile_key=?", new String[]{legacy})
                || migrationProjectionMissing(
                        db, "event_trips_v1_preserved", "event_trips", eventColumns, legacy)
                || migrationProjectionMissing(
                        db, "event_updates_v1_preserved", "event_updates", updateColumns, legacy)
                || migrationProjectionMissing(
                        db, "booking_imports_v1_preserved", "booking_imports", bookingColumns, legacy)
                || countRows(
                        db,
                        "event_trips",
                        "person_profile_key=? AND monitor_enabled<>0",
                        new String[]{legacy}) != 0) {
            throw new IllegalStateException("Event-trip v1 migration exact-row verification failed");
        }
        db.execSQL("DROP TABLE event_updates_v1_preserved");
        db.execSQL("DROP TABLE booking_imports_v1_preserved");
        db.execSQL("DROP TABLE event_trips_v1_preserved");
    }

    /**
     * Append-preserving profile move. A semantic collision keeps the source
     * event as a separate row with a deterministic internal event key; no
     * event, update, or booking row is deleted.
     */
    private static boolean moveProfileKey(Context context, String oldKey, String newKey) {
        if (context == null || oldKey == null || newKey == null
                || oldKey.isEmpty() || newKey.isEmpty()) return false;
        if (oldKey.equals(newKey)) return true;
        EventTripStore helper = new EventTripStore(context, newKey, true);
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            long oldEvents = countRows(db, "event_trips", "person_profile_key=?", new String[]{oldKey});
            long oldUpdates = countRows(db, "event_updates", "person_profile_key=?", new String[]{oldKey});
            long oldBookings = countRows(db, "booking_imports", "person_profile_key=?", new String[]{oldKey});
            long targetEvents = countRows(db, "event_trips", "person_profile_key=?", new String[]{newKey});
            long targetUpdates = countRows(db, "event_updates", "person_profile_key=?", new String[]{newKey});
            long targetBookings = countRows(db, "booking_imports", "person_profile_key=?", new String[]{newKey});

            try (Cursor rows = db.rawQuery(
                    "SELECT id,event_key,destination FROM event_trips "
                            + "WHERE person_profile_key=? ORDER BY id ASC",
                    new String[]{oldKey})) {
                while (rows.moveToNext()) {
                    long id = rows.getLong(0);
                    String originalKey = rows.getString(1);
                    String destination = rows.getString(2);
                    String movingKey = originalKey;
                    int suffix = 0;
                    while (eventIdentityExists(db, newKey, movingKey, destination)) {
                        movingKey = EventTripProfilePolicy.collisionEventKey(originalKey, id)
                                + (suffix == 0 ? "" : "_" + suffix);
                        suffix++;
                    }
                    ContentValues moved = new ContentValues();
                    moved.put("person_profile_key", newKey);
                    if (!movingKey.equals(originalKey)) moved.put("event_key", movingKey);
                    if (db.update("event_trips", moved, "id=? AND person_profile_key=?",
                            new String[]{String.valueOf(id), oldKey}) != 1) {
                        throw new IllegalStateException("Event-trip profile move lost an event row");
                    }
                    ContentValues updateOwner = new ContentValues();
                    updateOwner.put("person_profile_key", newKey);
                    db.update("event_updates", updateOwner,
                            "event_trip_id=? AND person_profile_key=?",
                            new String[]{String.valueOf(id), oldKey});
                }
            }
            ContentValues remainingUpdateOwner = new ContentValues();
            remainingUpdateOwner.put("person_profile_key", newKey);
            db.update("event_updates", remainingUpdateOwner,
                    "person_profile_key=?", new String[]{oldKey});
            ContentValues bookingOwner = new ContentValues();
            bookingOwner.put("person_profile_key", newKey);
            db.update("booking_imports", bookingOwner,
                    "person_profile_key=?", new String[]{oldKey});

            boolean verified = countRows(db, "event_trips", "person_profile_key=?", new String[]{oldKey}) == 0
                    && countRows(db, "event_updates", "person_profile_key=?", new String[]{oldKey}) == 0
                    && countRows(db, "booking_imports", "person_profile_key=?", new String[]{oldKey}) == 0
                    && countRows(db, "event_trips", "person_profile_key=?", new String[]{newKey})
                            == targetEvents + oldEvents
                    && countRows(db, "event_updates", "person_profile_key=?", new String[]{newKey})
                            == targetUpdates + oldUpdates
                    && countRows(db, "booking_imports", "person_profile_key=?", new String[]{newKey})
                            == targetBookings + oldBookings;
            if (!verified) throw new IllegalStateException("Event-trip profile move verification failed");
            db.setTransactionSuccessful();
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            db.endTransaction();
            helper.close();
        }
    }

    private static boolean eventIdentityExists(
            SQLiteDatabase db,
            String ownerKey,
            String key,
            String destination) {
        try (Cursor cursor = db.rawQuery(
                "SELECT 1 FROM event_trips WHERE person_profile_key=? AND event_key=? "
                        + "AND lower(destination)=lower(?) LIMIT 1",
                new String[]{ownerKey, key, destination})) {
            return cursor.moveToFirst();
        }
    }

    private static long countRows(
            SQLiteDatabase db,
            String table,
            String condition,
            String[] args) {
        String sql = "SELECT count(*) FROM " + table
                + (condition == null || condition.isEmpty() ? "" : " WHERE " + condition);
        try (Cursor cursor = db.rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }

    /** Every complete v1 projection must exist byte-for-byte/value-for-value in v2. */
    private static boolean migrationProjectionMissing(
            SQLiteDatabase db,
            String oldTable,
            String newTable,
            String columns,
            String legacyKey) {
        String sql = "SELECT count(*) FROM (SELECT " + columns + " FROM " + oldTable
                + " EXCEPT SELECT " + columns + " FROM " + newTable
                + " WHERE person_profile_key=?)";
        try (Cursor cursor = db.rawQuery(sql, new String[]{legacyKey})) {
            return !cursor.moveToFirst() || cursor.getLong(0) != 0;
        }
    }

    private static String eventKey(String eventName) {
        return value(eventName).toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
