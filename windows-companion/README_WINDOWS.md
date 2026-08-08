# Sarah Morgan Windows Companion 2.5 R2 owner-acceptance candidate

Sarah on Windows is another embodiment of the same continuing Sarah used on Android. It is designed for a desktop or laptop with more room for conversation, research, trip planning, source cards, photos, backups and an offline/local model.

## Included

- movable, always-on-top adult Sarah portrait window with continuous CPU-only
  2D rendering, naturalized blinks, restrained eye/head idle motion, and
  decoded-audio mouth motion while ElevenLabs audio plays;
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

The event-ready UI has a **Connection** button. The R2 candidate writes settings
only to `%APPDATA%\SarahMorgan-R2-Candidate\runtime-config.json`; on Windows,
credential values in that file are protected for the current Windows account
with DPAPI and are never stored as plaintext. Its installer, executable,
shortcuts, registry entry, and writable data root are side-by-side with the
preserved R1 Windows artifact. R2 does not start or advertise the legacy
plain-HTTP LAN sync service; **Devices** shows `Setup required`. The online-judge
CI build bundles a workflow-generated `sarah-event-config.json` containing only
non-secret URL/model/voice defaults. Resolution order is environment variable,
then R2 per-user runtime config, then those non-secret bundled defaults.

No app token or provider key is bundled in the APK or EXE. The event build may
include a public Worker URL, provider/model identity, and approved voice IDs;
Robert activates the revocable Sarah access code after installation through
**Connection**. Candidate Worker limits reduce ordinary abuse but are not a
hard global spending cap. Rotate the Worker access code if its private transfer
or per-user installation is exposed. Never commit, email, or distribute the
per-user runtime configuration. Type **CLEAR** in the access-code prompt to
remove the saved activation.

The Windows model client uses the same provider-neutral request and `{ "reply": "..." }` response contract as Android. This is required for one deployed Worker to serve both embodiments.

When the protected model backend is configured, Windows Sarah automatically
derives its voice URL as `<backend>/voice` and reuses the DPAPI-protected,
owner-activated Sarah access code. This enables ElevenLabs without storing its
provider key in the EXE or as plaintext in the local runtime file. A separately
configured voice-backend URL/token may override that derivation and is protected
by the same per-user storage boundary.

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

Sarah's approved portrait remains the exact hash-validated source image. The
Windows corner window now renders it at 20 frames per second with small
parameterized deformations; it does not cycle through saved still images and
does not require a GPU. When the corner window is hidden, Sarah stops rendering
invisible frames and uses a low-cost 250 ms visibility poll until it is shown
again. ElevenLabs MP3 is decoded locally to a bounded 40 ms
RMS envelope using `miniaudio`, and that exact envelope drives mouth opening
during the matching cancellable voice generation. Decoding is capped at ten
minutes. If the exact audio cannot be decoded, or Windows System.Speech is the
active offline route, the UI uses a separately labeled speaking-activity cue
instead of falsely calling it audio lip sync. Sending a new turn or pressing
**Stop voice** also stops only the matching avatar speech generation.

Automated tests prove continuous frame differences, blink scheduling, exact
generation cancellation, real PCM energy following, renderer bounds, and
portrait-hash preservation. They do not prove that blink placement, mouth
shape, audio/video alignment, motion comfort, or CPU use looks acceptable on
Robert's physical 8 GB Windows laptop; that owner-visible run remains required.

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

The owner-approved adult portrait asset is implemented and hash-validated.
Bounded 2D facial presence animation and decoded-audio-driven mouth motion are
implemented and covered by automated tests. This is not a 3D body or full
facial-performance rig, and it has not passed physical owner visual/hearing
acceptance. The R2 installer remains pending physical operation on Robert's
8 GB no-GPU laptop.
