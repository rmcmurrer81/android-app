"""Static boundary checks for the staged Android durable-auth foundation.

These tests intentionally do not claim enrollment, renewal, server, UI, APK,
or physical-device acceptance. They prove the new foundation stays disconnected
from the current canonical and 72-hour event runtime paths.
"""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "android-app/app"
JAVA = APP / "src/main/java/com/kiraworld/sarahtravel"
PROTOCOL = JAVA / "DurableDeviceAuthProtocol.java"
MANAGER = JAVA / "AndroidDurableDeviceCredentialManager.java"


class DurableDeviceAuthAndroidFoundationTest(unittest.TestCase):
    def test_foundation_is_explicitly_staged_and_not_runtime_connected(self):
        protocol = PROTOCOL.read_text(encoding="utf-8")
        manager = MANAGER.read_text(encoding="utf-8")
        self.assertIn('IMPLEMENTATION_STATUS = "STAGED_NOT_CONNECTED"', protocol)
        self.assertIn('IMPLEMENTATION_STATUS = "STAGED_NOT_CONNECTED"', manager)

        other_java = "\n".join(
            path.read_text(encoding="utf-8")
            for path in JAVA.glob("*.java")
            if path not in {PROTOCOL, MANAGER}
        )
        self.assertNotIn("AndroidDurableDeviceCredentialManager", other_java)
        self.assertNotIn("DurableDeviceAuthProtocol", other_java)

    def test_keystore_key_is_versioned_p256_nonexportable_and_sign_only(self):
        manager = MANAGER.read_text(encoding="utf-8")
        for required in (
            'KEY_ALIAS_PREFIX = "SarahDurableDeviceAuthP256V1.Key."',
            'KeyStore.getInstance(ANDROID_KEYSTORE)',
            'KeyProperties.KEY_ALGORITHM_EC',
            'new ECGenParameterSpec("secp256r1")',
            'KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY',
            '.setDigests(KeyProperties.DIGEST_SHA256)',
            '.setUserAuthenticationRequired(false)',
            'privateKey.getEncoded() != null',
            'unexpectedly exportable',
        ):
            self.assertIn(required, manager)

    def test_bound_key_loss_is_fail_closed_without_replacement(self):
        manager = MANAGER.read_text(encoding="utf-8")
        self.assertIn("BOUND_TO_DEVICE", manager)
        self.assertIn("KeyState.KEY_MISSING", manager)
        self.assertIn("KEY_MISSING_REENROLL_REQUIRED", manager)
        self.assertIn("A bound device may not generate a replacement key", manager)

        creation_method = manager[
            manager.index("public static CredentialDescriptor createForFreshEnrollment"):
            manager.index("public static String signEnrollmentChallenge")
        ]
        self.assertLess(
            creation_method.index("binding != DeviceBinding.UNENROLLED"),
            creation_method.index("generator.generateKeyPair()"),
        )

    def test_foundation_has_no_network_or_durable_token_storage(self):
        combined = PROTOCOL.read_text(encoding="utf-8") + MANAGER.read_text(encoding="utf-8")
        for forbidden in (
            "SharedPreferences",
            "SQLiteDatabase",
            "FileOutputStream",
            "HttpURLConnection",
            "BuildConfig.",
            "SARAH_EVENT_BACKEND_TOKEN",
            "SARAH_MODEL_BACKEND_TOKEN",
            "refresh_token",
        ):
            self.assertNotIn(forbidden, combined)

    def test_active_build_does_not_compile_a_full_auth_secret_or_endpoint(self):
        gradle = (APP / "build.gradle").read_text(encoding="utf-8")
        for forbidden in (
            "SARAH_FULL_API_ORIGIN",
            "SARAH_DEVICE_AUTH_PRIVATE_KEY",
            "SARAH_DEVICE_AUTH_BEARER",
            "SARAH_DEVICE_AUTH_REFRESH_TOKEN",
        ):
            self.assertNotIn(forbidden, gradle)

        # The separate event lane remains recognizable and unchanged by this
        # staged foundation; this is not a replacement for its full test suite.
        self.assertIn("applicationIdSuffix '.eventcandidate'", gradle)
        self.assertIn("SARAH_EVENT_BACKEND_TOKEN", gradle)


if __name__ == "__main__":
    unittest.main()
