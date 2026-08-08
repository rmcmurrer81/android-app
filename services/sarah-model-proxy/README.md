# Sarah model proxy

This Cloudflare Worker gives Sarah a protected online conversation route without placing a model-provider credential in the Android APK or Windows EXE.

The hackathon default is Cloudflare Workers AI using the `AI` binding and `@cf/google/gemma-4-26b-a4b-it`. Cloudflare's free Workers AI allocation is bounded rather than unlimited; when it is unavailable or exhausted, Sarah's clients must label the connected failure and continue through their local/offline path. OpenAI remains an explicit optional provider, but it is not required by the default build.

## Required deployment secret

```text
SARAH_BACKEND_TOKEN
```

The online-judge workflow requires one owner-revocable repository Actions secret named `SARAH_MODEL_BACKEND_TOKEN` and deploys that exact value as `SARAH_BACKEND_TOKEN`. Do not put it in `wrangler.jsonc`, source, an issue, or a pull-request comment. `OPENAI_API_KEY` is needed only when `SARAH_MODEL_PROVIDER=openai` is deliberately selected.

## Worker configuration

`wrangler.jsonc` binds Workers AI as `AI` and supplies non-secret defaults:

```text
SARAH_MODEL_PROVIDER=workers-ai
SARAH_MODEL_ID=@cf/google/gemma-4-26b-a4b-it
```

The team may change those ordinary Worker variables without rebuilding Sarah. Provider IDs accepted by the Worker are `workers-ai` and `openai`.

## Routes and shared contract

- `GET /health` reports provider readiness without exposing a credential.
- `POST /` requires `Authorization: Bearer <SARAH_BACKEND_TOKEN>`.
- `POST /voice` uses the same Sarah token and streams the server-approved ElevenLabs voice; the ElevenLabs key never enters either client.

Android and Windows send the same provider-neutral JSON fields:

```json
{
  "provider": "workers-ai",
  "model": "@cf/google/gemma-4-26b-a4b-it",
  "system_prompt": "Sarah's prompt",
  "history": [],
  "message": "Hello",
  "web_search": false
}
```

The Worker returns `{ "reply": "...", "provider": "workers-ai", "model": "..." }`.

The protected voice route additionally requires the Worker secret `ELEVENLABS_API_KEY` and ordinary variables `SARAH_ELEVENLABS_VOICE_ID` and `SARAH_ELEVENLABS_MODEL_ID`. A client cannot substitute another voice ID. The judge workflow smoke-tests a short WAV/MP3 response before building the APK, while text remains independent and clients retain their local voice fallback.

Workers AI itself is not represented as a live-web-search tool. If `web_search` is requested on that route, the Worker explicitly tells the model that no live search result was attached and returns `web_search_applied: false`. Sarah's source-backed travel tools remain separate.

## Verification

```bash
cd services/sarah-model-proxy
npm install
npm run check
```

The tests use a mocked Workers AI binding. They do not spend quota or call a live model.

## Security boundary

The APK and Windows EXE contain only non-secret route, model, and voice identity. They do not contain a Sarah bearer token or a Cloudflare, OpenAI, or ElevenLabs account credential. After installation, a confirmed owner privately supplies a revocable Sarah access code; Android protects it with its Keystore-backed secure store and Windows protects it with per-user DPAPI storage. Rotate or revoke that access code if the private transfer or one device's stored credential is exposed. CI keeps reusable credentials out of source, logs, build environments, and artifact manifests.
