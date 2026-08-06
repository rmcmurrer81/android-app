"""Reference Sarah model backend using FastAPI and the OpenAI Responses API.

This is a hackathon starting point, not a complete production service. Add real
user authentication, rate limits, abuse controls, monitoring, billing controls,
and secret rotation before public use.
"""

from __future__ import annotations

import base64
import os
from typing import Literal

from fastapi import FastAPI, Header, HTTPException
from openai import OpenAI
from pydantic import BaseModel, Field

OPENAI_MODEL = os.getenv("SARAH_OPENAI_MODEL", "gpt-5.1")
APP_TOKEN = os.getenv("SARAH_APP_TOKEN", "")
MAX_HISTORY = 20
MAX_MESSAGE_CHARS = 12_000
MAX_IMAGE_BYTES = 5_000_000

app = FastAPI(title="Sarah OpenAI Router", version="1.0")
client = OpenAI()  # Reads OPENAI_API_KEY from the server environment.


class HistoryMessage(BaseModel):
    role: Literal["user", "assistant", "system", "developer"] = "user"
    content: str = Field(default="", max_length=20_000)


class SarahRequest(BaseModel):
    provider: str = "openai"
    model: str = ""
    system_prompt: str = Field(default="", max_length=80_000)
    history: list[HistoryMessage] = Field(default_factory=list)
    message: str = Field(default="", max_length=MAX_MESSAGE_CHARS)
    web_search: bool = False
    image_jpeg_base64: str | None = None


def authorize(authorization: str | None) -> None:
    if not APP_TOKEN:
        raise HTTPException(
            status_code=503,
            detail="SARAH_APP_TOKEN is not configured on the server.",
        )
    expected = f"Bearer {APP_TOKEN}"
    if authorization != expected:
        raise HTTPException(status_code=401, detail="Unauthorized")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "provider": "openai", "model": OPENAI_MODEL}


@app.post("/v1/sarah/respond")
def respond(
    request: SarahRequest,
    authorization: str | None = Header(default=None),
) -> dict[str, str]:
    authorize(authorization)
    if request.provider.lower().strip() not in {"", "openai"}:
        raise HTTPException(status_code=400, detail="This example only routes OpenAI.")

    model = request.model.strip() or OPENAI_MODEL
    input_items: list[dict] = []
    for item in request.history[-MAX_HISTORY:]:
        content = item.content.strip()
        if content:
            input_items.append({"role": item.role, "content": content})

    current_content: list[dict[str, str]] = [
        {"type": "input_text", "text": request.message.strip()}
    ]
    if request.image_jpeg_base64:
        try:
            image = base64.b64decode(request.image_jpeg_base64, validate=True)
        except Exception as exc:
            raise HTTPException(status_code=400, detail="Invalid image encoding") from exc
        if len(image) > MAX_IMAGE_BYTES:
            raise HTTPException(status_code=413, detail="Image is too large")
        data_url = "data:image/jpeg;base64," + base64.b64encode(image).decode("ascii")
        current_content.append(
            {"type": "input_image", "image_url": data_url, "detail": "auto"}
        )

    input_items.append({"role": "user", "content": current_content})
    kwargs: dict = {
        "model": model,
        "instructions": request.system_prompt,
        "input": input_items,
        "store": False,
    }
    if request.web_search:
        kwargs["tools"] = [{"type": "web_search"}]

    response = client.responses.create(**kwargs)
    reply = (response.output_text or "").strip()
    if not reply:
        raise HTTPException(status_code=502, detail="OpenAI returned no readable reply")
    return {"reply": reply}
