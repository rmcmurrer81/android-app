#!/usr/bin/env python3
"""Fail closed when a required Sarah 2.2 continuity contract disappears."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/java/com/kiraworld/sarahtravel"
WIN = ROOT / "windows-companion"


def text(path: Path) -> str:
    if not path.is_file():
        raise AssertionError(f"Required file is missing: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def require(path: Path, *needles: str) -> None:
    source = text(path)
    for needle in needles:
        if needle not in source:
            raise AssertionError(f"{path.relative_to(ROOT)} is missing required contract: {needle}")


def main() -> None:
    require(JAVA / "IdentityIntent.java", "stressing", "anxious", "correctedName", "isStressOrFear")
    speaker = text(JAVA / "SpeakerContext.java")
    if speaker.index("detectIdentityCorrection(raw)") > speaker.index("handlePending(raw, lower)"):
        raise AssertionError("Identity correction must run before an unfinished age/profile question")
    require(JAVA / "UniversalCalmSupport.java", '"train"', '"plane"', '"bus"', '"boat"', '"car"', "kid-friendly trivia")
    require(JAVA / "OnboardingActivity.java", "SarahVoiceRouter", "voiceRouter.speak")
    require(JAVA / "SarahChannelResponse.java", "<SPOKEN>", "<PRIVATE_MIND>", "<FACTUAL_TRUTH>", "Only SPOKEN")
    require(JAVA / "MindEventStore.java", "MindCrypto.encrypt", "private_mind", "factual_truth")
    require(JAVA / "TrustedDeviceStore.java", "peers_json", "List<String> hosts", "revoke(Context c,String host)")
    require(JAVA / "TrustedSyncClient.java", "syncAll", "syncAllAsync", "SarahSyncImporter.importPayload")
    require(JAVA / "SarahDatabase.java", '"event_id", "android-message-"', "created_at")
    require(JAVA / "SarahSyncExporter.java", "discoveries", "memory_id", "trip_id", "wish_id")
    require(JAVA / "SarahSyncImporter.java", "SyncSeenStore", "ProactiveDiscoveryStore", "Synced trip photo")
    require(JAVA / "ProactiveDiscoveryCoordinator.java", "nearby_discoveries", "nearby_area", "memory_consent", "MODE_LOCAL_ONLY")
    require(JAVA / "TavilyClient.java", "https://api.tavily.com/search", "SARAH_TAVILY_API_KEY")
    require(JAVA / "MainActivity.java", "TrustedSyncClient.syncAllAsync", "SarahChannelResponse.parse", "MindEventStore.record")

    require(WIN / "sarah_core.py", "device.key", "source.backup(snapshot)", "derive_sync_key", "_trusted_sync_mind_events", "as_bool")
    require(WIN / "sarah_sync_server.py", "sync_encrypt", "both directions", "Pairing code is wrong or expired")
    require(WIN / "google_drive_backup.py", "appDataFolder", "upload_encrypted_backup", "download_latest_encrypted_backup")
    require(WIN / "sarah_windows.py", "_maybe_onboard", "Nearby permission", "notification area", "drive_restore", "_animate_avatar")
    require(WIN / "tests/test_sarah_core.py", "wrong password", "test_sync_import_merges_rows", "test_channel_privacy")

    forbidden_patterns = [
        re.compile(r"sk-[A-Za-z0-9_-]{20,}"),
        re.compile(r"xi-api-key\s*[=:]\s*['\"][A-Za-z0-9_-]{20,}['\"]", re.I),
        re.compile(r"AIza[0-9A-Za-z_-]{30,}"),
    ]
    for path in list(ROOT.rglob("*.java")) + list(ROOT.rglob("*.py")) + list(ROOT.rglob("*.yml")):
        if ".git" in path.parts:
            continue
        source = path.read_text(encoding="utf-8", errors="ignore")
        for pattern in forbidden_patterns:
            if pattern.search(source):
                raise AssertionError(f"Possible real credential found in {path.relative_to(ROOT)}")

    print("Sarah 2.2 source-contract audit passed.")

if __name__ == "__main__":
    main()
