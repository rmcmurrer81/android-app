# Sarah Morgan ElevenLabs Voice Setup

This guide is for the Sarah Travel OS hackathon team. The person who installs Sarah is **not** asked to enter an ElevenLabs key, choose a provider, or paste a voice ID into the Android app.

Sarah Travel OS 2.0 uses:

```text
Online premium voice: ElevenLabs
Voice display name: Sarah Morgan
Default model: eleven_multilingual_v2
Output: MP3 44.1 kHz, 128 kbps
Offline/error fallback: Android text-to-speech
```

The supplied Sarah Morgan Voice Design samples were generated with approximately:

```text
Speed: 1.00
Stability: 0.50
Similarity: 0.75
Style exaggeration: 0.00
Speaker boost: on
```

Those values are implemented in:

```text
Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/java/com/kiraworld/sarahtravel/ElevenLabsVoiceConfig.java
```

## What the API key name means

Naming the key **Sarah Morgan** is completely fine. The name is only a label shown inside your ElevenLabs account. It does not connect the key to the voice automatically.

Sarah still needs two different values:

1. an ElevenLabs **API key**, which authorizes text-to-speech requests;
2. the **voice ID** for the saved Sarah Morgan voice.

Never paste the API key into a chat, README, issue, pull request, Java file, screenshot, or public message.

## Create the restricted ElevenLabs key

In ElevenLabs:

1. Open **Developers**.
2. Open **API Keys**.
3. Choose **Create API key**.
4. Use a label such as `Sarah Morgan` or `Sarah Travel OS Backend`.
5. Restrict the key to **Text to Speech** for the first integration.
6. Set a modest credit limit for development.
7. Create the key.
8. Copy the secret value immediately and keep that browser tab open until it is saved in GitHub.

The display name can be Sarah Morgan. What matters is the secret value that ElevenLabs displays after creation.

## Copy the Sarah Morgan voice ID

In ElevenLabs:

1. Open **Voices**.
2. Open **My Voices**.
3. Find the saved voice named **Sarah Morgan**.
4. Open the voice menu, usually represented by three dots or a details button.
5. Choose **Copy voice ID**.

The voice ID is not the same as the display name. It is an identifier similar to a long group of letters and numbers.

A voice ID is safe to show to the development team. The API key is not.

## Add the two values to GitHub

On GitHub, open:

```text
rmcmurrer81/android-app
```

Then:

1. Open **Settings** for the repository.
2. Open **Secrets and variables**.
3. Open **Actions**.
4. Under **Repository secrets**, choose **New repository secret**.

Create this secret:

```text
Name: SARAH_ELEVENLABS_API_KEY
Secret: the ElevenLabs API key value
```

Create another secret:

```text
Name: SARAH_ELEVENLABS_VOICE_ID
Secret: the copied voice ID for Sarah Morgan
```

The model is already defaulted in source to:

```text
eleven_multilingual_v2
```

An optional repository secret may override it:

```text
Name: SARAH_ELEVENLABS_MODEL_ID
Secret: eleven_multilingual_v2
```

After adding the secrets, open **Actions**, select **Build Sarah Android APK**, and run the workflow again. The new APK will include the private hackathon ElevenLabs connection.

## What happens without the secrets

The APK still builds and works. Sarah uses Android text-to-speech.

This makes voice fail safely:

```text
ElevenLabs available + internet → Sarah Morgan premium voice
ElevenLabs unavailable, out of credits, slow, or offline → Android voice
```

Sarah never becomes unable to speak merely because a cloud service is unavailable.

## Private hackathon build versus public release

The repository supports a direct key in a private test APK because it is the simplest way to make a bounded hackathon demonstration work. A key inside an APK can potentially be extracted.

For any public release, use:

```text
Sarah Android app
    → authenticated HTTPS
Sarah voice backend
    → ElevenLabs
```

The protected-backend build values are:

```text
SARAH_ELEVENLABS_BACKEND_URL
SARAH_ELEVENLABS_BACKEND_TOKEN
SARAH_ELEVENLABS_VOICE_ID
SARAH_ELEVENLABS_MODEL_ID
```

The backend should hold the real ElevenLabs API key. It should add authentication, device or account authorization, rate limits, spending limits, abuse controls, monitoring, secret rotation, and privacy/deletion controls.

## Relevant Android source files

```text
ElevenLabsVoiceConfig.java
CloudVoiceClient.java
SettingsActivity.java
activity_settings.xml
MainActivity.java
app/build.gradle
.github/workflows/build-apk.yml
```

`CloudVoiceClient` keeps its older class name so existing Sarah code remains compatible. It now sends speech to ElevenLabs rather than OpenAI TTS.

## Voice request behavior

The Android voice adapter:

- calls the ElevenLabs streaming text-to-speech endpoint;
- requests MP3 44.1 kHz at 128 kbps;
- sends the Sarah Morgan Voice Design settings;
- normalizes links and basic Markdown before speech;
- stops a previous Sarah reply when a new one begins;
- deletes temporary MP3 files after playback;
- limits oversized responses;
- falls back to Android speech when a request fails.

## Suggested voice tests

Test these exact categories on a physical phone:

### Normal conversation

```text
Hi, Robert. We can talk about the trip, or we can talk about something completely different.
```

### Dates and transportation

```text
Your train leaves Newark Penn Station at 8:42 a.m. on August 19.
```

### Prices

```text
The complete hotel price is 268 dollars and 14 cents after mandatory fees.
```

### Calm support

```text
Keep your seat belt fastened and follow the crew's instructions. I can stay with you or start some trivia.
```

### Place names

```text
The route goes from Newark Liberty International Airport to the Indiana Convention Center.
```

### Long answer interruption

Send a second message while Sarah is still speaking. The old playback should stop before the new reply begins.

### Offline fallback

Turn on airplane mode and ask Sarah a Local question. She should continue with Android speech.

## Voice credits and response length

Every generated character consumes ElevenLabs usage. Sarah should not read huge source dumps or long hotel lists aloud by default. The visual interface can show full detail while the voice gives a concise summary.

Recommended behavior:

```text
Speak: the conclusion, best options, warnings, and one next step
Show on screen: complete comparisons, source notes, links, and detailed lists
```

## Hotel voice concierge is separate

Sarah's ordinary ElevenLabs speaking voice does not automatically place phone calls.

The supervised hotel-call system uses a separate team backend and these existing build values:

```text
SARAH_VOICE_CONCIERGE_URL
SARAH_VOICE_CONCIERGE_TOKEN
```

A future ElevenLabs Agents integration should:

- disclose that it is an AI travel assistant when appropriate;
- use a traveler-approved phone number and script;
- require explicit confirmation before initiating a call;
- never provide card details;
- never authorize charges;
- never cancel or change a booking without verified traveler confirmation;
- return a transcript or structured result for review;
- distinguish submitted, connected, acknowledged, hotel-confirmed, and failed states.

## Troubleshooting

### Sarah still uses Android voice

Check:

- both GitHub repository secrets exist;
- the voice ID is the actual copied ID, not the words `Sarah Morgan`;
- the key has Text to Speech permission;
- the key has available credits;
- the newest APK was rebuilt after adding the secrets;
- Sarah Settings has `Sarah Morgan ElevenLabs voice` selected;
- the phone has internet access.

### The GitHub log says voice ID not configured

Add:

```text
SARAH_ELEVENLABS_VOICE_ID
```

and run the workflow again.

### ElevenLabs returns an authorization error

Create or repair the restricted API key and replace the GitHub secret. Do not expose the key in logs or screenshots.

### Numbers sound wrong

Keep `eleven_multilingual_v2`, review Sarah's number-normalization text, and test dates, prices, phone numbers, confirmation codes, and transit times before the demonstration.

### The voice is too slow or too dramatic

Edit only the constants in `ElevenLabsVoiceConfig.java`, rebuild, and compare the same fixed test sentences. Avoid changing several settings at once.

## Secret-handling checklist

- [ ] API key stored only as a GitHub secret or server environment variable
- [ ] key restricted to required endpoints
- [ ] credit limit set
- [ ] voice ID stored separately
- [ ] no key in Git history
- [ ] no key in screenshots
- [ ] no key in issue or PR comments
- [ ] Android fallback tested
- [ ] key rotated after a private demo if it was embedded in an APK
