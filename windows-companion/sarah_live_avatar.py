from __future__ import annotations

from array import array
from dataclasses import dataclass
import math
from pathlib import Path
import random
import sys
import threading
import time
import wave

from PIL import Image, ImageDraw, ImageFilter, ImageOps

try:
    import miniaudio
except Exception:  # pragma: no cover - exercised by the explicit no-decoder test
    miniaudio = None


LIVE_AVATAR_FRAME_INTERVAL_MS = 50
HIDDEN_AVATAR_POLL_INTERVAL_MS = 250
AUDIO_ENVELOPE_FRAME_SECONDS = 0.04
MAX_AUDIO_ENVELOPE_SECONDS = 600.0


@dataclass(frozen=True)
class AudioEnvelope:
    values: tuple[float, ...]
    frame_seconds: float
    duration_seconds: float
    decoded: bool
    route: str
    reason: str = ""


@dataclass(frozen=True)
class AvatarPose:
    blink: float
    mouth_open: float
    head_x: float
    head_y: float
    head_roll_degrees: float
    gaze_x: float
    gaze_y: float
    state: str
    lip_sync_source: str


def _bounded(value: float, lower: float = 0.0, upper: float = 1.0) -> float:
    return max(lower, min(upper, float(value)))


def _signed_pcm_samples(payload: bytes, sample_width: int) -> array:
    """Decode little-endian PCM without numpy or a media framework."""
    if sample_width == 1:
        return array("h", ((value - 128) << 8 for value in payload))
    if sample_width == 2:
        result = array("h")
        result.frombytes(payload[: len(payload) - (len(payload) % 2)])
        if sys.byteorder != "little":
            result.byteswap()
        return result
    if sample_width == 3:
        result = array("i")
        for offset in range(0, len(payload) - 2, 3):
            value = int.from_bytes(payload[offset:offset + 3], "little", signed=True)
            result.append(value >> 8)
        return result
    if sample_width == 4:
        raw = array("i")
        raw.frombytes(payload[: len(payload) - (len(payload) % 4)])
        if sys.byteorder != "little":
            raw.byteswap()
        return array("h", (value >> 16 for value in raw))
    raise ValueError("unsupported_pcm_sample_width")


def _normalized_rms_envelope(
    samples,
    sample_rate: int,
    channels: int,
    *,
    frame_seconds: float = AUDIO_ENVELOPE_FRAME_SECONDS,
) -> tuple[float, ...]:
    if sample_rate <= 0 or channels <= 0 or frame_seconds <= 0:
        raise ValueError("invalid_pcm_shape")
    values = samples if isinstance(samples, array) else array("h", samples)
    samples_per_window = max(channels, int(sample_rate * frame_seconds) * channels)
    raw_rms: list[float] = []
    maximum_samples = int(sample_rate * channels * MAX_AUDIO_ENVELOPE_SECONDS)
    sample_count = min(len(values), maximum_samples)
    for start in range(0, sample_count, samples_per_window):
        stop = min(sample_count, start + samples_per_window)
        if stop <= start:
            continue
        square_sum = 0.0
        for sample in values[start:stop]:
            normalized = float(sample) / 32768.0
            square_sum += normalized * normalized
        raw_rms.append(math.sqrt(square_sum / (stop - start)))
    if not raw_rms:
        return ()

    ordered = sorted(raw_rms)
    noise_floor = min(0.02, ordered[max(0, int(len(ordered) * 0.12) - 1)] + 0.003)
    reference = max(0.035, ordered[min(len(ordered) - 1, int(len(ordered) * 0.9))])
    span = max(0.02, reference - noise_floor)
    mapped = [_bounded((value - noise_floor) / span) for value in raw_rms]

    # Fast attack and slower release keep the mouth responsive without chatter.
    smoothed: list[float] = []
    current = 0.0
    for value in mapped:
        coefficient = 0.72 if value > current else 0.38
        current += (value - current) * coefficient
        smoothed.append(_bounded(current))
    return tuple(smoothed)


def decode_audio_envelope(
    audio_path: Path | str,
    *,
    frame_seconds: float = AUDIO_ENVELOPE_FRAME_SECONDS,
) -> AudioEnvelope:
    """Decode an exact local voice file to a bounded RMS mouth envelope.

    WAV uses only the standard library. MP3 and other compressed audio use the
    small offline miniaudio decoder. A decode failure never blocks speech; it
    returns an explicit non-audio-driven result so the renderer can use its
    visibly separate speaking-activity fallback.
    """
    path = Path(audio_path)
    if not path.is_file():
        return AudioEnvelope((), frame_seconds, 0.0, False, "unavailable", "audio_file_missing")
    try:
        if path.suffix.lower() == ".wav":
            with wave.open(str(path), "rb") as source:
                channels = source.getnchannels()
                sample_rate = source.getframerate()
                sample_width = source.getsampwidth()
                max_frames = int(sample_rate * MAX_AUDIO_ENVELOPE_SECONDS)
                frame_count = min(source.getnframes(), max_frames)
                samples = _signed_pcm_samples(source.readframes(frame_count), sample_width)
            values = _normalized_rms_envelope(
                samples, sample_rate, channels, frame_seconds=frame_seconds
            )
            duration = len(samples) / max(1, sample_rate * channels)
            return AudioEnvelope(values, frame_seconds, duration, bool(values), "wav_pcm")

        if miniaudio is None:
            return AudioEnvelope(
                (), frame_seconds, 0.0, False, "unavailable", "miniaudio_not_installed"
            )
        info = miniaudio.get_file_info(str(path))
        if float(info.duration) > MAX_AUDIO_ENVELOPE_SECONDS:
            return AudioEnvelope(
                (), frame_seconds, 0.0, False, "unavailable", "audio_duration_exceeds_bound"
            )
        decoded = miniaudio.decode_file(
            str(path),
            output_format=miniaudio.SampleFormat.SIGNED16,
            nchannels=1,
            sample_rate=16000,
        )
        values = _normalized_rms_envelope(
            decoded.samples,
            int(decoded.sample_rate),
            int(decoded.nchannels),
            frame_seconds=frame_seconds,
        )
        duration = min(
            MAX_AUDIO_ENVELOPE_SECONDS,
            float(decoded.num_frames) / max(1, int(decoded.sample_rate)),
        )
        return AudioEnvelope(values, frame_seconds, duration, bool(values), "miniaudio_pcm")
    except Exception as error:
        return AudioEnvelope(
            (), frame_seconds, 0.0, False, "unavailable", f"decode_failed:{type(error).__name__}"
        )


class AvatarMotionModel:
    """Thread-safe continuous idle/blink/head/lip state with deterministic hooks."""

    def __init__(self, *, seed: int = 0x53415241, clock=time.monotonic):
        self._clock = clock
        self._seed = int(seed)
        self._started_at = float(clock())
        self._lock = threading.Lock()
        self._blink_random = random.Random(self._seed)
        self._blink_centers: list[float] = []
        self._next_blink = 1.8 + self._blink_random.random() * 1.8
        self._audio: AudioEnvelope | None = None
        self._audio_started_at = 0.0
        self._speech_generation: int | None = None
        self._fallback_speaking = False

    def _extend_blinks(self, through_elapsed: float) -> None:
        while self._next_blink <= through_elapsed:
            self._blink_centers.append(self._next_blink)
            # Occasional double blinks are deterministic and remain subtle.
            if len(self._blink_centers) % 7 == 0:
                self._blink_centers.append(self._next_blink + 0.23)
            self._next_blink += 2.8 + self._blink_random.random() * 3.1

    def start_audio(self, envelope: AudioEnvelope, generation: int, *, now: float | None = None) -> None:
        with self._lock:
            self._audio = envelope if envelope.decoded and envelope.values else None
            self._audio_started_at = float(self._clock() if now is None else now)
            self._speech_generation = int(generation)
            self._fallback_speaking = not bool(self._audio)

    def start_speaking_fallback(self, generation: int, *, now: float | None = None) -> None:
        self.start_audio(
            AudioEnvelope((), AUDIO_ENVELOPE_FRAME_SECONDS, 0.0, False, "unavailable", "no_audio_file"),
            generation,
            now=now,
        )

    def stop_speaking(self, generation: int) -> bool:
        with self._lock:
            if self._speech_generation != int(generation):
                return False
            self._audio = None
            self._speech_generation = None
            self._fallback_speaking = False
            return True

    @property
    def active_generation(self) -> int | None:
        with self._lock:
            return self._speech_generation

    def pose_at(self, now: float | None = None) -> AvatarPose:
        current_time = float(self._clock() if now is None else now)
        elapsed = max(0.0, current_time - self._started_at)
        with self._lock:
            self._extend_blinks(elapsed + 0.2)
            nearby = self._blink_centers[-4:]
            blink = 0.0
            for center in nearby:
                distance = abs(elapsed - center)
                if distance <= 0.105:
                    blink = max(blink, 1.0 - distance / 0.105)

            mouth = 0.0
            state = "idle"
            lip_source = "none"
            if self._speech_generation is not None:
                state = "speaking"
                audio_elapsed = max(0.0, current_time - self._audio_started_at)
                if self._audio is not None and self._audio.values:
                    position = audio_elapsed / self._audio.frame_seconds
                    left = int(position)
                    if left < len(self._audio.values):
                        right = min(len(self._audio.values) - 1, left + 1)
                        fraction = position - left
                        mouth = (
                            self._audio.values[left] * (1.0 - fraction)
                            + self._audio.values[right] * fraction
                        )
                    lip_source = "decoded_audio_envelope"
                elif self._fallback_speaking:
                    # Explicitly not called lip sync: a bounded activity cue for
                    # Windows System.Speech or decoder failure only.
                    mouth = 0.2 + 0.28 * abs(math.sin(audio_elapsed * 10.7))
                    lip_source = "speaking_activity_fallback"

        phase = (self._seed % 997) / 997.0 * math.tau
        return AvatarPose(
            blink=_bounded(blink),
            mouth_open=_bounded(mouth),
            head_x=0.55 * math.sin(elapsed * 0.43 + phase),
            head_y=0.32 * math.sin(elapsed * 0.31 + phase * 0.7),
            head_roll_degrees=0.24 * math.sin(elapsed * 0.22 + phase * 1.3),
            gaze_x=0.55 * math.sin(elapsed * 0.19 + phase * 0.4),
            gaze_y=0.30 * math.sin(elapsed * 0.16 + phase * 1.7),
            state=state,
            lip_sync_source=lip_source,
        )


class PortraitFrameRenderer:
    """CPU-only, layered 2D deformation of the exact approved portrait."""

    def __init__(self, portrait: Image.Image, display_size: tuple[int, int]):
        self.display_size = tuple(int(value) for value in display_size)
        self._scale = 2
        width, height = self.display_size
        self._render_size = (width * self._scale, height * self._scale)
        fitted = ImageOps.fit(
            portrait.convert("RGB"),
            self._render_size,
            method=Image.Resampling.LANCZOS,
            centering=(0.5, 0.48),
        )
        self._base = fitted
        self._background = fitted.getpixel((3, 3))

    @staticmethod
    def _sample_average(image: Image.Image, box: tuple[int, int, int, int]) -> tuple[int, int, int]:
        crop = image.crop(box).resize((1, 1), Image.Resampling.BOX)
        return tuple(int(value) for value in crop.getpixel((0, 0))[:3])

    def _eye_box(self, side: str, dx: int, dy: int) -> tuple[int, int, int, int]:
        width, height = self._render_size
        bounds = (0.405, 0.301, 0.468, 0.339) if side == "left" else (0.532, 0.301, 0.595, 0.339)
        return (
            int(bounds[0] * width) + dx,
            int(bounds[1] * height) + dy,
            int(bounds[2] * width) + dx,
            int(bounds[3] * height) + dy,
        )

    def _add_gaze(self, frame: Image.Image, pose: AvatarPose, dx: int, dy: int) -> None:
        if pose.blink > 0.55:
            return
        for side in ("left", "right"):
            x0, y0, x1, y1 = self._eye_box(side, dx, dy)
            center_x = (x0 + x1) // 2
            center_y = (y0 + y1) // 2
            radius_x = max(2, int((x1 - x0) * 0.18))
            radius_y = max(2, int((y1 - y0) * 0.27))
            source_box = (
                center_x - radius_x,
                center_y - radius_y,
                center_x + radius_x + 1,
                center_y + radius_y + 1,
            )
            iris = frame.crop(source_box)
            mask = Image.new("L", iris.size, 0)
            ImageDraw.Draw(mask).ellipse((0, 0, iris.width - 1, iris.height - 1), fill=190)
            mask = mask.filter(ImageFilter.GaussianBlur(max(1, self._scale)))
            sclera = self._sample_average(
                frame,
                (max(0, x0), max(0, center_y - 1), max(1, x0 + 3 * self._scale), center_y + 2),
            )
            cover = Image.new("RGB", iris.size, sclera)
            frame.paste(cover, source_box[:2], mask)
            offset_x = int(round(pose.gaze_x * 1.1 * self._scale))
            offset_y = int(round(pose.gaze_y * 0.8 * self._scale))
            frame.paste(iris, (source_box[0] + offset_x, source_box[1] + offset_y), mask)

    def _add_blink(self, frame: Image.Image, pose: AvatarPose, dx: int, dy: int) -> None:
        if pose.blink <= 0.01:
            return
        closed = frame.copy()
        draw = ImageDraw.Draw(closed)
        for side in ("left", "right"):
            x0, y0, x1, y1 = self._eye_box(side, dx, dy)
            skin = self._sample_average(
                frame,
                (
                    x0 + 2 * self._scale,
                    y1 + self._scale,
                    x1 - 2 * self._scale,
                    y1 + 5 * self._scale,
                ),
            )
            center_y = (y0 + y1) // 2
            draw.ellipse((x0, y0, x1, y1), fill=skin)
            line = tuple(max(0, int(value * 0.48)) for value in skin)
            draw.arc(
                (x0, center_y - 2 * self._scale, x1, center_y + 3 * self._scale),
                start=8,
                end=172,
                fill=line,
                width=max(1, self._scale),
            )
        frame.paste(Image.blend(frame, closed, _bounded(pose.blink)), (0, 0))

    def _add_mouth(self, frame: Image.Image, pose: AvatarPose, dx: int, dy: int) -> None:
        amount = _bounded(pose.mouth_open)
        if amount <= 0.025:
            return
        width, height = self._render_size
        center_x = int(0.501 * width) + dx
        center_y = int(0.443 * height) + dy
        half_width = int((0.036 + amount * 0.006) * width)
        half_height = max(self._scale, int((0.002 + amount * 0.012) * height))
        overlay = Image.new("RGBA", frame.size, (0, 0, 0, 0))
        draw = ImageDraw.Draw(overlay)
        interior = (54, 24, 28, 225)
        draw.ellipse(
            (center_x - half_width, center_y - half_height, center_x + half_width, center_y + half_height),
            fill=interior,
        )
        if amount > 0.32:
            tooth_height = max(1, int(half_height * 0.28))
            draw.rounded_rectangle(
                (
                    center_x - int(half_width * 0.72),
                    center_y - half_height + self._scale,
                    center_x + int(half_width * 0.72),
                    center_y - half_height + self._scale + tooth_height,
                ),
                radius=max(1, self._scale),
                fill=(236, 220, 203, 205),
            )
        lip = self._sample_average(
            frame,
            (
                center_x - int(half_width * 0.65),
                center_y - 2 * self._scale,
                center_x + int(half_width * 0.65),
                center_y + 3 * self._scale,
            ),
        )
        lip_color = (max(80, lip[0]), max(35, int(lip[1] * 0.72)), max(40, int(lip[2] * 0.72)), 220)
        draw.arc(
            (center_x - half_width, center_y - half_height, center_x + half_width, center_y + half_height),
            185,
            355,
            fill=lip_color,
            width=max(1, self._scale),
        )
        draw.arc(
            (center_x - half_width, center_y - half_height, center_x + half_width, center_y + half_height),
            5,
            175,
            fill=lip_color,
            width=max(1, self._scale),
        )
        frame.alpha_composite(overlay)

    def render(self, pose: AvatarPose) -> Image.Image:
        width, height = self._render_size
        margin = 2 * self._scale
        enlarged = ImageOps.expand(self._base, border=margin, fill=self._background)
        dx = int(round(pose.head_x * self._scale))
        dy = int(round(pose.head_y * self._scale))
        frame = enlarged.crop((margin - dx, margin - dy, margin - dx + width, margin - dy + height))
        if abs(pose.head_roll_degrees) > 0.01:
            frame = frame.rotate(
                pose.head_roll_degrees,
                resample=Image.Resampling.BICUBIC,
                expand=False,
                fillcolor=self._background,
            )
        frame = frame.convert("RGBA")
        self._add_gaze(frame, pose, dx, dy)
        self._add_blink(frame, pose, dx, dy)
        self._add_mouth(frame, pose, dx, dy)
        return frame.convert("RGB").resize(self.display_size, Image.Resampling.LANCZOS)
