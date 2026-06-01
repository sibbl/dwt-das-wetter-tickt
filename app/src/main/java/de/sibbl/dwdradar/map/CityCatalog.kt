package de.sibbl.dwdradar.map

import de.sibbl.dwdradar.model.GeoPoint
import de.sibbl.dwdradar.model.ZoomPreset

data class CityLabel(
    val name: String,
    val location: GeoPoint,
    val minimumZoom: ZoomPreset = ZoomPreset.FAR
)

object CityCatalog {
    val labels: List<CityLabel> = listOf(
        CityLabel("Hamburg", GeoPoint(53.5511, 9.9937)),
        CityLabel("Berlin", GeoPoint(52.5200, 13.4050)),
        CityLabel("Frankfurt", GeoPoint(50.1109, 8.6821)),
        CityLabel("München", GeoPoint(48.1351, 11.5820)),
        CityLabel("Köln", GeoPoint(50.9375, 6.9603), ZoomPreset.MID),
        CityLabel("Stuttgart", GeoPoint(48.7758, 9.1829), ZoomPreset.MID),
        CityLabel("Leipzig", GeoPoint(51.3397, 12.3731), ZoomPreset.MID),
        CityLabel("Dresden", GeoPoint(51.0504, 13.7373), ZoomPreset.MID),
        CityLabel("Bremen", GeoPoint(53.0793, 8.8017), ZoomPreset.MID),
        CityLabel("Hannover", GeoPoint(52.3759, 9.7320), ZoomPreset.MID),
        CityLabel("Düsseldorf", GeoPoint(51.2277, 6.7735), ZoomPreset.MID),
        CityLabel("Erfurt", GeoPoint(50.9848, 11.0299), ZoomPreset.MID),
        CityLabel("Magdeburg", GeoPoint(52.1205, 11.6276), ZoomPreset.MID),
        CityLabel("Dortmund", GeoPoint(51.5136, 7.4653), ZoomPreset.MID),
        CityLabel("Nürnberg", GeoPoint(49.4521, 11.0767), ZoomPreset.NEAR),
        CityLabel("Freiburg", GeoPoint(47.9990, 7.8421), ZoomPreset.NEAR),
        CityLabel("Kiel", GeoPoint(54.3233, 10.1228), ZoomPreset.NEAR),
        CityLabel("Rostock", GeoPoint(54.0924, 12.0991), ZoomPreset.NEAR),
        CityLabel("Saarbrücken", GeoPoint(49.2402, 6.9969), ZoomPreset.NEAR),
        CityLabel("Amsterdam", GeoPoint(52.3676, 4.9041), ZoomPreset.NEAR),
        CityLabel("Brüssel", GeoPoint(50.8503, 4.3517), ZoomPreset.NEAR),
        CityLabel("Prag", GeoPoint(50.0755, 14.4378), ZoomPreset.NEAR),
        CityLabel("Zürich", GeoPoint(47.3769, 8.5417), ZoomPreset.NEAR)
    )

    fun visibleFor(zoomPreset: ZoomPreset): List<CityLabel> {
        return labels.filter { label ->
            when (label.minimumZoom) {
                ZoomPreset.FAR -> true
                ZoomPreset.MID -> zoomPreset != ZoomPreset.FAR
                ZoomPreset.NEAR -> zoomPreset == ZoomPreset.NEAR
            }
        }
    }
}
