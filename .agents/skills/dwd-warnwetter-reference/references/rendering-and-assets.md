# APK and code layout

## Decompilation output

- `decompiled/apktool/`: manifest, resources, smali, assets, native libs
- `decompiled/jadx/`: decompiled Java/Kotlin source tree plus exported assets

The manifest package is:

```xml
<manifest package="de.dwd.warnapp">
```

## Most relevant classes

| Class | Path | Role |
| --- | --- | --- |
| `WWMapView` | `de.dwd.warnapp.map.WWMapView` | Current base-map widget |
| `TheNewAnimationFragment` | `de.dwd.warnapp.TheNewAnimationFragment` | Current interactive weather/radar map screen |
| `LegacyAnimationFragment` | `de.dwd.warnapp.LegacyAnimationFragment` | Older radar/animation renderer |
| `C6443c` | `de.dwd.warnapp.map.C6443c` | Local provider for style JSON, sprites, and injected GeoJSON |
| `LayerManagerInterface` | `de.dwd.warnapp.shared.map.LayerManagerInterface` | Native animation layer manager used by the new path |
| `MapOverlayFactory` | `de.dwd.warnapp.shared.map.MapOverlayFactory` | Native overlay factory used by the legacy path and homescreen renderers |

## Native libraries

| Library | Likely responsibility |
| --- | --- |
| `libmapscore.so` | OpenMobileMaps base-map renderer |
| `liblayeranimation.so` | Radar / weather animation compositing |
| `liblayergps.so` | GPS overlay integration |
| `libdwd_shared.so` | DWD-specific native glue / shared logic |

The APK also declares `android:glEsVersion="0x30002"`, so the rendering stack expects OpenGL ES 3.2 support.

## Important asset folders

| Path | Contents |
| --- | --- |
| `assets/map/styles/` | Base map style JSONs, sprites, glyph references |
| `assets/map/geojson/` | Germany bounds / out-of-bounds helper data |
| `assets/map_tiles/` | Bundled static border tiles |
| `assets/shader_scales/` | Radar / wind / temperature / lightning color and pattern assets |

## Base-map engine

`WWMapView` is the key proof that the app is using OpenMobileMaps rather than MapLibre:

```java
public final void setupMap(Lifecycle lifecycle) {
    C7342a.m33796s(
        this,
        new MapConfig(CoordinateSystemFactory.INSTANCE.getEpsg3857System()),
        Math.max(300.0f, getResources().getDisplayMetrics().xdpi),
        false,
        false,
        8,
        null
    );
}
```

The same class builds the background vector layer from a local style/sprite provider:

```java
this.backgroundLayer = m29924L(
    this,
    0,
    "background_layer_" + backgroundLayer.getLayerIdentifier(),
    c6443c,
    null,
    0,
    null,
    40,
    null
);
```

That `c6443c` instance is `de.dwd.warnapp.map.C6443c`, which loads style JSON and local sprite assets and swaps the dev host to the production host.

## Rendering pipeline

The base map uses bundled style JSON and OpenMobileMaps. The current interactive
path creates a native `LayerManagerInterface`, attaches it to `WWMapView`, and
hands it the overview, active types, and current time.

The legacy path uses `MapOverlayFactory.addAnimationOverlay(...)`, configures
radar color maps and pattern textures, then starts loading sections. Java
primarily orchestrates loading and active types; native code performs
compositing, timing, and map-layer integration.

Important native boundaries:

- `LayerManagerInterface.createAnimationWithOpenGl(...)`
- `LayerManagerInterface.addToMap(...)`
- `LayerManagerInterface.setOverview(...)`
- `LayerManagerInterface.setTime(...)`
- `AnimationOverlayHandler.startLoadingSections(...)`
- `MapOverlayFactory.addAnimationOverlay(...)`

## Radar assets and coloring

| Asset | Purpose |
| --- | --- |
| `shader_scales/precip_scale_17.png` | Main precipitation lookup texture |
| `shader_scales/precip_scale.png` | Secondary legacy precipitation lookup |
| `shader_scales/precipitation_pattern.png` | Radar pattern texture |
| `shader_scales/blitz_atlas.png` | Lightning atlas |
| `shader_scales/blitz_forecast_pattern.png` | Forecast-lightning pattern |
| `shader_scales/temp_scale.png` | Temperature lookup |
| `shader_scales/wind_scale.png` | Wind lookup |

The legacy renderer explicitly combines the raw radar image with precipitation
lookup and pattern textures. The newer renderer still receives a radar pattern,
while color handling appears to live deeper in the native renderer.

The lightweight homescreen path downloads
`radar_wolken_blitz_homescreen.zip`, containing:

```text
cloud.png
radar.png
blitz.png
```

It applies `precip_scale_17.png` to the radar image before compositing.
