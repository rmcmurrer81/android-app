# ElevenLabs-Enabled Build Marker

This marker triggers a fresh Sarah Travel OS 2.0 GitHub Actions build after the repository-level ElevenLabs text-to-speech credential was configured.

The credential remains in GitHub Actions secrets and is not stored in this repository. The Sarah Morgan Voice Design ID is selected in `ElevenLabsVoiceConfig.java`. The build workflow reports only whether the service is configured and never prints the secret value.

Expected behavior in the resulting private hackathon APK:

- Sarah Morgan ElevenLabs voice while online and the service is available;
- Android text-to-speech fallback while offline or when the service fails;
- no API-key field for people installing the app.
