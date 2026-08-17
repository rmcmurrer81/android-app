# Sarah R2 adaptive offline and research boundary

Status: source implementation completed; physical Galaxy A17 and 8 GB Windows acceptance still required. This note does not approve or release an APK or installer.

## Offline calm support

Sarah's takeoff, turbulence, landing, breathing, grounding, quiet-company, and trivia paths are local tools. They do not wait for Workers AI, Tavily, ElevenLabs, a GPU, or an internet connection. A calm response records `LOCAL_TOOL_RESULT` and states that Sarah did not inspect a vehicle, diagnose the traveler, verify safety, or contact anyone.

Trivia can adapt to the active profile's saved interests and planned destination. For example, Power Rangers plus a saved New Zealand trip selects a New Zealand-themed prompt. The local prompt identifies that its answer comes from the saved trip context; it does not claim a fresh web fact. Another profile without those saved facts receives a general offline question.

## Optional adaptive research

Automatic research is off by default for every profile. It requires all of the following:

- the exact active owner profile;
- profile memory consent;
- the separate per-profile automatic-research opt-in;
- validated internet;
- non-local-only mode;
- a configured protected current-source route;
- a planned destination or separately approved fresh approximate area.

Each run is capped at two queries and four returned source candidates per query. Planned-destination research and approved-nearby research remain separate. A combined Power Rangers and New Zealand context can select filming-location sources; the query is not itself a claim that a particular location or event is current.

Android saves an exact profile-scoped receipt with `RUNNING`, `SUCCEEDED`, or `FAILED`, provider, trigger, query count, source-result count, saved count, start/completion time, and a bounded failure class. Results are stored only after an HTTPS source result returns. The discovery database now keys new rows to a durable profile ID; an append-preserving v1 migration lets the matching active profile claim its exact legacy display-name rows.

Research runs on a dedicated executor and is deferred at startup. A submitted
owner chat turn cancels the exact active Tavily/model connection and interrupts
that research worker; its failure receipt and pending knowledge request remain
available for a later bounded retry. Photo sanitization has its own executor,
so neither startup research nor image work may queue ahead of text chat.

The owner-facing **Research now** action executes that same bounded lookup immediately and reports completion or failure in ordinary language. It does not describe a merely scheduled request as completed. The screen also renders the latest profile-owned receipt without exposing raw JSON.

Windows uses the same two-query boundary. Opt-in background work begins only after two minutes without a message or active speech, and repeats no more often than every six hours while the companion is running. Its profile-scoped receipt records the same running/success/failure truth. Windows remains a small Tk/Python client and does not require a local model, GPU, or sustained heavy process.

## Truth and privacy

- A queued or running job is never described as a completed search.
- A failed search stores no invented event, price, schedule, or availability.
- Discovery source URL and capture time remain attached to each saved candidate.
- Network device sync is disabled in R2. The prior cleartext LAN prototype is
  preserved as inactive source only and cannot transfer records until an
  accepted TLS or authenticated key-agreement transport is implemented.
- Destination knowledge becomes ready only with the existing source/time receipt and expiry checks.
- Approximate area and research consent remain profile-specific.
- Fresh installs retain `Traveler`/unknown onboarding state and do not contain Robert, age 45, Newark, Power Rangers, or New Zealand defaults.
- Existing R1 APK/EXE artifacts are unchanged; this work is an R2 source candidate only.

## Remaining acceptance

Run the hosted Android compile/tests, then physically verify on the Galaxy A17 that offline takeoff support, breathing, and trivia work in airplane mode. Opt in to research on a temporary profile, verify the visible source card and profile isolation, then verify opt-out cancels future scheduling. On the 8 GB laptop, verify the UI stays responsive during the bounded idle job and that no research runs while Sarah is speaking or within two minutes of a message.
