# Sarah protected CI attempt log — 2026-08-09

This is append-only engineering evidence for the protected Sarah R3 owner-candidate workflow. It is not physical phone/laptop owner acceptance. Secret values are never recorded here.

## Credential boundary

- The repository now has a server-side Actions secret named `SARAH_TAVILY_API_KEY`.
- The secret is supplied only to the temporary protected Worker during CI.
- It is not printed, committed, embedded in an APK/EXE, or returned by the Worker capability contract.
- The app-to-Worker bearer token and ElevenLabs credential retain the same server-side boundary.

## Preserved runs

### Run 31291499147, rerun attempt 2 — job 93203356382

- Exact Worker deployment/source/config/provider/model identity passed.
- Missing and wrong bearer tokens were rejected.
- Exact Workers AI Gemma online inference passed.
- Protected Tavily standalone search and search-coupled contextual chat passed after the new secret became available.
- Generated solid-red JPEG vision passed.
- Protected ElevenLabs voice transport passed with a real audio response.
- The later production `ModelClient` ten-turn battery failed its objective gate. This predated the redacted per-gate diagnostic, so no reply text is inferred or reconstructed.
- No owner artifact was uploaded; the run-owned temporary Worker was retired.

### Run 31297523757 — job 93204951019 — commit f7b9961274f9d5d39cf3b47fcafa465342c361fa

- Source/pure-policy and deployment steps passed.
- The freshly named route returned a platform `404` to the exact-token capability URL after negative-auth checks.
- No artifact was uploaded; the run-owned temporary Worker was retired.

### Run 31297721374 — job 93205424227 — commit 7a05430bb23ba9e00649e378f4361406b10d0f15

- Exact capability, Workers AI, protected Tavily search, and contextual source coupling passed.
- Generated-image vision reached Workers AI and received a transient HTTP `500` before the bounded vision retry existed.
- No artifact was uploaded; the run-owned temporary Worker was retired.

### Run 31297851710 — job 93205745436 — commit 51793caf9d556a12bb10021ade61ecdf8c7a0582

- The exact-token capability probe used one identical URL for every retry.
- A fresh-route platform `404` remained edge-cached for the short retry window.
- No artifact was uploaded; the run-owned temporary Worker was retired.

### Run 31298056250 — job 93206243000 — commit a8a08df348634fa6eeeb7ff1fa9689f7e796c64e

- Missing and wrong bearer-token contracts passed.
- The exact-token capability probe remained pinned to the same cached `404` URL across all 20 attempts in the 60-second window.
- The failure established that repeated requests to one URL were not a valid readiness strategy.
- No artifact was uploaded; the run-owned temporary Worker was retired.

### Run 31298492422 — job 93207320527 — commit cd9f310125436103f3a95067d3b3caf9453be1d2

- The cache-busted capability repair worked.
- The exact Worker became active after bounded propagation responses (`404`, then `500`).
- Missing and wrong bearer tokens were rejected.
- The authenticated capability response matched the exact deployment, source, config, provider, model, voice, rate-limit, and protected-search contract.
- The immediately following queryless JSON model POST received five edge-cached platform `404` responses and stopped before model inference.
- This is a route-propagation/cache failure, not evidence that the Tavily key, bearer authorization, Gemma model, or app code failed.
- No artifact was uploaded; the run-owned temporary Worker was retired successfully.

### Run 31299285949 — job 93209350789 — commit dabef556fc850cd1d0ee01e7b0faa01af39ac587

- The cache-busted protected POST repair worked for every route reached before the failure.
- Exact deployment, missing/wrong-token rejection, and authenticated capability identity passed.
- Exact Workers AI Gemma returned `ONLINE_READY` through the protected model route.
- Protected Tavily search returned three bounded HTTPS results.
- Search-coupled contextual chat passed with five HTTPS source receipts.
- Generated solid-red JPEG vision passed on the exact configured model.
- The workflow then exited immediately after the protected voice `curl` wrote its three timing fields. The write-out record had no terminating newline, so Bash `read` returned end-of-file status under `set -e` before voice status/audio/header validation could run.
- The next repair adds only the missing newline to that non-secret timing record. It does not weaken or skip any voice gate.
- No artifact was uploaded; the run-owned temporary Worker was retired successfully.

### Run 31299615327 / job 93210169524 / commit b369376790348e55451172cd28de60414e157fd4

- Exact deployment/source/config identity, missing/wrong-token rejection, authenticated capability identity, Workers AI Gemma inference, protected Tavily search, search-coupled contextual chat, generated-image vision, and protected ElevenLabs audio/route/latency validation all passed.
- The production `ModelClient` battery passed its ordinary online turns, then failed only its two current-source turns before receiving response bytes.
- Both failed turns used the fail-closed route `ONLINE_FAILED_FELL_BACK_OFFLINE`, had no raw model reply, and completed in approximately 11.07 seconds. No unsupported current claim or unverified ticket URL was displayed.
- The immediately preceding exact source-coupled Worker smoke completed successfully in approximately 14.4 seconds. Production `ModelClient` divided its 15-second turn budget into at most two reads capped at 5.5 seconds, so both valid sequential Tavily-plus-model operations were cancelled before the already-proven route could complete.
- The bounded repair retains at most two attempts, the original ordinary-chat window, and all current-source receipt gates. Only a `web_search=true` request receives one useful read window: a 25-second total budget, 18-second maximum read, and 25.5-second acceptance ceiling. No secret, provider, model, search rule, or fallback rule changes.
- No artifact was uploaded; the run-owned temporary Worker was retired successfully.

## Current bounded repair

The workflow applies a unique, non-secret query string to each bounded attempt for the protected model, search, contextual-chat, vision, and voice POST routes. It retries only transport failure, `404`, `408`, `429`, or `5xx`; every other HTTP status fails immediately. Existing exact response validators remain mandatory. The production `ModelClient` ten-turn online/offline/restore battery still runs afterward on its normal route, so cache-busted smoke probes cannot substitute for real application routing. The next run also verifies the current-source-specific bounded read repair described above.

No run in this log is an owner-ready release. Only a workflow that passes every protected gate and uploads an artifact explicitly labeled `CURRENT-OWNER-TEST` may advance to physical Galaxy A17 and 8 GB Windows laptop testing.
