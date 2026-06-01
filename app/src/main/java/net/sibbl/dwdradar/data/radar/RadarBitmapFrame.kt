package net.sibbl.dwdradar.data.radar

import android.graphics.Bitmap
import net.sibbl.dwdradar.model.GeoBounds
import net.sibbl.dwdradar.model.RadarFrameReference

data class RadarBitmapFrame(
    val reference: RadarFrameReference,
    val bitmap: Bitmap,
    val bounds: GeoBounds
)

