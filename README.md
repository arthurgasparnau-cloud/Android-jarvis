# J.A.R.V.I.S. Android

Android companion app for the J.A.R.V.I.S. system.

- Latest APK: `JARVIS-Android-0.3.3-debug.apk`
- Android project source: `android-source/`
- Voice activation uses Android SpeechRecognizer in a foreground microphone service with Portuguese (Brazil) recognition.
- Voice commands are processed by the foreground service, independently of the app screen.
- Brian playback requests Android media audio routing and transient audio focus, then resumes voice recognition.
- Google, Chrome and YouTube voice shortcuts are recognized. Background app opening is presented as a persistent notification action because Android may block direct Activity launches from background services.
- Close-app commands offer a notification action to return to the Android home screen. Android does not let ordinary apps force-stop other apps.
- Network requests now always disconnect cleanly, report non-JSON HTTP errors clearly, and use bounded timeouts.
- Brian playback validates PCM format, checks AudioTrack initialization, limits stalled writes, and always releases audio focus even if track setup fails.
- The app connects to the PC core over the private Tailscale HTTPS endpoint when available.
- ElevenLabs credentials remain on the PC; the APK receives generated PCM audio only.
- Build: open `android-source/` in Android Studio or run `gradlew.bat assembleDebug` on Windows.

## 0.3.3

- Hardened PC-core HTTP calls with reliable connection cleanup, bounded timeouts, and clearer handling of HTTP and malformed responses.
- Hardened Brian PCM playback validation and AudioTrack lifecycle, including audio-focus cleanup after initialization failures.
- Added protection against a stalled audio output loop.
- Verified Android debug build. Physical-device testing is still pending.

## 0.3.2

- Background app-opening no longer relies on Android throwing an exception. The notification action is created proactively and remains visible while the voice service is listening.
- Added background close/exit commands that offer a persistent **Ir para tela inicial** action.
- Clarified Android's restriction: the app can navigate home but cannot force-stop another app.
- Verified Android debug build. Physical-device testing is still pending.

## 0.3.1

- Improved background app-launch handling with a notification action that opens the requested app after a user tap when Android blocks background Activity launches.
- Expanded Portuguese app aliases for YouTube, Google and Chrome.
- Routed Brian PCM playback through the normal media stream and requested transient audio focus to avoid silent or misrouted output.
- Voice failures now appear in the persistent notification and service status.
- Verified Android debug build and mobile API tests (11/11 passing). Physical-device playback and launch testing is still pending.

## 0.3.0

- Moved background voice command execution and Brian playback into the foreground service.
- Added response persistence and improved Google speech recognition restart.
