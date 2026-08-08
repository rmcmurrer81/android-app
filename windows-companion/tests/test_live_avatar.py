from array import array
import hashlib
import math
from pathlib import Path
import threading
import wave

from PIL import Image, ImageChops

from sarah_live_avatar import (
    AUDIO_ENVELOPE_FRAME_SECONDS,
    HIDDEN_AVATAR_POLL_INTERVAL_MS,
    AudioEnvelope,
    AvatarMotionModel,
    AvatarPose,
    PortraitFrameRenderer,
    decode_audio_envelope,
)
from sarah_windows import (
    SARAH_PORTRAIT_DISPLAY_SIZE,
    SARAH_PORTRAIT_RELATIVE_PATH,
    SARAH_PORTRAIT_SHA256,
)
import sarah_live_avatar
import sarah_windows


WINDOWS_ROOT = Path(__file__).resolve().parents[1]
RUNTIME_PORTRAIT = WINDOWS_ROOT / SARAH_PORTRAIT_RELATIVE_PATH


class FixedClock:
    def __init__(self, value: float = 0.0):
        self.value = value

    def __call__(self) -> float:
        return self.value


def _write_test_wav(path: Path) -> None:
    sample_rate = 16000
    silence = [0] * int(sample_rate * 0.20)
    tone = [int(12000 * math.sin(2 * math.pi * 220 * index / sample_rate))
            for index in range(int(sample_rate * 0.24))]
    samples = array("h", silence + tone)
    with wave.open(str(path), "wb") as target:
        target.setnchannels(1)
        target.setsampwidth(2)
        target.setframerate(sample_rate)
        target.writeframes(samples.tobytes())


def test_wav_envelope_tracks_silence_then_real_pcm_energy(tmp_path):
    audio = tmp_path / "voice.wav"
    _write_test_wav(audio)

    envelope = decode_audio_envelope(audio)

    assert envelope.decoded is True
    assert envelope.route == "wav_pcm"
    assert envelope.reason == ""
    assert envelope.frame_seconds == AUDIO_ENVELOPE_FRAME_SECONDS
    assert 0.42 <= envelope.duration_seconds <= 0.45
    assert len(envelope.values) >= 10
    assert max(envelope.values[:4]) < 0.05
    assert max(envelope.values[6:]) > 0.75


def test_missing_audio_fails_open_for_voice_but_not_as_fake_audio_sync(tmp_path):
    result = decode_audio_envelope(tmp_path / "missing.mp3")
    assert result.decoded is False
    assert result.values == ()
    assert result.route == "unavailable"
    assert result.reason == "audio_file_missing"


def test_existing_compressed_audio_without_decoder_is_explicit_fallback(tmp_path, monkeypatch):
    audio = tmp_path / "voice.mp3"
    audio.write_bytes(b"bounded-test-placeholder")
    monkeypatch.setattr(sarah_live_avatar, "miniaudio", None)

    result = decode_audio_envelope(audio)

    assert result.decoded is False
    assert result.values == ()
    assert result.route == "unavailable"
    assert result.reason == "miniaudio_not_installed"


def test_motion_is_continuous_deterministic_and_includes_natural_blink():
    clock_a = FixedClock()
    clock_b = FixedClock()
    motion_a = AvatarMotionModel(seed=31415, clock=clock_a)
    motion_b = AvatarMotionModel(seed=31415, clock=clock_b)

    samples_a = [motion_a.pose_at(index * 0.025) for index in range(320)]
    samples_b = [motion_b.pose_at(index * 0.025) for index in range(320)]

    assert samples_a == samples_b
    assert max(pose.blink for pose in samples_a) > 0.9
    assert any(abs(samples_a[index].head_x - samples_a[index - 1].head_x) > 0.0001
               for index in range(1, len(samples_a)))
    assert all(pose.state == "idle" and pose.lip_sync_source == "none" for pose in samples_a)


def test_decoded_envelope_drives_mouth_and_generation_stop_is_exact():
    clock = FixedClock(10.0)
    motion = AvatarMotionModel(seed=8, clock=clock)
    envelope = AudioEnvelope(
        values=(0.0, 0.15, 0.92, 0.3),
        frame_seconds=0.10,
        duration_seconds=0.40,
        decoded=True,
        route="test_pcm",
    )
    motion.start_audio(envelope, 7, now=10.0)

    quiet = motion.pose_at(10.0)
    open_mouth = motion.pose_at(10.2)

    assert quiet.state == "speaking"
    assert quiet.mouth_open == 0.0
    assert open_mouth.mouth_open > 0.9
    assert open_mouth.lip_sync_source == "decoded_audio_envelope"
    assert motion.stop_speaking(6) is False
    assert motion.active_generation == 7
    assert motion.stop_speaking(7) is True
    assert motion.pose_at(10.3).state == "idle"


def test_system_speech_path_is_truthfully_labeled_activity_not_lip_sync():
    clock = FixedClock(20.0)
    motion = AvatarMotionModel(seed=9, clock=clock)
    motion.start_speaking_fallback(3, now=20.0)
    pose = motion.pose_at(20.13)
    assert pose.state == "speaking"
    assert pose.mouth_open > 0.0
    assert pose.lip_sync_source == "speaking_activity_fallback"


def test_renderer_changes_blink_mouth_and_head_without_mutating_approved_portrait():
    before = hashlib.sha256(RUNTIME_PORTRAIT.read_bytes()).hexdigest()
    with Image.open(RUNTIME_PORTRAIT) as source:
        source.load()
        renderer = PortraitFrameRenderer(source, SARAH_PORTRAIT_DISPLAY_SIZE)

    neutral = AvatarPose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, "idle", "none")
    expressive = AvatarPose(
        1.0, 0.85, 0.55, -0.25, 0.22, 0.4, -0.2,
        "speaking", "decoded_audio_envelope",
    )
    neutral_frame = renderer.render(neutral)
    expressive_frame = renderer.render(expressive)

    assert neutral_frame.size == SARAH_PORTRAIT_DISPLAY_SIZE
    assert expressive_frame.size == SARAH_PORTRAIT_DISPLAY_SIZE
    assert ImageChops.difference(neutral_frame, expressive_frame).getbbox() is not None
    assert hashlib.sha256(RUNTIME_PORTRAIT.read_bytes()).hexdigest() == before == SARAH_PORTRAIT_SHA256


def test_many_frames_remain_memory_images_not_a_generated_still_sequence():
    with Image.open(RUNTIME_PORTRAIT) as source:
        renderer = PortraitFrameRenderer(source, SARAH_PORTRAIT_DISPLAY_SIZE)
    motion = AvatarMotionModel(seed=21, clock=FixedClock())

    frames = [renderer.render(motion.pose_at(index * 0.05)) for index in range(40)]

    assert len(frames) == 40
    assert all(frame.size == SARAH_PORTRAIT_DISPLAY_SIZE for frame in frames)
    assert len({hashlib.sha256(frame.tobytes()).hexdigest() for frame in frames}) > 8


def test_windows_exact_audio_playback_binds_and_stops_matching_avatar_generation(tmp_path, monkeypatch):
    audio = tmp_path / "sarah.wav"
    _write_test_wav(audio)
    app = sarah_windows.SarahApp.__new__(sarah_windows.SarahApp)
    app.voice_generation = 5
    app._voice_control_lock = threading.Lock()
    app._voice_cancel_reasons = {}
    app._active_voice_process = None
    app._active_voice_generation = None
    commands = []
    app._run_cancellable_voice_process = (
        lambda command, generation: commands.append((command, generation)) or (True, "")
    )
    monkeypatch.setattr(sarah_windows.sys, "platform", "win32")

    ok, reason = app._play_audio_file(audio, 5)

    assert ok is True and reason == ""
    assert commands and commands[0][1] == 5
    assert app._avatar_lip_sync_receipt["mode"] == "decoded_audio_envelope"
    assert app._avatar_lip_sync_receipt["decoder_route"] == "wav_pcm"
    assert app._avatar_lip_sync_receipt["envelope_frames"] >= 10
    assert app._avatar_lip_sync_receipt["physical_visual_acceptance"] == "pending"
    assert app._ensure_avatar_motion().active_generation is None


def test_windows_corner_schedules_true_20fps_render_instead_of_border_only(monkeypatch):
    calls = []

    class Canvas:
        def delete(self, *args):
            calls.append(("delete", args))

        def create_image(self, *args, **kwargs):
            calls.append(("image", args, kwargs))

        def create_rectangle(self, *args, **kwargs):
            calls.append(("rectangle", args, kwargs))

    class Root:
        def after(self, delay, callback):
            calls.append(("after", delay, callback.__name__))

    class Renderer:
        @staticmethod
        def render(_pose):
            return Image.new("RGB", SARAH_PORTRAIT_DISPLAY_SIZE, "#123456")

    app = sarah_windows.SarahApp.__new__(sarah_windows.SarahApp)
    app.corner = object()
    app.canvas = Canvas()
    app.root = Root()
    app.speaking = False
    app._portrait_renderer = Renderer()
    app._avatar_motion = AvatarMotionModel(clock=FixedClock())
    monkeypatch.setattr(sarah_windows.ImageTk, "PhotoImage", lambda image, master: (image.size, master))

    app._animate_avatar()

    assert any(call[0] == "image" for call in calls)
    assert ("after", 50, "_animate_avatar") in calls
    assert not any(call[0] == "vector" for call in calls)


def test_hidden_corner_skips_render_work_and_polls_for_show(monkeypatch):
    calls = []

    class Corner:
        @staticmethod
        def winfo_viewable():
            return 0

    class Canvas:
        def delete(self, *args):
            calls.append(("delete", args))

    class Root:
        def after(self, delay, callback):
            calls.append(("after", delay, callback.__name__))

    app = sarah_windows.SarahApp.__new__(sarah_windows.SarahApp)
    app.corner = Corner()
    app.canvas = Canvas()
    app.root = Root()
    app.speaking = False
    app._portrait_renderer = object()
    app._avatar_motion = AvatarMotionModel(clock=FixedClock())

    app._animate_avatar()

    assert calls == [("after", HIDDEN_AVATAR_POLL_INTERVAL_MS, "_animate_avatar")]
