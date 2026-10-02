# Radar performance and optional companion prototype

This change integrates watch loading improvements, an optional rain companion,
bounded prepared-frame caching and time-pill controls. See
[time controls](time-controls.md) for gesture behavior.

## What changed

- ZIP downloads are shared per asset and owned by the repository. Scrubbing can
  cancel a frame request without deleting its shared download. Repository shutdown
  cancels outstanding HTTP work. Up to two ZIP transfers can run together.
- CPU image preparation runs on `Dispatchers.Default`, one frame at a time;
  selected requests take precedence over queued prefetch. The decoder permit is
  never held across network transfers. Cache hits retain the complete frame,
  including bounds, and do not reopen the ZIP. Bounds and PNG share one ZIP handle.
- One bitmap cache across all layers has a hard 32 MiB allocation budget. The
  currently selected enabled layers are preferred. A larger current ring is fully
  prepared sequentially but no longer pinned entirely in RAM.
- Fully colorized frames and their bounds are stored losslessly in a second disk
  cache (128 MiB / 512 entries per device, least-recently-used eviction). Returning
  to an evicted RAM frame reads this PNG without source ZIP extraction/colorization
  or a repeated companion transfer. PNG decoding is still required on a disk hit.
  Keys include the entire source reference and a rendering-format revision; a new
  DWD asset identity cannot reuse an old frame. Entries unused for a day are removed
  when reopening the cache. Raw source ZIPs remain reusable in the existing cache.
- Frame preparation as well as ZIP downloads has repository-owned, per-identity
  shared lifetime. Scrolling cancels an obsolete wait, while useful started work
  finishes and populates the prepared cache. Repository/process shutdown ends it.
- Prefetch still prepares every current-ring frame and enabled overlay before
  preparing previous/next rings. Readiness includes fully prepared disk frames,
  so RAM eviction does not cause a never-ending preparation barrier. Disabled
  cloud/lightning layers are excluded; rain remains the master timeline.
- The 32 MiB cap describes cached bitmap allocations, not total process memory.
  Currently displayed images, decoder/encoder scratch space, GPU resources and
  in-flight results add memory. Prepared disk cache and source ZIPs are separate.
- Fresh mutable rain/cloud bitmaps are colorized in place; lightning forecasts use
  a bulk pixel read instead of repeated native `getPixel` calls. No map projection,
  raster resolution, or zoom behavior changed.

## Companion behavior

The phone advertises `dwt_rain_frames_v1`. The watch discovers reachable compatible
nodes and prefers nearby ones. Presence is rechecked after 15 seconds. If absent,
unsupported, unavailable, disconnected, or processing/transfer fails, the local
watch pipeline is used automatically. Discovery has a 1.5-second timeout; an
active frame transfer has a 20-second timeout. Failures have a 15-second cooldown
so other frames do not all wait on the same unavailable phone. A working current
transfer is not preempted, but selected work precedes queued background work.

Only **rain frames** use the companion. The overview, clouds, lightning, camera,
and optional device location remain on the watch. The phone downloads the source
ZIP, extracts the requested timestamp, and applies exactly the same colorizer as
standalone mode. It sends one lossless PNG in the original raster resolution,
with static bounds, via a versioned on-demand Data Layer channel. No ring bundle,
location, viewport, or account is sent. The transferred image is limited to 4 MiB
and 4096 pixels per dimension; unsupported payloads fall back locally. No image
is downscaled, so high zoom retains source detail. The phone keeps at most 12
recent frame identities under the same hard bitmap cache budget, preferring its
current request and retaining prepared frames in the bounded disk cache. Android may stop or force-stop the companion; fallback remains
available. Opening the phone app once is useful during testing.

Both APKs have application ID `net.sibbl.dwt`. Data Layer requires matching
signing certificates on watch and phone. The debug APKs built on this Mac were
verified to have the same certificate. Both modules support the same existing local release-signing environment.
No phone publishing workflow is configured. Shared radar/model/transport sources compile in both
modules; they are not copied or maintained as a separate decoder implementation.

Sources verified October 2, 2026:

- [Data Layer overview and package/signature requirements](https://developer.android.com/training/wearables/data/overview)
- [Reachable nodes and CapabilityClient](https://developer.android.com/training/wearables/data/discover-devices)
- [Data synchronization](https://developer.android.com/training/wearables/data/sync)
- [Client types and on-demand streaming channels](https://developer.android.com/training/wearables/data/client-types)

## Local checks and builds

Set `JAVA_HOME` and `ANDROID_HOME` to your installed JDK and Android SDK:

```sh
./gradlew \
  :app:testDebugUnitTest :companion:testDebugUnitTest \
  :app:lintDebug :companion:lintDebug \
  :app:assembleDebug :companion:assembleDebug :app:assembleDebugAndroidTest
```

The host tests cover priority and cancellation, download coalescing and retry,
byte eviction/current-ring protection, the full-ring prefetch barrier, protocol
validation, real Android bitmap decoding, identical mutable/immutable colorization,
and companion success/failure routing. Android bitmap tests run through Robolectric
on API 35. They do not exercise a real Google Play services Data Layer connection.

Build outputs:

- Watch: `app/build/outputs/apk/debug/app-debug.apk`
- Phone: `companion/build/outputs/apk/debug/companion-debug.apk`
- Watch UI test APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

## Before any device installation

Paired physical devices were validated with matching locally signed release
APKs and data-preserving updates. See [release-device validation](real-device-performance-results.md)
and the earlier [emulator observations](emulator-performance-results.md).

Once the user enables ADB, first inspect device IDs, installed app version, and
installed certificate **read-only**. A debug APK may conflict with a Play/release
signature or have a lower version code. Do not uninstall, clear app data, or add
`-d` automatically to resolve a conflict. Uninstalling/clearing data loses local
preferences and cache. Agree on the installation strategy before proceeding.

The watch and phone must use the same package ID and signing certificate.
Inspect the installed versions and certificates before an update.

## Repeatable hardware comparison

1. Use the performance-branch watch APK and its companion APK, signed together.
   For an initial controlled comparison, enable only rain on the watch. Repeat
   afterward with clouds and lightning enabled to measure their remaining cost.
2. Standalone run: on the watch open the layer menu → Info, enable **Nur auf Uhr /
   Watch only**. For the next sample, restart the process to remove in-memory
   frames from the previous route. Restarting preserves preferences and disk cache.
3. Companion run: turn that switch off, restart the watch process, and keep the
   compatible phone reachable. Check the logs for `route=companion`; availability
   alone does not prove that a frame used the companion. No companion installed
   also exercises automatic standalone mode without changing the switch.
4. Keep network, zoom, layers, selected timestamps, scroll cadence, battery,
   temperature, and display state comparable. Run the same 3–4 steps and a full
   ring in both directions. Measure a prepared/warm run separately from startup.
   Repeat each route several times and alternate order to reduce warming bias.
5. For genuinely cold download comparisons, deliberately reset only the relevant
   radar cache on both devices or use fresh asset identities, **with explicit
   approval**. Force-stopping alone does not clear the disk ZIP cache. Do not use
   `pm clear` or uninstall as a shortcut. Record which caches were warm.
6. Disable/uninstall the companion only if explicitly approved; alternatively
   make the phone unreachable. Confirm automatic local fallback and reconnection.

After installation is separately approved, diagnostics can be enabled on each
chosen device (replace the placeholder with the `adb devices -l` serial):

```sh
adb -s WATCH_SERIAL shell setprop log.tag.DwtPerf DEBUG
adb -s PHONE_SERIAL shell setprop log.tag.DwtPerf DEBUG
adb -s WATCH_SERIAL logcat -v threadtime -s DwtPerf:D '*:S'
adb -s PHONE_SERIAL logcat -v threadtime -s DwtPerf:D '*:S'
```

Optional process restart for a new in-memory sample, after approval:

```sh
adb -s WATCH_SERIAL shell am force-stop net.sibbl.dwt
```

Open the watch app again normally. Disable verbose diagnostics afterward with
`setprop log.tag.DwtPerf INFO`. No diagnostics are uploaded.

Log interpretation:

- `asset queueMs`: wait for a download slot.
- `download bytes totalMs`: actual ZIP transfer/write duration; failures log type.
- `frame assetWaitMs decodeQueueMs processMs`: shared-asset wait, CPU queue, and
  total image preparation. `stage=bounds/png/color durationMs` splits the work.
- `route=companion phoneMs watchDecodeMs pngBytes totalMs`: phone preparation
  (including its waits/download and PNG encoding), watch PNG decode, payload size,
  and end-to-end companion request duration. Clocks are not assumed synchronized.
- `request totalMs cacheHit cancelled`: total repository request, including fallback.
- `selection rainReadyMs/allVisibleReadyMs`: selection to ready UI state for rain
  and all visible overlays. This is **not** GPU presentation or frame-jank time.
- `cache bytes frames budget`: actual retained bitmap allocation, capped at 32 MiB.
- `route=prepared`: a persisted colorized frame was reused without source decoding
  or phone transfer; its PNG still needs decoding to an Android bitmap.

Use a device frame/CPU/heap trace alongside these logs to measure real UI jank,
GC and power/thermal effects. Host/emulator durations are not watch performance
claims. The end-to-end Data Layer transfer, service lifecycle, battery cost, and
general hardware speedup require controlled measurement on paired devices.
