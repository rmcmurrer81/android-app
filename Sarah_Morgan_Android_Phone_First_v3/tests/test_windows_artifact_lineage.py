from __future__ import annotations

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
LEGACY = ROOT / ".github" / "workflows" / "sarah-2-2-ci.yml"
VALIDATION = ROOT / ".github" / "workflows" / "sarah-2.5-pr-validation.yml"
OWNER = ROOT / ".github" / "workflows" / "sarah-2.5-online-judge-build.yml"
BUILD = ROOT / "Sarah_Morgan_Android_Phone_First_v3" / "android-app" / "app" / "build.gradle"
BUILD_VERSION = ROOT / "BUILD_VERSION.txt"
INSTALLER = ROOT / "windows-companion" / "sarah_installer.py"


class WindowsArtifactLineageTest(unittest.TestCase):
    def test_legacy_workflow_is_manual_and_every_artifact_is_do_not_install(self) -> None:
        text = LEGACY.read_text(encoding="utf-8")
        self.assertIn("LEGACY EVIDENCE - Sarah 2.2 - DO NOT INSTALL", text)
        self.assertIn("workflow_dispatch:", text)
        self.assertNotIn("pull_request:", text)
        self.assertNotIn("name: Sarah-Morgan-Windows-2.2\n", text)
        self.assertIn("LEGACY-EVIDENCE-DO-NOT-INSTALL-Sarah-2.2-Android", text)
        self.assertIn("LEGACY-EVIDENCE-DO-NOT-INSTALL-Sarah-2.2-Windows", text)
        self.assertIn("READ_THIS_FIRST_DO_NOT_INSTALL.md", text)

    def test_credential_free_pr_artifact_cannot_be_mistaken_for_owner_test(self) -> None:
        text = VALIDATION.read_text(encoding="utf-8")
        self.assertIn(
            "Sarah-2.5-R3-CURRENT-ENGINEERING-EVIDENCE-DO-NOT-INSTALL-Windows",
            text,
        )
        self.assertIn(
            "Sarah-2.5-R3-CURRENT-ENGINEERING-EVIDENCE-DO-NOT-INSTALL-Android",
            text,
        )
        self.assertNotIn("name: Sarah-2.5-validated-Windows-EXE\n", text)
        self.assertIn("sarah_event_ready.py", text)
        self.assertIn("sarah_adult_portrait_r2_runtime_512.png;assets", text)

    def test_only_gated_workflow_names_the_current_owner_test(self) -> None:
        expected_android = "Sarah-2.5-R3-CURRENT-OWNER-TEST-Android-APK"
        expected_windows = "Sarah-2.5-R3-CURRENT-OWNER-TEST-Windows-ElevenLabs-Candidate"
        owner_text = OWNER.read_text(encoding="utf-8")
        self.assertIn(expected_android, owner_text)
        self.assertIn(expected_windows, owner_text)
        self.assertIn("Sarah-Morgan-2.5-R3-CURRENT-OWNER-TEST.apk", owner_text)
        self.assertIn("SarahMorganTravelOS-2.5-R3-CURRENT-OWNER-TEST-Setup.exe", owner_text)
        self.assertIn("needs.deploy-smoke-test-and-build.outputs.backend_url", owner_text)

        for workflow in (ROOT / ".github" / "workflows").glob("*.yml"):
            text = workflow.read_text(encoding="utf-8")
            upload_names = re.findall(r"(?m)^\s+name:\s+([^\n]+CURRENT-OWNER-TEST[^\n]*)$", text)
            if workflow != OWNER:
                self.assertEqual(upload_names, [], workflow.name)
        self.assertEqual(owner_text.count(expected_android), 1)
        self.assertEqual(owner_text.count(expected_windows), 1)

    def test_r3_source_and_windows_payload_identity_are_exact(self) -> None:
        self.assertEqual(
            BUILD_VERSION.read_text(encoding="utf-8").strip(),
            "Sarah Morgan Android/Windows 2.5-r3-owner-repair source target",
        )
        gradle = BUILD.read_text(encoding="utf-8")
        self.assertIn("versionCode 27", gradle)
        self.assertIn("versionName '2.5-r3-owner-repair'", gradle)
        self.assertIn("applicationId 'com.kiraworld.sarahtravel'", gradle)
        installer = INSTALLER.read_text(encoding="utf-8")
        self.assertIn('APP_EXE = "SarahTravelOS-R3-Candidate.exe"', installer)
        self.assertIn('APP_VERSION = "2.5-r3-owner-repair-candidate"', installer)


if __name__ == "__main__":
    unittest.main()
