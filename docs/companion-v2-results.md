# Compact companion frames

The implementation keeps independently cached weather layers. On the sampled DWD
frames, a full-resolution composite costs more bytes and removes cross-frame
reuse of cloud/lightning timestamps. No map labels or viewport are transmitted.
Original bounds, times, raster dimensions, colors and alpha are retained.

## Protocol and scheduling

- `dwt_all_frames_v2` and `/dwt/radar/v2` support rain, clouds, measured lightning
  and forecast lightning. V1 remains available for older watches. New watches
  use v1 rain plus standalone overlays with older phones; absence or failure
  always permits standalone loading.
- Binary requests preserve the entire source reference and foreground priority,
  without JSON field names. Responses use a 14-byte header for exact default
  bounds, or 46 bytes with four unrounded doubles for other bounds. Duration and
  payload length remain explicit. Payloads are bounded before decoding.
- The smallest supported complete payload wins: PNG, packed exact palette
  (1/2/4/8 bits), exact constant-RGB alpha, solid color, repeated 12px tiles,
  or deflated measured-lightning coordinates. Unknown formats fail closed and
  trigger standalone fallback. This is a minimum among implemented candidates,
  not a claim of an information-theoretic minimum.
- Cloud RGB is not assumed constant after Android premultiplication. Palette
  values are read from the actual rendered bitmap. Forecast tiles store exact
  antialiased pixels, rather than assuming every output pixel is one of three
  opaque colors. Up to four unique tiles use 2-bit indices (256 bytes for a
  32×32 tile map), with the tile dictionary and indices compressed together.
- Measurements preserve original float coordinates and draw order; only points
  rejected by the existing bounds check are removed. The watch draws these
  with the shared renderer. This reduces transfer substantially but still costs
  watch rendering time; raster formats remain candidates.
- Rain and overlays use independent priority lanes and failure cooldowns, so a
  stalled overlay does not consume the rain lane. Foreground requests precede
  queued prefetch. Active operations are not preempted. Companion work is tied
  to service lifetime; service destruction cancels it and permits fallback.
- RAM/prepared-disk lookup precedes every optional provider request. Shared
  source identities deduplicate matching overlay times. Disk cache remains
  128 MiB/512 entries; bitmap cache remains 32 MiB. The rendering cache revision
  remains valid because the pixel rendering is unchanged. Changed source
  references invalidate entries. A mismatched provider identity is rejected.

## Reproducible host checks

`CompactFrameCodecTest` uses deterministic empty, typical and dense native
Android bitmap fixtures for every layer. `CompanionFixtureBenchmarkTest` is
opt-in: set `DWT_FIXTURE_DIR` to a directory containing public DWD ZIPs named
`PRECIPITATION.zip`, `CLOUD.zip`, `BLITZ_MEASUREMENT.zip`, `BLITZ_FORECAST.zip`.
It reads their per-frame geographic bounds. Ordinary tests need no network;
the fixture benchmark skips when the directory is not supplied.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Checks include exact pixel equality, dimensions, exact alternate bounds, v1/v2
separation, invalid versions/layers/paths, malformed lengths/streams/dimensions,
A→B→A→B and restart reuse for all four layers, changed source references, stale
provider rejection, and rain progress while an overlay is blocked. Existing
cache corruption, expiration, limits and priority tests remain enabled.

The public DWD snapshot is from 2026-10-02, frame timestamp `1790931600000`.
Rain is 1200×1200, cloud 406×311, and rendered lightning 384×384. Both sampled
lightning frames are empty; populated cases below are synthetic, not a claim
about current thunderstorm density.

| Layer | Legacy PNG response | V2 response | V2 request | V2 request + response |
|---|---:|---:|---:|---:|
| Rain | 55,078 B | 24,354 B | 103 B | 24,457 B |
| Cloud | 91,970 B | 28,505 B | 95 B | 28,600 B |
| Measured lightning (empty) | 729 B | 23 B | 107 B | 130 B |
| Forecast lightning (empty) | 729 B | 55 B | 104 B | 159 B |

The legacy column includes its 48-byte response envelope. V2 includes its full
response envelope and encoding metadata; alternative forecast bounds account
for the larger empty response. Requests include all fields and UTF path bytes.
These are application protocol bytes, not Bluetooth/TLS/Google Play services
framing, which is not exposed by this measurement. V1 did not support cloud or
lightning: those legacy columns are same-image PNG baselines, not an old
working offload implementation.

| Synthetic 384×384 case | PNG response | V2 response |
|---|---:|---:|
| Typical rain | 52,521 B | 15,519 B |
| Dense rain | 147,988 B | 51,765 B |
| Typical cloud | 95,868 B | 35,667 B |
| Dense cloud | 343,327 B | 127,635 B |
| 50 measured bolts | 9,324 B | 430 B |
| 3,000 measured bolts | 238,155 B | 21,475 B |
| Typical forecast glyphs | 3,581 B | 183 B |
| Dense forecast glyphs | 3,205 B | 167 B |

Seven warmed native host iterations gave approximate median encode/decode times:
rain 54.1/3.7 ms, cloud 15.2/0.5 ms, empty measurements 0.18/0.13 ms and empty
forecast 0.17/0.11 ms. These include candidate encoding work and are host numbers,
not phone or watch predictions. Synthetic dense measured points required about
7.8 ms of host decode/render work; smaller bytes do not imply free rendering.

## Composite comparison and cache reuse

The sampled full rain/cloud/forecast composite required 84,350 response bytes,
versus 52,914 for three separate responses. Composition itself took about 5.5 ms
and composite encoding 78.1 ms in one host iteration. This exploratory composite
uses the rain geographic rectangle, maps overlays by their actual bounds and
has no basemap/labels. The sampled forecast is empty. It is not certified as a
pixel-equivalent general replacement: nonempty forecast bounds extend beyond
rain, and the actual UI independently projects and filters each raster before
blending. Flattening then scaling introduces different filtering, clipping and
alpha rounding; display-size output would additionally lose zoom detail.

A second distinct rain frame with unchanged overlays only needs another rain
request/response. Using the sampled rain size as an illustrative estimate,
separate responses total 77,268 bytes for that pair, versus 168,700 for two
similarly sized composites. This pair figure is a size estimate, not a measured
second weather frame. Returning A→B→A→B transfers nothing after preparation
when cache entries remain. Unit tests confirm two provider loads per layer for
that sequence, none on restart, and a new load after source identity changes.
Toggling a prepared layer back on also reuses its independent entry; no combined
layer-mask or viewport-specific cache must be generated. Cold activation loads
only the missing layer. A composite could reduce draw calls but that saving has
not been measured against watch GPU presentation. There is no evidence here to
justify its larger transfer and cache complexity.

## Fixture identity

ZIPs were fetched from the public DWD v16 endpoint, are not committed, and may
expire upstream. SHA-256 values permit identifying an archived copy:

| ZIP layer | Bytes | SHA-256 |
|---|---:|---|
| Rain | 503561 | `37bbc0479430c19c22a05f0dfe0a753cde709ae56e558df8cb7714d8731dcc37` |
| Cloud | 409198 | `3fc6abf56741fe05a1936142badad3a7bf8e6520d047bb1b4ef5792f96822533` |
| Measured lightning | 3912 | `8981b30fc07d916db2e1615037e31cc7a4e04163bfea3b873564a90d71a3d0be` |
| Forecast lightning | 6106 | `0fb7844f4b9a65e663b60f0402ce5fd38afc9311f0a827c80926ee16cfc2d3b6` |

## Release-device smoke check

Both release APKs were installed as `1.1.5` / version code `1001005`, with the
existing matching release certificate, no DEBUGGABLE flag, and unchanged app
UIDs. No uninstall, downgrade, data reset, public release or tag was used.

A short fresh-start observation confirmed 12 v2 companion responses on the
watch, with no logged fallback: 5 rain, 2 cloud and 5 forecast-lightning frames.
Three prepared-cache hits also occurred. Measured lightning was not transferred
in this observation; its point/raster formats are covered by native fixture tests.

| Layer | Samples | Observed end-to-end min / median / max | Watch decode median | Application bytes min–max |
|---|---:|---|---:|---:|
| Rain | 5 | 659 / 772 / 1109 ms | 58 ms | 19925–21267 B |
| Cloud | 2 | 654 / 990 / 1326 ms | 13 ms | 30483–30526 B |
| Forecast lightning | 5 | 347 / 355 / 469 ms | 7 ms | 159 B |

End-to-end starts before capability discovery and client queueing and ends after
watch decode. Repository-level logs additionally include outer queue/cache work.
These were live frames, different from the fixed host snapshot, with mixed cache
state and no randomized old/new baseline. No universal speedup, GPU presentation,
jank, energy or radio-byte claim follows. Dense on-device lightning rendering and
background-service stress remain unmeasured. Local diagnostics were enabled only
for the check and their original settings restored. Raw logs and identifiers are
excluded from this repository.

Final validation: 82 watch and 39 companion unit tests passed, including the
opt-in public fixtures; debug/release lint and debug/signed-release builds passed.
Lint reported warnings but no errors. No separate TypeScript/typecheck task exists; Kotlin compilation ran for both modules.
