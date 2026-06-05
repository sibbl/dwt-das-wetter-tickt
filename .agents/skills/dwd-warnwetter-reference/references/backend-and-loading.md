# Backend and payloads

## Static host used by the map / radar stack

The central constant is in `p459r6.C8969a`:

```java
private static String BASE_URL_STATIC = "https://app-prod-static.warnwetter.de/v16/";

public static final String m42336b(Context context) {
    return m42338c(context)
        + f35902a.m42342i0(context)
        + "animation_overview_v3"
        + context.getString(R.string.request_locale_appendix)
        + ".json";
}
```

## Relevant URLs from code

| Purpose | URL pattern |
| --- | --- |
| Current DE animation overview | `https://app-prod-static.warnwetter.de/v16/mobile/animation_overview_v3*.json` |
| Current Europe icon overview | `https://app-prod-static.warnwetter.de/v16/mobile/icon_animation_overview_v3*.json` |
| Legacy DE animation overview | `https://app-prod-static.warnwetter.de/v16/animation_overview_v2*.json` |
| Legacy Europe icon overview | `https://app-prod-static.warnwetter.de/v16/mobile/icon_animation_overview*.json` |
| Homescreen composite ZIP | `https://app-prod-static.warnwetter.de/v16/radar_wolken_blitz_homescreen.zip` |
| Base vector tiles | `https://app-prod-static.warnwetter.de/map/v2/europe/base/{z}/{x}/{y}.pbf` |
| Relief raster tiles | `https://app-prod-static.warnwetter.de/map/v1/europe/hillshade/light/{z}/{x}/{y}.png` |

## Request / parsing path

The overview request is a typed JSON `GET`:

```java
C8973e<AnimationOverviewModel> loader =
    new C8973e<>(new C9384g(strM42339d), AnimationOverviewModel.class, true);
```

`C9384g` is only an HTTP `GET` wrapper:

```java
public class C9384g extends AbstractC9387j {
    @Override
    public String getMethod() {
        return "GET";
    }
}
```

`C8973e` is the generic JSON loader used throughout the app. It uses Gson and reads cache control from S3-style metadata headers:

```java
InterfaceC8753e bestBefore = response.mo5714o0("x-amz-meta-best-before");
InterfaceC8753e cache = response.mo5714o0("x-amz-meta-cache");
InterfaceC8753e nextRefresh = response.mo5714o0("x-amz-meta-next-refresh");
InterfaceC8753e backoff = response.mo5714o0("x-amz-meta-backoff");
```

That means the app is **not** hardcoding refresh intervals for these assets; it lets the backend steer freshness with response headers.

## Live overview sample

Observed against the production endpoint on **2026-05-21**:

```json
{
  "files": {
    "PRECIPITATION_V4": {
      "file": "mobile/PRECIPITATION_V4.DE.WEB_MERCATOR.202605200600.202605200800.202605200755.webp",
      "timeStep": 300000
    },
    "PRECIPITATION": {
      "file": "mobile/PRECIPITATION.DE.WEB_MERCATOR.202605200600.202605200800.202605200755.webp",
      "timeStep": 300000
    },
    "BLITZ_MEASUREMENT": {
      "file": "mobile/BLITZ_MEASUREMENT.DE.LEGACY.202605200600.202605200800.202605200755.zip",
      "timeStep": 300000
    }
  }
}
```

The top-level overview keys were:

```text
data, firstBlitzForecast, firstCloudForecast, firstOrteForecast,
firstPrecipitationForecast, isobarsData, lastBlitzMeasurement,
lastCloudMeasurement, lastOrteMeasurement, lastPrecipitationMeasurement,
mobile, now, ranges, type
```

## Current file formats

The important split:

- `PRECIPITATION_V4`, `PRECIPITATION`, `WIND`, `CLOUD` are **animated WebP**
- `BLITZ_MEASUREMENT` is still a **ZIP**
- `radar_wolken_blitz_homescreen.zip` is a simple **three-file composite ZIP**

Animated WebP evidence from a live precipitation file:

```text
chunks [('VP8X', 10), ('ANIM', 6), ('ANMF', ...), ('ANMF', ...), ...]
```

Sample lightning ZIP contents:

```text
1779260400000.json
1779260400000.png
1779260700000.json
1779260700000.png
...
```

Sample homescreen ZIP contents:

```text
cloud.png
radar.png
blitz.png
```

## What this means

The backend has a **newer V3 format** for major animated weather layers and a **still-supported ZIP format** for some legacy/lightning paths. The current app contains both old and new client-side loading abstractions, so it is best to treat them as **parallel pipelines**, not as one perfectly uniform format.

## Models and loading

The following notes describe the corresponding Java-side models and loaders.

### Core Java-side models

| Class | Role |
| --- | --- |
| `AnimationOverviewModel` | Parsed overview JSON: sections, ranges, measurement times, optional isobars data |
| `DataSection` | One time range with a `files` map keyed by animation/backend identifier |
| `DataSectionFile` | Lazy loader for a section file plus per-layer `timeStep` |
| `ZipSection` | Older single-layer section wrapper |

`AnimationOverviewModel` turns JSON into range metadata plus per-layer section
lists. Each `DataSection` exposes files keyed by backend identifiers such as
`RADAR`, `WIND`, `CLOUDS`, `BLITZ`, and `ORTE_*`.

The older ZIP loader downloads a section and reads timestamped entries:

```text
TIMESTAMP.png
TIMESTAMP.json
TIMESTAMP_stationType.json
```

The decompiled Java legacy loader is ZIP-oriented, while live V3 precipitation,
wind, and cloud files were animated WebP. The safest interpretation is:

1. The legacy Java ZIP loader remains valid for legacy and ZIP-backed layers.
2. The native `LayerManagerInterface` path consumes animated WebP directly.
3. Both formats coexist behind the high-level overview model.
