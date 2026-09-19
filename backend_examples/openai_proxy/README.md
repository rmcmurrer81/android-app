# Sarah OpenAI Backend Example

This folder contains a small FastAPI reference service for the team-owned model connection. It keeps the OpenAI API key on a server instead of asking each person who installs Sarah to provide a key.

## Environment variables

```text
OPENAI_API_KEY=server-side OpenAI project key
SARAH_APP_TOKEN=a long random revocable access code supplied privately after installation
SARAH_OPENAI_MODEL=gpt-5.1
```

Run locally for development:

```bash
python -m venv .venv
source .venv/bin/activate              # Windows: .venv\Scripts\activate
pip install -r requirements.txt
uvicorn app:app --host 0.0.0.0 --port 8000
```

The Android client requires HTTPS, so a real deployment must place this behind a valid HTTPS endpoint. A release artifact may contain the non-secret endpoint, but the access code is entered only after installation by a confirmed owner:

```text
SARAH_MODEL_BACKEND_URL=https://your-host.example/v1/sarah/respond
SARAH_MODEL_BACKEND_TOKEN=leave empty during every APK and EXE build
```

Do not put `OPENAI_API_KEY` in the Android repository or APK. The example deliberately reads it only from the server environment.

## Request contract

The Android app sends JSON containing:

- `provider`
- `model`
- `system_prompt`
- `history`
- `message`
- `web_search`
- optional `image_jpeg_base64`

The service returns:

```json
{"reply":"Sarah's public reply"}
```

## Before public use

Add real account authentication, per-user or per-device authorization, rate limits, usage quotas, abuse controls, audit logs that do not expose private content, secret rotation, deletion controls, billing limits, monitoring, retry policy, and a privacy statement. Never embed the sample bearer token in an APK or EXE; provision a revocable per-install credential privately after a confirmed-owner action.
