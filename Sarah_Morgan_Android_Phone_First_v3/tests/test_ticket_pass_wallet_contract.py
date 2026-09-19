import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
JAVA = ROOT / "android-app" / "app" / "src" / "main" / "java" / "com" / "kiraworld" / "sarahtravel"
MANIFEST = ROOT / "android-app" / "app" / "src" / "main" / "AndroidManifest.xml"


class TicketPassWalletContractTest(unittest.TestCase):
    def test_profile_isolated_encrypted_store_has_bounded_schema(self):
        text = (JAVA / "TicketPassVaultStore.java").read_text(encoding="utf-8")
        self.assertIn('NAMESPACE = "ticket_pass_wallet"', text)
        self.assertIn("SecureProfileVault.getOrThrow", text)
        self.assertIn("SecureProfileVault.putVerified", text)
        self.assertIn("MAX_PASSES_PER_PROFILE", text)
        self.assertIn('item.put("image_jpeg_base64"', text)
        for forbidden_key in (
            'item.put("password"',
            'item.put("payment_card"',
            'item.put("cvv"',
            'item.put("purchase_confirmed"',
        ):
            self.assertNotIn(forbidden_key, text)

    def test_corrupt_or_partial_ciphertext_fails_closed_before_mutation(self):
        vault = (JAVA / "SecureProfileVault.java").read_text(encoding="utf-8")
        policy = (JAVA / "SecureVaultReadPolicy.java").read_text(encoding="utf-8")
        store = (JAVA / "TicketPassVaultStore.java").read_text(encoding="utf-8")
        activity = (JAVA / "TicketPassWalletActivity.java").read_text(encoding="utf-8")
        self.assertIn("getOrThrow", vault)
        self.assertIn("CORRUPT_PARTIAL", policy)
        self.assertIn("authenticatedDecryptionPassed", policy)
        self.assertIn("SecureProfileVault.getOrThrow", store)
        self.assertIn(
            "Encrypted ticket/pass wallet is unreadable; no record was changed.",
            store,
        )
        self.assertIn("No pass was changed", activity)

    def test_import_is_owner_selected_sanitized_and_metadata_stripped(self):
        text = (JAVA / "TicketPassWalletActivity.java").read_text(encoding="utf-8")
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT", text)
        self.assertIn("PrivateContentSnapshot.capture", text)
        self.assertIn("ImageSanitizer.sanitize", text)
        self.assertIn("MAX_ENCRYPTED_IMAGE_BYTES", text)
        self.assertNotIn("takePersistableUriPermission", text)
        self.assertIn("does not verify a purchase or ticket validity", text)

    def test_share_provider_is_internal_read_only_and_scoped(self):
        manifest = MANIFEST.read_text(encoding="utf-8")
        provider = (JAVA / "TicketPassShareProvider.java").read_text(encoding="utf-8")
        self.assertIn('android:name=".TicketPassShareProvider"', manifest)
        self.assertIn('android:exported="false"', manifest)
        self.assertIn('android:grantUriPermissions="true"', manifest)
        self.assertIn('if (!"r".equals(mode))', provider)
        self.assertIn('new File(getContext().getCacheDir(), "ticket_pass_share")', provider)
        self.assertIn("getCanonicalFile", provider)

    def test_workbench_and_event_center_expose_wallet_and_exact_source(self):
        hub = (JAVA / "TravelHubActivity.java").read_text(encoding="utf-8")
        center = (JAVA / "EventTripCenterActivity.java").read_text(encoding="utf-8")
        lookup = (JAVA / "OfficialEventPageLookup.java").read_text(encoding="utf-8")
        search = (JAVA / "TravelSearchHelper.java").read_text(encoding="utf-8")
        fallback = (JAVA / "PublicOnlineFallback.java").read_text(encoding="utf-8")
        self.assertIn('"Tickets and passes"', hub)
        self.assertIn("TicketPassWalletActivity.class", hub)
        self.assertIn("Exact official event / ticket source", center)
        self.assertIn("TicketPassPolicy.exactHttpsUrl(storedOfficial)", center)
        self.assertIn("Open verified official website / tickets", center)
        self.assertIn("EXTRA_VERIFIED_EVENT_SOURCE", center)
        self.assertIn("Official website or ticket source:", lookup)
        self.assertIn("Opening the source does not purchase a ticket", lookup)
        self.assertIn('"buy tickets"', search)
        self.assertIn('"official website"', search)
        self.assertIn("TicketPassPolicy.exactHttpsUrl", search)
        self.assertIn("Open verified official website / tickets", search)
        self.assertIn("knownEvent.officialUrl", fallback)
        self.assertIn("Opening it does not purchase a ticket", fallback)

    def test_profile_correction_migrates_pass_wallet(self):
        migrator = (JAVA / "OwnerProfileDataMigrator.java").read_text(encoding="utf-8")
        self.assertIn("TicketPassVaultStore.moveProfile", migrator)


if __name__ == "__main__":
    unittest.main()
