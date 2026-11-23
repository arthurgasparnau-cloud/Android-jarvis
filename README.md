# J.A.R.V.I.S. Android

Android companion app for the J.A.R.V.I.S. system.

- Latest APK: `JARVIS-Android-0.3.1-debug.apk`
- Android project source: `android-source/`
- Voice activation uses Android SpeechRecognizer in a foreground microphone service with Portuguese (Brazil) recognition.
- Voice commands are processed by the foreground service, independently of the app screen.
- Brian playback requests Android media audio routing and transient audio focus, then resumes voice recognition.
- Google, Chrome and YouTube voice shortcuts are recognized. If Android blocks an app launch from the background, the service adds a direct **Abrir** action to its persistent notification; Android requires a user gesture in that situation.
- The app connects to the PC core over the private Tailscale HTTPS endpoint when available.
- ElevenLabs credentials remain on the PC; the APK receives generated PCM audio only.
- Build: open `android-source/` in Android Studio or run `gradlew.bat assembleDebug` on Windows.

## 0.3.1

- Improved background app-launch handling with a notification action that opens the requested app after a user tap when Android blocks background Activity launches.
- Expanded Portuguese app aliases for YouTube, Google and Chrome.
- Routed Brian PCM playback through the normal media stream and requested transient audio focus to avoid silent or misrouted output.
- Voice failures now appear in the persistent notification and service status.
- Verified Android debug build and mobile API tests (11/11 passing). Physical-device playback and launch testing is still pending.

## 0.3.0

- Moved background voice command execution and Brian playback into the foreground service.
- Added response persistence and improved Google speech recognition restart.
