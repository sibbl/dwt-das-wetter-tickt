# DWD WarnWetter rain radar reverse-engineering notes

This folder now contains:

- the original APK: `dwd-app.apk`
- resource-oriented decompilation: `decompiled/apktool/`
- source-oriented decompilation: `decompiled/jadx/`
- the markdown notes below

## Quick findings

- The **base map** is rendered with **OpenMobileMaps / mapscore** (`WWMapView` + `libmapscore.so`).
- The **current interactive radar path** is driven by a native **`LayerManagerInterface`** attached to `WWMapView`.
- The backend host for map/radar assets is **`https://app-prod-static.warnwetter.de/v16/`**.
- The **current live V3 payload** serves **animated WebP** for precipitation / wind / clouds, while some legacy-style data such as lightning is still delivered as ZIP payloads.
- A full **legacy renderer** still exists and uses `MapOverlayFactory` + `AnimationOverlayHandler` + ZIP sections + shader scale assets.
- There is also a much simpler **homescreen composite** path based on `radar_wolken_blitz_homescreen.zip`, which is the most realistic starting point for Wear OS.

## Reading order

| File | Purpose |
| --- | --- |
| [`01-apk-and-code-layout.md`](./01-apk-and-code-layout.md) | Package layout, key classes, assets, native libs |
| [`02-backend-and-payloads.md`](./02-backend-and-payloads.md) | Hosts, endpoints, request loader, cache headers, live payload sample |
| [`03-models-and-loading.md`](./03-models-and-loading.md) | `AnimationOverviewModel`, `DataSection`, ZIP/WebP loading behavior |
| [`04-rendering-pipeline.md`](./04-rendering-pipeline.md) | Base map, current renderer, legacy renderer, native boundaries |
| [`05-radar-assets-and-coloring.md`](./05-radar-assets-and-coloring.md) | Shader/color assets, radar lookup tables, patterns, homescreen assets |
| [`06-wearos-porting-notes.md`](./06-wearos-porting-notes.md) | Recommended implementation directions for a Wear OS app |

## Most important takeaway

If the goal is a practical Wear OS port, do **not** start by cloning the full native renderer stack. The lightest viable path is the **homescreen composite ZIP** approach; the richer path is to follow the **current V3 overview + animated WebP** backend and build a custom renderer around it.
