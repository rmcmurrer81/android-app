# Sarah model proxy

This Cloudflare Worker gives Sarah a protected online conversation route without placing the OpenAI API key inside the Android APK.

The Android app sends Sarah's system prompt, recent conversation, current message, optional image, requested model, and whether current web research is needed. The Worker verifies Sarah's build token, calls the OpenAI Responses API with the server-side key, and returns only a JSON `reply` to the app.

## Required Worker secrets

```text
OPENAI_API_KEY
SARAH_BACKEND_TOKEN
```

Never place either value in `wrangler.jsonc`, source code, an issue, a pull-request comment, or a committed `.env` file.

## Local setup

```bash
cd services/sarah-model-proxy
npm install
npx wrangler login
npx wrangler secret put OPENAI_API_KEY
npx wrangler secret put SARAH_BACKEND_TOKEN
npm run deploy
```

The repository's **Sarah 2.5 online judge build** workflow is the easier event path: after the three GitHub deployment secrets are configured, it generates and rotates `SARAH_BACKEND_TOKEN`, deploys this Worker, live-tests a real model reply, and builds the APK with the same URL and token.

## Routes

- `GET /health` — reports whether the Worker has its required bindings and whether a server-side model override exists. It never reveals secret values.
- `POST /` — authenticated Sarah conversation request. It expects the contract used by `SarahBackendClient.java` and returns `{ "reply": "..." }`.

## Change the model without rebuilding the APK

In Cloudflare, open this Worker, then **Settings → Variables and Secrets**. Add or change the ordinary text variable:

```text
SARAH_OPENAI_MODEL=gpt-5.1
```

Deploy the variable change. When present, this server-side value overrides the model requested by the installed APK. Delete the variable to let the APK select its build-time model again.

`SARAH_OPENAI_MODEL` is not a secret. The OpenAI key and Sarah backend token are secrets.

## Security boundary

The APK contains a short-lived Sarah backend token, not the OpenAI API key. A determined person can still extract that token from an APK, so rotate it after a public demonstration or whenever the APK leaves the team. Re-running the online-judge workflow automatically generates a new token and replaces the Worker's old token.
