# Radar assets and coloring

## Shader / lookup assets

| Asset | Used by | Purpose |
| --- | --- | --- |
| `shader_scales/precip_scale_17.png` | legacy radar, homescreen radar | main precipitation lookup texture |
| `shader_scales/precip_scale.png` | legacy radar | secondary/older precipitation lookup |
| `shader_scales/precipitation_pattern.png` | new + legacy radar | radar pattern / stipple texture |
| `shader_scales/blitz_atlas.png` | new renderer | lightning atlas |
| `shader_scales/blitz_forecast_pattern.png` | new renderer | forecast-lightning pattern |
| `shader_scales/blitz_pattern.png` | legacy renderer | lightning forecast pattern |
| `shader_scales/temp_scale.png` | legacy renderer | temperature lookup |
| `shader_scales/wind_scale.png` | legacy renderer | wind lookup |

## Legacy radar asset hookup

The legacy renderer wires the radar textures explicitly:

```java
this.f24407O0.setRadarOverlayColorMaps(
    new C1667a(BitmapFactory.decodeStream(assets.open("shader_scales/precip_scale_17.png")), false),
    new C1667a(BitmapFactory.decodeStream(assets.open("shader_scales/precip_scale.png")), false)
);
this.f24407O0.setPatternTexture(
    new C1667a(BitmapFactory.decodeStream(assets.open("shader_scales/precipitation_pattern.png")), false),
    AnimationType.RADAR
);
```

That is the clearest evidence that radar is **not just a pre-colored bitmap** in the old path. The renderer expects:

1. the raw section image
2. a precipitation color lookup texture
3. a precipitation pattern texture

## New renderer pattern hookup

The new path still injects a radar pattern from assets:

```java
public TextureHolderInterface getTexturePatternFor(AnimationType animationType) {
    if (animationType == AnimationType.RADAR) {
        Bitmap bmp = BitmapFactory.decodeStream(
            assets.open("shader_scales/precipitation_pattern.png")
        );
        return new C7337b(bmp, 0, 0, null, 14, null);
    }
    ...
}
```

The new path does **not** expose an equivalent Java-side `setRadarOverlayColorMaps(...)` call, which strongly suggests the color handling moved deeper into the native/OpenMobileMaps side.

## Precipitation color buckets

`AnimationColorScaleInfos` contains a hardcoded precipitation scale:

```java
ColorScaleBucket b0 = new ColorScaleBucket(0.0f,  "#FFFFFF");
ColorScaleBucket b1 = new ColorScaleBucket(1.0f,  "#FFFFFF");
ColorScaleBucket b5 = new ColorScaleBucket(5.0f,  "#33FFFF");
ColorScaleBucket b10 = new ColorScaleBucket(10.0f, "#1ACC9A");
ColorScaleBucket b15 = new ColorScaleBucket(15.0f, "#019934");
...
PRECIPITATION = new ColorScaleInfo(128.0f, bucketsUpTo85mm);
```

This is useful for a Wear OS port because it gives you a **semantic precipitation-to-color mapping**, not just an image asset.

## Homescreen composite radar

The lightweight homescreen path downloads `radar_wolken_blitz_homescreen.zip` and renders:

```java
ImageInterpolateOverlayHandler radar = companion.addRadarHomescreenOverlay(map);
radar.setColorMap(new C1667a(BitmapFactory.decodeStream(
    this.context.getAssets().open("shader_scales/precip_scale_17.png")
)));
radar.setImages(new ZipImageHolder(zipFile, "radar.png", options), null);
```

Sample ZIP contents:

```text
cloud.png
radar.png
blitz.png
```

That is much simpler than the full interactive path and is the strongest candidate for a small-screen renderer.
