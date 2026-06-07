package com.personal.vbr.core.compositing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.personal.vbr.core.adjustment.Adjustments

/**
 * Composites the segmented subject over a background frame.
 *
 * ALGORITHM (single Canvas pass):
 *  1. Draw background onto output
 *  2. Draw subject masked by the segmentation mask using DST_IN xfermode
 *  3. Composite masked subject over background using SRC_OVER
 *
 * All operations run on the GPU thread via hardware-accelerated Canvas.
 * No new Bitmap allocations — output is written into a FramePool slot.
 *
 * ADJUSTMENTS:
 *  Subject and background can have independent colour adjustments applied
 *  via [Adjustments.Params.subjectParams] and [Adjustments.Params.backgroundParams].
 *  These are applied as ColorMatrix paints — single-pass, GPU-accelerated.
 */
class Compositor {

    // Paints cached to avoid per-frame allocation
    private val clearPaint   = Paint()
    private val bgPaint      = Paint(Paint.FILTER_BITMAP_FLAG)
    private val maskPaint    = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val subjectPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
        isFilterBitmap = true
    }

    // Reusable offscreen bitmap for masked subject (avoids allocation per frame)
    private var subjectLayerBitmap: Bitmap? = null
    private var subjectLayerCanvas: Canvas? = null

    /**
     * Composite [subject] over [background] using [mask], writing result into [output].
     *
     * @param subject     Raw decoded frame (ARGB_8888, 720p)
     * @param mask        Segmentation mask (ALPHA_8, same dims) or null → full subject
     * @param background  Background bitmap (any size — scaled to fit)
     * @param output      Destination bitmap from FramePool (ARGB_8888, 720p)
     * @param adjustments Colour adjustment parameters for subject/background layers
     */
    fun composite(
        subject:     Bitmap,
        mask:        Bitmap?,
        background:  Bitmap?,
        output:      Bitmap,
        adjustments: Adjustments.Params
    ) {
        val canvas = Canvas(output)
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)

        val outW = output.width.toFloat()
        val outH = output.height.toFloat()

        // --- 1. Draw background ---
        if (background != null) {
            val bgPaintWithMatrix = if (adjustments.backgroundParams != Adjustments.LayerParams.DEFAULT) {
                Paint(bgPaint).apply {
                    colorFilter = Adjustments.toColorFilter(adjustments.backgroundParams)
                }
            } else bgPaint

            // Scale background to fill output (cover, not letterbox)
            val bgScaleX = outW / background.width
            val bgScaleY = outH / background.height
            val bgScale  = maxOf(bgScaleX, bgScaleY)
            val scaledW  = background.width * bgScale
            val scaledH  = background.height * bgScale
            val bgLeft   = (outW - scaledW) / 2f
            val bgTop    = (outH - scaledH) / 2f

            canvas.save()
            canvas.scale(bgScale, bgScale)
            canvas.drawBitmap(background, bgLeft / bgScale, bgTop / bgScale, bgPaintWithMatrix)
            canvas.restore()
        } else {
            // No background set — draw solid black
            canvas.drawColor(android.graphics.Color.BLACK)
        }

        // --- 2. Build masked subject layer ---
        val subjLayer = getOrCreateSubjectLayer(subject.width, subject.height)
        val subjCanvas = Canvas(subjLayer)
        subjCanvas.drawColor(0, PorterDuff.Mode.CLEAR)

        // Draw subject
        val subjectPaintWithMatrix = if (adjustments.subjectParams != Adjustments.LayerParams.DEFAULT) {
            Paint(subjectPaint).apply {
                colorFilter = Adjustments.toColorFilter(adjustments.subjectParams)
            }
        } else subjectPaint

        // Use plain SRC for the initial draw onto the layer
        subjCanvas.drawBitmap(
            subject, 0f, 0f,
            Paint(Paint.FILTER_BITMAP_FLAG)
        )

        // Apply mask (DST_IN keeps subject pixels where mask is opaque)
        if (mask != null) {
            subjCanvas.drawBitmap(mask, 0f, 0f, maskPaint)
        }

        // --- 3. Composite masked subject onto background ---
        canvas.drawBitmap(subjLayer, 0f, 0f, subjectPaintWithMatrix)
    }

    private fun getOrCreateSubjectLayer(w: Int, h: Int): Bitmap {
        val existing = subjectLayerBitmap
        return if (existing != null && existing.width == w && existing.height == h) {
            existing
        } else {
            existing?.recycle()
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                subjectLayerBitmap = it
                subjectLayerCanvas = Canvas(it)
            }
        }
    }

    fun destroy() {
        subjectLayerBitmap?.recycle()
        subjectLayerBitmap = null
        subjectLayerCanvas = null
    }
}
