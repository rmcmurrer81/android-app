from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys
import wave


def chunks(text: str, limit: int = 180) -> list[str]:
    cleaned = re.sub(r"\s+", " ", text).strip()
    if len(cleaned) <= limit:
        return [cleaned]
    pieces = re.split(r"(?<=[.!?])\s+", cleaned)
    out: list[str] = []
    current = ""
    for piece in pieces:
        if not piece:
            continue
        candidate = (current + " " + piece).strip()
        if len(candidate) <= limit:
            current = candidate
            continue
        if current:
            out.append(current)
        while len(piece) > limit:
            cut = piece.rfind(" ", 0, limit + 1)
            if cut < 40:
                cut = limit
            out.append(piece[:cut].strip())
            piece = piece[cut:].strip()
        current = piece
    if current:
        out.append(current)
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--reference", required=True)
    parser.add_argument("--text-file", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--device", choices=("cpu", "cuda"), default="cpu")
    args = parser.parse_args()

    reference = Path(args.reference)
    text = Path(args.text_file).read_text(encoding="utf-8").strip()
    output = Path(args.output)
    if not reference.is_file() or not text:
        raise SystemExit("missing reference or text")

    import numpy as np
    import soundfile as sf
    import torch
    from chatterbox.tts import ChatterboxTTS

    model = ChatterboxTTS.from_pretrained(device=args.device)
    sample_rate = int(model.sr)
    output.parent.mkdir(parents=True, exist_ok=True)
    with sf.SoundFile(
        str(output),
        mode="w",
        samplerate=sample_rate,
        channels=1,
        subtype="PCM_16",
        format="WAV",
    ) as sink:
        parts = chunks(text)
        for index, part in enumerate(parts):
            wav = model.generate(part, audio_prompt_path=str(reference))
            values = wav.squeeze().detach().cpu().numpy() if hasattr(wav, "detach") else wav
            samples = np.asarray(values, dtype=np.float32).reshape(-1)
            if samples.size < max(800, sample_rate // 10):
                raise RuntimeError("generated audio was unexpectedly short")
            sink.write(samples)
            if index < len(parts) - 1:
                sink.write(np.zeros(max(1, int(sample_rate * 0.06)), dtype=np.float32))

    with wave.open(str(output), "rb") as handle:
        if handle.getnchannels() != 1 or handle.getframerate() < 8000 or handle.getnframes() < 800:
            raise RuntimeError("generated WAV validation failed")
    if args.device == "cuda" and torch.cuda.is_available():
        torch.cuda.empty_cache()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
