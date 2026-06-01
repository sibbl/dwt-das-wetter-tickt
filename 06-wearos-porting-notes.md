# Wear OS porting notes

## Recommendation

**Recommended starting point: reuse the homescreen-style pipeline, not the full native map stack.**

The full app depends on:

- `libmapscore.so`
- `liblayeranimation.so`
- `libdwd_shared.so`
- OpenGL-backed map / overlay integration

That is a lot to carry into Wear OS unless the DWD team wants to ship supported native binaries there as well.

## Two realistic implementation paths

| Path | Reuse level | Pros | Cons |
| --- | --- | --- | --- |
| Homescreen composite ZIP | Low/medium | Smallest implementation, easiest battery story, already packages radar/cloud/lightning into a single asset bundle | No full panning/zooming/weather-layer parity |
| Current V3 overview + animated WebP | Medium/high | Matches current backend direction, more future-proof than the legacy ZIP renderer | Requires a custom Wear renderer for animated WebP + map composition |

## Path A: homescreen-style renderer

Mirror `p530y5.C10074i`:

1. download `https://app-prod-static.warnwetter.de/v16/radar_wolken_blitz_homescreen.zip`
2. extract `radar.png`, `cloud.png`, `blitz.png`
3. apply the radar color lookup (`precip_scale_17.png`) in your renderer
4. draw the three layers in order
5. optionally add a location dot / city labels

This is the best fit if the Wear app only needs:

- a compact radar screen
- a complication/tile preview
- simple swipe-through forecast imagery

## Path B: current interactive backend

Mirror the current `TheNewAnimationFragment` contract:

1. fetch `mobile/animation_overview_v3*.json`
2. parse sections/ranges/timesteps
3. treat precipitation / cloud / wind assets as **animated WebP**
4. treat lightning as a ZIP-backed special case
5. render on top of either:
   - a simplified custom base map, or
   - the same vector tile/style sources from `assets/map/styles/*`

If you do this, prefer the **current V3 backend format** over the legacy ZIP-only animation path.

## Things I would not port 1:1

- the old `LegacyAnimationFragment` renderer
- RenderScript-based blur from `ZipSectionImagerHolder`
- direct reliance on the current Android native `.so` set unless DWD explicitly wants to own that dependency

## Things worth reusing exactly

- `AnimationType` names (`RADAR`, `BLITZ`, `WIND`, `CLOUDS`, ...)
- precipitation color buckets / lookup assets
- the overview JSON concept: ranges + sections + per-type file/timestep mapping
- the base-map style URLs if you want geographic context

## Practical decision

If the first Wear release only needs **“show me the current rain radar”**, build **Path A** first.

If the first Wear release needs **interactive layers and time scrub**, build **Path B**, but base it on the **new V3/WebP contract**, not on the old ZIP section loader.
