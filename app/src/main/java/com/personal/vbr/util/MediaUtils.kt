package com.personal.vbr.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.Image
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Stateless bitmap and colour math utilities.
 * Merges what would otherwise be BitmapUtils + ColorMath — they share no state
 * and are always used together in the same callers.
 *
 * ALLOCATION POLICY:
 *  Functions that return a new Bitmap are marked [ALLOCATES].
 *  Functions that operate in-place are marked [IN_PLACE].
 *  Callers on hot paths (GPU thread, decode thread) should only use [IN_PLACE] variants.
 */
object MediaUtils {

    private const val TAG = "MediaUtils"

    // ---------------------------------------------------------------------------
    // Bitmap scaling
    // ---------------------------------------------------------------------------

    /**
     * [ALLOCATES] Scale [source] to [targetW] x [targetH] using bilinear filtering.
     * Returns a new Bitmap — caller is responsible for recycling.
     */
    fun scaleBitmap(source: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (source.width == targetW && source.height == targetH) return source
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    /**
     * [IN_PLACE] Scale [source] into [dest] (must already be the target size).
     * Zero allocation — reuses [dest] buffer.
     */
    fun scaleBitmapInto(source: Bitmap, dest: Bitmap) {
        require(dest.isMutable) { "Destination bitmap must be mutable" }
        val canvas = Canvas(dest)
        val scaleX = dest.width.toFloat() / source.width
        val scaleY = dest.height.toFloat() / source.height
        val matrix = Matrix().apply { setScale(scaleX, scaleY) }
        canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /**
     * [ALLOCATES] Flip [source] horizontally. Used for subject mirror effect.
     */
    fun flipHorizontal(source: Bitmap): Bitmap {
        val matrix = Matrix().apply { setScale(-1f, 1f, source.width / 2f, source.height / 2f) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * [IN_PLACE] Flip [bitmap] horizontally in place.
     */
    fun flipHorizontalInPlace(bitmap: Bitmap) {
        require(bitmap.isMutable)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (y in 0 until bitmap.height) {
            val rowStart = y * bitmap.width
            val rowEnd   = rowStart + bitmap.width - 1
            var left = rowStart
            var right = rowEnd
            while (left < right) {
                val tmp = pixels[left]
                pixels[left] = pixels[right]
                pixels[right] = tmp
                left++; right--
            }
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    // ---------------------------------------------------------------------------
    // YUV ↔ ARGB conversion (for MediaCodec Image output)
    // ---------------------------------------------------------------------------

    /**
     * [IN_PLACE] Convert a YUV_420_888 [Image] (from MediaCodec) to ARGB_8888 [dest].
     * Handles both planar and semi-planar YUV layouts.
     */
    fun yuv420ToBitmap(image: Image, dest: Bitmap) {
        val planes = image.planes
        val yBuf  = planes[0].buffer
        val uBuf  = planes[1].buffer
        val vBuf  = planes[2].buffer

        val yRowStride = planes[0].rowStride
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride

        val width  = image.width
        val height = image.height
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val yIndex = y * yRowStride + x
                val uvX = x / 2
                val uvY = y / 2
                val uvIndex = uvY * uvRowStride + uvX * uvPixelStride

                val yVal = (yBuf.get(yIndex).toInt() and 0xFF) - 16
                val uVal = (uBuf.get(uvIndex).toInt() and 0xFF) - 128
                val vVal = (vBuf.get(uvIndex).toInt() and 0xFF) - 128

                val r = (1.164f * yVal + 1.596f * vVal).roundToInt().coerceIn(0, 255)
                val g = (1.164f * yVal - 0.813f * vVal - 0.391f * uVal).roundToInt().coerceIn(0, 255)
                val b = (1.164f * yVal + 2.018f * uVal).roundToInt().coerceIn(0, 255)

                pixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        dest.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    /**
     * [IN_PLACE] Convert ARGB_8888 [bitmap] to YUV420 and write into [outputBuffer].
     * Used by VideoEncoder to feed the MediaCodec input buffer.
     */
    fun bitmapToYuv420(bitmap: Bitmap, outputBuffer: ByteBuffer) {
        val width  = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        outputBuffer.clear()

        // Write Y plane
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8)  and 0xFF
            val b =  pixels[i]         and 0xFF
            val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
            outputBuffer.put(y.coerceIn(0, 255).toByte())
        }

        // Write UV planes (4:2:0 sub-sampled)
        for (row in 0 until height step 2) {
            for (col in 0 until width step 2) {
                val r = (pixels[row * width + col] shr 16) and 0xFF
                val g = (pixels[row * width + col] shr 8)  and 0xFF
                val b =  pixels[row * width + col]         and 0xFF
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                outputBuffer.put(u.coerceIn(0, 255).toByte())
                outputBuffer.put(v.coerceIn(0, 255).toByte())
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Color math helpers (HSV / luminance)
    // ---------------------------------------------------------------------------

    /** Convert RGB [0,255] to HSV. Returns FloatArray(3): H[0,360], S[0,1], V[0,1]. */
    fun rgbToHsv(r: Int, g: Int, b: Int): FloatArray {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val delta = max - min

        val h = when {
            delta == 0f -> 0f
            max == rf   -> 60f * (((gf - bf) / delta) % 6)
            max == gf   -> 60f * ((bf - rf) / delta + 2)
            else        -> 60f * ((rf - gf) / delta + 4)
        }.let { if (it < 0) it + 360f else it }

        val s = if (max == 0f) 0f else delta / max
        return floatArrayOf(h, s, max)
    }

    /** Perceived luminance (WCAG formula). Returns [0,1]. */
    fun luminance(r: Int, g: Int, b: Int): Float =
        0.2126f * (r / 255f) + 0.7152f * (g / 255f) + 0.0722f * (b / 255f)

    /** Estimate file size for export settings. Returns size in MB. */
    fun estimateExportSizeMb(durationMs: Long, bitrateBps: Int): Float =
        (bitrateBps / 8f * durationMs / 1000f) / (1024f * 1024f)
}
