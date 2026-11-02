# Pace Trainer — Build Plan

Lightweight Android app for running pace training. You set a target km pace; the app listens to GPS and plays audible click cues to keep you on pace. No run tracking, no maps, no accounts.

## Status
- [x] Requirements and architecture agreed
- [x] GitHub repo created (mariocannon/pace-trainer)
- [ ] M1 – Service + GPS + pace in notification (code written, needs a CI build and a screen-locked test walk/run)
- [ ] M2 – Click engine + cue logic + music ducking
- [ ] M3 – Settings screen + pace picker
- [ ] M4 – Battery optimisation prompt + polish

## Requirements

### Must have
- Set target pace in **min:sec per km**
- Audible cues:
  - **Fast clicks** when too slow, meaning "speed up"
  - **Slow clicks** when too fast, meaning "slow down"
- Phone-based, using GPS for pace
- **Works with the phone locked** in a pocket or armband
- Android only

### Explicitly not needed
- Run tracking, history, maps, routes, accounts, cloud

### Constraints
- **$0 cost.** No paid APIs, no backend, no Play Store.
- **No Google Play Services.** Use Android's built-in `LocationManager` with `GPS_PROVIDER`, not the Fused Location library.
- No network access needed at all.
- Distribute by sideloading the APK that GitHub Actions builds.

## Key decisions

| Decision | Choice | Why |
|---|---|---|
| Platform | Native Kotlin | Most reliable for screen-off operation; JS timers (Expo/RN) get throttled when locked |
| UI | Jetpack Compose, single Activity | Small and modern |
| Background | Foreground service, `foregroundServiceType="location"` | Survives the screen being locked; persistent notification required |
| Location | `LocationManager.GPS_PROVIDER`, 1 s interval | No Google dependency, free |
| Location permission | `ACCESS_FINE_LOCATION` only | The service is started while the app is visible, so `ACCESS_BACKGROUND_LOCATION` isn't needed |
| Audio | `AudioTrack` playing generated sine-burst clicks | No asset files, precise timing, tiny APK |
| Timing | Dedicated `HandlerThread` plus `PARTIAL_WAKE_LOCK` while running | Click timing stays tight with the screen off |
| Music | Request audio focus `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` | Spotify and podcasts duck under the clicks |
| Settings | `SharedPreferences` | Lighter than DataStore for a few values |
| SDK | minSdk 26, target/compile latest stable | Covers almost all current phones |
| Distribution | GitHub Actions builds the APK, then sideload | Free |

## Architecture

```
app/src/main/java/.../pacetrainer/
├── MainActivity.kt          # Compose UI, permissions, start/stop service
├── ui/PaceScreen.kt         # Picker, live pace, GPS indicator, start/stop
├── service/PaceService.kt   # Foreground service: GPS → engine → audio
├── engine/PaceEngine.kt     # PURE Kotlin: smoothing + cue state (unit-tested)
├── audio/ClickPlayer.kt     # AudioTrack click generation + scheduling + focus
└── data/Settings.kt         # SharedPreferences wrapper
```

- **PaceService** owns everything while running.
- The **UI** observes service state through a `StateFlow` exposed via a bound service or a singleton holder.
- **PaceEngine** has no Android dependencies, so the pace and cue logic can be unit-tested with fake location sequences.

## Pace algorithm (PaceEngine)

1. **Filter fixes:**
   - Drop any fix with `accuracy > 20 m`.
   - Report state `NO_GPS` until 3 consecutive good fixes arrive.
2. **Speed:**
   - Keep a rolling window of the last **15 s** of good fixes.
   - Prefer `location.speed` when `hasSpeed()` (Doppler-based, less noisy), averaged over the window.
   - Otherwise, use haversine distance summed across the window divided by elapsed time.
3. **Smoothing:** apply an EMA on top of the window (alpha ≈ 0.3).
4. **Pace:** `paceSecPerKm = 1000 / speedMps`.
   - If speed is below 0.8 m/s, report state `STOPPED` and play no cues.
5. **Deviation:** `delta = currentPace − targetPace` in seconds.
   - Positive means slower than target.
6. **States:**
   - `ON_PACE` when |delta| ≤ tolerance (default **8 s/km**)
   - `TOO_SLOW` when delta > tolerance
   - `TOO_FAST` when delta < −tolerance
7. **Hysteresis:**
   - The new state must persist **5 s** before cues change.
   - Ignore the first **20 s** after start (warm-up and GPS settling); make this configurable later.

## Cue design (ClickPlayer)

| State | Sound | Rate |
|---|---|---|
| TOO_SLOW | High click (~1500 Hz, 25 ms) | Interval scales from **600 ms** at the tolerance edge to **250 ms** at 30+ s/km off |
| TOO_FAST | Low click (~600 Hz, 40 ms) | Interval scales from **1500 ms** to **900 ms** |
| ON_PACE | Soft tick | One every 30 s, as a reassurance that the app is alive |
| NO_GPS | Double beep | Every 10 s |
| STOPPED | Silence | — |

- Generate the click as a sine burst with a short attack/decay envelope, so it doesn't pop.
- Use `AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, which ducks music nicely.
- Request audio focus when entering TOO_SLOW or TOO_FAST, and abandon it when back to ON_PACE. Don't request focus per click.
- Volume comes from settings.

## Manifest permissions

```
ACCESS_FINE_LOCATION
ACCESS_COARSE_LOCATION
FOREGROUND_SERVICE
FOREGROUND_SERVICE_LOCATION
POST_NOTIFICATIONS              (Android 13+)
WAKE_LOCK
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
```

Declare the service with `android:foregroundServiceType="location"`.

## UI (single screen)

- **Target pace picker:** mm:ss per km, with min and sec wheels or +/- buttons in 5 s steps.
- **Tolerance:** ±5 / 8 / 12 s.
- **Big live pace readout,** coloured by state:
  - green for on pace
  - amber for too slow
  - blue for too fast
  - grey for no GPS or stopped
- **GPS lock indicator** showing accuracy in metres.
- **Start/Stop button.** Start is disabled until location permission is granted.
- **Notification:**
  - Shows the current pace and target.
  - Has a Stop action.

## Battery / OEM gotcha

Samsung and other OEMs kill background apps aggressively. On first start:
- Check `PowerManager.isIgnoringBatteryOptimizations()`.
- If it returns false, prompt with `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- Explain in one line why it's needed.

## Build & distribution

- **Gradle:** Kotlin DSL with a version catalog (`libs.versions.toml`).
- **GitHub Actions** (`.github/workflows/build.yml`):
  - Runs on every push to `main`.
  - `actions/setup-java` with JDK 17, then `./gradlew assembleRelease`.
  - Uploads the APK as a workflow artifact.
  - Optionally attaches the APK to a GitHub Release on tags.
- **Signing gotcha:**
  - A debug keystore generated on CI changes every run, so the phone would refuse to install updates over the old app.
  - Generate one release keystore once.
  - Store it base64-encoded in repo secrets (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).
  - Sign the release build with it in CI.
- **Cost:** Actions are free for public repos, and private repos get about 2,000 free minutes a month.
- **Install:**
  - Download the APK on the phone.
  - Allow "install unknown apps" for the browser or Files app.
  - Install the APK.

## Milestones and acceptance tests

1. **M1 – Service + GPS**
   - Build: start a foreground service and show the live smoothed pace in the notification.
   - Test: walk or run with the screen locked for 10+ minutes. The notification keeps updating and the service isn't killed.
2. **M2 – Cues**
   - Build: ClickPlayer plus state logic and ducking.
   - Unit tests: PaceEngine produces the correct states for synthetic fast, slow, on-pace and noisy-GPS sequences.
   - Test: on a real run, deliberate surges and slowdowns trigger the right cues within about 5–10 s, and music ducks.
3. **M3 – UI**
   - Build: picker, tolerance setting and live readout. Settings persist across restarts.
4. **M4 – Polish**
   - Battery optimisation prompt.
   - Notification Stop action.
   - Volume setting.
   - App icon.

## Later ideas (not MVP)
- Interval mode, e.g. 4 × 1 km at 4:30 with 2 min jog recoveries
- Spoken pace every km using Android's built-in TTS (free, offline)
- Saved pace presets
- Cadence metronome mode
