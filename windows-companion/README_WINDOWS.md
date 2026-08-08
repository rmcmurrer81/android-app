# Sarah Morgan Windows Companion 2.5 R2 owner-acceptance candidate

Sarah on Windows is another embodiment of the same continuing Sarah used on Android. It is designed for a desktop or laptop with more room for conversation, research, trip planning, source cards, photos, backups and an offline/local model.

## Included

- movable, always-on-top adult Sarah portrait window with a lightweight idle pulse;
- full dashboard for chat, discoveries, trips, photos, trusted devices, backups and factual activity;
- optional notification-area/hidden-icons operation;
- Sarah Morgan ElevenLabs voice with an identity-bound, owner-managed local cache;
- Windows offline speech fallback;
- optional protected model backend or local Ollama;
- Tavily source-backed pre-trip and nearby discovery;
- Power Rangers + New Zealand filming-location example through ordinary approved interests and trip context, not a hard-coded claim;
- sanitized photo imports, duplicate detection and trip-photo transfer from Android;
- SPOKEN / PRIVATE MIND / FACTUAL TRUTH separation, with only SPOKEN sent to chat and TTS;
- encrypted owner backups; trusted phone sync is visibly disabled until its
  pairing key can be transported with authenticated TLS or a reviewed key
  agreement;
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

The event-ready UI also has an **Online setup** button. The R2 candidate writes only to `%APPDATA%\SarahMorgan-R2-Candidate\runtime-config.json`. Its installer, executable, shortcuts, registry entry, and writable data root are side-by-side with the preserved R1 Windows artifact. R2 does not start or advertise the legacy plain-HTTP LAN sync service; **Devices** shows `Setup required`. The online-judge CI build additionally bundles a workflow-generated `sarah-event-config.json` inside the application so the event laptop works without manually copying a token. Resolution order is environment variable, then R2 per-user runtime config, then bundled event config. The config is generated only inside CI and is never committed or uploaded separately.

The bundled event app token is deliberately revocable but is extractable from the APK or EXE by a determined person. Keeping it out of source, logs, and manifests does not make a client-held token secret. Candidate Worker route limits reduce ordinary abuse but are per Cloudflare location, not a hard global spending cap. Rotate `SARAH_MODEL_BACKEND_TOKEN`, redeploy the Worker, and rebuild/reinstall the event clients after the event or whenever a binary leaves the intended team; use provider/account budget controls where available. Do not commit, email, or distribute the per-user runtime file either; it can contain an override token or direct ElevenLabs key. Use **CLEAR** in a secret prompt to remove a saved value.

The Windows model client uses the same provider-neutral request and `{ "reply": "..." }` response contract as Android. This is required for one deployed Worker to serve both embodiments.

When the protected model backend is configured, Windows Sarah automatically derives its voice URL as `<backend>/voice` and reuses the revocable Sarah app token. This enables ElevenLabs without storing its provider key in the EXE or local runtime file. A separately configured voice-backend URL/token may override that derivation.

The voice-cache key includes route, approved voice ID, model, voice settings, output format, and exact normalized text. Protected responses must carry `X-Sarah-Voice-Route: elevenlabs-protected`, identify an audio MIME type, and contain a minimum viable payload before they are cached or played. The cache has a documented 256 MiB owner-managed maximum; Sarah never deletes it automatically. **Devices & backup → Voice cache status / cleanup** reports its size and, after confirmation, removes only regenerable `.mp3` derivatives—not voice configuration, models, references, profiles, or memories.

Windows voice is generation-bound to the exact displayed turn and active
profile. Sending a newer message, restoring a profile archive, exiting, or
pressing **Stop voice** terminates only Sarah's exact active speech child and
drops superseded queued speech with a route receipt; it does not stop an
unrelated process. Text remains available immediately and a stale reply cannot
continue speaking over a newer correction. The ElevenLabs MP3 and Windows
speech fallback both use this cancellable boundary. Physical hearing and
latency acceptance on the 8 GB event laptop remains pending.

ElevenLabs synthesis streams with cancellation checks, 3-second connect and
5-second read bounds, a 15-second total application budget, and a 16 MiB
response cap. Stop/new-turn can close a response between chunks; an initial
socket operation may remain occupied until its bounded timeout, so this is not
claimed as zero-latency cancellation.

The receipt's `playback_start` is the child-process attempt time, not proof of
the first audible sample; `playback_start_semantics` and
`audible_start_confirmed=false` preserve that distinction. A physical owner
hearing run is required for real first-audio latency.

## Android/Windows transfer boundary

Do not attempt phone pairing in R2. **Devices & backup** reports `Setup
required` and keeps the network listener off. Use a password-encrypted owner
backup for a deliberate manual transfer, and wait for a later authenticated
sync transport before enabling automatic phone/Windows exchange.

The candidate does not send pairing material or profile data over local HTTP.
Its retained loopback server exists only for a bounded self-test and rejects a
non-loopback bind. A future phone-sync path needs authenticated TLS or a
reviewed key agreement before the UI may enable it. All public model, search,
and voice endpoints remain HTTPS-only.

## Truth boundary

A source card, link, accommodation handoff or rewards link is not a booking. Sarah does not claim she purchased, reserved, called, confirmed, notified or completed anything unless a verified tool result proves it.

The adult portrait asset is implemented and hash-validated, but full facial animation, body animation, and lip synchronization are not. The idle pulse must not be described as a completed animated-person acceptance. The R2 installer remains pending physical operation on Robert's 8 GB no-GPU laptop.
