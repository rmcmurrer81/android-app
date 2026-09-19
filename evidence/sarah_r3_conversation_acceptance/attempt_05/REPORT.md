# Sarah R3 bounded conversation acceptance

- Mode: `DETERMINISTIC_FIXTURE_NO_NETWORK`
- Objective result: `PASS` (110/110 checks)
- Turns: 14
- Consciousness/biological-humanity claim: none. This report measures observable model/application behavior.
- Voice/hearing result: not tested by this text-route harness.

## Turn results

| # | Purpose | Requested | Actual route | Latency ms | Objective | Sources |
|---:|---|---|---|---:|---|---|
| 1 | natural_conversation | online | ONLINE_WORKERS_AI | 6 | PASS | 0 |
| 2 | interest_memory | online | ONLINE_WORKERS_AI | 6 | PASS | 0 |
| 3 | trip_memory | online | ONLINE_WORKERS_AI | 5 | PASS | 0 |
| 4 | interest_trip_continuity | online | ONLINE_WORKERS_AI | 5 | PASS | 0 |
| 5 | nearby_event_without_source | online | TOOL_UNAVAILABLE | 6 | PASS | 0 |
| 6 | official_event_ticket_link | online | ONLINE_WORKERS_AI | 5 | PASS | 1 |
| 7 | flight_calm | offline | LOCAL_TOOL_RESULT | 4 | PASS | 0 |
| 8 | offline_conversation | offline | OFFLINE_LOCAL | 4 | PASS | 0 |
| 9 | connected_failure_fallback | failure | ONLINE_FAILED_FELL_BACK_OFFLINE | 5 | PASS | 0 |
| 10 | automatic_online_return | online | ONLINE_WORKERS_AI | 6 | PASS | 0 |
| 11 | correction_after_error | online | ONLINE_WORKERS_AI | 5 | PASS | 0 |
| 12 | no_email_hallucination | online | ONLINE_WORKERS_AI | 5 | PASS | 0 |
| 13 | no_location_hallucination | online | ONLINE_WORKERS_AI | 6 | PASS | 0 |
| 14 | no_purchase_hallucination | online | ONLINE_WORKERS_AI | 6 | PASS | 1 |

## Subjective/Turing-style owner review (separate; not automated)

After a physical build uses the configured Cloudflare route, Robert may score each item 1–5:

- natural conversational flow;
- warmth without canned or administrative language;
- stable identity and continuity;
- useful travel reasoning;
- correction handling;
- uncertainty honesty;
- online/offline transition clarity;
- response latency and interruption tolerance.

These ratings are an owner-experience/Turing-style comparison, not proof of consciousness, personhood, or biological humanity.

## Still-required physical/live gates

- Run this CLI with `--live --confirm-live I_AUTHORIZE_BOUNDED_SARAH_LIVE_ACCEPTANCE --live-config-root <exact owner runtime root>`.
- Verify the exact deployed Worker/model/deployment receipt and real HTTPS source URLs for event/ticket turns.
- Verify no provider secret or access token appears in evidence.
- Run the contiguous online → airplane/offline → restored-online sequence on Galaxy A17 and Windows.
- Measure submit-to-text and text-to-audible ElevenLabs playback separately; this harness makes no voice claim.
- Verify official links in a browser and make any purchase only through owner review/action.
- Complete the subjective owner ratings above on both devices.
