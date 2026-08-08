# Sarah Morgan Windows Companion 2.2

Sarah on Windows is another embodiment of the same continuing Sarah used on Android. It is designed for a desktop or laptop with more room for conversation, research, trip planning, source cards, photos, backups and an offline/local model.

## Included

- movable, always-on-top animated Sarah corner window;
- full dashboard for chat, discoveries, trips, photos, trusted devices, backups and factual activity;
- optional notification-area/hidden-icons operation;
- Sarah Morgan ElevenLabs voice with reusable local cache;
- Windows offline speech fallback;
- optional protected model backend or local Ollama;
- Tavily source-backed pre-trip and nearby discovery;
- Power Rangers + New Zealand filming-location example through ordinary approved interests and trip context, not a hard-coded claim;
- sanitized photo imports, duplicate detection and trip-photo transfer from Android;
- SPOKEN / PRIVATE MIND / FACTUAL TRUTH separation, with only SPOKEN sent to chat and TTS;
- six-digit trusted phone pairing and encrypted, signed same-Wi-Fi sync;
- device revocation;
- password-encrypted `.sarahmind` backup;
- optional upload of the already-encrypted archive to Google Drive `appDataFolder` using the owner's OAuth desktop client;
- automated tests and a GitHub Actions Windows executable build.

## Install from source

Run `SETUP_SARAH_WINDOWS.bat`, then `START_SARAH_WINDOWS.bat`.

Optional environment variables:

```text
SARAH_ELEVENLABS_API_KEY=
SARAH_ELEVENLABS_VOICE_ID=
SARAH_ELEVENLABS_MODEL_ID=eleven_multilingual_v2
SARAH_TAVILY_API_KEY=
SARAH_MODEL_BACKEND_URL=
SARAH_MODEL_BACKEND_TOKEN=
SARAH_MODEL_PROVIDER=workers-ai
SARAH_MODEL_ID=@cf/google/gemma-4-26b-a4b-it
SARAH_OLLAMA_URL=http://localhost:11434
SARAH_OLLAMA_MODEL=qwen3.5:9b
```

Do not put provider credentials into source. Use Windows environment variables, a local `.env` loader outside source control, or a protected backend. The online-judge workflow uses one repository Actions secret named `SARAH_MODEL_BACKEND_TOKEN` as the event app-to-Worker credential; it is not a provider key.

The event-ready UI also has an **Online setup** button. It writes only to the current Windows user's `%APPDATA%\SarahMorgan\runtime-config.json`. The online-judge CI build additionally bundles a workflow-generated `sarah-event-config.json` inside the application so the event laptop works without manually copying a token. Resolution order is environment variable, then per-user runtime config, then bundled event config. The config is generated only inside CI and is never committed or uploaded separately.

The bundled event app token is deliberately revocable but is extractable from the APK or EXE by a determined person. Keeping it out of source, logs, and manifests does not make a client-held token secret. Rotate `SARAH_MODEL_BACKEND_TOKEN`, redeploy the Worker, and rebuild/reinstall the event clients after the event or whenever a binary leaves the intended team. Do not commit, email, or distribute the per-user runtime file either; it can contain an override token or direct ElevenLabs key. Use **CLEAR** in a secret prompt to remove a saved value.

The Windows model client uses the same provider-neutral request and `{ "reply": "..." }` response contract as Android. This is required for one deployed Worker to serve both embodiments.

When the protected model backend is configured, Windows Sarah automatically derives its voice URL as `<backend>/voice` and reuses the revocable Sarah app token. This enables ElevenLabs without storing its provider key in the EXE or local runtime file. A separately configured voice-backend URL/token may override that derivation.

## Pair Android

1. Put both devices on a trusted private Wi-Fi network.
2. Open **Devices & backup** in Windows Sarah.
3. Note the local IP address and temporary six-digit code.
4. On Android Sarah open **Devices & photos**.
5. Enter the address and code, approve the pairing, and press sync.
6. Revoke the device from either side when it should no longer receive Sarah data.

The prototype encrypts and signs the payload before sending it over local HTTP. A public release should add certificate pinning or a protected relay.

## Truth boundary

A source card, link, accommodation handoff or rewards link is not a booking. Sarah does not claim she purchased, reserved, called, confirmed, notified or completed anything unless a verified tool result proves it.
