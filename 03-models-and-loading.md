# Models and loading

## Core Java-side models

| Class | Role |
| --- | --- |
| `AnimationOverviewModel` | Parsed overview JSON: sections, ranges, measurement times, optional isobars data |
| `DataSection` | One time range with a `files` map keyed by animation/backend identifier |
| `DataSectionFile` | Lazy loader for a section file plus per-layer `timeStep` |
| `ZipSection` | Older single-layer section wrapper |

## Overview model

`AnimationOverviewModel` turns the JSON into range metadata plus per-layer section lists:

```java
public class AnimationOverviewModel {
    private ArrayList<DataSection> data;
    private ArrayList<GlobalRange> ranges;
    private long lastPrecipitationMeasurement;
    private long firstPrecipitationForecast;

    public AnimationMeasurementTimes getAnimationMeasurementTimes() {
        return new AnimationMeasurementTimes(
            this.lastPrecipitationMeasurement,
            this.firstPrecipitationForecast,
            this.lastCloudMeasurement,
            this.firstCloudForecast,
            this.lastBlitzMeasurement,
            this.firstBlitzForecast,
            this.lastOrteMeasurement,
            this.firstOrteForecast
        );
    }
}
```

The new native renderer receives a shared/native version of this object via `animationOverview.toShared()`.

## Section file map

Each `DataSection` exposes a file map keyed by backend identifiers such as `RADAR`, `WIND`, `CLOUDS`, `BLITZ`, `ORTE_*`, `DRUCK`, `GEOPOTENTIAL`, etc.

```java
public class DataSection extends Section {
    private HashMap<String, DataSectionFile> files;
    private ArrayList<String> requiredLayers;

    @Override
    public SectionLayer getSectionLayer(String str) {
        if (str.startsWith("ORTE_")) {
            str = "ORTE";
        }
        DataSectionFile file = this.files.get(str);
        if (file != null && ("DRUCK".equalsIgnoreCase(str) || "FI_50K".equalsIgnoreCase(str))) {
            file.setBlurRadius(5);
        }
        return file;
    }
}
```

Those `requiredLayers` are filled from the native layer manager:

```java
ArrayList<String> required = layerManagerInterface.getRequiredBackendIdentfiers();
for (DataSection section : animationOverviewModel.getData()) {
    section.setRequiredLayers(required);
}
```

## Legacy ZIP loading

The older loader treats a section as a ZIP and downloads it from `BASE_URL_STATIC + file`:

```java
public void load(SectionLoadingType sectionLoadingType) {
    C5889l request = new C5889l(new C9384g(C8969a.m42335a(null) + this.file));
    this.canceller = request.mo27854e();
    request.m27823W(86400000L).mo27771c();
    this.localPath = request.m27843s().getAbsolutePath();
}
```

`ZipSectionImagerHolder` then reads timestamped image and bounds files from the ZIP:

```java
InputStream image = zipFile.getInputStream(zipFile.getEntry(this.time + ".png"));
Bitmap bitmap = BitmapFactory.decodeStream(image, null, getBitmapFactoryOptions());

ZipEntry entry = zipFile.getEntry(this.time + ".json");
BoundsProxy bounds = gson.fromJson(new InputStreamReader(zipFile.getInputStream(entry)), BoundsProxy.class);
```

So the old format is:

- `TIMESTAMP.png`
- `TIMESTAMP.json`
- optionally station JSON blobs like `TIMESTAMP_stationType.json`

## Current V3 nuance

The **decompiled Java legacy loader** is ZIP-oriented, but the **current live V3 precipitation / wind / cloud files** are animated WebP. The safest interpretation is:

1. the legacy Java ZIP loader is still valid for the **legacy renderer** and for ZIP-based layers
2. the **new native `LayerManagerInterface` path** now consumes the newer animated WebP assets directly
3. both formats coexist behind the same high-level overview model

This is why the docs keep the **legacy ZIP path** and **current WebP path** separate.
