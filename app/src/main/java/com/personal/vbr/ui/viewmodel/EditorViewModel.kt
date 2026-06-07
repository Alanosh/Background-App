package com.personal.vbr.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.vbr.core.adjustment.Adjustments
import com.personal.vbr.core.compositing.BackgroundRenderer
import com.personal.vbr.core.compositing.Compositor
import com.personal.vbr.core.compositing.EffectsProcessor
import com.personal.vbr.core.pipeline.FramePipeline
import com.personal.vbr.core.pipeline.FramePool
import com.personal.vbr.core.pipeline.PipelineState
import com.personal.vbr.core.segmentation.MaskProcessor
import com.personal.vbr.core.segmentation.SegmentationEngine
import com.personal.vbr.core.selection.SelectionEngine
import com.personal.vbr.media.BackgroundFrameCache
import com.personal.vbr.media.ExportManager
import com.personal.vbr.media.VideoDecoder
import com.personal.vbr.media.VideoEncoder
import com.personal.vbr.util.MemoryGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single ViewModel for the editor screen.
 * Owns the pipeline, decoder, and export state — survives configuration changes.
 *
 * UI LAYER CONTRACT:
 *  - UI reads state via StateFlow (never calls pipeline directly).
 *  - UI calls public fun methods on this ViewModel only.
 *  - ViewModel translates UI actions into pipeline calls.
 *
 * EXPORT STATE is managed here (not a separate ViewModel) because export is simply
 * a mode the editor enters — sharing the same ViewModel avoids passing data between two.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    // ---------------------------------------------------------------------------
    // Core components (built once, reused for session)
    // ---------------------------------------------------------------------------

    private val segmentationEngine = SegmentationEngine.getInstance()
    private val maskProcessor      = MaskProcessor()
    private val compositor         = Compositor()
    private val backgroundRenderer = BackgroundRenderer()
    private val effectsProcessor   = EffectsProcessor()

    private val framePool = FramePool(
        width  = FramePipeline.PROCESS_WIDTH,
        height = FramePipeline.PROCESS_HEIGHT
    )

    val pipeline = FramePipeline(
        segmentationEngine = segmentationEngine,
        maskProcessor      = maskProcessor,
        compositor         = compositor,
        backgroundRenderer = backgroundRenderer,
        effectsProcessor   = effectsProcessor,
        framePool          = framePool
    )

    private var videoDecoder: VideoDecoder? = null
    private var exportManager: ExportManager? = null
    private var selectionEngine: SelectionEngine? = null

    // ---------------------------------------------------------------------------
    // UI State flows
    // ---------------------------------------------------------------------------

    val pipelineState: StateFlow<PipelineState> = pipeline.state

    private val _videoLoaded   = MutableStateFlow(false)
    val videoLoaded: StateFlow<Boolean> = _videoLoaded.asStateFlow()

    private val _durationMs    = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _currentMs     = MutableStateFlow(0L)
    val currentMs: StateFlow<Long> = _currentMs.asStateFlow()

    private val _exportProgress = MutableStateFlow<Float?>(null)
    val exportProgress: StateFlow<Float?> = _exportProgress.asStateFlow()

    private val _exportComplete = MutableStateFlow<Uri?>(null)
    val exportComplete: StateFlow<Uri?> = _exportComplete.asStateFlow()

    private val _exportError    = MutableStateFlow<String?>(null)
    val exportError: StateFlow<String?> = _exportError.asStateFlow()

    private val _backgroundCacheProgress = MutableStateFlow<Float?>(null)
    val backgroundCacheProgress: StateFlow<Float?> = _backgroundCacheProgress.asStateFlow()

    // Currently selected tool label for UI button states
    private val _activeTool = MutableStateFlow<ActiveTool>(ActiveTool.NONE)
    val activeTool: StateFlow<ActiveTool> = _activeTool.asStateFlow()

    // Adjustment params — two-way bound to AdjustmentPanel sliders
    private val _adjustments = MutableStateFlow(Adjustments.Params())
    val adjustments: StateFlow<Adjustments.Params> = _adjustments.asStateFlow()

    // Effects params
    private val _glowEnabled   = MutableStateFlow(false)
    val glowEnabled: StateFlow<Boolean> = _glowEnabled.asStateFlow()

    private val _glowIntensity = MutableStateFlow(0.5f)
    val glowIntensity: StateFlow<Float> = _glowIntensity.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _subjectFlipped = MutableStateFlow(false)
    val subjectFlipped: StateFlow<Boolean> = _subjectFlipped.asStateFlow()

    // ---------------------------------------------------------------------------
    // Initialisation
    // ---------------------------------------------------------------------------

    init {
        // Initialise segmentation engine on background thread
        viewModelScope.launch {
            segmentationEngine.init(application)
        }

        // Wire MemoryGuard callbacks
        MemoryGuard.onWarning  = { framePool.shrinkToTwo() }
        MemoryGuard.onCritical = { pipeline.pause() }
        MemoryGuard.onFatal    = {
            pipeline.pause()
            videoDecoder?.pause()
        }
    }

    // ---------------------------------------------------------------------------
    // Video loading
    // ---------------------------------------------------------------------------

    fun loadVideo(uri: Uri) {
        viewModelScope.launch {
            val decoder = VideoDecoder(getApplication(), pipeline, framePool)
                .also { videoDecoder = it }

            val ok = decoder.prepare(uri)
            if (!ok) return@launch

            selectionEngine = SelectionEngine(
                FramePipeline.PROCESS_WIDTH,
                FramePipeline.PROCESS_HEIGHT
            )

            _durationMs.value = decoder.durationMs
            _videoLoaded.value = true
            decoder.startPlayback()
        }
    }

    // ---------------------------------------------------------------------------
    // Playback controls
    // ---------------------------------------------------------------------------

    fun play()  { videoDecoder?.resume(); pipeline.resume() }
    fun pause() { videoDecoder?.pause();  pipeline.pause() }

    fun seekTo(ms: Long) {
        _currentMs.value = ms
        videoDecoder?.seekTo(ms)
        pipeline.seekTo(ms)
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        videoDecoder?.setPlaybackSpeed(speed)
        pipeline.playbackSpeed = speed
    }

    // ---------------------------------------------------------------------------
    // Background
    // ---------------------------------------------------------------------------

    fun setColorBackground(color: Int) = backgroundRenderer.setColorBackground(color)
    fun setImageBackground(bitmap: Bitmap) = backgroundRenderer.setImageBackground(bitmap)
    fun setBlurBackground(radius: Float) = backgroundRenderer.setBlurBackground(radius)

    fun setVideoBackground(uri: Uri) {
        val cache = BackgroundFrameCache(getApplication())
        cache.onProgress = { _backgroundCacheProgress.value = it }
        cache.onReady    = { _backgroundCacheProgress.value = null }
        backgroundRenderer.setVideoBackground(cache)
        cache.prepare(uri)
    }

    // ---------------------------------------------------------------------------
    // Adjustments
    // ---------------------------------------------------------------------------

    fun updateSubjectAdjustments(params: Adjustments.LayerParams) {
        val current = _adjustments.value
        val updated = current.copy(subjectParams = params)
        _adjustments.value = updated
        pipeline.adjustmentParams = updated
    }

    fun updateBackgroundAdjustments(params: Adjustments.LayerParams) {
        val current = _adjustments.value
        val updated = current.copy(backgroundParams = params)
        _adjustments.value = updated
        pipeline.adjustmentParams = updated
    }

    // ---------------------------------------------------------------------------
    // Effects
    // ---------------------------------------------------------------------------

    fun setGlowEnabled(enabled: Boolean) {
        _glowEnabled.value = enabled
        pipeline.glowEnabled = enabled
    }

    fun setGlowIntensity(intensity: Float) {
        _glowIntensity.value = intensity
        pipeline.glowIntensity = intensity
    }

    fun toggleSubjectFlip() {
        _subjectFlipped.value = !_subjectFlipped.value
        // Compositor reads this flag on next frame
    }

    // ---------------------------------------------------------------------------
    // Selection tools
    // ---------------------------------------------------------------------------

    fun activateLasso()         { selectionEngine?.activateLasso();         _activeTool.value = ActiveTool.LASSO }
    fun activateMagneticLasso() { selectionEngine?.activateMagneticLasso(); _activeTool.value = ActiveTool.MAGNETIC }
    fun activateBrush()         { selectionEngine?.activateBrush();         _activeTool.value = ActiveTool.BRUSH }
    fun deactivateTool()        { selectionEngine?.deactivateTool();        _activeTool.value = ActiveTool.NONE }

    fun undoSelection(): Boolean = selectionEngine?.undo() ?: false
    fun canUndo(): Boolean = selectionEngine?.canUndo() ?: false

    fun getSelectionEngine(): SelectionEngine? = selectionEngine

    // ---------------------------------------------------------------------------
    // Export
    // ---------------------------------------------------------------------------

    fun startExport(
        resolution: VideoEncoder.ExportResolution,
        trimStartMs: Long = 0L,
        trimEndMs: Long = -1L,
        sourceUri: Uri
    ) {
        _exportProgress.value = 0f
        _exportComplete.value = null
        _exportError.value    = null

        val manager = ExportManager(getApplication(), sourceUri, segmentationEngine, backgroundRenderer)
            .also { exportManager = it }

        manager.onProgress = { _exportProgress.value = it }
        manager.onComplete = { uri ->
            _exportProgress.value = null
            _exportComplete.value = uri
        }
        manager.onError = { msg ->
            _exportProgress.value = null
            _exportError.value    = msg
        }

        manager.startExport(resolution, trimStartMs, trimEndMs)
    }

    fun cancelExport() {
        exportManager?.cancel()
        _exportProgress.value = null
    }

    fun clearExportResult() {
        _exportComplete.value = null
        _exportError.value    = null
    }

    // ---------------------------------------------------------------------------
    // Cleanup
    // ---------------------------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
        pipeline.destroy()
        videoDecoder?.destroy()
        exportManager?.destroy()
        selectionEngine?.destroy()
        backgroundRenderer.destroy()
        compositor.destroy()
        effectsProcessor.destroy()
        maskProcessor.destroy()
        segmentationEngine.close()
    }

    enum class ActiveTool { NONE, LASSO, MAGNETIC, BRUSH }
}
