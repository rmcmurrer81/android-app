import hashlib
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "android-app" / "app" / "src" / "main"
JAVA = APP / "java" / "com" / "kiraworld" / "sarahtravel"


class AndroidPresenceSurfaceTest(unittest.TestCase):
    def test_approved_portrait_is_exact_binary_copy(self):
        approved = ROOT.parent / "windows-companion" / "assets" / "sarah_adult_portrait_r2_runtime_512.png"
        android = APP / "res" / "drawable-nodpi" / "sarah_adult_portrait_r2_runtime_512.png"
        self.assertTrue(approved.is_file())
        self.assertTrue(android.is_file())
        expected = "3f5801ddcb99ba5e20a2f1a62d1bca8415210b1545c9b05ca1115b123a7b5b4f"
        self.assertEqual(expected, hashlib.sha256(approved.read_bytes()).hexdigest())
        self.assertEqual(expected, hashlib.sha256(android.read_bytes()).hexdigest())
        self.assertEqual(approved.read_bytes(), android.read_bytes())

    def test_normal_surface_is_conversation_first_and_keeps_real_workbench(self):
        layout = (APP / "res" / "layout" / "activity_main.xml").read_text(encoding="utf-8")
        self.assertIn('android:id="@+id/sarahPresenceHost"', layout)
        self.assertIn('android:id="@+id/sarahPortraitView"', layout)
        self.assertIn('android:id="@+id/voiceCallButton"', layout)
        self.assertIn('android:id="@+id/messageInput"', layout)
        self.assertIn("TravelHubButton", layout)
        self.assertIn('android:id="@+id/tripContextPanel"', layout)
        self.assertIn('android:visibility="gone"', layout)
        self.assertIn("ExploreButton", layout)
        self.assertIn("ProactiveDiscoveryButton", layout)
        self.assertIn("TrustedSyncButton", layout)
        self.assertIn('android:id="@+id/settingsButton"', layout)
        self.assertNotIn("bottomNavigation", layout)
        self.assertNotIn("chatNavButton", layout)

    def test_destination_preview_can_appear_without_opening_more_tools(self):
        root = ET.parse(APP / "res" / "layout" / "activity_main.xml").getroot()
        preview = next(node for node in root if node.tag.endswith("ExploreButton"))
        panel = next(
            node for node in root
            if node.attrib.get("{http://schemas.android.com/apk/res/android}id", "")
            == "@+id/tripContextPanel"
        )
        self.assertIsNot(preview, panel)
        self.assertEqual("gone", panel.attrib["{http://schemas.android.com/apk/res/android}visibility"])

    def test_motion_is_continuous_bounded_and_not_a_slideshow(self):
        source = (JAVA / "SarahPortraitView.java").read_text(encoding="utf-8")
        self.assertIn("FRAME_INTERVAL_MS = 50L", source)
        self.assertIn("drawEyeGaze", source)
        self.assertIn("drawBlink", source)
        self.assertIn("drawSpeakingMouth", source)
        self.assertIn("headAngle", source)
        self.assertIn("BitmapFactory.decodeResource", source)
        self.assertIn("sarah_adult_portrait_r2_runtime_512", source)
        self.assertIn("PHONEME_ACCURACY_PENDING", source)
        self.assertNotIn("AnimationDrawable", source)
        self.assertNotIn("Bitmap.createBitmap", source)
        self.assertNotIn("Bitmap.createScaledBitmap", source)

    def test_power_saving_is_persistent_and_stops_frame_loop(self):
        layout = (APP / "res" / "layout" / "activity_main.xml").read_text(encoding="utf-8")
        main = (JAVA / "MainActivity.java").read_text(encoding="utf-8")
        portrait = (JAVA / "SarahPortraitView.java").read_text(encoding="utf-8")
        self.assertIn('android:id="@+id/presencePowerButton"', layout)
        self.assertIn('android:id="@+id/presenceModeText"', layout)
        self.assertIn('PREF_PORTRAIT_MOTION = "portrait_motion_enabled"', main)
        self.assertIn("putBoolean(PREF_PORTRAIT_MOTION, portraitMotionEnabled)", main)
        self.assertIn("setAnimationActive(activityResumed && portraitMotionEnabled)", main)
        self.assertIn("text and voice remain available", main)
        self.assertIn("animationHandler.removeCallbacks(frame)", portrait)
        self.assertIn("if (active && isShown()) animationHandler.post(frame)", portrait)

    def test_mouth_motion_uses_real_playback_boundaries(self):
        main = (JAVA / "MainActivity.java").read_text(encoding="utf-8")
        cloud = (JAVA / "CloudVoiceClient.java").read_text(encoding="utf-8")
        self.assertIn("default void onPlaybackStarted(long playbackStartedAt)", cloud)
        self.assertIn("listener.onPlaybackStarted(startedAt)", cloud)
        self.assertIn("onPlaybackStarted(long playbackStartedAt)", main)
        self.assertIn("beginSpeechEnvelope(text, playbackStartedAt)", main)
        self.assertIn("beginSpeechEnvelope(text, startedAt)", main)
        self.assertGreaterEqual(main.count("endSpeechEnvelope()"), 4)


if __name__ == "__main__":
    unittest.main()
