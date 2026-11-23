# J.A.R.V.I.S. Android

Android companion app for the local J.A.R.V.I.S. desktop core.

- Latest APK: `JARVIS-Android-0.1.5-debug.apk`
- Android project source: `android-source/`
- Wake-word detection runs locally with sherpa-onnx.
- The app connects to the PC core over the private Tailscale HTTPS endpoint.
- ElevenLabs credentials remain on the PC; the APK receives generated PCM audio only.
- Build: open `android-source/` in Android Studio or run `gradlew.bat assembleDebug` on Windows.
