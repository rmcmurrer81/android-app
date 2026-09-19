# Sarah online owner-activation repair - 2026-08-08

Status: implementation and focused static/unit tests passed; physical owner activation is still required.

## Exact failure

The owner-test APK included the non-secret HTTPS Worker address but correctly did not include the reusable Worker bearer token. Android treated that missing owner activation as the generic state `Online unavailable`, hid the activation action in detailed Settings, and used the offline mind despite validated phone internet. Windows could also attempt the configured endpoint with an empty token before falling back.

Internet availability and authenticated Sarah access are separate truths.

## Repair

- Android now distinguishes validated internet from missing owner activation.
- A confirmed owner with validated internet and a build-supplied HTTPS address receives one prominent activation prompt per activity lifetime.
- The address is prefilled; the owner enters only the revocable Sarah access code in the normal case.
- The status itself is actionable and opens that activation path.
- After encrypted activation, existing capability checks run immediately and on connectivity recovery; later messages retry automatically.
- Non-owners are never offered the credential-entry prompt.
- Windows exposes the same truthful state and does not send an unauthenticated inference request when only an address is configured.
- After the DPAPI-protected Windows access code exists, each ordinary request uses the protected route and retains the existing bounded retry.

## Security boundary retained

No Cloudflare, Workers AI, OpenAI, Tavily, Gmail, ElevenLabs, or reusable app-to-Worker credential is embedded in a public APK or EXE. `/capabilities`, conversation, search, and voice remain bearer-protected. An app-to-Worker access code in a public artifact would be extractable and is therefore not an acceptable shortcut.

## Remaining physical gate

GitHub repository secrets are intentionally write-only and cannot be recovered for the owner after entry. Before physical acceptance, the owner must set or rotate `SARAH_MODEL_BACKEND_TOKEN` to a random 32-256 character URL-safe value that the owner retains, deploy the exact Worker, enter that value once on each approved installation, and verify authenticated `/capabilities`, conversation, source search, and ElevenLabs voice.

Passing source tests does not claim that the current phone or laptop has completed that activation or that the deployed Worker is currently reachable.
