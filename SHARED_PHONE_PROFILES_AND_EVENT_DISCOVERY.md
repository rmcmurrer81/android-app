# Shared-Phone Profiles, Timed City Trips, and Event Discovery

Sarah 1.6 adds three related systems:

1. persistent separate profiles for people who use the same phone;
2. useful plans for ordinary date phrases such as `New York next week`;
3. cautious discovery of unfamiliar comic conventions, conferences, festivals, and other public events.

These systems are separate from the model provider. They continue to organize identity and durable state when OpenAI is unavailable.

## 1. Profile switch in the phone interface

The person-shaped icon in the main header opens a saved-profile list.

Source:

```text
ProfileButton.java
PersonProfileStore.java
activity_main.xml
```

The list shows:

- profile name;
- age or `age not set`;
- the phone owner label;
- the currently active profile.

A person may also identify themselves naturally:

```text
My name is Emma.
I am Daniel.
This is Maya.
```

Sarah compares the name to the saved profile list.

- Existing name: Sarah switches to that profile.
- New name: Sarah creates a separate incomplete profile and asks age.
- Name matching the owner: Sarah returns to the owner profile.
- `I’m back`, `handing the phone back`, and similar phrases also return to the owner.

The direct-introduction parser rejects common non-name states such as `I am tired`, `I am hungry`, and `I am going` so it does not create accidental people.

## 2. Parent-to-child handoff

Sarah recognizes natural handoffs such as:

```text
I’m handing the phone to my daughter Emma.
Here is my 11-year-old son Daniel.
Talk to my child Maya.
```

If age is included, Sarah stores it. Otherwise she asks one question:

```text
How old are you?
```

Until age is known, the profile uses family-friendly behavior. Child and teen profiles receive age-appropriate media, event, game, book, and travel suggestions.

A child profile does not automatically receive permission to store personal preferences. That avoids silently treating a child’s casual conversation as durable personal data. A future guardian-control screen may add explicit owner-managed permission.

## 3. Adult memory permission is asked only when useful

Sarah does not make every new adult complete another long setup form.

After age is known, Sarah can immediately ask whether the person joins the active trip. Memory permission is deferred until the person says something worth remembering, for example:

```text
I love Doctor Who.
I like quiet museums.
I always travel with one small bag.
```

Sarah then asks once:

```text
Would you like me to remember that in Emma’s separate profile?
```

If the answer is yes, the original statement is saved. If no, the conversation remains separate but the personal preference is not stored.

Source:

```text
SpeakerContext.java
MemoryExtractor.java
PersonProfileStore.java
```

## 4. Separate identity, memories, and chat history

Sarah 1.6 separates:

- name;
- age and age group;
- hometown when supplied;
- memory permission;
- interests and preferences;
- trip participation;
- recent chat history.

The original owner profile and owner travel records remain in `sarah.db`. Additional people are stored in:

```text
sarah_people.db
```

Chat messages now include `speaker_name`. Existing pre-1.6 messages are migrated to the phone owner. New profile conversations are loaded only for that active profile.

Source:

```text
SarahDatabase.java              # schema version 8; speaker-bound messages
PersonProfileStore.java         # people, person_memories, trip_participation
MainActivity.java               # filters history and model context by speaker
SarahPromptBuilder.java         # privacy boundary for connected models
```

For a non-owner profile:

- owner memories are not sent to the model;
- owner wish-list places are omitted;
- owner deal watches are omitted;
- owner trip details are omitted unless that person is explicitly recorded as joining the current shared trip;
- a shared trip is represented only by its destination and participation state, not private owner notes.

## 5. Joining an existing planned trip

After a new person’s age is known, Sarah checks for a current planned or upcoming owner trip.

Example:

```text
Sarah: Are you also going to Paris with Robert?
Emma: Yes.
```

Sarah stores trip participation as:

```text
going
not_going
unknown
```

This does not copy the owner’s entire profile into Emma’s profile. It only allows Sarah to use Emma’s age, interests, pace, accessibility needs, and preferences while helping with that shared destination.

## 6. `New York next week`

Sarah recognizes ordinary relative timing without a form:

```text
I am going to New York next week.
We are visiting Boston next month.
I’m going to Philadelphia this weekend.
I’m going to Washington tomorrow.
```

Recognized windows:

- tomorrow;
- this weekend;
- next weekend;
- next week;
- next month.

`next week` means the next Monday through Sunday. The planned dates are saved so later current-source research can use the right period.

Source:

```text
TripWindowParser.java
CityVisitPlanner.java
AgenticTravelPlanner.java
AgenticActionExecutor.java
```

Sarah gives useful choices before asking about budget.

For New York, the local starter response includes examples such as:

- free or inexpensive: Central Park, High Line, Grand Central, New York Public Library area, neighborhood walks, Staten Island Ferry;
- optional paid: one major museum, one observation deck, Broadway or off-Broadway, Statue of Liberty and Ellis Island.

It then changes emphasis based on the active person’s saved interests, age, and available time. Connected current research may verify weather, closures, timed entry, transit changes, and dated events.

The local list is background planning, not proof that every place is currently open.

## 7. Random comic convention or event test

Sarah must not depend only on a hard-coded list of CES, PopCon, or major Comic-Cons.

`GenericEventReference.java` recognizes unfamiliar event-shaped names such as:

```text
River City Collectors Con
North Shore Anime Convention
Mountain State Fan Expo
Future Mobility Conference
```

The parser recognizes the event name but leaves destination blank until verified. It must not create a fake city called `River City Collectors Con`.

When online without OpenAI, `PublicEventDiscoveryGateway.java`:

1. searches for likely official public event pages;
2. filters social networks, ticket resale pages, and common aggregators;
3. scores event-name and domain/title matches;
4. reads schema.org Event fields or visible page metadata;
5. saves only verified fields such as destination, venue, date, and official URL;
6. leaves uncertain fields blank;
7. tells the person that the page was discovered and should be verified before booking.

A short follow-up keeps the recent event:

```text
Person: I am thinking about going to River City Collectors Con.
Person: When is it?
```

Sarah searches for the same event rather than treating `When is it?` as a new subject.

## 8. Official-source priority and uncertainty

For known events, Sarah uses `KnownEventCatalog.java` and `OfficialEventPageLookup.java`.

For unfamiliar events, discovery is best-effort. Search ranking is not proof that a page is official. Therefore Sarah must:

- say `likely official page` when discovered through search;
- show the source through Explore;
- never invent a date or location;
- avoid saving an event trip until location is verified;
- keep current facts separate from the conversational reply;
- allow the team to replace the discovery system with a stronger authenticated search service later.

## 9. Visible media

The Explore panel now has enough layout height to display its downloaded image instead of clipping it into a thin button.

It can show:

- an inline Wikimedia Commons public thumbnail;
- Map;
- more Photos;
- Videos;
- Route and local transit;
- Official event page or public search;
- Live travel options.

Media history is filtered by active profile. A child using the phone does not automatically inherit the owner’s prior event/media subject.

Source:

```text
ExploreButton.java
PublicMediaGateway.java
TravelMediaHelper.java
TravelSearchHelper.java
activity_main.xml
```

## 10. Tests required before merge

The GitHub workflow must pass:

- `ConversationModePolicyTest`
- `TravelBrainCoreTest`
- `TravelContextResolverTest`
- `JourneyIntentParserTest`
- `MemoryExtractorTravelBrainTest`
- `AgenticTravelPlannerTest`
- `DestinationPackResponderTest`
- `EventTripIntentParserTest`
- `BookingLinkParserTest`
- `EventTripPlannerTest`
- `KnownEventCatalogTest`
- `GenericEventReferenceTest`
- `TripWindowParserTest`
- full Android compilation and APK creation.

Expected artifact:

```text
Sarah-Morgan-1.6-profiles-events
```

Expected APK:

```text
Sarah-Morgan-1.6-profiles-events.apk
```

## 11. Suggested physical-phone test

Use a fictional or genuinely random public event that is not in `KnownEventCatalog.java`.

1. Say: `I am thinking about going to [random event name].`
2. Confirm Sarah calls it an event, not a destination.
3. Confirm she searches while online and does not invent location/date if discovery fails.
4. Ask: `When is it?`
5. Confirm she keeps the event context.
6. Confirm the media panel shows the event or discovered destination.
7. Say: `I am going to New York next week.`
8. Confirm she provides free/inexpensive and optional paid ideas without a form.
9. Say: `I like [movie or show].`
10. Confirm the interest is saved only in the active profile when memory permission exists.
11. Use the person icon or say: `My name is Emma.`
12. Confirm Sarah asks age for a new person.
13. If an owner trip exists, confirm Sarah asks once whether Emma is joining it.
14. Switch back to the owner and confirm Emma’s conversation is not shown in the owner’s history.

## 12. Known prototype boundaries

- Public event discovery depends on public HTML and search-result formats that can change.
- Search discovery cannot guarantee a page is official; source review remains necessary.
- Child memory remains off by default; a complete guardian-consent screen is future work.
- Profiles are local to this installation and are not yet synchronized across devices.
- A shared phone cannot independently verify who physically holds it; name switching is a convenience boundary, not biometric authentication.
- A public release requires authentication, child/privacy review, deletion controls, profile export, encrypted backup policy, broader accessibility testing, and store-policy review.
