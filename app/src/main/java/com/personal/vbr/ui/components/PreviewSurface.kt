package com.personal.vbr.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.util.Log

/**
 * SurfaceView that the Compositor renders into.
 *
 * DESIGN:
 *  - Hardware-accelerated surface. The compositor calls [drawFrame] from the GPU thread.
 *  - Double-buffered by default (SurfaceView's native behaviour).
 *  - Maintains aspect ratio (letterbox/pillarbox) to avoid stretching.
 *
 * THREAD SAFETY:
 *  - [drawFrame] is called from the GPU thread (safe — uses lockCanvas/unlockAndPost).
 *  - All View-lifecycle methods run on main thread as normal.
 */
class PreviewSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "PreviewSurface"
        private const val VIDEO_ASPECT = 16f / 9f  // 1280/720
    }

    @Volatile private var surfaceReady = false
    private val drawRect = Rect()

    init {
        holder.addCallback(this)
        // Keep surface alive during window focus changes
        setZOrderOnTop(false)
    }

    // ---------------------------------------------------------------------------
    // SurfaceHolder.Callback
    // ---------------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        Log.d(TAG, "Surface created: ${width}x${height}")
        computeDrawRect()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        computeDrawRect()
        Log.d(TAG, "Surface changed: ${width}x${height}")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        Log.d(TAG, "Surface destroyed")
    }

    // ---------------------------------------------------------------------------
    // Frame rendering (called from GPU thread)
    // ---------------------------------------------------------------------------

    /**
     * Draw [frame] onto the surface.
     * Thread-safe: uses lockCanvas/unlockAndPost.
     * Drops the frame silently if the surface is not ready.
     */
    fun drawFrame(frame: Bitmap) {
        if (!surfaceReady) return
        val h = holder
        val canvas: Canvas = try {
            h.lockCanvas(null) ?: return
        } catch (e: Exception) {
            return
        }

        try {
            canvas.drawColor(android.graphics.Color.BLACK)
            canvas.drawBitmap(frame, null, drawRect, null)
        } finally {
            h.unlockAndPost(canvas)
        }
    }

    // ---------------------------------------------------------------------------
    // Aspect ratio management
    // ---------------------------------------------------------------------------

    private fun computeDrawRect() {
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW == 0f || viewH == 0f) return

        val viewAspect = viewW / viewH
        val (drawW, drawH) = if (viewAspect > VIDEO_ASPECT) {
            // Pillarbox
            val h = viewH
            val w = h * VIDEO_ASPECT
            w to h
        } else {
            // Letterbox
            val w = viewW
            val h = w / VIDEO_ASPECT
            w to h
        }

        val left   = ((viewW - drawW) / 2).toInt()
        val top    = ((viewH - drawH) / 2).toInt()
        val right  = (left + drawW).toInt()
        val bottom = (top + drawH).toInt()
        drawRect.set(left, top, right, bottom)
    }
}
