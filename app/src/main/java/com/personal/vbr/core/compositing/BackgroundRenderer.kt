package com.personal.vbr.core.compositing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.Log
import com.personal.vbr.media.BackgroundFrameCache

/**
 * Resolves the correct background bitmap for a given video timestamp.
 *
 * Supports four background modes:
 *  - [BackgroundMode.SolidColor]  — fills with a flat ARGB colour
 *  - [BackgroundMode.Image]       — scales a static bitmap to output size
 *  - [BackgroundMode.Blur]        — blurs the subject frame itself (bokeh effect)
 *  - [BackgroundMode.Video]       — fetches from [BackgroundFrameCache]
 *
 * MEMORY:
 *  - Static image backgrounds are scaled once on load, then reused every frame.
 *  - Blur backgrounds reuse a single pre-allocated output bitmap.
 *  - Video backgrounds are sourced from BackgroundFrameCache (LRU, bounded).
 *
 * THREAD: Called from GPU thread inside FramePipeline.
 */
class BackgroundRenderer {

    companion object {
        private const val TAG = "BackgroundRenderer"
        private const val OUTPUT_W = 1280
        private const val OUTPUT_H = 720
    }

    sealed class BackgroundMode {
        data class SolidColor(val color: Int = Color.BLACK) : BackgroundMode()
        data class Image(val bitmap: Bitmap) : BackgroundMode()
        data class Blur(val radius: Float = 20f) : BackgroundMode()
        object Video : BackgroundMode()
    }

    @Volatile var mode: BackgroundMode = BackgroundMode.SolidColor()
    @Volatile var videoCache: BackgroundFrameCache? = null

    // Pre-scaled static image — only re-created when source changes
    private var scaledImageCache: Bitmap? = null
    private var scaledImageSource: Bitmap? = null  // reference for change detection

    // Reusable bitmap for solid colour and blur backgrounds
    private var solidBitmap: Bitmap? = null
    private var blurBitmap: Bitmap? = null

    // The last subject frame, stored so Blur mode can use it
    @Volatile var lastSubjectFrame: Bitmap? = null

    private val colorPaint = Paint()

    /**
     * Returns the background bitmap to composite under the subject for [timestampMs].
     * Returns null to signal "use solid black" (compositor handles null gracefully).
     */
    fun getFrameFor(timestampMs: Long): Bitmap? {
        return when (val m = mode) {
            is BackgroundMode.SolidColor -> getSolidColorBitmap(m.color)
            is BackgroundMode.Image      -> getScaledImage(m.bitmap)
            is BackgroundMode.Blur       -> getBlurredBackground(m.radius)
            is BackgroundMode.Video      -> videoCache?.getFrame(timestampMs)
        }
    }

    private fun getSolidColorBitmap(color: Int): Bitmap {
        val bmp = getOrCreateSolid()
        bmp.eraseColor(color)
        return bmp
    }

    private fun getScaledImage(source: Bitmap): Bitmap {
        // Return cached scaled version if source hasn't changed
        if (source === scaledImageSource && scaledImageCache != null) {
            return scaledImageCache!!
        }

        // Scale to cover OUTPUT_W x OUTPUT_H
        val scaleX = OUTPUT_W.toFloat() / source.width
        val scaleY = OUTPUT_H.toFloat() / source.height
        val scale  = maxOf(scaleX, scaleY)
        val scaledW = (source.width * scale).toInt()
        val scaledH = (source.height * scale).toInt()

        val scaled = Bitmap.createScaledBitmap(source, scaledW, scaledH, true)

        // Crop to output size
        val cropX = (scaledW - OUTPUT_W) / 2
        val cropY = (scaledH - OUTPUT_H) / 2
        val cropped = Bitmap.createBitmap(scaled, cropX, cropY, OUTPUT_W, OUTPUT_H)

        if (scaled !== cropped) scaled.recycle()

        scaledImageCache?.recycle()
        scaledImageCache = cropped
        scaledImageSource = source

        Log.d(TAG, "Background image scaled: ${source.width}x${source.height} → ${OUTPUT_W}x${OUTPUT_H}")
        return cropped
    }

    private fun getBlurredBackground(radius: Float): Bitmap {
        val subject = lastSubjectFrame ?: return getSolidColorBitmap(Color.DKGRAY)
        val output = getOrCreateBlur()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: hardware RenderEffect blur (GPU-accelerated, ~1ms)
            // Applied at the Canvas level — no bitmap copy needed
            val canvas = Canvas(output)
            val paint = Paint().apply {
                renderEffect = RenderEffect.createBlurEffect(
                    radius, radius, Shader.TileMode.CLAMP
                )
            }
            canvas.drawBitmap(subject, 0f, 0f, paint)
        } else {
            // API 29-30: software blur approximation
            // Simple box blur via multiple scale-down/scale-up passes (fast, ~3ms)
            softwareBlur(subject, output, radius)
        }

        return output
    }

    /**
     * Fast software blur via downscale → upscale (Gaussian approximation).
     * Acceptable quality at blur radii > 8px.
     */
    private fun softwareBlur(source: Bitmap, output: Bitmap, radius: Float) {
        val factor = (radius / 20f).coerceIn(0.1f, 0.5f)
        val smallW = (source.width * factor).toInt().coerceAtLeast(1)
        val smallH = (source.height * factor).toInt().coerceAtLeast(1)

        val small = Bitmap.createScaledBitmap(source, smallW, smallH, true)
        val canvas = Canvas(output)
        canvas.drawBitmap(
            Bitmap.createScaledBitmap(small, output.width, output.height, true),
            0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG)
        )
        small.recycle()
    }

    private fun getOrCreateSolid(): Bitmap {
        val b = solidBitmap
        return if (b != null && b.width == OUTPUT_W && b.height == OUTPUT_H) b
        else Bitmap.createBitmap(OUTPUT_W, OUTPUT_H, Bitmap.Config.ARGB_8888).also { solidBitmap = it }
    }

    private fun getOrCreateBlur(): Bitmap {
        val b = blurBitmap
        return if (b != null && b.width == OUTPUT_W && b.height == OUTPUT_H) b
        else Bitmap.createBitmap(OUTPUT_W, OUTPUT_H, Bitmap.Config.ARGB_8888).also { blurBitmap = it }
    }

    fun setVideoBackground(cache: BackgroundFrameCache) {
        videoCache = cache
        mode = BackgroundMode.Video
    }

    fun setImageBackground(bitmap: Bitmap) {
        mode = BackgroundMode.Image(bitmap)
    }

    fun setColorBackground(color: Int) {
        mode = BackgroundMode.SolidColor(color)
    }

    fun setBlurBackground(radius: Float = 20f) {
        mode = BackgroundMode.Blur(radius)
    }

    fun destroy() {
        scaledImageCache?.recycle()
        scaledImageCache = null
        solidBitmap?.recycle()
        solidBitmap = null
        blurBitmap?.recycle()
        blurBitmap = null
        videoCache?.destroy()
        videoCache = null
    }
}
