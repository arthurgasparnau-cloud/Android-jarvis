# J.A.R.V.I.S. Android

Android companion app for the J.A.R.V.I.S. system.

- Latest APK: `JARVIS-Android-0.1.6-debug.apk`
- Android project source: `android-source/`
- Offline Hey JARVIS keyword detection uses sherpa-onnx.
- The app connects to the PC core over the private Tailscale HTTPS endpoint when available.
- The mobile app now monitors the authenticated PC connection while open and automatically retries/reloads conversations when the PC becomes reachable again.
- ElevenLabs credentials remain on the PC; the APK receives generated PCM audio only.
- Build: open `android-source/` in Android Studio or run `gradlew.bat assembleDebug` on Windows.
