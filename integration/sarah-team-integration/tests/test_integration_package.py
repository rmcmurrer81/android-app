from __future__ import annotations

import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile


PACKAGE_ROOT = Path(__file__).resolve().parents[1]
TOOLS_ROOT = PACKAGE_ROOT / "tools"
sys.path.insert(0, str(TOOLS_ROOT))

import build_package  # noqa: E402
import secret_scan  # noqa: E402


class SarahTeamIntegrationPackageTests(unittest.TestCase):
    def test_contract_contains_required_reusable_surfaces(self) -> None:
        contract = json.loads(
            (PACKAGE_ROOT / "contracts" / "sarah-team-contract-v1.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual("sarah-team-integration-v1", contract["schemaVersion"])
        required_operations = {
            "conversation.send",
            "location.resolveApproximate",
            "currentSources.search",
            "profiles.list",
            "profiles.select",
            "trips.list",
            "trips.upsert",
            "calendar.list",
            "calendar.upsert",
            "wallet.list",
            "wallet.upsert",
            "workbench.listDestinations",
            "workbench.openDestination",
            "voice.synthesize",
            "access.status",
            "access.renew",
        }
        self.assertEqual(required_operations, set(contract["operations"]))
        self.assertFalse(contract["packageSafety"]["containsRuntimeCredentials"])
        self.assertFalse(contract["packageSafety"]["containsServiceEndpoints"])
        self.assertFalse(contract["packageSafety"]["containsOwnerData"])
        self.assertTrue(contract["packageSafety"]["hostOwnsAuthenticatedTransport"])
        self.assertIn(
            "ONLINE_FAILED_FELL_BACK_OFFLINE", contract["enums"]["turnRoute"]
        )
        self.assertIn("PROTECTED_ELEVENLABS", contract["enums"]["voiceRoute"])

    def test_examples_preserve_source_and_voice_failure_truth(self) -> None:
        conversation = json.loads(
            (PACKAGE_ROOT / "contracts" / "examples" /
             "conversation-current-source.json").read_text(encoding="utf-8")
        )
        result = conversation["result"]
        self.assertFalse(result["sourceReceipt"]["applied"])
        self.assertNotEqual("PUBLIC_SOURCE_TOOL_RESULT", result["route"])
        self.assertEqual("CURRENT_SOURCE_UNAVAILABLE", result["error"]["code"])

        voice = json.loads(
            (PACKAGE_ROOT / "contracts" / "examples" / "voice-failure.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertFalse(voice["result"]["completed"])
        self.assertEqual("NONE", voice["result"]["actualRoute"])
        self.assertEqual("AUTH_UNAVAILABLE", voice["result"]["error"]["code"])

    def test_source_tree_is_secret_free(self) -> None:
        self.assertEqual([], secret_scan.scan_tree(PACKAGE_ROOT))

    def test_auth_boundary_does_not_expose_transport_secrets(self) -> None:
        source = (
            PACKAGE_ROOT
            / "src" / "main" / "java" / "org" / "sarahteam" / "integration"
            / "api" / "HostServices.java"
        ).read_text(encoding="utf-8").lower()
        for forbidden_identifier in (
            "gettoken",
            "getendpoint",
            "apikey",
            "authorizationheader",
            "providercredential",
        ):
            self.assertNotIn(forbidden_identifier, source)
        self.assertIn("renewableaccessboundary", source)
        self.assertIn("protectedvoicegateway", source)

    def test_inventory_is_explicit_and_source_only(self) -> None:
        inventory = json.loads(
            (PACKAGE_ROOT / "PACKAGE_INVENTORY.json").read_text(encoding="utf-8")
        )
        component_paths = {component["path"] for component in inventory["components"]}
        self.assertIn("contracts/sarah-team-contract-v1.json", component_paths)
        self.assertIn(
            "src/main/java/org/sarahteam/integration/android/CurrentSarahAndroidAdapter.java",
            component_paths,
        )
        excluded = " ".join(inventory["explicitlyExcluded"]).lower()
        self.assertIn("owner profiles", excluded)
        self.assertIn("provider", excluded)
        self.assertIn("event-only", excluded)
        self.assertIn("apk", excluded)

    def test_android_adapter_and_native_example_are_concrete(self) -> None:
        adapter = (
            PACKAGE_ROOT / "src" / "main" / "java" / "org" / "sarahteam"
            / "integration" / "android" / "CurrentSarahAndroidAdapter.java"
        ).read_text(encoding="utf-8")
        example = (
            PACKAGE_ROOT / "src" / "main" / "java" / "org" / "sarahteam"
            / "integration" / "examples" / "NativeAndroidExample.java"
        ).read_text(encoding="utf-8")
        mapping = (PACKAGE_ROOT / "android" / "CURRENT_NATIVE_MAPPING.md").read_text(
            encoding="utf-8"
        )
        self.assertIn("implements SarahIntegration", adapter)
        self.assertIn("interface NativePorts", adapter)
        self.assertIn("new CurrentSarahAndroidAdapter", example)
        self.assertIn("ConnectedModelGateway", mapping)
        self.assertIn("CurrentLocationPolicy", mapping)
        self.assertIn("SarahVoiceRouter", mapping)
        self.assertIn("TravelHubActivity", mapping)

    @unittest.skipUnless(shutil.which("javac"), "javac is unavailable")
    def test_java_sources_compile_with_java_8_contract(self) -> None:
        sources = sorted((PACKAGE_ROOT / "src" / "main" / "java").rglob("*.java"))
        self.assertGreaterEqual(len(sources), 4)
        with tempfile.TemporaryDirectory() as temporary:
            completed = subprocess.run(
                [
                    shutil.which("javac"),
                    "-encoding", "UTF-8",
                    "-source", "8",
                    "-target", "8",
                    "-d", temporary,
                    *[str(path) for path in sources],
                ],
                capture_output=True,
                text=True,
                check=False,
            )
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_deterministic_archive_and_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as first_dir, tempfile.TemporaryDirectory() as second_dir:
            first = build_package.build(Path(first_dir))
            second = build_package.build(Path(second_dir))
            first_bytes = Path(first["archive"]).read_bytes()
            second_bytes = Path(second["archive"]).read_bytes()
            self.assertEqual(first_bytes, second_bytes)
            digest = hashlib.sha256(first_bytes).hexdigest()
            self.assertEqual(digest, first["archive_sha256"])
            self.assertEqual(digest, second["archive_sha256"])
            self.assertEqual(
                Path(first["manifest"]).read_bytes(),
                Path(second["manifest"]).read_bytes(),
            )
            self.assertEqual([], secret_scan.scan_zip(Path(first["archive"])))
            self._assert_archive_inventory(Path(first["archive"]), Path(first["manifest"]))

    def _assert_archive_inventory(self, archive_path: Path, manifest_path: Path) -> None:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        with zipfile.ZipFile(archive_path, "r") as archive:
            infos = archive.infolist()
            names = [info.filename for info in infos]
            self.assertEqual(sorted(names), names)
            self.assertEqual(manifest["file_count"], len(names))
            required_suffixes = {
                "README.md",
                "contracts/sarah-team-contract-v1.json",
                "src/main/java/org/sarahteam/integration/api/SarahIntegration.java",
                "src/main/java/org/sarahteam/integration/android/CurrentSarahAndroidAdapter.java",
                "src/main/java/org/sarahteam/integration/examples/NativeAndroidExample.java",
            }
            for name, info in zip(names, infos):
                parsed = PurePosixPath(name)
                self.assertEqual(build_package.PACKAGE_NAME, parsed.parts[0])
                self.assertNotIn("..", parsed.parts)
                self.assertEqual(build_package.FIXED_ZIP_TIME, info.date_time)
                self.assertNotIn("runtime", name.lower())
                self.assertFalse(name.lower().endswith((".db", ".apk", ".exe")))
            stripped = {
                str(PurePosixPath(name).relative_to(build_package.PACKAGE_NAME))
                for name in names
            }
            self.assertTrue(required_suffixes.issubset(stripped))
            for item in manifest["files"]:
                data = archive.read(item["path"])
                self.assertEqual(item["size"], len(data))
                self.assertEqual(item["sha256"], hashlib.sha256(data).hexdigest())


if __name__ == "__main__":
    unittest.main()
