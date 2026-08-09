# Sarah R3 bounded online/offline conversation acceptance

Status: deterministic route-contract harness implemented; physical/live owner acceptance pending.

The harness at `windows-companion/sarah_r3_acceptance.py` drives the real
`SarahDatabase` and `ModelClient` paths. It writes each run to a new
`attempt_NN` folder and never overwrites an earlier attempt. Its JSON evidence
keeps, per turn, the submitted text, exact connected prompt, raw connected
reply when one completed, final public `SPOKEN`, route, classification,
FACTUAL_TRUTH receipt, HTTPS source URLs, and timestamps/latencies. Prompts and
public/raw replies also receive SHA-256 bindings.

## Default automated run

The default uses deterministic in-process route fixtures and performs no
network request. It covers:

- natural conversation;
- explicit Power Rangers interest and New Zealand trip continuity;
- a current-nearby event answer withheld when the connected result lacks a
  completed source receipt;
- official-event/ticket-link receipt handling without a purchase claim;
- on-device flight calming support;
- forced offline conversation;
- bounded connected failure with automatic offline fallback;
- return to the exact Workers AI route on the next ordinary turn;
- correction after an error;
- no invented purchase, Gmail read, flight number, or exact location.

Run from `windows-companion`:

```powershell
py -B sarah_r3_acceptance.py `
  --working-root <temporary-runtime-root> `
  --evidence-root <append-only-evidence-root>
```

Fixture URLs prove source-receipt plumbing only. They are explicitly labeled
`DETERMINISTIC_FIXTURE_NO_NETWORK` and are not current event evidence.

## Explicit live run

The harness will not make a live request by default. A live run requires all
of the following:

1. an exact existing owner runtime root containing an activated HTTPS Sarah
   route;
2. provider `workers-ai`;
3. a model other than Llama 3.1 (the R3 default is
   `@cf/google/gemma-4-26b-a4b-it`);
4. the explicit CLI confirmation string.

```powershell
py -B sarah_r3_acceptance.py `
  --working-root <fresh-temporary-runtime-root> `
  --evidence-root <append-only-evidence-root> `
  --live `
  --live-config-root <exact-owner-config-root> `
  --confirm-live I_AUTHORIZE_BOUNDED_SARAH_LIVE_ACCEPTANCE
```

No paid OpenAI service is required or selected. Ordinary conversation keeps
at most two attempts, a 15-second route budget, and a 15.5-second acceptance
limit. A request that requires current sources may use at most three attempts
inside a separate 25-second route budget and 25.5-second acceptance limit
because the protected Worker performs Tavily retrieval and then source-coupled
model inference sequentially. Only transport/timeouts and HTTP `408`, `429`,
or `5xx` may retry; authorization, nontransient `4xx`, and response-contract
failures stop immediately. This does not relax the source gate: current claims
still require an applied search plus HTTPS source receipts. Evidence records
only whether an access token was present; it never writes the token value.

## Objective versus subjective evidence

Automated scoring checks route truth, nonempty public reply, source gating,
continuity in the exact prompt, action-truthfulness, prohibited Llama 3.1
absence, correction behavior, and the bounded text deadline.

A separate owner/Turing-style worksheet asks Robert to rate observable
naturalness, warmth, continuity, usefulness, correction handling, uncertainty,
route-transition clarity, and latency. Those ratings are not proof of
consciousness, personhood, biological humanity, or any hidden subjective
state.

## Physical/live gates not satisfied by source tests

- exact deployed Worker, model, and deployment identity;
- real official HTTPS event/ticket sources opened and checked;
- Galaxy A17 and Windows online → airplane/offline → restored-online sequence;
- submit-to-text and text-to-audible ElevenLabs playback measurements;
- owner hearing and subjective review on both devices;
- confirmation that Gmail, location, purchase, and ticket claims occur only
  after their own exact tool/action receipts.

No voice, purchase, email access, current event, or owner acceptance is claimed
by the deterministic run.
