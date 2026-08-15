# CampusLink Android Core Chat

Native Kotlin / Jetpack Compose client focused on the unified Core Chat. It consumes
`/api/chat/stream` and `/api/chat/resume`, rendering SSE tokens, Agent/Utility activity,
Lost & Found match cards, and HITL confirmations.

## Build

Use Android Studio, Android SDK 36, and JDK 17:

```bash
cd frontend_mobile
./gradlew testDemoDebugUnitTest lintDemoDebug detekt
./gradlew assembleDemoDebug
```

`localDebug` connects an emulator to `http://10.0.2.2:8080/`. `demoDebug` and
`prodRelease` connect only to `https://campuslink.tonywu.top/` with normal platform TLS validation.
The demo APK is produced at `app/build/outputs/apk/demo/debug/app-demo-debug.apk`.

`prodRelease` requires `CAMPUSLINK_RELEASE_STORE_FILE`,
`CAMPUSLINK_RELEASE_STORE_PASSWORD`, `CAMPUSLINK_RELEASE_KEY_ALIAS`, and
`CAMPUSLINK_RELEASE_KEY_PASSWORD`. The build fails if any value is missing; all
four values must come from local secure storage or CI secrets, never Git.

JWTs and the SQLCipher Room passphrase are protected by Android Keystore. Chat history is local,
encrypted, and isolated by account. The first release is text-only and does not include full native
Mail, Facilities, or Lost & Found screens.
