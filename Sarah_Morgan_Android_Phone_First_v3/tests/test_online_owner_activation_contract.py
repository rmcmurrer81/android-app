from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "android-app" / "app" / "src" / "main" / "java" / "com" / "kiraworld" / "sarahtravel"


def test_android_online_activation_is_prominent_and_owner_bound():
    policy = (JAVA / "OwnerOnlineActivationPolicy.java").read_text(encoding="utf-8")
    main = (JAVA / "MainActivity.java").read_text(encoding="utf-8")
    settings = (JAVA / "SettingsActivity.java").read_text(encoding="utf-8")
    assert "confirmedOwner" in policy
    assert "access code once" in policy
    assert "maybeOpenOwnerOnlineActivation" in main
    assert "ConfirmedOwnerLease.capture(this)" in main
    assert "EXTRA_OPEN_ONLINE_ACCESS" in main
    assert "configureOnlineMind.post(configureOnlineMind::performClick)" in settings


def test_public_artifacts_still_do_not_bundle_reusable_access_code():
    model_config = (JAVA / "SarahModelConfig.java").read_text(encoding="utf-8")
    gradle = (ROOT / "android-app" / "app" / "build.gradle").read_text(encoding="utf-8")
    assert "loadSarahBackendToken(context)" in model_config
    assert "BuildConfig.SARAH_MODEL_BACKEND_TOKEN" not in model_config
    assert "buildConfigField 'String', 'SARAH_MODEL_BACKEND_TOKEN', '\"\"'" in gradle
    assert "System.getenv('SARAH_MODEL_BACKEND_TOKEN')" not in gradle


def test_saved_activation_keeps_automatic_capability_retry():
    main = (JAVA / "MainActivity.java").read_text(encoding="utf-8")
    capabilities = (JAVA / "ProtectedBackendCapabilities.java").read_text(encoding="utf-8")
    assert "if (internetAvailable)" in main
    assert "refreshProtectedCapabilities();" in main
    assert "ProtectedBackendCapabilities.refreshAsync" in main
    assert '"Authorization", "Bearer " + SarahModelConfig.backendToken()' in capabilities


def test_every_isolated_conversation_policy_harness_compiles_activation_policy():
    workflows = ROOT.parent / ".github" / "workflows"
    consumers = []
    for path in workflows.glob("*.yml"):
        text = path.read_text(encoding="utf-8")
        if "ConversationModePolicyTest.java" in text:
            consumers.append(path.name)
            assert "OwnerOnlineActivationPolicy.java" in text, path.name
    assert sorted(consumers) == ["build-apk.yml", "sarah-2.5-pr-validation.yml"]


def test_online_judge_retries_transient_worker_route_propagation_without_weakening_auth():
    workflow = (
        ROOT.parent / ".github" / "workflows" / "sarah-2.5-online-judge-build.yml"
    ).read_text(encoding="utf-8")
    assert "require_worker_unauthorized_capability" in workflow
    assert "for attempt in $(seq 1 8)" in workflow
    assert "response != {'error': 'unauthorized'}" in workflow
    assert "if [[ \"$status\" =~ ^[23] ]]" in workflow
    assert "Protected capabilities accepted or redirected" in workflow
    assert "auth_probe=$label&attempt=$attempt" in workflow
    assert "absent /tmp/sarah-capabilities-absent.json" in workflow
    assert "wrong /tmp/sarah-capabilities-wrong.json" in workflow
    assert "Authorization: Bearer deliberately-wrong-sarah-token" in workflow
