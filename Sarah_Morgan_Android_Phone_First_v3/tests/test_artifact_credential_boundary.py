"""Fail-closed static gates for Sarah client-artifact credential isolation.

These checks do not claim a deployed backend works. They prove that active
APK/EXE build paths cannot turn CI secrets into reusable client credentials.
"""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
APP = ROOT / "android-app"
JAVA = APP / "app/src/main/java/com/kiraworld/sarahtravel"
WORKFLOWS = REPO / ".github/workflows"

SENSITIVE_BUILD_FIELDS = (
    "SARAH_OPENAI_API_KEY",
    "SARAH_MODEL_BACKEND_TOKEN",
    "SARAH_TRAVEL_COMMERCE_TOKEN",
    "SARAH_VOICE_CONCIERGE_TOKEN",
    "SARAH_ELEVENLABS_API_KEY",
    "SARAH_ELEVENLABS_BACKEND_TOKEN",
    "SARAH_TAVILY_API_KEY",
    "SARAH_STAY22_BACKEND_TOKEN",
)

WORKFLOW_CREDENTIAL_FIELDS = frozenset(SENSITIVE_BUILD_FIELDS + (
    "CLOUDFLARE_API_TOKEN",
    "OPENAI_API_KEY",
    "TAVILY_API_KEY",
))

ACTIVE_WORKFLOW_NAMES = (
    "apply-sarah-2-2.yml",
    "build-apk.yml",
    "materialize-sarah-public-source-baseline.yml",
    "sarah-2-2-authoritative-gate.yml",
    "sarah-2-2-ci.yml",
    "sarah-2-2-final-authoritative-v2.yml",
    "sarah-2-2-final-gate.yml",
    "sarah-2-2-materialize.yml",
    "sarah-2.4-main-kickoff.yml",
    "sarah-2.4-stay22-release.yml",
    "sarah-2.5-event-ready.yml",
    "sarah-2.5-final-release.yml",
    "sarah-2.5-online-diagnostic.yml",
    "sarah-2.5-online-judge-build.yml",
    "sarah-2.5-pr-validation.yml",
    "sarah-2.5-publish-validated.yml",
    "sarah-2.5-source-extract.yml",
    "sarah-public-release.yml",
    "sarah-public-source-assembler.yml",
)

LEGACY_22_WORKFLOW_NAMES = (
    "apply-sarah-2-2.yml",
    "sarah-2-2-authoritative-gate.yml",
    "sarah-2-2-ci.yml",
    "sarah-2-2-final-authoritative-v2.yml",
    "sarah-2-2-final-gate.yml",
    "sarah-2-2-materialize.yml",
)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def step(text: str, name: str) -> str:
    marker = f"      - name: {name}"
    start = text.index(marker)
    end = text.find("\n      - name:", start + len(marker))
    if end < 0:
        end = len(text)
    return text[start:end]


def named_step_blocks(text: str):
    matches = list(re.finditer(r"(?m)^      - name: (.+)$", text))
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        yield match.group(1).strip(), text[match.start():end]


def credential_secret_refs(text: str):
    return {
        name for name in re.findall(r"secrets\.([A-Z0-9_]+)", text)
        if name in WORKFLOW_CREDENTIAL_FIELDS
    }


class ArtifactCredentialBoundaryTest(unittest.TestCase):
    def test_android_build_config_forces_every_credential_field_empty(self):
        gradle = read(APP / "app/build.gradle")
        for field in SENSITIVE_BUILD_FIELDS:
            self.assertNotIn(f"System.getenv('{field}')", gradle, field)
            self.assertRegex(
                gradle,
                rf"buildConfigField\s+'String',\s+'{field}',\s+'\"\"'",
                field,
            )

    def test_android_uses_one_keystore_backed_owner_activation(self):
        secure = read(JAVA / "SecureStore.java")
        model = read(JAVA / "SarahModelConfig.java")
        voice = read(JAVA / "ElevenLabsVoiceConfig.java")
        settings = read(JAVA / "SettingsActivity.java")
        layout = read(APP / "app/src/main/res/layout/activity_settings.xml")

        for phrase in (
            "saveSarahBackendAccess",
            "loadSarahBackendUrl",
            "loadSarahBackendToken",
            "clearSarahBackendAccess",
            'Cipher.getInstance("AES/GCM/NoPadding")',
            'KeyStore.getInstance("AndroidKeyStore")',
            'normalizedCode.matches("[A-Za-z0-9_-]{32,256}")',
        ):
            self.assertIn(phrase, secure)
        self.assertIn("ConfirmedOwnerLease.capture(this)", settings)
        self.assertIn("lease.requireActive()", settings)
        self.assertIn("configureOnlineMindAccessButton", layout)
        self.assertIn("Revocable Sarah access code", settings)
        self.assertIn("Do not enter a Cloudflare, OpenAI, ElevenLabs", settings)
        for rules in (
            APP / "app/src/main/res/xml/backup_rules.xml",
            APP / "app/src/main/res/xml/data_extraction_rules.xml",
        ):
            self.assertIn('domain="sharedpref" path="."', read(rules), str(rules))
        self.assertIn("SecureStore.loadSarahBackendToken(context)", model)
        self.assertNotIn("BuildConfig.SARAH_OPENAI_API_KEY", model)
        self.assertNotIn("BuildConfig.SARAH_MODEL_BACKEND_TOKEN", model)
        self.assertEqual(voice.count("SarahModelConfig.backendToken()"), 2)
        self.assertNotIn("BuildConfig.SARAH_ELEVENLABS_API_KEY", voice)
        self.assertNotIn("BuildConfig.SARAH_ELEVENLABS_BACKEND_TOKEN", voice)

    def test_active_generic_and_final_builds_have_no_job_level_credentials(self):
        generic = read(WORKFLOWS / "build-apk.yml")
        generic_job_env = generic[generic.index("    env:"):generic.index("    steps:")]
        final = read(WORKFLOWS / "sarah-2.5-final-release.yml")
        final_global_env = final[final.index("env:"):final.index("\njobs:")]
        for field in SENSITIVE_BUILD_FIELDS:
            self.assertNotIn(field, generic_job_env, field)
            self.assertNotIn(field, final_global_env, field)

        validation = step(generic, "Validate Sarah Morgan ElevenLabs voice")
        self.assertIn("SARAH_ELEVENLABS_VALIDATION_API_KEY", validation)
        self.assertIn("secrets.SARAH_ELEVENLABS_API_KEY", validation)
        apk_build = step(generic, "Build Sarah Travel OS 2.1 APK")
        self.assertNotIn("secrets.", apk_build)
        self.assertNotIn("VALIDATION_API_KEY", apk_build)

    def test_online_judge_bundles_identity_but_not_access_code(self):
        workflow = read(WORKFLOWS / "sarah-2.5-online-judge-build.yml")
        apk = step(workflow, "Build the online/offline owner-acceptance candidate APK")
        self.assertIn("SARAH_MODEL_BACKEND_TOKEN: ''", apk)
        self.assertIn("SARAH_ELEVENLABS_BACKEND_TOKEN: ''", apk)
        self.assertNotIn("secrets.SARAH_MODEL_BACKEND_TOKEN", apk)
        self.assertNotIn("secrets.SARAH_ELEVENLABS_BACKEND_TOKEN", apk)

        windows = step(
            workflow,
            "Generate the event-only config and build the Windows installer",
        )
        self.assertNotIn("$env:SARAH_MODEL_BACKEND_TOKEN", windows)
        self.assertNotRegex(
            windows,
            r"(?m)^\s+SARAH_MODEL_BACKEND_TOKEN\s*=",
        )
        self.assertIn("A reusable credential was added", windows)
        self.assertIn("vars.SARAH_GMAIL_DESKTOP_CLIENT_ID", windows)
        self.assertIn("vars.SARAH_GMAIL_DESKTOP_CLIENT_SECRET", windows)
        self.assertNotIn("secrets.SARAH_GMAIL_DESKTOP", windows)
        self.assertIn('--add-data "$gmailOAuthPath;."', windows)
        self.assertIn("event_app_token_bundled = $false", workflow)
        self.assertIn("owner_runtime_activation_required = $true", workflow)

        event_ready = read(REPO / "windows-companion/sarah_event_ready.py")
        self.assertIn("Bundled event configuration contains reusable credential fields", event_ready)
        self.assertNotIn("Bundled event model backend token is absent", event_ready)

        windows_core = read(REPO / "windows-companion/sarah_core.py")
        self.assertIn("RUNTIME_SECRET_KEYS", windows_core)
        self.assertIn("CryptProtectData", windows_core)
        self.assertIn("CryptUnprotectData", windows_core)
        self.assertIn("DPAPI_SECRET_PREFIX", windows_core)

    def test_every_active_workflow_is_credential_scanned(self):
        active_paths = set(WORKFLOWS.glob("*.yml")) | set(WORKFLOWS.glob("*.yaml"))
        actual = tuple(sorted(path.name for path in active_paths))
        self.assertEqual(actual, ACTIVE_WORKFLOW_NAMES)

        artifact_step_words = re.compile(
            r"\b(build|compile|generate|materialize|package|rebuild|reproduce)\b",
            re.IGNORECASE,
        )
        for name in ACTIVE_WORKFLOW_NAMES:
            workflow = read(WORKFLOWS / name)
            lines = workflow.splitlines()
            for line_number, line in enumerate(lines, 1):
                refs = credential_secret_refs(line)
                if not refs:
                    continue
                current_indent = len(line) - len(line.lstrip())
                env_indent = None
                for earlier in reversed(lines[:line_number - 1]):
                    earlier_indent = len(earlier) - len(earlier.lstrip())
                    if earlier.strip() == "env:" and earlier_indent < current_indent:
                        env_indent = earlier_indent
                        break
                    if earlier.strip() and earlier_indent < current_indent - 2:
                        break
                self.assertIsNotNone(
                    env_indent, f"{name}:{line_number} credential is not in an env block"
                )
                self.assertGreaterEqual(
                    env_indent, 8,
                    f"{name}:{line_number} credential is workflow/job scoped",
                )

            for step_name, block in named_step_blocks(workflow):
                if artifact_step_words.search(step_name):
                    self.assertFalse(
                        credential_secret_refs(block),
                        f"{name} artifact step {step_name!r} receives a credential",
                    )

    def test_legacy_22_workflows_limit_secrets_to_exact_live_checks(self):
        expected = {
            "apply-sarah-2-2.yml": set(),
            "sarah-2-2-ci.yml": set(),
            "sarah-2-2-authoritative-gate.yml": {
                ("Validate required ElevenLabs voice without exposing credentials",
                 "SARAH_ELEVENLABS_API_KEY"),
            },
            "sarah-2-2-final-authoritative-v2.yml": {
                ("Required ElevenLabs live voice", "SARAH_ELEVENLABS_API_KEY"),
            },
            "sarah-2-2-final-gate.yml": {
                ("Required live Sarah Morgan ElevenLabs validation",
                 "SARAH_ELEVENLABS_API_KEY"),
                ("Optional live Tavily sponsor smoke test", "SARAH_TAVILY_API_KEY"),
            },
            "sarah-2-2-materialize.yml": {
                ("Live-validate required Sarah Morgan ElevenLabs voice",
                 "SARAH_ELEVENLABS_API_KEY"),
                ("Smoke-test Tavily when the event key is available",
                 "SARAH_TAVILY_API_KEY"),
            },
        }
        self.assertEqual(set(expected), set(LEGACY_22_WORKFLOW_NAMES))
        for name in LEGACY_22_WORKFLOW_NAMES:
            observed = set()
            for step_name, block in named_step_blocks(read(WORKFLOWS / name)):
                for field in credential_secret_refs(block):
                    observed.add((step_name, field))
            self.assertEqual(observed, expected[name], name)

    def test_current_artifact_build_workflows_run_this_gate(self):
        for name in (
            "build-apk.yml",
            "sarah-2.5-final-release.yml",
            "sarah-2.5-online-judge-build.yml",
            "sarah-2.5-pr-validation.yml",
        ):
            workflow = read(WORKFLOWS / name)
            self.assertIn("test_artifact_credential_boundary.py", workflow, name)

    def test_generic_release_identity_is_source_derived_r3_engineering_evidence(self):
        final = read(WORKFLOWS / "sarah-2.5-final-release.yml")
        for phrase in (
            "source_code = int(code_match.group(1))",
            "source_name = name_match.group(1)",
            "expected_variant_name = source_name",
            "source_code != 27 or source_name != '2.5-r3-owner-repair'",
            "name != expected_variant_name or code != source_code",
            "Sarah-Morgan-2.5-R3-ENGINEERING-EVIDENCE-DO-NOT-INSTALL.apk",
        ):
            self.assertIn(phrase, final)
        self.assertNotIn("Sarah-Morgan-2.5-event-ready.apk", final)
        self.assertNotIn("version code 25", final.lower())
        self.assertNotIn("CURRENT-OWNER-TEST", final)

    def test_documentation_does_not_claim_client_tokens_are_bundled(self):
        documents = {
            "pr_validation": read(WORKFLOWS / "sarah-2.5-pr-validation.yml"),
            "worker": read(REPO / "services/sarah-model-proxy/README.md"),
            "example": read(REPO / "backend_examples/openai_proxy/README.md"),
        }
        for label, document in documents.items():
            for stale in (
                "embeds only its rotating Sarah app token",
                "contain the same revocable Sarah backend token",
                "token shared with the private APK build",
                "A token embedded in an APK can be extracted",
            ):
                self.assertNotIn(stale, document, label)
        self.assertIn("after installation", documents["worker"].lower())
        self.assertIn("leave empty during every APK and EXE build", documents["example"])


if __name__ == "__main__":
    unittest.main()
