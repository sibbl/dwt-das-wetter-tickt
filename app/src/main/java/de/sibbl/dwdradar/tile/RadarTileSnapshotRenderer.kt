package de.sibbl.dwdradar.tile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.geometry.Rect as ComposeRect
import de.sibbl.dwdradar.R
import de.sibbl.dwdradar.data.radar.RadarBackend
import de.sibbl.dwdradar.data.radar.RadarBitmapFrame
import de.sibbl.dwdradar.data.radar.RadarRepository
import de.sibbl.dwdradar.map.CityCatalog
import de.sibbl.dwdradar.map.GermanyOutlineRepository
import de.sibbl.dwdradar.map.MapViewport
import de.sibbl.dwdradar.model.GeoPoint
import de.sibbl.dwdradar.model.MapCamera
import de.sibbl.dwdradar.model.RadarFrameReference
import de.sibbl.dwdradar.model.ZoomPreset
import de.sibbl.dwdradar.util.TimeFormatters
import java.io.ByteArrayOutputStream

class RadarTileSnapshotRenderer(
    context: Context,
    private val radarRepository: RadarRepository = RadarRepository(context),
    private val outlineRepository: GermanyOutlineRepository = GermanyOutlineRepository(context)
) {
    private val appContext = context.applicationContext

    suspend fun render(reference: RadarFrameReference, sizePx: Int = DEFAULT_SIZE_PX): ByteArray {
        val frame = radarRepository.loadFrame(reference)
        val outlines = outlineRepository.loadOutlines()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        drawSnapshot(
            canvas = Canvas(bitmap),
            sizePx = sizePx,
            frame = frame,
            outlines = outlines
        )
        return bitmap.toPng()
    }

    fun renderFallback(message: String, sizePx: Int = DEFAULT_SIZE_PX): ByteArray {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(SCREEN_BACKGROUND)

        val messageBox = RectF(
            sizePx * 0.12f,
            sizePx * 0.34f,
            sizePx * 0.88f,
            sizePx * 0.66f
        )
        canvas.drawRoundRect(
            messageBox,
            sizePx * 0.08f,
            sizePx * 0.08f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = FOOTER_BACKGROUND
                style = Paint.Style.FILL
            }
        )

        drawCenteredText(
            canvas = canvas,
            text = message,
            centerX = messageBox.centerX(),
            centerY = messageBox.centerY(),
            paint = textPaint(
                sizePx = sizePx,
                textColor = 0xFFFFFFFF.toInt(),
                textSizeFactor = 0.06f,
                isBold = true
            )
        )
        return bitmap.toPng()
    }

    private fun drawSnapshot(
        canvas: Canvas,
        sizePx: Int,
        frame: RadarBitmapFrame,
        outlines: List<List<GeoPoint>>
    ) {
        val mapDiameter = sizePx * 0.74f
        val mapCenterX = sizePx / 2f
        val mapCenterY = sizePx * 0.41f
        val mapRect = RectF(
            mapCenterX - mapDiameter / 2f,
            mapCenterY - mapDiameter / 2f,
            mapCenterX + mapDiameter / 2f,
            mapCenterY + mapDiameter / 2f
        )
        val viewport = MapViewport(
            mapRect = ComposeRect(
                left = mapRect.left,
                top = mapRect.top,
                right = mapRect.right,
                bottom = mapRect.bottom
            ),
            camera = MapCamera(
                center = RadarBackend.defaultBounds.center,
                zoomPreset = ZoomPreset.MID
            )
        )

        canvas.drawColor(SCREEN_BACKGROUND)

        val saveCount = canvas.save()
        canvas.clipPath(
            Path().apply {
                addCircle(mapCenterX, mapCenterY, mapDiameter / 2f, Path.Direction.CW)
            }
        )

        canvas.drawColor(MAP_BACKGROUND)
        drawGraticule(canvas, viewport, sizePx)
        drawGermanyOutlines(canvas, viewport, outlines, sizePx)
        drawRadarFrame(canvas, viewport, frame)
        drawCityLabels(canvas, viewport, sizePx)
        canvas.restoreToCount(saveCount)

        canvas.drawCircle(
            mapCenterX,
            mapCenterY,
            mapDiameter / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = MAP_RING
                style = Paint.Style.STROKE
                strokeWidth = sizePx * 0.008f
            }
        )

        drawFooter(
            canvas = canvas,
            sizePx = sizePx,
            timestampMillis = frame.reference.timestampMillis
        )
    }

    private fun drawGraticule(canvas: Canvas, viewport: MapViewport, sizePx: Int) {
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GRID_COLOR
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.0036f
        }

        listOf(45.0, 50.0, 55.0).forEach { latitude ->
            val left = viewport.toScreen(GeoPoint(latitude, 0.0))
            val right = viewport.toScreen(GeoPoint(latitude, 17.0))
            canvas.drawLine(left.x, left.y, right.x, right.y, strokePaint)
        }
        listOf(0.0, 5.0, 10.0, 15.0).forEach { longitude ->
            val top = viewport.toScreen(GeoPoint(57.0, longitude))
            val bottom = viewport.toScreen(GeoPoint(43.75, longitude))
            canvas.drawLine(top.x, top.y, bottom.x, bottom.y, strokePaint)
        }
    }

    private fun drawGermanyOutlines(
        canvas: Canvas,
        viewport: MapViewport,
        outlines: List<List<GeoPoint>>,
        sizePx: Int
    ) {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GERMANY_FILL
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GERMANY_STROKE
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.004f
        }

        outlines.forEach { outline ->
            if (outline.size < 2) {
                return@forEach
            }
            val path = Path()
            outline.forEachIndexed { index, point ->
                val screen = viewport.toScreen(point)
                if (index == 0) {
                    path.moveTo(screen.x, screen.y)
                } else {
                    path.lineTo(screen.x, screen.y)
                }
            }
            path.close()
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, strokePaint)
        }
    }

    private fun drawRadarFrame(canvas: Canvas, viewport: MapViewport, frame: RadarBitmapFrame) {
        val radarRect = viewport.radarRect(frame.bounds)
        canvas.drawBitmap(
            frame.bitmap,
            null,
            RectF(radarRect.left, radarRect.top, radarRect.right, radarRect.bottom),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isFilterBitmap = true
                alpha = 242
            }
        )
    }

    private fun drawCityLabels(canvas: Canvas, viewport: MapViewport, sizePx: Int) {
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CITY_COLOR
            style = Paint.Style.FILL
        }
        val shadowDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
            style = Paint.Style.FILL
        }
        val labelPaint = textPaint(
            sizePx = sizePx,
            textColor = CITY_COLOR,
            textSizeFactor = 0.036f
        )
        val shadowPaint = textPaint(
            sizePx = sizePx,
            textColor = 0xFF000000.toInt(),
            textSizeFactor = 0.036f
        )

        CityCatalog.visibleFor(ZoomPreset.MID).forEach { city ->
            val point = viewport.toScreen(city.location)
            canvas.drawCircle(point.x + 1f, point.y + 1f, sizePx * 0.007f, shadowDotPaint)
            canvas.drawCircle(point.x, point.y, sizePx * 0.006f, dotPaint)

            val labelX = point.x + sizePx * 0.015f
            val labelY = point.y - sizePx * 0.012f
            canvas.drawText(city.name, labelX + 1f, labelY + 1f, shadowPaint)
            canvas.drawText(city.name, labelX, labelY, labelPaint)
        }
    }

    private fun drawFooter(canvas: Canvas, sizePx: Int, timestampMillis: Long) {
        val footerRect = RectF(
            sizePx * 0.18f,
            sizePx * 0.77f,
            sizePx * 0.82f,
            sizePx * 0.92f
        )
        canvas.drawRoundRect(
            footerRect,
            sizePx * 0.075f,
            sizePx * 0.075f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = FOOTER_BACKGROUND
                style = Paint.Style.FILL
            }
        )

        val footerText = buildString {
            append(appContext.getString(R.string.time_now))
            append(" \u2022 ")
            append(TimeFormatters.absoluteTime(timestampMillis))
        }

        drawCenteredText(
            canvas = canvas,
            text = footerText,
            centerX = footerRect.centerX(),
            centerY = footerRect.centerY(),
            paint = textPaint(
                sizePx = sizePx,
                textColor = 0xFFFFFFFF.toInt(),
                textSizeFactor = 0.05f,
                isBold = true
            )
        )
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        paint: Paint
    ) {
        val fontMetrics = paint.fontMetrics
        val baseline = centerY - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(text, centerX - (paint.measureText(text) / 2f), baseline, paint)
    }

    private fun textPaint(
        sizePx: Int,
        textColor: Int,
        textSizeFactor: Float,
        isBold: Boolean = false
    ): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textAlign = Paint.Align.LEFT
            textSize = sizePx * textSizeFactor
            typeface = if (isBold) {
                android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            } else {
                android.graphics.Typeface.DEFAULT
            }
        }
    }

    private fun Bitmap.toPng(): ByteArray {
        val outputStream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        return outputStream.toByteArray()
    }

    private companion object {
        const val DEFAULT_SIZE_PX = 384

        const val SCREEN_BACKGROUND = 0xFF000000.toInt()
        const val MAP_BACKGROUND = 0xFF09131D.toInt()
        const val GRID_COLOR = 0xFF182230.toInt()
        const val GERMANY_FILL = 0xFF0F1620.toInt()
        const val GERMANY_STROKE = 0xFF41505F.toInt()
        const val CITY_COLOR = 0xFFE8EEF5.toInt()
        const val MAP_RING = 0xFF1A2531.toInt()
        const val FOOTER_BACKGROUND = 0xD9202B38.toInt()
    }
}
