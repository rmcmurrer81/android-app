# Sarah 2.1 Offline Flight Companion

Sarah's Offline Flight Companion is a local, profile-aware support screen for takeoff, turbulence, landing, and ordinary flight anxiety. It is designed to remain useful after the phone loses cellular service, Wi-Fi, OpenAI access, or ElevenLabs access.

The feature is not an aircraft-monitoring tool, medical service, emergency service, or substitute for the flight crew.

## Entry point

The main chat header contains a visible airplane button:

```text
✈
```

It opens:

```text
FlightCalmActivity.java
```

The button is implemented by:

```text
FlightCalmButton.java
```

The older question-mark menu remains available for quick calm support and trivia.

## Offline guarantee

The flight screen does not call:

- OpenAI;
- ElevenLabs;
- a hotel or travel-commerce backend;
- public web lookup;
- maps;
- location services;
- email;
- a booking provider.

It uses only:

- text bundled in the APK;
- the active local profile;
- locally stored trip or wish-list context when allowed;
- Android's installed offline text-to-speech engine;
- local timers and dialogs.

ElevenLabs remains Sarah's preferred online speaking voice in ordinary chat. The flight screen deliberately uses Android speech so the tools do not disappear in airplane mode or consume cloud credits during a long flight.

## Safety boundary

Every phase-specific response follows these rules:

1. Keep the seat belt fastened when required.
2. Follow the flight crew and the airline's device rules.
3. Do not claim that Sarah can inspect the aircraft.
4. Do not promise that a sound, movement, or sensation is safe.
5. Encourage the traveler to ask a crew member when something concerns them.
6. Keep support concise enough that it does not compete with announcements.

The FAA advises passengers to follow cabin-crew instructions, pay attention to the safety briefing, and keep seat belts fastened while seated, especially during takeoff, landing, and turbulence.

Reference sources:

```text
https://www.faa.gov/travelers/fly_safe/safety_tips
https://www.faa.gov/travelers/fly_safe/turbulence
https://www.faa.gov/travelers/fly_safe
```

## Flight phases

### Takeoff

Sarah can acknowledge that takeoff may involve loud sounds, changes in angle, acceleration, and pressure without interpreting a particular aircraft sound.

For a young child, Sarah uses shorter language and offers:

- flower-and-candle breathing;
- a color hunt;
- trivia;
- a short sing-along;
- quiet company.

### Turbulence

Sarah reminds the traveler to stay seated, keep the belt fastened, and follow the crew. She can guide attention toward feet, shoulders, breathing, trivia, or a noticing game.

### Landing

Sarah supports the traveler through the final phase without promising an exact landing time or interpreting aircraft systems. She keeps the focus on the belt, crew instructions, breathing, and a bounded game.

### Quiet company

A traveler may choose `Just stay with me`. Sarah does not require them to explain the fear and does not turn the moment into a travel questionnaire.

## Gentle breathing

The adult default is:

```text
comfortable inhale: 4 counts
slightly longer exhale: 6 counts
cycles: 6
breath holding: none
```

The child default is:

```text
smell the pretend flower: 3 counts
blow out the pretend candle: 4 counts
cycles: 6
breath holding: none
```

The app tells the person not to force the breath and to return to ordinary breathing if counting feels uncomfortable or causes lightheadedness.

The longer, gentle exhale is consistent with public NHS calming-breath guidance. The feature is for ordinary stress management, not treatment.

Reference sources:

```text
https://oxfordhealth.nhs.uk/camhs/self-care/sleep/relaxation/breathing/
https://www.geh.nhs.uk/patients-and-visitors/patients/patient-leaflets/calming-hand
https://www.northumbria.nhs.uk/our-services/health-psychology/help-anxiety
```

## Offline games

### Profile-aware trivia

`CalmSupport.questions` uses:

- active person's age;
- locally approved interests;
- current or planned destination when allowed;
- general travel and safety questions.

A child receives simpler questions. An adult may receive city, rail, geography, history, or saved-interest questions.

The old unrequested John Wick question was removed.

### Color and noticing hunt

The prompts ask the traveler to notice colors, shapes, textures, letters, sounds, or points of physical support in the immediate environment.

### Alphabet travel game

The traveler chooses a category and names answers beginning with a sequence of letters. No network or factual answer database is required.

## Child profiles

The feature reads the currently active profile from `PersonProfileStore`.

For a child profile:

- language is shorter and family-friendly;
- personal memory remains governed by the profile's existing consent rules;
- the owner's private chat, bookings, loyalty identifiers, and unrelated trips are not shown;
- the child can use breathing, games, and songs without joining the owner's full profile.

## Public-domain sing-alongs

Sarah includes short first-verse sing-alongs for:

- `Twinkle, Twinkle, Little Star`;
- `Row, Row, Row Your Boat`;
- `Mary Had a Little Lamb`;
- `Baa, Baa, Black Sheep`.

The app does not copy a modern recording or modern arrangement. `OfflineSongCatalog.java` stores the old text and an original sequence of pitch and speech-rate changes. `SarahTts.java` performs each line with the phone's local Android voice.

The result depends on the installed TTS engine. On some phones it may sound like gentle singing; on others it may sound like rhythmic spoken lyrics. The interface calls this a sing-along and explains that limitation honestly.

### Rights basis

The U.S. Copyright Office states that works published in the United States before January 1, 1931 are in the public domain as of 2026.

Publication background:

- Jane Taylor's `The Star`, the source of `Twinkle, Twinkle, Little Star`, was published in 1806; the familiar French melody is older.
- `Row, Row, Row Your Boat` appeared in the 19th century, including the familiar modern tune by the 1880s.
- Sarah Josepha Hale's `Mary's Lamb` was published in 1830.
- `Baa, Baa, Black Sheep` is an 18th-century traditional nursery rhyme.

Only the old verse and Sarah's own runtime treatment are used. Do not replace them with a modern commercial recording or a modern copyrighted arrangement without permission.

Reference sources:

```text
https://copyright.gov/what-is-copyright/
https://en.wikisource.org/wiki/Row,_Row,_Row_Your_Boat
https://en.wikisource.org/wiki/Mary_Had_a_Little_Lamb
```

## Main source files

```text
FlightCalmActivity.java
FlightCalmButton.java
CalmSupport.java
OfflineSongCatalog.java
SarahTts.java
activity_main.xml
AndroidManifest.xml
```

## Tests

The pure-Java regression test is:

```text
Sarah_Morgan_Android_Phone_First_v3/tests/OfflineFlightCompanionTest.java
```

It checks:

- child takeoff language includes the seat belt and crew;
- adult landing language does not claim aircraft assessment;
- trivia has enough questions;
- the old John Wick fallback is absent;
- child and adult noticing games have multiple prompts;
- every bundled song has a rights note and bounded pitch/rate values;
- the expected songs are available offline.

The GitHub Actions workflow runs this test before Android compilation.

## Physical phone test

1. Install the current APK.
2. Open the airplane icon while online and verify the screen appears.
3. Turn on airplane mode and disable Wi-Fi.
4. Open takeoff, turbulence, and landing support.
5. Run all six breathing cycles.
6. Play several trivia questions.
7. Complete the noticing and alphabet games.
8. Test every song and the stop button.
9. Switch to a child profile and repeat.
10. Confirm Android speech is used offline even when ElevenLabs is selected in ordinary settings.
11. Confirm the screen remains responsive with large text enabled.
12. Confirm leaving the screen stops timers and speech.

## Known limitations

- Android TTS quality differs by phone and installed voice.
- The sing-along is a local TTS performance, not a prerecorded studio song.
- Sarah cannot hear cabin announcements unless the user repeats them.
- Sarah cannot determine the flight phase automatically without an authorized data source.
- Sarah cannot assess aircraft sounds, turbulence severity, safety, or medical symptoms.
- A person should seek help from the flight crew when they need assistance.
