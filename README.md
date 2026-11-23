# J.A.R.V.I.S. Android

Android companion app for the J.A.R.V.I.S. system.

- Latest APK: `JARVIS-Android-0.3.4-debug.apk`
- Android project source: `android-source/`
- Voice activation uses Android SpeechRecognizer in a foreground microphone service with Portuguese (Brazil) recognition.
- Voice commands are processed by the foreground service, independently of the app screen.
- Brian playback requests Android media audio routing and transient audio focus, then resumes voice recognition.
- Google, Chrome and YouTube voice shortcuts are recognized. Background app opening is presented as a persistent notification action because Android may block direct Activity launches from background services.
- Close-app commands offer a notification action to return to the Android home screen. Android does not let ordinary apps force-stop other apps.
- Camera capture now requests the original image through a private, URI-granted content provider instead of relying on the small thumbnail returned by camera apps.
- Camera images are resized to a 1600-pixel maximum edge and JPEG-compressed to fit the authenticated vision API payload limit.
- The PC vision core analyzes images locally through Ollama `qwen3.5:4b`; the Android app sends them through the private Tailscale connection.
- Network requests now always disconnect cleanly, report non-JSON HTTP errors clearly, and use bounded timeouts.
- Brian playback validates PCM format, checks AudioTrack initialization, limits stalled writes, and always releases audio focus even if track setup fails.
- ElevenLabs credentials remain on the PC; the APK receives generated PCM audio only.
- Build: open `android-source/` in Android Studio or run `gradlew.bat assembleDebug` on Windows.

## 0.3.4

- Replaced thumbnail-only camera capture with a full-resolution capture flow using a non-exported content provider and temporary URI permissions.
- Added a compatibility fallback for camera apps that return only a thumbnail.
- Increased vision input detail to a 1600-pixel maximum edge with adaptive JPEG compression under the existing 5 MB Base64 limit.
- Verified Android debug build. Physical-camera testing is still pending.

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
