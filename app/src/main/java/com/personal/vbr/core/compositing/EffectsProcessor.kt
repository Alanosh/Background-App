package com.personal.vbr.core.compositing

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Log

/**
 * Applies post-composite visual effects to the output frame.
 *
 * CURRENT EFFECTS:
 *  1. Subject Glow — renders a coloured bloom around the subject boundary.
 *     Algorithm: extract subject silhouette → expand via blur → tint → blend under/over subject.
 *  2. Subject Flip — horizontal mirror of the subject layer before compositing.
 *     (Applied in Compositor pre-pass, not here.)
 *
 * PERFORMANCE:
 *  - Glow uses BlurMaskFilter on an ALPHA_8 layer, then one SRC_OVER composite.
 *    Total cost ~4ms at 720p on Mali-G57.
 *  - All bitmaps are pre-allocated and reused per call.
 *
 * THREAD: Called from GPU thread after Compositor.composite().
 */
class EffectsProcessor {

    companion object {
        private const val TAG = "EffectsProcessor"
    }

    // Reusable offscreen bitmaps for glow calculation
    private var glowLayerBitmap: Bitmap? = null

    // Glow paint (BlurMaskFilter applied dynamically)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }

    // Cached glow radius for change detection (recreate BlurMaskFilter only when changed)
    private var cachedGlowRadius = -1f

    /**
     * Applies a glow bloom around the subject onto [outputFrame] in-place.
     *
     * @param outputFrame  Composited frame (ARGB_8888) — modified in-place
     * @param mask         Subject mask (ALPHA_8) — defines glow boundary
     * @param intensity    0f = none, 1f = full. Maps to blur radius 4-24px and alpha 60-200
     * @param glowColor    Tint colour of the glow (default: warm white)
     */
    fun applyGlow(
        outputFrame: Bitmap,
        mask: Bitmap?,
        intensity: Float,
        glowColor: Int = Color.argb(180, 255, 240, 200)
    ) {
        if (mask == null || intensity <= 0f) return

        val w = outputFrame.width
        val h = outputFrame.height

        val blurRadius = (4f + intensity * 20f)  // 4px → 24px
        val glowAlpha  = (60 + intensity * 140).toInt().coerceIn(0, 255)

        // Recreate BlurMaskFilter only if radius changed (relatively expensive)
        if (blurRadius != cachedGlowRadius) {
            glowPaint.maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.OUTER)
            cachedGlowRadius = blurRadius
        }
        glowPaint.alpha = glowAlpha
        glowPaint.color = glowColor

        val glowLayer = getOrCreateGlowLayer(w, h)

        // Draw expanded glow silhouette onto transparent layer
        val glowCanvas = Canvas(glowLayer)
        glowCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
        glowCanvas.drawBitmap(mask, 0f, 0f, glowPaint)

        // Blend glow layer onto output using SCREEN mode (brightens, never darkens)
        val canvas = Canvas(outputFrame)
        canvas.drawBitmap(glowLayer, 0f, 0f, overlayPaint)
    }

    /**
     * Applies a speed-ramping visual hint (subtle motion blur) — placeholder for future.
     */
    fun applySpeedEffect(outputFrame: Bitmap, speedMultiplier: Float) {
        if (speedMultiplier == 1.0f) return
        // TODO: apply subtle horizontal motion-blur smear for 2x speed, sharpen for 0.5x
    }

    private fun getOrCreateGlowLayer(w: Int, h: Int): Bitmap {
        val existing = glowLayerBitmap
        return if (existing != null && existing.width == w && existing.height == h) {
            existing
        } else {
            existing?.recycle()
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                glowLayerBitmap = it
            }
        }
    }

    fun destroy() {
        glowLayerBitmap?.recycle()
        glowLayerBitmap = null
        Log.d(TAG, "EffectsProcessor destroyed")
    }
}
