package com.personal.vbr.core.selection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Magnetic Lasso — snaps to high-contrast edges in the source frame.
 *
 * ALGORITHM:
 *  1. On [setSourceFrame], compute a simple Sobel edge magnitude map (CPU, one-time cost).
 *  2. As the user drags, find the highest-magnitude pixel within [SNAP_RADIUS] of the cursor.
 *  3. Snap the path point to that edge pixel.
 *
 * PERFORMANCE:
 *  - Edge map computation runs once on a still frame, NOT per-frame. ~10ms at 720p.
 *  - Snap search is O(SNAP_RADIUS²) per touch event — negligible.
 *  - Run [setSourceFrame] on Dispatchers.Default, not main thread.
 *
 * IMPORTANT: This tool operates on a STILL FRAME (the paused frame when the user
 * activates the tool). It never processes live video frames.
 */
class MagneticLassoTool(
    private val width: Int,
    private val height: Int
) : SelectionTool {

    companion object {
        private const val SNAP_RADIUS = 20   // pixels to search around cursor
        private const val EDGE_THRESHOLD = 30 // Sobel magnitude to consider an edge
    }

    // Edge magnitude map: 0 = flat, 255 = hard edge. Same dims as source frame.
    private var edgeMap: ByteArray? = null
    private val points = mutableListOf<PointF>()
    private val path = Path()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    /**
     * Pre-compute edge map from a still frame.
     * Call from Dispatchers.Default before the user starts drawing.
     */
    fun setSourceFrame(frame: Bitmap) {
        val w = frame.width
        val h = frame.height
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, 0, 0, w, h)

        val edges = ByteArray(w * h)

        // Sobel edge detection (luminance-based)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx = sobelX(pixels, x, y, w)
                val gy = sobelY(pixels, x, y, w)
                val mag = sqrt((gx * gx + gy * gy).toDouble()).toInt().coerceIn(0, 255)
                edges[y * w + x] = mag.toByte()
            }
        }

        edgeMap = edges
    }

    /**
     * Add a user touch point. Snaps to nearest strong edge within [SNAP_RADIUS].
     */
    fun addPoint(x: Float, y: Float) {
        val snapped = snapToEdge(x, y) ?: PointF(x, y)
        points.add(snapped)
        if (points.size == 1) path.moveTo(snapped.x, snapped.y)
        else path.lineTo(snapped.x, snapped.y)
    }

    private fun snapToEdge(cx: Float, cy: Float): PointF? {
        val map = edgeMap ?: return null
        val startX = (cx - SNAP_RADIUS).toInt().coerceAtLeast(0)
        val endX   = (cx + SNAP_RADIUS).toInt().coerceAtMost(width - 1)
        val startY = (cy - SNAP_RADIUS).toInt().coerceAtLeast(0)
        val endY   = (cy + SNAP_RADIUS).toInt().coerceAtMost(height - 1)

        var bestMag = EDGE_THRESHOLD
        var bestX = cx
        var bestY = cy
        var found = false

        for (py in startY..endY) {
            for (px in startX..endX) {
                val mag = map[py * width + px].toInt() and 0xFF
                if (mag > bestMag) {
                    bestMag = mag
                    bestX = px.toFloat()
                    bestY = py.toFloat()
                    found = true
                }
            }
        }
        return if (found) PointF(bestX, bestY) else null
    }

    fun close() {
        if (points.size >= 3) path.close()
    }

    fun getPath(): Path = path

    fun buildMask(): Bitmap? {
        if (points.size < 3) return null
        val closedPath = Path(path).apply { close() }
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.BLACK)
            drawPath(closedPath, fillPaint)
        }
        return bmp
    }

    // Sobel kernel helpers
    private fun luma(pixel: Int) =
        (0.299 * ((pixel shr 16) and 0xFF) +
         0.587 * ((pixel shr 8) and 0xFF) +
         0.114 * (pixel and 0xFF)).toInt()

    private fun sobelX(p: IntArray, x: Int, y: Int, w: Int): Int =
        -luma(p[(y-1)*w+(x-1)]) + luma(p[(y-1)*w+(x+1)]) +
        -2*luma(p[y*w+(x-1)])   + 2*luma(p[y*w+(x+1)]) +
        -luma(p[(y+1)*w+(x-1)]) + luma(p[(y+1)*w+(x+1)])

    private fun sobelY(p: IntArray, x: Int, y: Int, w: Int): Int =
        -luma(p[(y-1)*w+(x-1)]) - 2*luma(p[(y-1)*w+x]) - luma(p[(y-1)*w+(x+1)]) +
         luma(p[(y+1)*w+(x-1)]) + 2*luma(p[(y+1)*w+x]) + luma(p[(y+1)*w+(x+1)])

    fun reset() {
        points.clear()
        path.reset()
        edgeMap = null
    }

    override fun destroy() { reset() }
}
