package com.maxverstappen.racedash

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import org.json.JSONException
import org.json.JSONObject

object TrackMapRenderer {
    fun render(geoJson: String, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        return try {
            val coordinates = JSONObject(geoJson)
                .getJSONArray("features")
                .getJSONObject(0)
                .getJSONObject("geometry")
                .getJSONArray("coordinates")
            if (coordinates.length() < 3) return null

            val points = (0 until coordinates.length()).map { index ->
                val pair = coordinates.getJSONArray(index)
                pair.getDouble(0) to pair.getDouble(1)
            }
            val minX = points.minOf { it.first }
            val maxX = points.maxOf { it.first }
            val minY = points.minOf { it.second }
            val maxY = points.maxOf { it.second }
            val spanX = (maxX - minX).takeIf { it > 0.0 } ?: 1.0
            val spanY = (maxY - minY).takeIf { it > 0.0 } ?: 1.0
            val padding = minOf(width, height) * 0.14f
            val scale = minOf(
                (width - 2 * padding) / spanX.toFloat(),
                (height - 2 * padding) / spanY.toFloat()
            )
            val drawnWidth = spanX.toFloat() * scale
            val drawnHeight = spanY.toFloat() * scale
            val left = (width - drawnWidth) / 2f
            val top = (height - drawnHeight) / 2f
            val path = Path()
            points.forEachIndexed { index, point ->
                val x = left + (point.first - minX).toFloat() * scale
                val y = height - top - (point.second - minY).toFloat() * scale
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(45, 154, 255)
                style = Paint.Style.STROKE
                strokeWidth = minOf(width, height) * 0.045f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                maskFilter = BlurMaskFilter(minOf(width, height) * 0.035f, BlurMaskFilter.Blur.NORMAL)
            }
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(106, 192, 255)
                style = Paint.Style.STROKE
                strokeWidth = minOf(width, height) * 0.018f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(path, glow)
            canvas.drawPath(path, line)
            val start = points.first()
            val startX = left + (start.first - minX).toFloat() * scale
            val startY = height - top - (start.second - minY).toFloat() * scale
            canvas.drawCircle(startX, startY, minOf(width, height) * 0.035f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(255, 67, 83)
                style = Paint.Style.FILL
            })
            bitmap
        } catch (error: JSONException) {
            Log.w("TrackMapRenderer", "Could not render circuit geometry", error)
            null
        }
    }
}
