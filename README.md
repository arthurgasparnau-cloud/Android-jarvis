# J.A.R.V.I.S. Android

Android companion app for the J.A.R.V.I.S. system.

- Latest APK: `JARVIS-Android-0.3.0-debug.apk`
- Android project source: `android-source/`
- Voice activation uses Android SpeechRecognizer in a foreground microphone service with Portuguese (Brazil) recognition.
- Voice commands are processed by the foreground service, independently of the app screen, and responses are returned to the interface when available.
- Brian audio playback runs in the foreground service and the Google recognizer is restarted after playback.
- Common phone app shortcuts include Google, Chrome and YouTube.
- The app connects to the PC core over the private Tailscale HTTPS endpoint when available.
- ElevenLabs credentials remain on the PC; the APK receives generated PCM audio only.
- Build: open `android-source/` in Android Studio or run `gradlew.bat assembleDebug` on Windows.

## 0.3.0

- Moved background voice command execution and Brian playback into the foreground service, removing the requirement for MainActivity to remain alive for core requests.
- Added response persistence so replies can appear when the app is reopened.
- Improved relaunch of Google speech recognition after a command response.
- Added direct app launch matching for Google, Chrome and YouTube.
