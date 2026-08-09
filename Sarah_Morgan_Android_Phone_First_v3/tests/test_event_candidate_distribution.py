from pathlib import Path


PACKAGE_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = PACKAGE_ROOT.parent
GRADLE = (PACKAGE_ROOT / "android-app" / "app" / "build.gradle").read_text(encoding="utf-8")
WORKFLOW = (REPO_ROOT / ".github" / "workflows" / "sarah-2.5-online-judge-build.yml").read_text(encoding="utf-8")
MODEL_CONFIG = (PACKAGE_ROOT / "android-app" / "app" / "src" / "main" / "java" / "com" / "kiraworld" / "sarahtravel" / "SarahModelConfig.java").read_text(encoding="utf-8")


def test_event_candidate_is_side_by_side_and_normal_r1_lane_remains():
    assert "applicationId 'com.kiraworld.sarahtravel'" in GRADLE
    assert "applicationIdSuffix '.eventcandidate'" in GRADLE
    assert "Sarah Morgan Event Candidate" in GRADLE
    assert "sarah-morgan-debug-signing-v1" in WORKFLOW
    assert "sarah-morgan-event-signing-v1" in WORKFLOW


def test_event_bundles_only_revocable_worker_bearer_and_disables_gmail():
    assert "SARAH_EVENT_BACKEND_TOKEN" in GRADLE
    assert "buildConfigField 'boolean', 'SARAH_GMAIL_AVAILABLE', 'false'" in GRADLE
    assert "SARAH_EVENT_GMAIL_AVAILABLE = 'false'" in WORKFLOW
    assert "SARAH_TAVILY_API_KEY: ''" in WORKFLOW
    assert "SARAH_ELEVENLABS_API_KEY: ''" in WORKFLOW
    assert "SARAH_GMAIL_DESKTOP_CLIENT_ID" not in WORKFLOW
    assert "SARAH_GMAIL_DESKTOP_CLIENT_SECRET" not in WORKFLOW
    assert 'BuildConfig.APPLICATION_ID.endsWith(".eventcandidate")' in MODEL_CONFIG
    assert "return clean(BuildConfig.SARAH_MODEL_BACKEND_TOKEN)" in MODEL_CONFIG
