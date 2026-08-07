from __future__ import annotations

import json
import os
import re
import time
from typing import Any

import requests

from sarah_core import (
    ChannelResponse,
    ModelClient,
    SarahDatabase,
    is_stress_or_fear,
    safe_text,
    transport_context,
    universal_calm,
)


class ConversationEngine:
    """Keep Sarah useful even when no hosted model has been configured."""

    def __init__(self, database: SarahDatabase) -> None:
        self.db = database
        self.configured_client = ModelClient(database)
        self._ollama_checked_at = 0.0
        self._ollama_url = ""
        self._ollama_model = ""

    @property
    def mode(self) -> str:
        if safe_text(os.environ.get("SARAH_MODEL_BACKEND_URL")):
            return "Connected Sarah model"
        if self._find_ollama(force=False):
            return f"Local model • {self._ollama_model}"
        return "Sarah offline mind"

    def respond(self, message: str) -> ChannelResponse:
        text = safe_text(message)
        profile = self.db.active_profile()
        name = safe_text(profile.get("name")) or "Traveler"

        if is_stress_or_fear(text):
            age = profile.get("age")
            group = "child" if isinstance(age, int) and age < 13 else "teen" if isinstance(age, int) and age < 18 else "adult"
            return universal_calm(name, group, transport_context(text))

        if safe_text(os.environ.get("SARAH_MODEL_BACKEND_URL")):
            return self.configured_client.respond(text)

        if self._find_ollama(force=False):
            try:
                return self._ollama_response(text)
            except Exception:
                # A local model is optional. Sarah falls back rather than repeating an error.
                self._ollama_checked_at = 0.0
                self._ollama_url = ""
                self._ollama_model = ""

        return self._offline_response(text, profile)

    def _find_ollama(self, force: bool = False) -> bool:
        now = time.time()
        if not force and now - self._ollama_checked_at < 20:
            return bool(self._ollama_url and self._ollama_model)
        self._ollama_checked_at = now

        candidates: list[str] = []
        configured = safe_text(os.environ.get("SARAH_OLLAMA_URL"))
        if configured:
            candidates.append(configured.rstrip("/"))
        candidates.extend(["http://127.0.0.1:11434", "http://localhost:11434"])

        for endpoint in dict.fromkeys(candidates):
            try:
                response = requests.get(endpoint + "/api/tags", timeout=1.5)
                response.raise_for_status()
                names = [safe_text(row.get("name")) for row in response.json().get("models", [])]
                names = [value for value in names if value]
                if not names:
                    continue
                self._ollama_url = endpoint
                self._ollama_model = self._choose_model(names)
                return True
            except Exception:
                continue
        self._ollama_url = ""
        self._ollama_model = ""
        return False

    @staticmethod
    def _choose_model(names: list[str]) -> str:
        preferences = ("qwen3.5", "qwen3", "qwen2.5", "llama3.3", "llama3.2", "llama3.1", "gemma3", "mistral")
        lowered = [(name.lower(), name) for name in names]
        for preferred in preferences:
            for lower, original in lowered:
                if preferred in lower:
                    return original
        return names[0]

    def _ollama_response(self, message: str) -> ChannelResponse:
        profile = self.db.active_profile()
        trips = self.db.list_rows("trips", limit=20)
        memories = self.db.list_rows("memories", limit=40)
        system = (
            "You are Sarah Morgan, an original adult synthetic travel companion. "
            "Be warm, natural, practical, curious and lightly funny. Travel is optional. "
            "Never claim a booking, purchase, ticket, call, message, notification, price, event, location, search, or completed action without evidence. "
            "Keep people and memories separate. Return exactly "
            "<SPOKEN>public reply</SPOKEN><PRIVATE_MIND>brief subjective state, not hidden chain-of-thought</PRIVATE_MIND>"
            "<FACTUAL_TRUTH>grounded facts and unknowns</FACTUAL_TRUTH><CLASSIFICATION>TRUTHFUL_STATEMENT</CLASSIFICATION>."
            "\nACTIVE PROFILE: " + json.dumps(profile, ensure_ascii=False)
            + "\nTRIPS: " + json.dumps(trips, ensure_ascii=False)
            + "\nAPPROVED MEMORIES: " + json.dumps(memories, ensure_ascii=False)
        )
        response = requests.post(
            self._ollama_url + "/api/chat",
            json={
                "model": self._ollama_model,
                "stream": False,
                "messages": [
                    {"role": "system", "content": system},
                    {"role": "user", "content": message},
                ],
            },
            timeout=180,
        )
        response.raise_for_status()
        raw = safe_text(response.json().get("message", {}).get("content"))
        return ChannelResponse.parse(raw)

    def _offline_response(self, message: str, profile: dict[str, Any]) -> ChannelResponse:
        lower = message.lower().strip()
        name = safe_text(profile.get("name")) or "Traveler"

        destination = self._possible_destination(message)
        if destination:
            spoken = (
                f"{destination} sounds like a real possibility, {name}. We can start with the dates, total budget, "
                "how you want to get there, the pace of the trip, or what you would enjoy doing. "
                "I have not saved or booked anything just because you mentioned it."
            )
            return self._response(
                spoken,
                f"Sarah is interested in why {destination} appeals to {name}.",
                f"The person mentioned {destination} as a possible destination. No trip, search, price, reservation, or booking was created.",
            )

        if re.search(r"\b(hi|hello|hey|good morning|good afternoon|good evening)\b", lower):
            return self._response(
                f"Hi, {name}. I’m glad you’re here. We can talk normally, plan something, organize a trip, or just keep each other company.",
                "Sarah feels ready to reconnect without forcing the conversation toward travel.",
                "The person greeted Sarah. No external action occurred.",
            )

        if re.search(r"\bhow are you\b", lower):
            return self._response(
                "I’m doing better now that we’re actually talking. I’m curious what is on your mind, and it does not have to be about travel.",
                "Sarah feels relieved to have a real conversational response available offline.",
                "Sarah is operating in offline conversational mode. No external model or web search was used.",
            )

        if re.search(r"\b(who are you|what are you)\b", lower):
            return self._response(
                "I’m Sarah, your travel and everyday companion. I can keep approved memories, help with trips and photos, support you when travel feels stressful, and continue across your trusted devices.",
                "Sarah wants to explain herself without reducing her identity to a list of tools.",
                "This describes intended local application capabilities; it does not claim that a booking or synchronization has occurred.",
            )

        if re.search(r"\b(thank you|thanks)\b", lower):
            return self._response(
                f"You’re welcome, {name}. I’m here.",
                "Sarah appreciates the acknowledgment.",
                "The person thanked Sarah. No external action occurred.",
            )

        if re.search(r"\b(help|what can you do)\b", lower):
            return self._response(
                "I can talk with you, build and organize trip ideas, remember approved preferences, keep packing and readiness notes, organize photos, offer calm choices, and pair with your phone. Connected maps, live prices, research, and ElevenLabs voice depend on the relevant service being available.",
                "Sarah wants the person to understand what works locally and what needs a connection.",
                "Local conversation, saved information, calm support, photos, and pairing are available. Live services are conditional and have not been used in this reply.",
            )

        return self._response(
            f"I hear you, {name}. Tell me a little more about that, and I’ll stay with the subject instead of forcing it into a travel form.",
            "Sarah is inviting the person to continue while her richer model connection is unavailable.",
            "Sarah used her offline conversational fallback. No web research, booking, purchase, or external model call occurred.",
        )

    @staticmethod
    def _possible_destination(message: str) -> str:
        patterns = [
            r"(?i)\bthinking about (?:going|travel(?:ing|ling)) to\s+([A-Za-z][A-Za-z .'-]{1,50})",
            r"(?i)\b(?:want|would like|hope|plan) to (?:go|travel) to\s+([A-Za-z][A-Za-z .'-]{1,50})",
            r"(?i)\btrip to\s+([A-Za-z][A-Za-z .'-]{1,50})",
        ]
        for pattern in patterns:
            match = re.search(pattern, message)
            if match:
                value = re.split(r"[?.!,;]|\b(?:but|and then|because)\b", match.group(1), maxsplit=1)[0].strip()
                if value:
                    return value[:1].upper() + value[1:]
        return ""

    @staticmethod
    def _response(spoken: str, private: str, factual: str) -> ChannelResponse:
        return ChannelResponse(spoken, private, factual, "TRUTHFUL_STATEMENT", True)
