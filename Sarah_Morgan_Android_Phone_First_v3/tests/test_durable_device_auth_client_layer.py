import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "android-app" / "app" / "src" / "main" / "java" / "com" / "kiraworld" / "sarahtravel"
JAVA = JAVA_ROOT / "durableauth"
CORE = JAVA / "DurableDeviceAuthClientCore.java"
ADAPTER = JAVA / "AndroidDurableDeviceCredential.java"
TRANSPORT = JAVA / "AndroidDurableDeviceAuthHttpsTransport.java"
DOC = ROOT / "DURABLE_DEVICE_AUTH_ANDROID_CLIENT_LAYER.md"


class DurableDeviceAuthClientLayerBoundaryTest(unittest.TestCase):
    def test_new_layer_exists_and_is_explicitly_disconnected(self):
        for path in (CORE, ADAPTER, TRANSPORT, DOC):
            self.assertTrue(path.is_file(), path)
            self.assertIn("STAGED_NOT_CONNECTED", path.read_text(encoding="utf-8"))

    def test_production_sources_do_not_reference_staged_layer(self):
        staged_names = {
            "DurableDeviceAuthClientCore",
            "AndroidDurableDeviceCredential",
            "AndroidDurableDeviceAuthHttpsTransport",
        }
        staged_files = {CORE.resolve(), ADAPTER.resolve(), TRANSPORT.resolve()}
        for path in JAVA_ROOT.rglob("*.java"):
            if path.resolve() in staged_files:
                continue
            text = path.read_text(encoding="utf-8")
            for name in staged_names:
                self.assertIsNone(
                    re.search(rf"\b{re.escape(name)}\b", text),
                    f"{path} connected staged {name}",
                )

    def test_no_persistence_build_secret_or_logging_surface(self):
        combined = "\n".join(
            path.read_text(encoding="utf-8") for path in (CORE, ADAPTER, TRANSPORT)
        )
        forbidden = (
            "SharedPreferences",
            "SQLiteDatabase",
            "FileOutputStream",
            "ObjectOutputStream",
            "BuildConfig",
            "SARAH_EVENT",
            "System.out",
            "System.err",
            "android.util.Log",
            "setHostnameVerifier",
            "setSSLSocketFactory",
            "X509TrustManager",
        )
        for marker in forbidden:
            self.assertNotIn(marker, combined)
        self.assertRegex(combined, r"private char\[\] accessToken;")
        self.assertNotRegex(combined, r"public\s+\w+[<\w, ?\[\]]*\s+getAccessToken\s*\(")

    def test_https_transport_uses_platform_trust_and_rejects_redirects(self):
        text = TRANSPORT.read_text(encoding="utf-8")
        self.assertIn("HttpsURLConnection", text)
        self.assertIn("setInstanceFollowRedirects(false)", text)
        self.assertIn('"https".equalsIgnoreCase', text)

    def test_expected_fail_closed_states_and_bounds_exist(self):
        text = CORE.read_text(encoding="utf-8")
        for marker in (
            "KEY_MISSING_REENROLL_REQUIRED",
            "REENROLLMENT_REQUIRED",
            "ROTATION_REQUIRED",
            "REVOKED",
            "CHALLENGE_REPLAY_REJECTED",
            "CLOCK_SKEW_REJECTED",
            "ACCESS_TOKEN_TTL_OUT_OF_BOUNDS",
            "response.status == 401 || response.status == 403",
        ):
            self.assertIn(marker, text)

    def test_request_routes_are_only_durable_protocol_routes(self):
        text = CORE.read_text(encoding="utf-8")
        for route in (
            "/v1/enrollments",
            "/v1/auth/challenges",
            "/v1/auth/token",
        ):
            self.assertIn(route, text)
        self.assertNotIn("eventcandidate", text.lower())
        self.assertNotIn("modelclient", text.lower())


if __name__ == "__main__":
    unittest.main()
