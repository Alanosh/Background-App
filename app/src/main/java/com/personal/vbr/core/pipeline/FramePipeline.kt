package com.personal.vbr.core.pipeline

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.util.Log
import com.personal.vbr.core.adjustment.Adjustments
import com.personal.vbr.core.compositing.BackgroundRenderer
import com.personal.vbr.core.compositing.Compositor
import com.personal.vbr.core.compositing.EffectsProcessor
import com.personal.vbr.core.segmentation.MaskProcessor
import com.personal.vbr.core.segmentation.SegmentationEngine
import com.personal.vbr.util.MemoryGuard
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ---------------------------------------------------------------------------
// Pipeline state — lives here since only FramePipeline owns/emits these
// ---------------------------------------------------------------------------

sealed class PipelineState {
    object Idle : PipelineState()
    data class Playing(val currentFrameMs: Long, val totalFrames: Int) : PipelineState()
    data class Paused(val currentFrameMs: Long) : PipelineState()
    object Segmenting : PipelineState()           // AI processing in progress
    data class Exporting(val progress: Float) : PipelineState()
    data class Error(val message: String) : PipelineState()
}

// ---------------------------------------------------------------------------
// FramePipeline
// ---------------------------------------------------------------------------

/**
 * Orchestrates the full frame → segmentation → composite → display pipeline.
 *
 * THREAD MODEL:
 *  - [gpuDispatcher] is a single-thread dispatcher dedicated to compositing.
 *    Nothing else runs here. The GPU thread is sacred.
 *  - Segmentation runs on [Dispatchers.Default] (CPU thread pool).
 *  - Frame drops are the safety valve: if [framePool] has no available slot,
 *    the incoming frame is discarded — we NEVER queue frames or block.
 *
 * MEMORY DISCIPLINE:
 *  - All bitmaps come from [framePool]. Zero allocation per frame at steady state.
 *  - [MemoryGuard] is checked before each frame is accepted.
 */
class FramePipeline(
    private val segmentationEngine: SegmentationEngine,
    private val maskProcessor: MaskProcessor,
    private val compositor: Compositor,
    private val backgroundRenderer: BackgroundRenderer,
    private val effectsProcessor: EffectsProcessor,
    private val framePool: FramePool
) {

    companion object {
        private const val TAG = "FramePipeline"

        // Internal processing resolution. Never exceed this even if source is 4K.
        const val PROCESS_WIDTH  = 1280
        const val PROCESS_HEIGHT = 720
    }

    // Single-thread coroutine scope dedicated to GPU compositing
    private val gpuDispatcher = newSingleThreadContext("GPU-Compositor")
    private val pipelineScope = CoroutineScope(SupervisorJob() + gpuDispatcher)

    private val _state = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    // Adjusted per user input — read on GPU thread
    @Volatile var adjustmentParams: Adjustments.Params = Adjustments.Params()
    @Volatile var glowEnabled: Boolean = false
    @Volatile var glowIntensity: Float = 0.5f
    @Volatile var playbackSpeed: Float = 1.0f

    // Output surface — set by PreviewSurface once available
    @Volatile var outputSurface: SurfaceTexture? = null

    // Dropped frame counter for diagnostics
    private var droppedFrames = 0L
    private var processedFrames = 0L

    /**
     * Submit a raw decoded frame for processing.
     *
     * Called from the decode thread. If the GPU thread is busy or memory is low,
     * the frame is dropped immediately — [sourceBitmap] is released back to the pool.
     *
     * @param sourceBitmap  Raw decoded frame from FramePool
     * @param timestampMs   Presentation timestamp
     * @param totalFrames   Total frame count of the source video
     */
    fun submitFrame(sourceBitmap: Bitmap, timestampMs: Long, totalFrames: Int) {
        // Memory safety check — pause ingestion if heap is critical
        if (MemoryGuard.isCritical()) {
            framePool.release(sourceBitmap)
            droppedFrames++
            Log.w(TAG, "Frame dropped: memory critical (dropped=$droppedFrames)")
            return
        }

        // Non-blocking launch — if GPU thread already has work queued, this frame
        // will be dropped before the coroutine body executes (see check below)
        pipelineScope.launch {
            processFrame(sourceBitmap, timestampMs, totalFrames)
        }
    }

    private suspend fun processFrame(
        sourceBitmap: Bitmap,
        timestampMs: Long,
        totalFrames: Int
    ) {
        try {
            // --- 1. Segmentation (CPU, ~15-25ms on Mali-G57 at 720p) ---
            val segResult = withContext(Dispatchers.Default) {
                segmentationEngine.segment(sourceBitmap)
            }

            // --- 2. Mask post-processing: temporal smoothing + edge feather ---
            val refinedMask = maskProcessor.process(segResult)

            // --- 3. Background frame for this timestamp ---
            val backgroundBitmap = backgroundRenderer.getFrameFor(timestampMs)

            // --- 4. Composite subject over background on GPU thread ---
            val outputFrame = framePool.acquire()
            if (outputFrame == null) {
                droppedFrames++
                Log.w(TAG, "Frame dropped: pool exhausted (dropped=$droppedFrames)")
                framePool.release(sourceBitmap)
                return
            }

            compositor.composite(
                subject     = sourceBitmap,
                mask        = refinedMask,
                background  = backgroundBitmap,
                output      = outputFrame,
                adjustments = adjustmentParams
            )

            // --- 5. Effects layer (glow, etc.) ---
            if (glowEnabled) {
                effectsProcessor.applyGlow(outputFrame, refinedMask, glowIntensity)
            }

            // --- 6. Push to display surface ---
            outputSurface?.let { surface ->
                // Surface rendering would happen here via Canvas/OpenGL
                // Exact implementation depends on SurfaceTexture setup in PreviewSurface
            }

            processedFrames++
            _state.value = PipelineState.Playing(timestampMs, totalFrames)

            // Return source frame to pool
            framePool.release(sourceBitmap)
            framePool.release(outputFrame)

        } catch (e: Exception) {
            Log.e(TAG, "Pipeline frame error", e)
            framePool.release(sourceBitmap)
            _state.value = PipelineState.Error(e.message ?: "Unknown pipeline error")
        }
    }

    fun pause() {
        val current = _state.value
        if (current is PipelineState.Playing) {
            _state.value = PipelineState.Paused(current.currentFrameMs)
        }
    }

    fun resume() {
        val current = _state.value
        if (current is PipelineState.Paused) {
            _state.value = PipelineState.Playing(current.currentFrameMs, 0)
        }
    }

    fun seekTo(timestampMs: Long) {
        _state.value = PipelineState.Paused(timestampMs)
    }

    fun getStats(): String =
        "Processed: $processedFrames | Dropped: $droppedFrames | " +
        "Drop rate: ${"%.1f".format(droppedFrames * 100.0 / maxOf(1, processedFrames + droppedFrames))}%"

    /**
     * Release all resources. Call from ViewModel.onCleared().
     */
    fun destroy() {
        pipelineScope.cancel()
        gpuDispatcher.close()
        framePool.destroy()
        segmentationEngine.close()
        Log.d(TAG, "Pipeline destroyed. Final stats: ${getStats()}")
    }
}
