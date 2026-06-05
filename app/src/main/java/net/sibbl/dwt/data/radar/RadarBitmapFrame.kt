package net.sibbl.dwt.data.radar

import android.graphics.Bitmap
import net.sibbl.dwt.model.GeoBounds
import net.sibbl.dwt.model.RadarFrameReference

data class RadarBitmapFrame(
    val reference: RadarFrameReference,
    val bitmap: Bitmap,
    val bounds: GeoBounds
)

