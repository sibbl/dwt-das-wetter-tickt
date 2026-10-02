# Emulator radar comparison — October 2, 2026

The optimized loader reduces selected-frame waits during cancellation and concurrent downloads in this controlled rain-only experiment. It does **not** make a full cold ring faster: that case took 3.3% longer and used 10.7% more process CPU. This is a repository/bitmap microbenchmark, not full application startup, UI presentation, GPU jank, battery consumption, or a physical-watch speed claim.

## Builds and setup

- Baseline production code: main `d93825dab1a06c29e5bdfab987311897920dc828`.
- Optimized production code: the initial loading prototype, before the hard bitmap cap and prepared-frame disk cache introduced in the final implementation.
- Same external Android instrumentation harness injected through a Gradle init script; no production source replaced. Both debug APKs and test APKs built and installed on the existing Wear emulator.
- Wear_OS_Small_Round: Wear OS 5.1, API 35-ext15 ARM64, 384×384, 320 dpi, four virtual cores, 512 MiB configured RAM. Actual ART max heap 192 MiB. Host: an ARM64 desktop. Emulator timings depend on host load and virtual hardware.
- Two valid fresh-process runs per variant, order baseline-1, optimized-1, optimized-2, baseline-3. All four finished `OK (1 test)`. Baseline-2 was excluded because a phone-emulator boot overlapped it; that run was excluded from these means. The phone was stopped for all valid timing runs.
- Frozen public DWD legacy-rain snapshot downloaded once October 2. Identical three ZIPs, total 2,333,433 bytes; 24 selected contiguous five-minute frames, each 1200×1200 ARGB8888. Fixture ZIPs and raw benchmark records are not included in this public branch.
- Controlled asset-response pacing: 100 ms header delay, 1 MiB/s for ring and scrolling, 256 KiB/s for background-priority case. The actual Android ZIP, Bitmap decoding and colorizer execute in the emulator. Pacing isolates repeated-transfer/locking behavior from changing Internet conditions; it is not measured Wi-Fi throughput.
- Each cold case creates its own fresh app cache subdirectory; no `pm clear`, uninstall, other-app data deletion or physical-device changes. Both variants retain the full 24-frame bitmap set. The optimized optional phone provider is absent in these loader benchmarks.

## Results

Arithmetic means of the two valid runs; exploratory n=2, no statistical confidence or hardware extrapolation.

| Case | Baseline | Optimized | Change |
|---|---:|---:|---:|
| Cold 24-frame ring | 3366.81 ms | 3476.82 ms | +3.3% |
| Warm 24-frame ring | 9.34 ms | 4.78 ms | -48.8% |
| Final selected frame after 5 steps | 881.92 ms | 272.25 ms | -69.1% |
| Five-step sequence total | 1507.03 ms | 893.43 ms | -40.7% |
| Selected frame during other ZIP download | 3098.03 ms | 60.73 ms | -98.0% |
| Selected + background total | 3253.47 ms | 3239.73 ms | -0.4% |

In the scrolling case, the first four selected requests are cancelled 150 ms apart; latency starts at the fifth selection. Baseline performs five ZIP requests, optimized one; mean bytes read fall from 952,360 to 755,752 (20.6%). This confirms the shared download survives selection cancellation. “Total” includes the initial four intervals.

The priority case starts with the selected ZIP already cached, its bitmap cold, and a different background ZIP absent. The selected request starts 150 ms after background loading. The full background job is awaited. Overall work duration stays roughly equal, while the selected frame no longer waits for the other ZIP download. This does not prove the companion route.

Mean cold-ring process CPU: baseline 956.5 ms, optimized 1,058.5 ms. Mean scroll CPU: 134 vs 121.5 ms; priority CPU: 142.5 vs 190.5 ms. Warm CPU: 10 vs 5 ms. These are process CPU counters, not profiler stacks or energy readings.

## Memory and limits

Cold-ring PSS sampled after loading: baseline 235.71 MiB, optimized 230.24 MiB. Native allocated heap: baseline 147.36 MiB, optimized 136.39 MiB. The latter reduction is consistent with avoiding one extra full-resolution copy, but these are snapshots, **not peak allocations or a proven hard memory bound**. Each protected 24-frame ring alone accounts for 131.84 MiB of bitmap allocation. That initial prototype used a soft 32 MiB budget. The final implementation instead caps retained bitmap allocations at 32 MiB and persists prepared frames; these historical numbers do not measure that later cache change. Matching cloud/lightning layers were not included; their combined memory/GC and full app UI still require measurement.

The warm case transfers no additional bytes (its counters are cumulative from the cold case). Warm bitmap-hit means are small in both builds; halving approximately 9 ms to 5 ms for all 24 requests is not evidence of visible animation improvement.

## Companion connection result

Both existing emulators were running after timing, and the phone companion APK was installed and opened. Successful watch NodeClient/CapabilityClient calls returned `nodes=[]` and `companionNodes=0`; phone companion associations and transports were empty. The phone image has no OEM Wear OS/Pixel Watch pairing app. Installing this project's radar companion alone does not pair the emulators. No end-to-end phone transfer could therefore be timed within the constraint of no new account/permission/setup flows. Both project APKs have the same application ID and debug certificate; the remaining blocker is emulator pairing, not an inferred package mismatch.

Android's [emulator pairing guide](https://developer.android.com/training/wearables/get-started/connect-phone) describes the pairing assistant and phone-side watch companion setup. Working emulator pairing or paired physical devices are needed to measure the phone route. Watch-only results above are complete.

## Evidence and reproduction

Raw test records and run markers are retained locally and excluded from public history. The instrumentation source is [RadarEmulatorBenchmark.kt](../tools/emulator-benchmark/RadarEmulatorBenchmark.kt).

Set absolute `DWT_BENCH_CODE` to this tools/emulator-benchmark directory and `DWT_BENCH_FIXTURES` to the fixture directory. Supply `frames.json` containing serialized `RadarFrameReference` objects and their matching DWD ZIP assets. The historical snapshot used three ZIPs totaling 2,333,433 bytes. Archived DWD URLs may expire, and the current cache implementation differs from the measured initial prototype. Use the same directories and JDK/SDK for both worktrees:

```sh
./gradlew -I "$DWT_BENCH_CODE/benchmark.init.gradle" :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r \
  -e class net.sibbl.dwt.benchmark.RadarEmulatorBenchmark#benchmark \
  -e variant baseline -e sample 1 \
  net.sibbl.dwt.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 exec-out run-as net.sibbl.dwt \
  cat files/emulator-benchmark-baseline-1.json > baseline-1.json
```

Repeat with optimized variant and distinct sample numbers, reverse order, keep the phone emulator stopped during loader timing, and retain excluded runs with reasons. The init script affects instrumentation only. For Data Layer discovery, run inspectDataLayer instead of benchmark with both emulators running. Physical installation remains a separate user-authorized step; debug/release signature/version conflicts must not be bypassed through uninstall or data clearing.
