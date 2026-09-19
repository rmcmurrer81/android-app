# Sarah Social Interest Module — UI + Social Provider Integration

This document explains how another interface can add Sarah's portable social-interest module, let a traveler connect social accounts, normalize authorized social activity, and send the resulting interest signals into Sarah without copying Sarah's Android UI.

## What this module is

The social-interest module is UI-neutral. A host application can provide its own web, Android, iOS, Windows, or desktop interface.

The host UI is responsible for:

1. showing the user which social providers can be connected;
2. launching each provider's supported authorization flow or accepting a user-owned export;
3. collecting only data the user has authorized the app to access;
4. translating that provider data into `SocialInterestSignal` records;
5. passing the records into `SocialInterestAnalyzer`;
6. optionally passing accepted learned interests into Sarah through `SarahSocialInterestBridge`.

Sarah is responsible for:

- confidence scoring;
- avoiding over-learning from one weak signal;
- profile isolation;
- respecting Sarah memory consent;
- exposing learned travel interests to Sarah's existing planning context.

## Recommended user experience

A host interface can show a page similar to:

```text
Personalize my travel recommendations

Connect social accounts so Sarah can learn likely interests from activity you choose to share.

Instagram        [ Connect ]
YouTube          [ Connect ]
TikTok           [ Connect ]
Facebook         [ Connect ]
Threads          [ Connect ]

[ Import my social-data export ]

Connected accounts
Instagram @example                 [ Disconnect ]
YouTube Example Channel            [ Disconnect ]
```

Do not ask the user to type a social-media password into Sarah or the host application.

A pasted public profile URL can be supported as a separate feature, but it must be labeled accurately. A public profile link only supports whatever public data the selected provider lawfully exposes; it does not magically grant access to private likes, saved posts, follows, DMs, or other private account activity.

## Connection flow

Use this provider-neutral flow:

```text
Connect button
    -> provider authorization / user-owned export
    -> host receives authorized data
    -> provider adapter normalizes data
    -> List<SocialInterestSignal>
    -> SocialInterestAnalyzer.analyze(...)
    -> learned interests + confidence
    -> SarahSocialInterestBridge (optional Sarah integration)
    -> Sarah travel recommendations
```

The host should keep provider access tokens out of Sarah's profile memory. Tokens belong in the host application's protected credential store or protected backend.

## Portable signal format

Each provider adapter converts provider-specific data to the common signal model.

Example conceptual record:

```json
{
  "provider": "instagram",
  "action": "save",
  "topic": "Power Rangers",
  "place": "Auckland",
  "sourceId": "provider-specific-id",
  "occurredAt": 1786291200000,
  "userAuthorized": true
}
```

Typical action types include:

- `like`
- `save`
- `follow`
- `comment`
- `share`
- `view`
- `post`
- `explicit_interest`

Provider adapters do not decide the final preference. `SocialInterestAnalyzer` combines repeated evidence and confidence.

## Example UI integration pseudocode

```java
public final class SocialConnectionsController {
    private final SocialInterestScraper scraper;

    public SocialConnectionsController(SocialInterestScraper scraper) {
        this.scraper = scraper;
    }

    public void refresh(String personScopeId) {
        scraper.collect(personScopeId, signals -> {
            SocialInterestAnalyzer.Result result =
                    SocialInterestAnalyzer.analyze(signals);

            renderInterestPreview(result.interests());
        });
    }
}
```

A host may implement one scraper per provider and aggregate them:

```text
CompositeSocialInterestScraper
    |- InstagramAdapter
    |- YouTubeAdapter
    |- TikTokAdapter
    |- FacebookAdapter
    |- ThreadsAdapter
    `- SocialExportAdapter
```

## Instagram

Recommended UI:

```text
Instagram
[ Connect Instagram ]
[ Import Instagram export ]
[ Add public profile URL ]
```

Current Meta developer access should be checked before implementation because Instagram permissions and eligible account types change over time. The host should use Meta-supported authorization/API access where available. At the time this integration guide was written, Meta's Instagram API documentation states that the Facebook Login path targets Instagram Professional accounts and cannot access ordinary consumer accounts through that path.

Therefore the adapter should support more than one ingestion strategy:

1. supported Instagram API authorization when the account and use case qualify;
2. user-owned Instagram data export import for data the user chooses to supply;
3. public-profile/public-content discovery only when the selected public interface or API permits it.

Do not build a credential-based bot that signs into Instagram with the user's password or steals/reuses session cookies.

## YouTube

Recommended UI:

```text
YouTube
[ Connect YouTube ]
```

Use Google OAuth 2.0. Request only the scopes required for the data your adapter actually uses. The YouTube Data API requires OAuth authorization for private/user-specific resources and supports normal OAuth flows for web, mobile, and desktop applications.

Possible authorized signals may come from resources that the current YouTube API exposes for that account. Not every historical viewing/liking behavior is necessarily available through the public developer API, so the adapter must report only what was actually retrieved.

## TikTok

Recommended UI:

```text
TikTok
[ Connect TikTok ]
[ Import TikTok export ]
```

Use TikTok Login Kit for supported account authorization. TikTok documents Login Kit as OAuth 2.0 based. Additional data access requires the applicable approved scopes. TikTok's Display API currently exposes profile information and a user's public videos with the relevant permissions, but it does not mean the host automatically receives the person's full private like/save history.

Use a user-owned export if the traveler wants to provide additional history that the supported developer APIs do not expose.

## Threads

Recommended UI:

```text
Threads
[ Connect Threads ]
```

Use Meta's supported Threads authorization/API flow. Keep the adapter isolated from the rest of Sarah so future Threads API changes only require replacing the adapter.

## Facebook

Recommended UI:

```text
Facebook
[ Connect Facebook ]
[ Import Facebook export ]
```

Use current Meta-supported Facebook Login/API permissions for data the app is approved to request. Do not imply that authorization exposes the user's entire Facebook activity history. A user-owned export can be normalized through the same `SocialInterestSignal` contract when the user explicitly imports it.

## User-owned social data exports

An export adapter is important because provider APIs frequently do not expose all of the activity that a personalization product would ideally learn from.

Recommended UI:

```text
Import my social data

Choose platform: [ Instagram v ]
Choose file:     [ Browse ]

[x] Analyze this file to improve my travel interests

[ Import ]
```

The importer should:

1. identify the provider/export format;
2. parse only the categories necessary for interest learning;
3. normalize them into `SocialInterestSignal` records;
4. show the traveler a preview of inferred interests;
5. let the traveler remove incorrect interests;
6. persist into Sarah only when the selected Sarah profile has memory consent.

Do not upload or retain unrelated export contents merely because they are present in the archive.

## Interest review UI

After collection, show the user the learned result before or alongside persistence:

```text
What Sarah learned

Power Rangers             High confidence    [ Keep ] [ Remove ]
AI & technology           High confidence    [ Keep ] [ Remove ]
Filming locations         Medium confidence  [ Keep ] [ Remove ]
History                   Medium confidence  [ Keep ] [ Remove ]
Baseball                  Low confidence     [ Keep ] [ Remove ]
```

Recommended actions:

- Keep
- Remove
- Not interested
- More like this
- Clear social-learned interests
- Disconnect provider

A user correction should outweigh a probabilistic inference.

## Feeding Sarah

Sarah already has profile-specific learned-interest handling. Use `SarahSocialInterestBridge` rather than writing directly into another person's profile.

Conceptually:

```java
SarahSocialInterestBridge bridge = new SarahSocialInterestBridge(context);
bridge.apply(personName, signals);
```

The bridge should fail closed if Sarah memory consent is absent for that profile.

The travel planner can then use the existing profile learning context alongside explicit interests.

Example:

```text
Destination: Auckland
Learned interests:
- Power Rangers
- filming locations
- AI
- history

Sarah research expansion:
- Power Rangers filming locations around Auckland
- current AI events during trip dates
- historic sites near itinerary
```

## Portable interface contract

The other team's interface does not need Sarah's Android activities. It only needs to produce normalized signals compatible with the social-interest contract and optionally invoke the Sarah bridge when running inside Sarah.

Suggested boundary for a non-Android host:

```text
POST /social-interest/v1/analyze

{
  "personScopeId": "person-123",
  "signals": [ ... ]
}
```

Suggested response:

```json
{
  "schema": "social-interest-v1",
  "personScopeId": "person-123",
  "interests": [
    {
      "topic": "Power Rangers",
      "confidence": 0.94,
      "evidenceCount": 14
    }
  ]
}
```

This HTTP endpoint is a recommended host wrapper, not a requirement of the Java core.

## Disconnect behavior

Each provider should support Disconnect.

Disconnect should:

- revoke/delete the application's stored access and refresh tokens where applicable;
- stop future collection;
- not silently delete Sarah's previously learned interests unless the user chooses to remove them;
- offer a separate `Remove learned data from this provider` action.

## Security requirements

Never store these in Sarah memories or `SocialInterestSignal`:

- social-media passwords;
- session cookies;
- recovery codes;
- OAuth client secrets;
- refresh tokens;
- private access tokens;
- payment information.

Use HTTPS for provider callbacks and backend traffic. Validate OAuth `state` and use PKCE when the provider/platform supports or requires it. Store server-capable secrets server-side.

## Truth requirements

The UI must distinguish:

- account connected;
- authorization granted;
- data actually retrieved;
- inferred interest;
- user-confirmed interest.

Do not label an interest as something the traveler definitely likes merely because one signal was observed.

Do not claim access to likes, saves, follows, viewing history, or private data unless the active provider integration actually returned that information under the user's authorization.

## Minimum viable version for the other interface

For a first usable build, implement:

1. Social Connections page;
2. Instagram, YouTube, TikTok, Facebook, and Threads provider cards;
3. working OAuth/API connection for whichever providers currently expose useful approved data;
4. social-data export import fallback;
5. normalization into `SocialInterestSignal`;
6. `SocialInterestAnalyzer`;
7. interest preview/correction screen;
8. pass accepted interests into the travel recommendation engine;
9. if running with Sarah, use `SarahSocialInterestBridge` for profile-specific learned memory.

The host can add additional providers without changing Sarah's learning engine.

## Files in this branch

Look for the social-interest files added by PR #24, including:

- `SocialInterestScraper`
- `SocialInterestSignal`
- `SocialInterestAnalyzer`
- `SarahSocialInterestBridge`
- the portable `social-interest-v1` contract
- social-interest analyzer tests

## Provider documentation used for this integration note

Provider APIs change. Before implementing or releasing a provider adapter, re-check the provider's current official developer documentation and approved scopes.

Current primary references used while preparing this file:

- Meta Instagram API developer documentation / official Meta API workspace
- Meta Threads API developer documentation / official Meta API workspace
- Google YouTube Data API v3 and OAuth 2.0 documentation
- TikTok Login Kit and Display API documentation

The important architectural rule is that provider-specific access stays inside replaceable adapters. Sarah receives normalized evidence, not social-media credentials.
