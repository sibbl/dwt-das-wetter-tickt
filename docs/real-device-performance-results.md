# Release-device validation

The integrated watch and phone apps were validated as genuine non-debuggable release builds with the same package and signing certificate. Updating existing installations preserved app data. The local release-device test version was 1.1.4; this document does not publish or tag a release.

The rain companion was discovered automatically on paired devices, and successful end-to-end frame transfers were confirmed. Cloud and lightning remain local in this change. Companion availability alone was not used as proof of offloading.

## Observations and limits

- A download-heavy local startup took approximately 9.1 seconds to selected-frame readiness; its rain ZIP transfer accounted for approximately 7.9 seconds. A later local restart with existing source files took approximately 0.54 seconds.
- Companion-selected-frame readiness was approximately 1.5–1.6 seconds in three observed restarts. Cache contents and selected source references differed between local and companion samples, so no speedup ratio is inferred.
- The final prepared-frame cache produced 26 observed disk hits across rain, cloud, measured lightning and forecast lightning. These avoid source extraction/colorization and another companion transfer, while still decoding a lossless cached PNG.
- The largest logged retained bitmap allocation was approximately 31.8 MiB, below the hard 32 MiB bitmap-cache cap. This excludes displayed images, transient encoder/decoder work, GPU resources and other process memory.
- Intermittent DNS/socket failures affected watch downloads. Memory snapshots occurred at different work stages and were not used to claim a process-memory reduction. UI-ready log events do not measure GPU presentation, jank, battery use or peak memory.

The round-watch pull-progress arc and pill movement were visually checked. Host UI tests cover tap-to-now, map-gesture isolation, repeated pulls, active-refresh suppression and short/sideways/cancelled gestures. Companion layout was checked with light/dark themes and larger fonts. User interaction overlapped parts of the hardware session; physical tap-to-now was not treated as an isolated assertion.

Raw device logs, run markers, identifiers, screenshots and installation records are intentionally excluded from this branch and its history. Aggregate observations above are exploratory validation, not a reproducible controlled hardware benchmark.

## Cache behavior

A visual segment is prepared frame-by-frame, including rain and enabled matching cloud/lightning layers. Scrolling within that segment keeps the preparation queue; changing segments reorders it. Repository-owned downloads and frame preparation continue independently of cancelled selection waits.

The bitmap cache has a hard 32 MiB allocation cap and prefers currently selected layers. Already colorized frames and bounds persist in a separate lossless cache, bounded by 128 MiB and 512 entries per device. Returning after RAM eviction can reuse these prepared files without another companion transfer or colorizer pass. Cache identities include the full source reference and rendering-format revision, preventing reuse after source identity changes. Current-segment preparation includes disk-ready frames, allowing the prefetch barrier to complete without retaining the whole segment in RAM.

Tests cover A→B→A→B reuse, restart persistence, stale-source prevention, damaged entries, expiration and byte/entry limits. The original source ZIP cache remains separate. Process/service shutdown can stop in-flight work; lack of disk space can prevent prepared-cache persistence.
