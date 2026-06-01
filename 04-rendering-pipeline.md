# Rendering pipeline

## 1. Base map

The base map comes from bundled style JSON plus OpenMobileMaps.

`C6443c` loads the style from assets and rewrites the asset host from dev to prod:

```java
public String getStyleJson() {
    InputStream in = this.context.getAssets().open(m29969g() + "/style.json");
    String json = readUtf8(in);
    return json.replace(
        "https://app-dev-static.warnwetter.de",
        "https://app-prod-static.warnwetter.de"
    );
}
```

Example from `assets/map/styles/europe/light/style.json`:

```json
{
  "sources": {
    "base-map": {
      "type": "vector",
      "tiles": [
        "https://app-dev-static.warnwetter.de/map/v2/europe/base/{z}/{x}/{y}.pbf"
      ]
    },
    "favorites": {
      "type": "geojson",
      "data": "https://app-dev-static.warnwetter.de/v1/injected_by_app.geojson"
    }
  }
}
```

`WWMapView` inserts that provider as a tiled vector layer:

```java
Tiled2dMapVectorLayerInterface layer =
    Tiled2dMapVectorLayerInterface.INSTANCE.createExplicitly(
        name, null, null, loaders, this.fontLoader, localDataProvider, zoomInfo, symbolDelegate, null
    );
mapInterface.insertLayerAt(layer.asLayerInterface(), layerIndex);
```

## 2. Current interactive renderer

The new path lives in `TheNewAnimationFragment`.

### Creation

```java
this._animationLayerManager =
    companion.createAnimationWithOpenGl(
        strM42338c,
        c6150d,
        c6151e,
        c6992a,
        c6152f,
        new C6148b(this, context)
    );
```

`strM42338c` is `BASE_URL_STATIC`, so the native manager gets the static asset host directly.

### Attachment to the map

```java
m28814E3();
m28826I3().addToMap(m28869X3().m33800p());
```

`m28869X3().m33800p()` is the `WWMapView` map interface.

### Overview handoff

```java
io.openmobilemaps.layer.animation.animation.AnimationOverviewModel shared =
    animationOverview.toShared();
layerManagerInterface.setOverview(shared);
layerManagerInterface.setActiveTypes(new HashSet<>(m28823H3()));
layerManagerInterface.setTime(time, progressOrNull);
```

### Native callback surface

The Java callback object feeding the native layer manager provides:

- a texture atlas for symbols/icons
- pattern textures for specific animation types
- vector-layer local providers for map overlays like isobars

```java
public abstract class LayerManagerCallbackInterface {
    public abstract TextureHolderInterface getTexturePatternFor(AnimationType animationType);
    public abstract TextureAtlas getTextureAtlas(ArrayList<String> textureIds);
    public abstract Future<Tiled2dMapVectorLayerLocalDataProviderInterface>
        getVectorLayerLocalDataProviderForType(String type);
}
```

## 3. Legacy renderer

The old path lives in `LegacyAnimationFragment`.

### Overlay creation

```java
this.f24407O0 = MapOverlayFactory.addAnimationOverlay(
    this.f24388E0.getMapRenderer(),
    new C6086c(),
    new C6088e(context),
    c6084a,
    new C6087d(context)
);
```

### Shader/color assets

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

### Section loading

```java
this.f24407O0.setGlobalRanges(animationOverviewModel.getRanges());
this.f24407O0.setActiveTypes(m28608w3());
this.f24407O0.startLoadingSections(
    new ArrayList<>(animationOverviewModel.getData()),
    this.f24382B0.getTime(),
    animationOverviewModel.getAnimationMeasurementTimes(),
    new ArrayList<>(Arrays.asList(AnimationType.values())),
    PreloadingType.DEFAULT_MOBILE
);
```

### Metadata DB

The legacy path also injects a native metadata DB:

```java
this.f24407O0.setMetadataDatabase(MetadataManager.getInstance(context).getDB());
```

That is mostly for stations / commune metadata, not the core radar texture itself.

## 4. What is actually native

The Java/Kotlin layer mostly does orchestration. The following are native-facing seams:

- `LayerManagerInterface.createAnimationWithOpenGl(...)`
- `LayerManagerInterface.addToMap(...)`
- `LayerManagerInterface.setOverview(...)`
- `LayerManagerInterface.setTime(...)`
- `AnimationOverlayHandler.startLoadingSections(...)`
- `MapOverlayFactory.addAnimationOverlay(...)`

In other words:

- **Java decides what to load and which overlay types are active**
- **native code does the heavy compositing, timing, and map-layer integration**
