# Sarah Morgan Event Candidate

This is the urgent, owner-authorized Travel Hack event build. It is a private
candidate, not a store release and not an in-place update of the older Sarah
phone app.

## Files produced by the protected build

- `Sarah-Morgan-Event-Candidate.apk` — Android 8+ side-by-side app.
- `Sarah-Morgan-Event-Candidate.sha256` — exact APK SHA-256.
- `Sarah-Morgan-Event-Candidate-manifest.json` — source commit, Worker,
  model, signer, live-test and rollback facts.
- `SarahMorgan-Event-Candidate-Setup.exe` — Windows x86-64 installer.
- `SarahMorgan-Event-Candidate-Setup.sha256` — exact installer SHA-256.
- `SarahMorgan-Event-Candidate-Setup-manifest.json` — Windows build facts.

Download the two `Sarah-Morgan-Event-Candidate-*` artifacts from the GitHub
Actions run linked on PR #21. Verify each file against its adjacent `.sha256`
record before installing.

## Install

Android uses application ID `com.kiraworld.sarahtravel.eventcandidate` and the
visible name **Sarah Morgan Event Candidate**. It installs beside the older R1
app. Do **not** uninstall the older app; Android does not copy its private data
into this separate candidate. Allow microphone, coarse location, and
notifications only if you want those features.

On Windows, run `SarahMorgan-Event-Candidate-Setup.exe`. It is intended for an
x86-64 Windows laptop including the 8 GB non-GPU event laptop. It retains the
approved portrait, real animation/power-saving toggle, Travel Workbench, maps
and public-media handoffs, trip tools, wallet, secure same-Wi-Fi discovery and
matching-code pairing.

## Online and offline behavior

The event artifacts contain the exact candidate Worker URL and one revocable
app-to-Worker bearer so protected Gemma conversation, source-backed Tavily
search and the approved ElevenLabs voice can work immediately. They contain
no Cloudflare, Tavily, ElevenLabs, or other provider API key. The bearer and
unique Worker must be retired after the event or sooner if either artifact is
shared outside the intended team.

When internet or the protected route is unavailable, Sarah keeps text chat and
her bounded on-device/offline travel knowledge. Offline answers cannot claim
current prices, availability, event dates, email access, or completed booking.
Phone system speech is the offline voice fallback. Maps, current public
sources, ElevenLabs and other network services require connectivity.

## Deliberately omitted

Gmail setup, monitoring and owner controls are hidden in both event artifacts.
Google OAuth package/signing registration was not configured and no physical
mailbox acceptance passed. Sarah can instead review an exact booking text,
screenshot or PDF that the owner chooses to share. No mail was read, changed,
sent or deleted.

An in-place R1 Android upgrade is also omitted. The exact R1 signer could not
be recovered from the cache, so pretending continuity would either fail install
or risk owner data. The side-by-side package and separately cached event signer
leave R1 untouched. Uninstalling the candidate removes only candidate-local
data; it does not retire the Worker or revoke the bearer.

## Evidence and limitations

The protected workflow requires successful bearer rejection tests, exact
Gemma/provider identity, Tavily HTTPS receipts, contextual source coupling,
solid-red JPEG vision, approved ElevenLabs audio, a bounded production
conversation battery, Android compile/signature/secret inspection, and the
exact Windows installer self-test before artifacts upload. Prior full protected
evidence is preserved in Actions run `31300663252`; the producing run and exact
source commit are recorded in each new artifact manifest.

Still pending is physical acceptance on Robert's Galaxy A17 and 8 GB laptop:
installation, permissions, speaker/microphone behavior, battery use, real
network changes, same-Wi-Fi pairing, portrait animation/lip motion, and live
owner experience. A green CI build does not claim those physical tests passed.

## Rollback and retirement

1. Keep R1 installed.
2. If the candidate misbehaves, stop it and uninstall only **Sarah Morgan Event
   Candidate** on Android, or uninstall the Windows candidate.
3. Use the exact `worker_name` and `retirement_command` in the artifact manifest
   to delete that Worker.
4. Rotate `SARAH_MODEL_BACKEND_TOKEN` after the event. Provider keys stay
   server-side and do not need to be removed from a phone or EXE.

