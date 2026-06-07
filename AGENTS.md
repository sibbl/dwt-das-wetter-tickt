# DWT Contributor Guide

## Project

DWT – Das Wetter tickt is a standalone Wear OS app for viewing animated rain,
cloud, and lightning data on a round watch display. The package/application ID is
`net.sibbl.dwt`.

The app fetches DWD WarnWetter raster data directly on the watch. It supports
time scrubbing, playback, layer toggles, map pan/zoom, optional location
centering, automatic refresh, disk caching, and decoded-frame prefetching.

Keep the app lightweight, readable on small round screens, and usable without
location permission.

## Architecture

- `app/src/main/java/net/sibbl/dwt/ui/`
  - `RadarScreen.kt`: Compose rendering and gestures.
  - `RadarViewModel.kt`: app state, loading, playback, refresh, caching, and prefetch orchestration.
  - `RadarUiState.kt`: user-visible state contract.
- `app/src/main/java/net/sibbl/dwt/data/radar/`
  - `RadarBackend.kt`: DWD endpoint and layer identifiers.
  - `RadarRepository.kt`: overview/asset download, disk and bitmap caches, frame decoding.
  - `RadarTimelineBuilder.kt`: converts DWD overview sections into ordered frame timelines.
  - `RadarFrameColorizer.kt`: converts source data into visible rain/cloud/lightning overlays.
- `app/src/main/java/net/sibbl/dwt/map/`
  - Geographic projection, viewport transforms, outlines, and city labels.
- `app/src/main/java/net/sibbl/dwt/location/`
  - Optional device location lookup.
- `app/src/main/java/net/sibbl/dwt/storage/`
  - Locally persisted camera, layer, and last-location preferences.
- `app/src/main/java/net/sibbl/dwt/model/`
  - Small immutable domain models.

Data flow:

1. `RadarRepository` fetches and parses the DWD overview.
2. `RadarTimelineBuilder` creates rain/cloud/lightning timelines and identifies “now”.
3. `RadarViewModel` selects, prefetches, decodes, and caches nearby frames.
4. `RadarScreen` draws map context and decoded radar bitmaps through `MapViewport`.

## Important Rules

- Preserve the existing geographic projection unless verified against real DWD
  raster bounds. Projection changes require focused viewport/projection tests
  and visual verification on the watch.
- Prefetch must prepare fully decoded frames, not only downloaded assets.
  Prioritize the selected segment and previous segment before future segments.
- Keep rain and matching cloud/lightning frames navigation-near and ready together.
- Location remains optional and must not be sent to the developer or DWD.
- Never commit keystores, credentials, tokens, signing passwords, APKs, or AABs.
- Do not remove or weaken DWD attribution, independence wording, privacy
  disclosures, or the confirmed-permission record without explicit instruction.
- `decompiled/` and `dwd-app.apk` are historical reverse-engineering inputs.
  Do not modify or depend on them in production code unless explicitly needed.

## Documentation

- `README.md`: concise public project overview, controls, and basic build usage.
- `DATA_SOURCES.md`: DWD attribution, rights, and publication-permission status.
- `PRIVACY.md`: public privacy policy used for distribution.
- `docs/releasing.md`: signing, GitHub release, and Google Play workflow.
- `store-assets/`: Play Store icon, graphics, screenshots, and localized listing copy.
- `.agents/skills/release-dwt/`: release preflight and publishing workflow.
- `.agents/skills/dwd-warnwetter-reference/`: historical WarnWetter backend,
  payload, rendering, and Wear OS reverse-engineering reference. Use it only
  for relevant backend/rendering tasks.

Keep user-facing documentation concise. Put reusable agent procedures in a
skill and detailed human-facing operational documentation under `docs/`.

## Build And Test

Requirements: JDK 17 and Android SDK 35.

Run focused tests while iterating, then validate relevant changes with:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Important test locations:

- `app/src/test/`: unit tests for timelines, projection, viewport, gestures, and refresh behavior.
- `app/src/androidTest/`: watch UI/instrumentation tests.

For visual or interaction changes, install the debug APK on a connected Wear OS
device and verify the round-screen layout:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Release

GitHub tags use `vMAJOR.MINOR.PATCH` and are the source of truth for release
version names. Before releasing:

```sh
.agents/skills/release-dwt/scripts/preflight.sh vMAJOR.MINOR.PATCH
```

Read `docs/releasing.md` before changing versioning, signing, GitHub Actions,
store assets, or Google Play configuration.
