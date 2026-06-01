package de.sibbl.dwdradar.data.radar

import android.graphics.Bitmap
import de.sibbl.dwdradar.model.GeoBounds
import de.sibbl.dwdradar.model.RadarFrameReference

data class RadarBitmapFrame(
    val reference: RadarFrameReference,
    val bitmap: Bitmap,
    val bounds: GeoBounds
)

