package com.personal.vbr.core.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult
import java.nio.FloatBuffer

// ---------------------------------------------------------------------------
// Result data class lives here — it's only ever produced by SegmentationEngine
// ---------------------------------------------------------------------------

/**
 * Output of one segmentation pass.
 *
 * @param maskBitmap  ALPHA_8 bitmap where 255 = subject, 0 = background.
 *                    Same dimensions as the input frame.
 * @param confidence  Mean confidence across subject pixels (0f–1f).
 *                    Below ~0.4f the cut is likely poor; UI can warn the user.
 */
data class SegmentationResult(
    val maskBitmap: Bitmap,
    val confidence: Float
)

// ---------------------------------------------------------------------------
// SegmentationEngine
// ---------------------------------------------------------------------------

/**
 * Singleton wrapper around MediaPipe ImageSegmenter (Selfie Segmentation model).
 *
 * LIFECYCLE:
 *  - Initialise once via [init]. The TFLite model is loaded from assets (~2MB).
 *  - Call [segment] from the CPU thread pool (Dispatchers.Default).
 *  - Call [close] from ViewModel.onCleared().
 *
 * PERFORMANCE ON MALI-G57:
 *  - The selfie_segmentation model uses NNAPI delegate which routes to the CPU
 *    on Mali-G57 (no dedicated NPU). Expect 15-25ms per 720p frame.
 *  - We deliberately do NOT run this on the GPU thread — segmentation latency
 *    must never block compositing.
 *
 * MEMORY:
 *  - Input Bitmap is wrapped (not copied) by BitmapImageBuilder.
 *  - Output mask is ALPHA_8 = 1 byte/pixel = ~0.9MB at 720p. We reuse a single
 *    pre-allocated output Bitmap across calls via [maskBitmapCache].
 */
class SegmentationEngine private constructor() {

    companion object {
        private const val TAG = "SegmentationEngine"
        private const val MODEL_ASSET = "mediapipe/selfie_segmentation.tflite"

        @Volatile private var instance: SegmentationEngine? = null

        fun getInstance(): SegmentationEngine =
            instance ?: synchronized(this) {
                instance ?: SegmentationEngine().also { instance = it }
            }
    }

    private var segmenter: ImageSegmenter? = null

    // Reusable ALPHA_8 output bitmap — avoids allocating 0.9MB per frame
    private var maskBitmapCache: Bitmap? = null

    // Track whether model is ready
    @Volatile var isInitialised: Boolean = false
        private set

    /**
     * Initialise the segmenter. Safe to call multiple times — subsequent calls are no-ops.
     * Call from a background coroutine; this loads a ~2MB TFLite model from assets.
     */
    fun init(context: Context) {
        if (isInitialised) return
        synchronized(this) {
            if (isInitialised) return

            try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    // Use NNAPI for hardware-accelerated inference where available.
                    // Falls back to CPU automatically on devices without NPU.
                    .useNnapi()
                    .build()

                val options = ImageSegmenter.ImageSegmenterOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.IMAGE)
                    .setOutputCategoryMask(false)   // we want confidence mask, not category
                    .setOutputConfidenceMasks(true)
                    .build()

                segmenter = ImageSegmenter.createFromOptions(context, options)
                isInitialised = true
                Log.i(TAG, "SegmentationEngine initialised successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialise segmenter", e)
                isInitialised = false
            }
        }
    }

    /**
     * Segment a single frame. Returns null if engine is not initialised.
     *
     * @param frame  ARGB_8888 bitmap at processing resolution (1280×720).
     *               Caller retains ownership — this method does not recycle it.
     */
    fun segment(frame: Bitmap): SegmentationResult? {
        val seg = segmenter ?: run {
            Log.w(TAG, "segment() called before init()")
            return null
        }

        return try {
            val mpImage = BitmapImageBuilder(frame).build()
            val result: ImageSegmenterResult = seg.segment(mpImage)

            // Extract the confidence mask for the "person" category (index 0)
            val confidenceMasks = result.confidenceMasks().orElse(null)
                ?: return null

            val maskBuffer: FloatBuffer = confidenceMasks[0].asFloat32Buffer()
            val width  = frame.width
            val height = frame.height

            // Reuse or allocate the ALPHA_8 output bitmap
            val maskBitmap = getOrCreateMaskBitmap(width, height)

            // Convert float confidence [0,1] → alpha byte [0,255]
            // and accumulate confidence for the result
            var confidenceSum = 0f
            var subjectPixels = 0

            val pixels = IntArray(width * height)
            maskBuffer.rewind()
            for (i in pixels.indices) {
                val conf = maskBuffer.get().coerceIn(0f, 1f)
                val alpha = (conf * 255).toInt()
                pixels[i] = (alpha shl 24) // ALPHA_8 stores in alpha channel
                if (conf > 0.5f) {
                    confidenceSum += conf
                    subjectPixels++
                }
            }
            maskBitmap.setPixels(pixels, 0, width, 0, 0, width, height)

            val meanConfidence = if (subjectPixels > 0) confidenceSum / subjectPixels else 0f

            SegmentationResult(
                maskBitmap  = maskBitmap,
                confidence  = meanConfidence
            )

        } catch (e: Exception) {
            Log.e(TAG, "Segmentation error", e)
            null
        }
    }

    private fun getOrCreateMaskBitmap(width: Int, height: Int): Bitmap {
        val cached = maskBitmapCache
        return if (cached != null && cached.width == width && cached.height == height) {
            cached
        } else {
            cached?.recycle()
            Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8).also {
                maskBitmapCache = it
            }
        }
    }

    fun close() {
        segmenter?.close()
        segmenter = null
        maskBitmapCache?.recycle()
        maskBitmapCache = null
        isInitialised = false
        instance = null
        Log.d(TAG, "SegmentationEngine closed")
    }
}
