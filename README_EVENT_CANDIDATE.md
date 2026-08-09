# Sarah Morgan — 72-HOUR EVENT CANDIDATE

> **Distribution label: 72-HOUR EVENT CANDIDATE.** This is a short-lived,
> private event build. Its protected online access stops at the exact
> `event_auth_expires_utc` recorded in each adjacent manifest, no later than
> 72 hours after the producing workflow derived its event capability.

This is the urgent, owner-authorized Travel Hack event build. It is a private
candidate, not a store release and not an in-place update of the older Sarah
phone app.

## Files produced by the protected build

- `Sarah-Morgan-Event-Candidate-72H.apk` — Android 8+ side-by-side app.
- `Sarah-Morgan-Event-Candidate-72H.sha256` — exact APK SHA-256.
- `Sarah-Morgan-Event-Candidate-72H-manifest.json` — source commit, Worker,
  model, signer, live-test and rollback facts.
- `SarahMorgan-Event-Candidate-72H-Setup.exe` — Windows x86-64 installer.
- `SarahMorgan-Event-Candidate-72H-Setup.sha256` — exact installer SHA-256.
- `SarahMorgan-Event-Candidate-72H-Setup-manifest.json` — Windows build facts.

Download the two `Sarah-Morgan-Event-Candidate-72H-*` artifacts from the GitHub
Actions run linked on PR #21. Verify each file against its adjacent `.sha256`
record before installing.

## Install

Android uses application ID `com.kiraworld.sarahtravel.eventcandidate` and the
visible name **Sarah Morgan 72-Hour Event Candidate**. It installs beside the older R1
app. Do **not** uninstall the older app; Android does not copy its private data
into this separate candidate. Allow microphone, coarse location, and
notifications only if you want those features.

On Windows, run `SarahMorgan-Event-Candidate-72H-Setup.exe`. It is intended for an
x86-64 Windows laptop including the 8 GB non-GPU event laptop. It retains the
approved portrait, real animation/power-saving toggle, Travel Workbench, maps
and public-media handoffs, trip tools, wallet, secure same-Wi-Fi discovery and
matching-code pairing.

The Windows event installer is not Authenticode-signed. Windows may therefore
show an **Unknown publisher** or SmartScreen warning even when its SHA-256
matches the adjacent manifest. This is an event-candidate packaging limitation,
not evidence that the hash changed. Do not bypass an unexpected hash mismatch;
download the artifact again and compare the exact recorded SHA-256 instead.

## Online and offline behavior

The event artifacts contain the exact candidate Worker URL and one short-lived,
artifact-scoped bootstrap bearer so protected Gemma conversation,
source-backed Tavily search and the approved ElevenLabs voice can work
immediately. The bearer is derived with HMAC-SHA256 from a repository-held key
and the exact repository, run, attempt, source commit, Worker name and expiry.
Only the derived bearer is bundled; the repository derivation key and all
Cloudflare, Tavily, ElevenLabs and model-provider API keys remain server-side.

The APK and EXE intentionally share this one derived bearer and one unique
Worker. Anyone who obtains either artifact can extract and replay that bearer
against that Worker until the exact `event_auth_expires_utc` recorded in the
adjacent manifest, or until the Worker is retired sooner. The Worker enforces
the expiry on every protected route. This limits the credential's blast radius
to that exact event Worker and time window, but it is not device-bound access.
Retire the Worker immediately if either artifact is shared unexpectedly.

When internet or the protected route is unavailable, Sarah keeps text chat and
her bounded on-device/offline travel knowledge. Offline answers cannot claim
current prices, availability, event dates, email access, or completed booking.
Phone system speech is the offline voice fallback. Maps, current public
sources, ElevenLabs and other network services require connectivity.

## Deliberately omitted

Gmail setup, monitoring and owner controls are hidden in both event artifacts.
The Android event package/signing pair has no Google Android OAuth
registration, and the Windows installer has no registered Desktop OAuth client
identity. Enabling a flag would not make those identities valid, so Sarah
cannot search or monitor Gmail in this build. Sarah can instead review an exact
booking text, link, screenshot or PDF that the owner deliberately shares. No
mailbox was accessed; no mail was read, changed, sent or deleted.

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
3. Use the exact `worker_name` and `retirement_command` in the Android artifact
   manifest (or `candidate_worker_name` and `worker_retirement_command` in the
   Windows manifest) to delete that Worker. The server also rejects protected
   use automatically at the recorded `event_auth_expires_utc`.
4. The repository derivation key is not embedded and does not need routine
   post-event rotation. Rotate it only if that repository secret itself is
   exposed. Provider keys stay server-side and do not need to be removed from a
   phone or EXE.
