"""Focused static acceptance for EventTripStore's destructive-operation boundary.

The Android database/filesystem integration still requires an instrumented device.
These checks prevent the two source-level regressions that caused this repair:
opening v2 without the sealed backup gate, and deleting booking rows before an
unverified best-effort derivative cleanup.
"""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
JAVA = (
    ROOT
    / "android-app/app/src/main/java/com/kiraworld/sarahtravel"
)
STORE = (JAVA / "EventTripStore.java").read_text(encoding="utf-8")
BOOKING_UI = (JAVA / "BookingImportActivity.java").read_text(encoding="utf-8")
PRIVATE_SNAPSHOT = (JAVA / "PrivateContentSnapshot.java").read_text(
    encoding="utf-8"
)
APPLICATION = (JAVA / "SarahApplication.java").read_text(encoding="utf-8")
EVENT_JOB = (JAVA / "EventMonitorJobService.java").read_text(encoding="utf-8")
PRE_UPGRADE_GATE = (JAVA / "EventTripPreUpgradeBackupGate.java").read_text(
    encoding="utf-8"
)
BACKUP_RULES = (
    ROOT / "android-app/app/src/main/res/xml/backup_rules.xml"
).read_text(encoding="utf-8")
EXTRACTION_RULES = (
    ROOT / "android-app/app/src/main/res/xml/data_extraction_rules.xml"
).read_text(encoding="utf-8")


class EventTripStoreContractTest(unittest.TestCase):
    def test_every_constructor_reaches_backup_gate_before_database_use(self):
        self.assertIn(
            "public EventTripStore(Context context, String personId)", STORE
        )
        self.assertIn(
            "this(context, EventTripProfilePolicy.profileKey(personId), true);",
            STORE,
        )
        self.assertNotIn("public EventTripStore(Context context)", STORE)

        constructor = STORE.split(
            "private EventTripStore(Context context, String key, boolean alreadyNormalized)",
            1,
        )[1].split("@Override", 1)[0]
        gate = constructor.index("EventTripPreUpgradeBackupGate.ensure(appContext)")
        assignment = constructor.index("profileKey =")
        self.assertLess(gate, assignment)
        self.assertIn("if (!backupGate.mayOpenV2)", constructor)
        self.assertNotIn("getWritableDatabase()", constructor)
        self.assertNotIn("getReadableDatabase()", constructor)

        self.assertIn("SarahApplication.eventTripUpgradeState()", BOOKING_UI)
        self.assertIn("if (!upgradeState.mayOpenV2)", BOOKING_UI)
        self.assertLess(
            BOOKING_UI.index("if (!upgradeState.mayOpenV2)"),
            BOOKING_UI.index("boundPersonId = EventTripStore.activePersonId(this)"),
        )

        store_open = EVENT_JOB.index("store = new EventTripStore(")
        guarded_try = EVENT_JOB.rfind("try {", 0, store_open)
        job_finish = EVENT_JOB.index("jobFinished(run.params, retry)", store_open)
        self.assertGreaterEqual(guarded_try, 0)
        self.assertIn("if (store != null) store.close()", EVENT_JOB)
        self.assertIn("ConnectedModelGateway.cancel(run.thread)", EVENT_JOB)
        self.assertIn("OfficialEventPageLookup.cancel(run.thread)", EVENT_JOB)
        self.assertIn("if (mayFinish) jobFinished(run.params, retry)", EVENT_JOB)
        self.assertGreater(job_finish, store_open)

    def test_files_are_quarantined_before_rows_and_precommit_can_restore(self):
        clear = STORE.split(
            "public BookingClearResult clearBookingImportsAndDerivatives()", 1
        )[1].split("/** Compatibility count", 1)[0]
        planned = clear.index('"PLANNED_BEFORE_FILE_MOVE"')
        moved = clear.index("checkedMoveToQuarantine(derivative)")
        row_delete = clear.index("db.delete(")
        commit = clear.index("db.setTransactionSuccessful()")
        transaction_end = clear.index("db.endTransaction()")
        post_verify = clear.index("remainingRows = countRows(")
        rollback = clear.index("restoreQuarantinedDerivatives(derivatives)")

        self.assertLess(planned, moved)
        self.assertLess(moved, row_delete)
        self.assertLess(row_delete, commit)
        self.assertLess(commit, transaction_end)
        self.assertLess(transaction_end, post_verify)
        self.assertLess(post_verify, rollback)
        self.assertIn("restoredRows == expectedRows", clear)
        self.assertIn("bookingRowsMatchSnapshot(db, bookingRows)", clear)
        self.assertIn("&& residuals.isEmpty()", clear)
        self.assertIn("PRECOMMIT_ROLLBACK_INCOMPLETE_REVIEW_REQUIRED", clear)

    def test_clear_is_bounded_profile_private_and_reports_residuals(self):
        for phrase in (
            "MAX_BOOKING_CLEAR_ROWS = 4096",
            'BOOKING_IMPORT_ROOT = "booking_imports"',
            'BOOKING_QUARANTINE_ROOT = "booking_import_quarantine"',
            "exactRowOwnedBookingImportFile(bookingId, storedPath)",
            "BOOKING_DERIVATIVE_PATH_NOT_CANONICAL",
            "directHistoricalProfileFile",
            "directLegacyFile",
            "BOOKING_DERIVATIVE_ROW_OWNERSHIP_REJECTED",
            'BOOKING_CLEAR_TOMBSTONE = "TOMBSTONE.json"',
            'BOOKING_CLEAR_JOURNAL = "JOURNAL.jsonl"',
            "output.getFD().sync()",
            "BOOKING_ROWS_CLEARED_RESIDUAL_PRIVATE_QUARANTINE",
            "Collections.unmodifiableList",
        ):
            self.assertIn(phrase, STORE)

        self.assertNotIn("clearBookingImportsAndReturnPaths", STORE)
        self.assertNotIn("deleteRecursively", STORE)
        delete_lines = [line.strip() for line in STORE.splitlines() if ".delete()" in line]
        self.assertEqual(
            delete_lines,
            [
                "if (!file.isFile() || !file.delete() || file.exists()) {",
                "|| !operationDirectory.delete() || operationDirectory.exists()) {",
            ],
        )

    def test_process_death_recovery_is_per_file_durable_and_fail_closed(self):
        clear = STORE.split(
            "public BookingClearResult clearBookingImportsAndDerivatives()", 1
        )[1].split("/** Compatibility count", 1)[0]
        self.assertLess(
            clear.index('"FILE_MOVE_INTENT"'),
            clear.index("checkedMoveToQuarantine(derivative)"),
        )
        self.assertLess(
            clear.index("checkedMoveToQuarantine(derivative)"),
            clear.index('"FILE_QUARANTINED"'),
        )
        for phrase in (
            "reconcileBookingClearOperations()",
            "MAX_BOOKING_RECOVERY_OPERATIONS = 128",
            '"sarah-booking-clear-journal-v2"',
            "new FileOutputStream(journal, true)",
            "output.getFD().sync()",
            '"RECOVERY_FILE_RESTORE_INTENT"',
            '"RECOVERY_FILE_RESTORED"',
            'root.put("booking_rows", rowItems)',
            "bookingRowsMatchSnapshot(db, bookingRows)",
            "BOOKING_RECOVERY_LEGACY_ROW_IDENTITY_INCOMPLETE_REVIEW_REQUIRED",
            "BOOKING_RECOVERY_MIXED_DATABASE_STATE_REVIEW_REQUIRED",
            "BOOKING_RECOVERY_COMMITTED_PRIVATE_RESIDUAL_REVIEW_REQUIRED",
            "BOOKING_RECOVERY_UNEXPECTED_OPERATION_CHILD",
        ):
            self.assertIn(phrase, STORE)
        recovery = STORE.split(
            "public BookingRecoveryResult reconcileBookingClearOperations()", 1
        )[1].split("public BookingClearResult clearBookingImportsAndDerivatives()", 1)[0]
        self.assertNotIn("checkedDeleteExactFile", recovery)
        self.assertNotIn("deleteCommittedQuarantine", recovery)
        self.assertIn("reconcileInterruptedBookingClearAtStartup()", APPLICATION)
        self.assertLess(
            APPLICATION.index("reconcileInterruptedBookingClearAtStartup();"),
            APPLICATION.index("ProtectedBackendCapabilities.refreshAsync(this)"),
        )

    def test_exported_file_share_requires_confirmation_and_bounded_content(self):
        share = BOOKING_UI.split("private void handleShared(Intent intent)", 1)[1].split(
            "private void reviewExternallySharedFile", 1
        )[0]
        review = BOOKING_UI.split("private void reviewExternallySharedFile", 1)[1].split(
            "private void reviewExternallySharedText", 1
        )[0]
        self.assertIn("reviewExternallySharedFile(uri, declaredType)", share)
        self.assertNotIn("importPdf(uri", share)
        self.assertNotIn("importScreenshot(uri", share)
        self.assertNotIn("getContentResolver()", share)
        self.assertIn("Nothing has been copied, saved, sent to a service, or scheduled", review)
        self.assertIn('setPositiveButton("Import for review"', review)
        self.assertIn("approvedMime(uri, declaredType)", review)
        for phrase in (
            '"content".equalsIgnoreCase(uri.getScheme())',
            '"image/jpeg".equals(resolved)',
            '"image/png".equals(resolved)',
            '"image/webp".equals(resolved)',
            "MAX_SHARED_FILE_BYTES = 12_000_000",
            "PrivateContentSnapshot.capture(",
            "MAX_SHARED_FILE_BYTES,",
            "ProfileMigrationPolicy.isConfirmedDisplayName",
            "requireConfirmedImportLease(ownerConfirmed)",
            "Exact private residual requiring review:",
            '".pending_image_" + UUID.randomUUID()',
            "cleanupImportArtifacts(derivative, stagingDirectory)",
            "derivative.getAbsolutePath()",
        ):
            self.assertIn(phrase, BOOKING_UI)
        screenshot = BOOKING_UI.split("private void importScreenshot(", 1)[1].split(
            "private void importPdf(", 1
        )[0]
        self.assertEqual(screenshot.count("PrivateContentSnapshot.capture("), 1)
        self.assertNotIn("openInputStream", screenshot)
        self.assertEqual(PRIVATE_SNAPSHOT.count("resolver.openInputStream(uri)"), 1)
        self.assertIn("output.getFD().sync()", PRIVATE_SNAPSHOT)
        self.assertIn("target.setReadOnly()", PRIVATE_SNAPSHOT)

    def test_owner_ui_never_performs_a_second_best_effort_file_delete(self):
        clear_ui = BOOKING_UI.split("private void clearImports()", 1)[1].split(
            "private File profileImportDirectory", 1
        )[0]
        self.assertIn("clearBookingImportsAndDerivatives()", clear_ui)
        self.assertNotIn("result.localPaths", clear_ui)
        self.assertNotIn(".delete()", clear_ui)
        self.assertIn("if (result.success)", clear_ui)
        self.assertIn("else if (result.rowsCleared)", clear_ui)
        self.assertIn("else if (result.rollbackComplete)", clear_ui)
        self.assertIn("private quarantine item(s) remain for review", clear_ui)
        self.assertIn("Nothing was cleared. Sarah restored", clear_ui)

    def test_private_quarantine_is_excluded_from_backup_and_device_transfer(self):
        exclusion = '<exclude domain="file" path="booking_import_quarantine/" />'
        self.assertIn(exclusion, BACKUP_RULES)
        self.assertEqual(EXTRACTION_RULES.count(exclusion), 2)

        recovery_exclusion = (
            '<exclude domain="file" path="recovery/event_trip_v1_pre_v2/" />'
        )
        self.assertIn(recovery_exclusion, BACKUP_RULES)
        self.assertEqual(EXTRACTION_RULES.count(recovery_exclusion), 2)
        all_database_exclusion = '<exclude domain="database" path="." />'
        self.assertIn(all_database_exclusion, BACKUP_RULES)
        self.assertEqual(EXTRACTION_RULES.count(all_database_exclusion), 2)
        all_preferences_exclusion = '<exclude domain="sharedpref" path="." />'
        self.assertIn(all_preferences_exclusion, BACKUP_RULES)
        self.assertEqual(EXTRACTION_RULES.count(all_preferences_exclusion), 2)

    def test_failed_pre_upgrade_backup_discards_only_its_exact_known_files(self):
        self.assertGreaterEqual(
            PRE_UPGRADE_GATE.count(
                "discardExactIncompleteTarget(recoveryRoot, target)"
            ),
            4,
        )
        cleanup = PRE_UPGRADE_GATE.split(
            "private static void discardExactIncompleteTarget", 1
        )[1].split("private static int readSqliteUserVersion", 1)[0]
        for phrase in (
            "target.getCanonicalFile()",
            "getParentFile().getCanonicalFile().equals(exactRoot)",
            "allowed.add(DATABASE)",
            'allowed.add(DATABASE + "-wal")',
            'allowed.add(DATABASE + "-shm")',
            'allowed.add("MANIFEST.json")',
            "if (!child.isFile() || !allowed.contains(child.getName())) return",
        ):
            self.assertIn(phrase, cleanup)
        self.assertNotIn("deleteRecursively", cleanup)


if __name__ == "__main__":
    unittest.main()
