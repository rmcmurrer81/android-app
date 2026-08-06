# Sarah Travel OS 2.4 — Stay22 live public release

Sarah 2.4 is the full public phone-and-Windows program, not an event-only demo. It keeps one continuing Sarah identity across Android, a Windows desktop, and a Windows laptop while adding real accommodation-search support based on Stay22's Gold Sponsor guidance.

## Why this branch uses a verified delta

The already verified Sarah 2.3 public source is preserved on `agent/sarah-public-2.3-full`. This branch stores a compact Sarah 2.4 delta in `public-release/2.4/delta-chunks`.

GitHub Actions:

1. reconstructs the delta;
2. verifies SHA-256 before using it;
3. archives the Sarah 2.3 public branch;
4. overlays and applies the Sarah 2.4 changes;
5. commits the complete readable Android, Windows, proxy, test, and documentation source to this branch;
6. runs the Android and Windows tests;
7. builds the Android APK;
8. builds one self-installing Windows EXE;
9. publishes the APK, EXE, complete readable source, and checksums as a GitHub prerelease.

No public user is asked to enter a Stay22, ElevenLabs, Tavily, or model-provider API key. Provider credentials belong only in a protected service or repository secret. The included Stay22 proxy source keeps the Stay22 key out of phone and Windows binaries.

## Stay22 behavior

Sarah can use live accommodation data, pricing, availability, supplier links, map coordinates, pagination, and comparison summaries. She distinguishes:

- a live quoted full-stay total;
- an undated result without a quote;
- a supplier that could not be reached;
- a search result from a confirmed reservation;
- Sarah's shortlist from a completed booking.

Responses are cached for no longer than the documented one-hour maximum, and rate-limit information is handled visibly. When a direct Stay22 response is unavailable, Sarah keeps an honest Searchbar or official-link fallback instead of pretending a live quote was retrieved.

## Public product scope

The phone and Windows programs also retain ordinary trip planning, offline destination packs, calm support for planes and fast trains, age-aware games and public-domain songs, consent-based memory, accessibility and sensory preferences, proactive interest research, trip-photo organization, encrypted backup, trusted-device pairing, and SPOKEN / PRIVATE MIND / FACTUAL TRUTH separation.

The Windows public release is one installer named `SarahMorganTravelOS-Setup.exe`; users do not have to run several BAT files or install Python manually.
