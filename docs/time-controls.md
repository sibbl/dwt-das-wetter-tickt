# Time-pill controls

These controls are integrated with the radar loader and optional rain companion.

- Tap the time pill: pause playback and select the existing timeline's now frame.
  No overview refresh, location request, map recenter, or zoom change. Missing
  frames and normal full-ring prefetch can still load.
- Pull downward from the time pill by 32 dp and release: refresh the overview and
  apply updated timelines while preserving the selected timestamp as closely as
  possible. Existing ZIPs and decoded frames are reused; no cache is cleared.
- The pill follows a pull with a small 8-dp displacement, a 3.5% stretch and a
  growing arc. It tracks the finger directly, then returns with a damped spring
  on release or cancellation. The pill border also indicates pull progress.
  Repeated pulls during an active refresh do not cancel/restart that refresh.
  During the overview refresh it retains
  the refresh accent and a small spinner. The existing frame-progress indicator
  handles any subsequently missing frame. Automatic refreshes also show the state.
- A short, upward, sideways, canceled, multi-touch, or retracted pull does not
  select now or refresh. Gestures beginning on the pill are consumed there and
  do not trigger map pan, playback, zoom, or time-ring scrubbing.
- Map long press keeps its existing now-and-location-center behavior. The existing
  on-resume refresh/select-now and five-minute automatic refresh are preserved.

Host checks use API-35 Robolectric with a round 192-dp watch profile, plus gesture
classification unit tests. They check tap/pull isolation from map actions, existing
map tap/double-tap/long-press/pan behavior, and persistent refresh semantics. The
physical-device test uses genuine signed Release APKs; no debug APK is installed
on physical test devices.

Set `JAVA_HOME` and `ANDROID_HOME` to your installed JDK and Android SDK.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

After installation is separately approved, verify on a real round watch: selected
past/future → tap time → now without map movement; short pull → no action; long
pull → exactly one refresh; all map and crown gestures unchanged. The debug APK
can conflict with a release/Play signing certificate or version code. Check the
existing installation before attempting an update; do not uninstall or clear data
without explicit agreement.
