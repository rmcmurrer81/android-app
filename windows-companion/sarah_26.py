from __future__ import annotations

from pathlib import Path
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageTk

from build_assets import build_assets
from sarah_event_ready import SarahRepairApp, self_test as base_self_test


CANVAS_WIDTH = 230
CANVAS_HEIGHT = 360


def resource_path(*parts: str) -> Path:
    root = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    return root.joinpath(*parts)


class Sarah26App(SarahRepairApp):
    """Sarah 2.6 with a polished adult humanoid embodiment and responsive composer."""

    def _build_ui(self):
        super()._build_ui()
        self.side.configure(width=250)
        self.avatar_canvas.configure(width=CANVAS_WIDTH, height=CANVAS_HEIGHT)

    @staticmethod
    def _avatar_source() -> Path:
        return resource_path("assets", "sarah_full_neutral.webp")

    @classmethod
    def _render_avatar(cls, state: str) -> Image.Image:
        source = cls._avatar_source()
        if not source.is_file():
            try:
                build_assets()
            except Exception:
                return SarahRepairApp._render_avatar(state).resize(
                    (CANVAS_WIDTH, CANVAS_HEIGHT), Image.Resampling.LANCZOS
                )

        figure = Image.open(source).convert("RGBA")
        figure.thumbnail((205, 345), Image.Resampling.LANCZOS)

        background = Image.new("RGBA", (CANVAS_WIDTH, CANVAS_HEIGHT), (13, 34, 50, 255))
        glow = Image.new("RGBA", background.size, (0, 0, 0, 0))
        glow_draw = ImageDraw.Draw(glow)
        if state == "talk":
            glow_draw.ellipse((24, 18, 206, 344), outline=(53, 214, 231, 145), width=7)
        else:
            glow_draw.ellipse((28, 22, 202, 340), outline=(53, 214, 231, 55), width=3)
        glow = glow.filter(ImageFilter.GaussianBlur(9 if state == "talk" else 5))
        background.alpha_composite(glow)

        x = (CANVAS_WIDTH - figure.width) // 2
        y = CANVAS_HEIGHT - figure.height - 3
        background.alpha_composite(figure, (x, y))

        draw = ImageDraw.Draw(background)
        # The full-body source is a real polished character frame. A lightweight
        # eyelid overlay gives Sarah a natural periodic blink without degrading it.
        if state == "blink":
            scale_x = figure.width / 230.0
            scale_y = figure.height / 390.0
            left_eye = (
                x + int(88 * scale_x),
                y + int(67 * scale_y),
                x + int(102 * scale_x),
                y + int(70 * scale_y),
            )
            right_eye = (
                x + int(126 * scale_x),
                y + int(67 * scale_y),
                x + int(140 * scale_x),
                y + int(70 * scale_y),
            )
            draw.line(left_eye, fill=(60, 38, 31, 255), width=2)
            draw.line(right_eye, fill=(60, 38, 31, 255), width=2)

        return background.convert("RGB")

    def _animate_avatar(self):
        if not hasattr(self, "avatar_canvas"):
            return
        state = "talk" if self.speaking else "blink" if self._avatar_frame % 37 == 0 else "neutral"
        image = self._render_avatar(state)
        self._avatar_photo = ImageTk.PhotoImage(image)
        self.avatar_canvas.delete("all")
        self.avatar_canvas.create_image(CANVAS_WIDTH // 2, CANVAS_HEIGHT // 2, image=self._avatar_photo)
        self._avatar_frame += 1
        self.root.after(160, self._animate_avatar)


def self_test() -> int:
    build_assets()
    base_self_test()
    source = Sarah26App._avatar_source()
    with Image.open(source) as image:
        if image.size != (230, 390):
            raise RuntimeError(f"Unexpected Sarah avatar size: {image.size}")
    rendered = Sarah26App._render_avatar("talk")
    if rendered.size != (CANVAS_WIDTH, CANVAS_HEIGHT):
        raise RuntimeError(f"Sarah avatar render failed: {rendered.size}")
    print("SARAH_2_6_POLISHED_HUMANOID_ASSET_OK")
    return 0


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        raise SystemExit(self_test())
    Sarah26App().run()
