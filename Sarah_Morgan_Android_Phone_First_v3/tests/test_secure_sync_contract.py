from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "android-app/app/src/main/java/com/kiraworld/sarahtravel"


def test_post_trust_sync_requires_preview_and_explicit_import():
    activity = (JAVA / "TrustedSyncActivity.java").read_text(encoding="utf-8")
    client = (JAVA / "SecureSyncPreviewClient.java").read_text(encoding="utf-8")
    assert "Review continuity from selected device" in activity
    assert "Import this Sarah continuity?" in activity
    assert "Import reviewed data" in activity
    assert "Nothing imports automatically" in activity
    assert "owner_import_required" in client
    assert "WINDOWS_TO_ANDROID_PULL_ONLY" in client
    assert "new Windows installation cannot yet pull" in activity
    assert "SarahSyncImporter.importPayload" not in client.split("public static Preview fetch", 1)[1].split("private static", 1)[0]
    assert "SarahSyncImporter.importPayload" in client.split("public int apply", 1)[1]


def test_post_trust_sync_excludes_sensitive_categories_and_never_sends_token():
    client = (JAVA / "SecureSyncPreviewClient.java").read_text(encoding="utf-8")
    legacy = (JAVA / "TrustedSyncClient.java").read_text(encoding="utf-8")
    for category in ("photos", "mind_events", "discoveries"):
        assert category in client
    assert "X-Sarah-Device-Token" not in client
    assert "HttpURLConnection" not in client
    assert "public static boolean isTransportAccepted() { return false; }" in legacy


def test_import_decision_and_completion_are_append_only_receipts():
    provenance = (JAVA / "SyncImportProvenance.java").read_text(encoding="utf-8")
    client = (JAVA / "SecureSyncPreviewClient.java").read_text(encoding="utf-8")
    assert "OWNER_APPROVED_SECURE_SYNC_IMPORT" in provenance
    assert "SECURE_SYNC_IMPORT_COMPLETED" in provenance
    assert "secure_sync_import_history.jsonl" in provenance
    assert "new FileOutputStream(path,true)" in provenance
    assert client.index("recordDecision") < client.index("SarahSyncImporter.importPayload")
    assert client.index("SarahSyncImporter.importPayload") < client.index("recordResult")


def test_fixed_python_android_crypto_vector_is_preserved():
    vector = (ROOT / "tests/SarahSecureSyncInteropTest.java").read_text(encoding="utf-8")
    assert "AAECAwQFBgcICQoL." in vector
    assert "vIL0hNhA0H+AkwJ6ua6kUqD8K7bhm3lZE/Eonpf0nWg=" in vector
    assert "TrustedSyncProtocol.decrypt" in vector
    assert "TrustedSyncProtocol.signature" in vector


def test_established_android_can_serve_only_after_sas_and_owner_review():
    responder = (JAVA / "SarahReverseSyncResponder.java").read_text(encoding="utf-8")
    exporter = (JAVA / "SarahSyncExporter.java").read_text(encoding="utf-8")
    activity = (JAVA / "TrustedSyncActivity.java").read_text(encoding="utf-8")
    for phrase in [
        "SarahPairingProtocol.respond", "pending.await", "acceptPeerConfirmation",
        "finalizeCredential", "saveFinalizedPeer", "secure-sync-request:",
        "ANDROID_TO_WINDOWS_PULL_ONLY", "owner_import_required",
    ]:
        assert phrase in responder
    assert responder.index("pending.await") < responder.index("finalizeCredential")
    assert "exportOwnerReview" in exporter
    for excluded in ["photos", "mind_events", "discoveries"]:
        assert f'output.put("{excluded}",new JSONArray())' in exporter
    assert "startReverseResponder" in activity
    assert "Codes match - approve" in activity
    assert "SarahReverseSyncResponder responder = reverseResponder" in activity
    assert "OnboardingActivity" not in activity
