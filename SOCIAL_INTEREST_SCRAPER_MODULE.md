# Sarah Social Interest Scraper Module

This module lets Sarah learn travel-relevant interests from user-authorized social-media activity while remaining portable to another interface.

## Boundary

The collector/scraper is intentionally provider-neutral. A host may implement `SocialInterestScraper` with a supported platform API, a user-owned data export, or another explicitly authorized provider. Sarah's core does not receive or store social-media passwords, session cookies, recovery codes, or hidden browser state.

Meta's current automated-data terms require express permission for automated collection that is not otherwise explicitly authorized. The Instagram adapter therefore belongs behind the provider boundary rather than being hard-coded as a login bot inside Sarah.

## Sarah flow

```text
Authorized social source
        |
        v
SocialInterestScraper.Result
        |
        v
SocialInterestAnalyzer
  - weights likes/saves/follows/comments/shares/posts
  - rewards repeated evidence
  - avoids treating one weak like as a durable preference
  - emits confidence + evidence count + sources
        |
        v
SarahSocialInterestBridge
  - exact active/person profile only
  - requires that profile's memory consent
  - stores accepted topics as profile_interest memories
        |
        v
Sarah travel planning / recommendations
```

Sarah already has profile-specific learned-interest handling. This module feeds that existing profile-memory path instead of creating a second identity system.

## Portable interface contract

The other UI/team should consume:

`integration/sarah-team-integration/contracts/social-interest-v1.json`

The UI is intentionally outside the contract. Android, web, desktop, or another hackathon interface can all send the same normalized signals.

## Signal example

```json
{
  "source": "instagram",
  "action": "SAVE",
  "topics": ["Power Rangers", "filming locations", "Auckland"],
  "source_reference": "opaque-provider-reference-or-public-url",
  "observed_at_ms": 1786291200000
}
```

## Interest example

```json
{
  "topic": "Power Rangers",
  "confidence": 0.91,
  "evidence_count": 6,
  "sources": ["instagram", "youtube"]
}
```

## Integration requirements

1. The host obtains the user's authorization before collection.
2. The host maps platform-specific activity into `SocialInterestSignal` records.
3. The host never mixes records from different Sarah person profiles.
4. The provider passes normalized results to `SarahSocialInterestBridge.ingest(...)`.
5. The UI may show or edit learned interests independently; Sarah continues to treat them as inferred and correctable.
6. Travel ranking may use `SarahSocialInterestBridge.travelInterestSummary(...)` as a compact interest string.

## Deliberate non-features

This module does not bypass private accounts, steal cookies, evade rate limits, defeat anti-bot controls, or embed a user's Instagram password in Sarah. Those are not needed for the travel-interest feature and would make the integration fragile and unsafe to distribute.
