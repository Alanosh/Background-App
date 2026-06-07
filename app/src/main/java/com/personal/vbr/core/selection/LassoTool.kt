package com.personal.vbr.core.selection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff

/**
 * Lasso tool — tap point by point to build a closed polygon path.
 *
 * USAGE:
 *  1. Call [addPoint] for each tap.
 *  2. Call [close] to seal the path.
 *  3. Call [buildMask] to get the filled ARGB_8888 result.
 *  4. Pass to [SelectionEngine.applyToolResult].
 *
 * The in-progress path is exposed via [getPath] for live rendering
 * in [SelectionOverlay] without touching the video pipeline.
 */
class LassoTool(
    private val width: Int,
    private val height: Int
) : SelectionTool {

    private val points = mutableListOf<PointF>()
    private val path = Path()
    private var isClosed = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    fun addPoint(x: Float, y: Float) {
        if (isClosed) return
        val pt = PointF(x, y)
        points.add(pt)
        if (points.size == 1) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
    }

    fun close() {
        if (points.size < 3) return
        path.close()
        isClosed = true
    }

    /** Get current path for live overlay rendering — no allocation. */
    fun getPath(): Path = path

    fun isReadyToClose(): Boolean = points.size >= 3

    fun isClosed(): Boolean = isClosed

    /** First point for snapping the close indicator in the UI. */
    fun firstPoint(): PointF? = points.firstOrNull()

    fun buildMask(): Bitmap? {
        if (!isClosed || points.size < 3) return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        canvas.drawPath(path, fillPaint)
        return bmp
    }

    fun reset() {
        points.clear()
        path.reset()
        isClosed = false
    }

    override fun destroy() { reset() }
}
