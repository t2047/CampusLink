# CampusLink Android Core Chat

Native Kotlin / Jetpack Compose client built around the unified Core Chat, with native
Facilities and Lost & Found service flows. It consumes
`/api/chat/stream` and `/api/chat/resume`, rendering SSE tokens, Agent/Utility activity,
Lost & Found match cards, and HITL confirmations. The native Lost & Found module supports
browsing, filtering, details, LOST/FOUND multipart reports, and the claim review workflow.

## Build

Use Android Studio, Android SDK 36, and JDK 17:

```bash
cd frontend_mobile
./gradlew testDemoDebugUnitTest lintDemoDebug detekt
./gradlew assembleDemoDebug
```

`localDebug` connects an emulator to `http://10.0.2.2:8080/`. `demoDebug` and
`prodRelease` connect only to `https://campuslink.tokeninf.xyz/` with normal platform TLS validation.
The demo APK is produced at `app/build/outputs/apk/demo/debug/app-demo-debug.apk`.

`prodRelease` requires `CAMPUSLINK_RELEASE_STORE_FILE`,
`CAMPUSLINK_RELEASE_STORE_PASSWORD`, `CAMPUSLINK_RELEASE_KEY_ALIAS`, and
`CAMPUSLINK_RELEASE_KEY_PASSWORD`. The build fails if any value is missing; all
four values must come from local secure storage or CI secrets, never Git.

JWTs and the SQLCipher Room passphrase are protected by Android Keystore. Chat history is local,
encrypted, and isolated by account. Core Chat remains text-only. Mail does not yet have native
screens; Facilities and Lost & Found are being delivered in phased native modules.
