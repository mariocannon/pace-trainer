# CLAUDE.md — Pace Trainer

Native Android app (Kotlin + Jetpack Compose) that plays audible click cues to keep a runner on a target km pace, using GPS. It must work with the phone locked.

**Read `PLAN.md` first.** It has the full spec, architecture, pace algorithm, cue design and milestones. Update its Status checklist as milestones land.

## Hard constraints
- **No Google Play Services.** Use `LocationManager.GPS_PROVIDER`, not Fused Location.
- **$0.** No paid APIs, no backend, no network calls, no analytics.
- Use a foreground service (`foregroundServiceType="location"`) so it works screen-off.
- No run tracking or history. Keep the app lightweight.
- Keep `engine/PaceEngine.kt` free of Android imports, with unit tests.

## Build
- `./gradlew assembleDebug` builds locally.
- `./gradlew test` runs the PaceEngine unit tests.
- CI: `.github/workflows/build.yml` builds a signed release APK on push to `main`.
  - Signing comes from repo secrets: `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
- Distribution: sideload the APK from the Actions artifact or Release.

## Working style
- The owner (Mario) prefers casual, direct communication.
- Work milestone by milestone (M1 → M4 in PLAN.md).
- After each milestone, say what to test on a real run.
