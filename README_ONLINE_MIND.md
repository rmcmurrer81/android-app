# Sarah 2.5 online mind and last-minute model switching

## Read this before the hackathon

The ordinary `Sarah-2.5-validated-APK` is **not** the judge build. It can use public web pages and local tools, but it was built without Sarah's full online conversation service.

For the judges, use the GitHub Actions artifact named:

```text
Sarah-2.5-ONLINE-JUDGE-APK
```

That artifact is created only after GitHub:

1. deploys Sarah's protected model proxy;
2. sends a real message through it;
3. receives exactly `ONLINE_READY` from the selected OpenAI model;
4. builds the APK with the tested proxy URL and a newly generated Sarah token;
5. verifies that the OpenAI API key is not inside the APK.

Sarah then behaves as intended:

```text
Internet available
    → Sarah automatically uses her online model

Airplane mode or no validated internet
    → Sarah automatically uses local/offline conversation and tools

Internet returns
    → Automatic mode tries Sarah's online model again on the next message
```

The person using Sarah does not enter an API key and does not need to switch modes manually.

---

## What Robert or a teammate must obtain

Three private values must be added to this GitHub repository's Actions secrets:

```text
OPENAI_API_KEY
CLOUDFLARE_ACCOUNT_ID
CLOUDFLARE_API_TOKEN
```

Do not put any of these values in a file, commit, issue, pull-request comment, screenshot, chat message, or APK.

The build workflow generates `SARAH_BACKEND_TOKEN` itself, sends it to Cloudflare as a secret, embeds only that short-lived token in the private judge APK, and rotates it every time the workflow is run.

### 1. Obtain the OpenAI API key

A ChatGPT subscription and OpenAI API billing are separate. A paid ChatGPT account does not automatically give the app API usage.

1. Sign in at <https://platform.openai.com/>.
2. Open the API billing page and add a payment method or prepaid credits. OpenAI currently states that the minimum prepaid purchase is $5.
3. Create a project for Sarah, such as `Sarah Hackathon`.
4. Open **API keys** inside that project.
5. Select **Create new secret key**.
6. Copy the key immediately and store it temporarily in a password manager. OpenAI shows the full secret only when it is created.
7. Add a small project budget or usage alert appropriate for the demonstration.

The key becomes the GitHub secret named `OPENAI_API_KEY`. It is uploaded from GitHub directly to the Cloudflare Worker and is never compiled into Sarah.

### 2. Obtain the Cloudflare account ID and deployment token

A free Cloudflare account is sufficient for the small hackathon proxy unless actual use exceeds Cloudflare's free limits.

1. Sign in at <https://dash.cloudflare.com/> or create an account.
2. Open the account where the Worker should live and copy its **Account ID**. Save it as `CLOUDFLARE_ACCOUNT_ID`.
3. Open **Manage Account → Account API Tokens**, or the user **API Tokens** page.
4. Select **Create Token**.
5. Use the **Edit Cloudflare Workers** template.
6. Restrict the token to only the account being used for Sarah.
7. Create it and copy the token immediately. Save it as `CLOUDFLARE_API_TOKEN`.

This token lets GitHub deploy the `sarah-model-proxy` Worker. It must never be committed to the repository.

### 3. Add the three GitHub Actions secrets

In `rmcmurrer81/android-app`:

1. Open **Settings**.
2. Open **Secrets and variables → Actions**.
3. Select **New repository secret**.
4. Add each exact name and value:

```text
OPENAI_API_KEY=<the OpenAI project key>
CLOUDFLARE_ACCOUNT_ID=<the Cloudflare account ID>
CLOUDFLARE_API_TOKEN=<the restricted Cloudflare Workers token>
```

GitHub does not reveal a stored secret afterward. If a value is uncertain, replace the secret rather than placing it in a comment for someone to inspect.

---

## Build the judge APK from the current PR branch

The online build workflow is stored at:

```text
.github/workflows/sarah-2.5-online-judge-build.yml
```

Because PR #21 is not merged, the reliable branch trigger is the build-request file.

1. Stay on branch `agent/sarah-2.5-event-ready`.
2. Copy:

```text
.github/ONLINE_JUDGE_BUILD_REQUEST.example.json
```

3. Create this file on the same branch:

```text
.github/ONLINE_JUDGE_BUILD_REQUEST.json
```

4. Use the desired model, for example:

```json
{
  "model": "gpt-5.1",
  "reason": "Sunday judge build"
}
```

5. Commit the file to `agent/sarah-2.5-event-ready`.
6. Open **Actions → Sarah 2.5 online judge build**.
7. Wait for the single job to finish. It intentionally fails instead of producing an APK when a secret is missing, Cloudflare deployment fails, OpenAI rejects the model, billing is unavailable, or the live `ONLINE_READY` proof fails.
8. Download the artifact:

```text
Sarah-2.5-ONLINE-JUDGE-APK
```

The artifact includes:

```text
Sarah-Morgan-2.5-ONLINE-JUDGE.apk
Sarah-Morgan-2.5-ONLINE-JUDGE.sha256
Sarah-Morgan-2.5-ONLINE-JUDGE-manifest.json
```

The manifest records the source commit, model requested by the APK, deployed backend URL, and successful online smoke test. It never contains the OpenAI key or Sarah backend token.

When the workflow is eventually present on the repository's default branch, the team can also use **Run workflow** and type a model ID without editing the request file.

---

## Change the model at the last minute

There are two supported methods.

### Fastest method: change the model without rebuilding Sarah

The Worker gives a server-side model choice priority over the model inside the APK.

1. Open Cloudflare **Workers & Pages**.
2. Open `sarah-model-proxy`.
3. Open **Settings → Variables and Secrets**.
4. Add or edit an ordinary text variable—not a secret—named:

```text
SARAH_OPENAI_MODEL
```

5. Enter an OpenAI API model ID, such as:

```text
gpt-5.1
```

or, for a faster and generally less expensive fallback:

```text
gpt-5-mini
```

6. Deploy the variable change.
7. Send Sarah a new message. No APK reinstall is needed.

Delete `SARAH_OPENAI_MODEL` to let each installed APK request its own build-time model again.

Model availability depends on the OpenAI project. If a model returns a model-not-found or access error, select a model shown for that project on the OpenAI models page and test again.

### Reproducible method: build a new APK with a different model

Edit `.github/ONLINE_JUDGE_BUILD_REQUEST.json`, change `model`, and commit it again. GitHub redeploys the proxy, rotates Sarah's backend token, proves the new model responds, and creates a new artifact.

This method is slower but gives the team a manifest and APK tied to the exact model choice.

---

## Judge demonstration script

### Online proof

1. Make sure airplane mode is off and the phone has validated Wi-Fi or cellular internet.
2. Open Sarah. The status should indicate that the internet and OpenAI connection are available; it must not say `OpenAI not included in this build`.
3. Ask a natural non-travel question, then a travel question that benefits from current information.
4. Confirm Sarah remembers that the active owner is Robert rather than `Phone owner` before presenting identity or memory features.

### Offline proof

1. Leave Sarah open.
2. Turn on airplane mode.
3. Wait for Android to report the connection loss.
4. Ask Sarah an ordinary question supported by her local conversation path or open the offline flight companion.
5. Explain that local/offline answers cannot know new live prices, schedules, or news and should not pretend otherwise.

### Automatic reconnection proof

1. Turn airplane mode off.
2. Wait for Wi-Fi or cellular data to reconnect.
3. Send the next message.
4. Automatic mode should try the online model again without opening Settings or entering a key.

Do this complete online → airplane mode → online sequence on the actual Samsung before showing the judges.

---

## Troubleshooting

### Banner says `Public web online` or `OpenAI not included in this build`

The wrong APK is installed. Install `Sarah-Morgan-2.5-ONLINE-JUDGE.apk` from the online-judge artifact. A public photo appearing only proves that public internet works; it does not prove the full conversation model was included.

### Workflow says a secret is missing

Add or replace the exact GitHub Actions secret named in the error. Secret names are case-sensitive.

### Cloudflare deployment fails

Confirm that:

- `CLOUDFLARE_ACCOUNT_ID` belongs to the same account authorized by the token;
- the token uses the **Edit Cloudflare Workers** template or has Workers Scripts edit permission;
- the token is restricted to, but still includes, the selected account.

### Live smoke test returns HTTP 401

- A Worker `401 unauthorized` usually means the generated Sarah backend token and deployed Worker do not match. Re-run the workflow so both are rotated together.
- An OpenAI `401` reported through the Worker means the OpenAI key is invalid, revoked, or saved incorrectly. Replace `OPENAI_API_KEY`.

### OpenAI returns HTTP 429 or billing errors

Check the API platform billing balance, project budget, and rate limits. ChatGPT billing does not fund API calls.

### Model not found or unavailable

Change the model to one enabled for the OpenAI project. `gpt-5.1` is the quality-oriented repository default; `gpt-5-mini` is the practical speed/cost fallback. Then rerun the build or set the Cloudflare model override.

### Android refuses to install over the earlier APK

The earlier APK may have a different debug signing key. Preserve Sarah's data first if needed, uninstall the earlier private-test package, and then install the online-judge APK. The workflow now uses the repository's stable Sarah debug-signing cache for future continuity.

### Online calls fail during the demonstration

Automatic mode falls back for that turn and tries online again on the next message after the connection is available. Do not claim that a fallback answer came from OpenAI. Check the status banner and the Worker's `/health` page.

---

## Security after the event

- Do not distribute the OpenAI key or Cloudflare deployment token.
- Do not embed the OpenAI key directly in the Android build.
- The judge workflow rotates the APK's Sarah backend token every run, but an APK token can still be extracted by a determined person.
- After the hackathon, rerun the workflow to rotate the Sarah token or replace the Cloudflare Worker secret.
- Revoke and replace any OpenAI or Cloudflare credential that appeared in a screenshot, message, log, or committed file.
- Set API budgets and review usage after public demonstrations.

## Source files the team may need

```text
services/sarah-model-proxy/src/index.js
services/sarah-model-proxy/wrangler.jsonc
services/sarah-model-proxy/README.md
.github/workflows/sarah-2.5-online-judge-build.yml
.github/ONLINE_JUDGE_BUILD_REQUEST.example.json
Sarah_Morgan_Android_Phone_First_v3/android-app/app/build.gradle
Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/java/com/kiraworld/sarahtravel/SarahModelConfig.java
Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/java/com/kiraworld/sarahtravel/ConnectedModelGateway.java
Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/java/com/kiraworld/sarahtravel/SarahBackendClient.java
```

This design keeps the provider key on the server, makes model selection editable under time pressure, and preserves Sarah's automatic online/offline routing.
