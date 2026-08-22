# UgisF1

Android TV client for F1 TV — remote-friendly Leanback UI, sideload install (`com.ugisf1.tv`).

**Current release:** `2.0.1` (versionCode 201)

Not affiliated with Formula 1 or F1 TV.

## What it does

- Browse current season, archive years, and **Shows & docs** (editorial F1 TV pages).
- Play main feeds, onboard cameras, Tracker/Data, pit lane, and other session channels.
- **In-player channel switch** — change camera/feed without leaving playback.
- **Multiview layouts** — fullscreen, side OBC strip, or quad (pit/tracker/data/OBC mix).
- Live race **map shortcut** (Tracker feed) when the session is live.
- Custom radio with sync delay; built-in streams or your own URL in Settings.
- Series filter (F1 / F2 / F3 / Academy / Porsche) on home and season browse.
- Race calendar (Amsterdam), championship standings, and race results (Jolpica).
- HDR / 4K paths when subscription and device allow.

## Requirements

- Android TV or Google TV, Android 9+ (API 28+).
- Valid F1 TV subscription.
- Sideloading enabled for APK install.

## Install

### Prebuilt APK

Release APK name: `com.ugisf1.tv-2.0.1.apk` (from `:app:assembleRelease`).

Install via ADB or your TV file manager / installer.

### Build from source

1. JDK 17+ and Android SDK (see `local.properties` for `sdk.dir`).
2. Clone this repo.
3. Optional: root `.env` for build-time defaults (see below).
4. Optional: release signing in `signing.local.properties` (see `gradle.properties.signing.example` — **never commit passwords**).
5. Build:

```bash
./gradlew :app:assembleRelease
# or debug:
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/release/com.ugisf1.tv-2.0.1.apk`

If no release signing is configured, the release build uses the debug keystore (installable for local testing only).

## Optional `.env`

```dotenv
F1_username=
F1_password=
TOKEN_REFRESH_INTERVAL_MS=
CUSTOM_RADIO_URL=
```

Sign-in on device is the normal path; `.env` only supplies optional build-time fallbacks.

## Custom radio

1. **Settings → Custom Radio URL** (optional).
2. During playback: **Audio → Custom Radio**.
3. Adjust delay via **Radio sync** or Settings → Radio sync delay.
4. Enable **Prefer custom radio** for auto-start on live sessions.

Built-in fallbacks are used when no URL is saved.

## Development

- Kotlin, Hilt, Room, Moshi, Media3/ExoPlayer, Leanback.
- Useful tasks: `assembleDebug`, `assembleRelease`, `installDebug`, `installRelease`.

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

## Credits

Fork lineage: [st14n/race-control-tv](https://github.com/st14n/race-control-tv), [Groggy](https://github.com/Groggy), [f1viewer](https://github.com/SoMuchForSubtlety/f1viewer), [leonardoxh](https://github.com/leonardoxh), and contributors noted in upstream history.
