package com.personal.vbr.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.*
import com.personal.vbr.core.adjustment.Adjustments
import com.personal.vbr.core.selection.BrushTool
import com.personal.vbr.core.selection.LassoTool
import com.personal.vbr.core.selection.SelectionEngine

// =============================================================================
// FrameScrubber — seek bar with time display
// =============================================================================

/**
 * Horizontal seek bar that reports timestamps in milliseconds.
 * Thin wrapper around SeekBar with ms↔progress conversion.
 */
class FrameScrubber @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onSeekListener: ((Long) -> Unit)? = null

    private val seekBar  = SeekBar(context)
    private val tvTime   = TextView(context)
    private var durationMs = 0L

    init {
        orientation = VERTICAL
        addView(seekBar,  LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(tvTime,   LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        seekBar.max = 1000
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && durationMs > 0) {
                    val ms = (progress / 1000f * durationMs).toLong()
                    tvTime.text = formatMs(ms)
                    onSeekListener?.invoke(ms)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    fun setDuration(ms: Long) { durationMs = ms }

    fun setPosition(ms: Long) {
        if (durationMs > 0) {
            val progress = ((ms.toFloat() / durationMs) * 1000).toInt()
            seekBar.progress = progress
            tvTime.text = formatMs(ms)
        }
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}

// =============================================================================
// SelectionOverlay — independent Canvas layer for tool drawing
// =============================================================================

/**
 * Transparent Canvas layer drawn over PreviewSurface.
 * Handles touch routing to the active selection tool.
 * NEVER touches the video pipeline — draw events are completely isolated.
 */
class SelectionOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var selectionEngine: SelectionEngine? = null

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 200, 255)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 0, 200, 255)
        style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val engine = selectionEngine ?: return false
        val tool = engine.getActiveTool() ?: return false

        when (tool) {
            is LassoTool -> handleLassoTouch(event, tool)
            is BrushTool -> handleBrushTouch(event, tool)
            else -> {}
        }
        invalidate()
        return true
    }

    private fun handleLassoTouch(event: MotionEvent, tool: LassoTool) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> tool.addPoint(event.x, event.y)
            MotionEvent.ACTION_MOVE -> tool.addPoint(event.x, event.y)
            MotionEvent.ACTION_UP   -> {
                if (tool.isReadyToClose()) {
                    tool.close()
                    val mask = tool.buildMask()
                    if (mask != null) {
                        selectionEngine?.applyToolResult(mask, SelectionEngine.ToolMode.ADD)
                    }
                    tool.reset()
                }
            }
        }
    }

    private fun handleBrushTouch(event: MotionEvent, tool: BrushTool) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> tool.startStroke(event.x, event.y)
            MotionEvent.ACTION_MOVE -> tool.continueStroke(event.x, event.y)
            MotionEvent.ACTION_UP   -> {
                val mask = tool.commitStroke()
                selectionEngine?.applyToolResult(mask, tool.mode)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val engine = selectionEngine ?: return
        val tool = engine.getActiveTool()

        // Draw live mask
        val liveMask = engine.getLiveMask()
        canvas.drawBitmap(liveMask, 0f, 0f, fillPaint)

        // Draw active tool path
        when (tool) {
            is LassoTool -> canvas.drawPath(tool.getPath(), overlayPaint)
            is BrushTool -> canvas.drawBitmap(tool.getLiveStroke(), 0f, 0f, overlayPaint)
            else -> {}
        }
    }
}

// =============================================================================
// AdjustmentPanel — sliders for brightness, contrast, saturation, hue, sharpness
// =============================================================================

class AdjustmentPanel @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onSubjectParamsChanged:    ((Adjustments.LayerParams) -> Unit)? = null
    var onBackgroundParamsChanged: ((Adjustments.LayerParams) -> Unit)? = null

    // Current values
    private var subjectParams    = Adjustments.LayerParams()
    private var backgroundParams = Adjustments.LayerParams()

    // Layer toggle (subject vs background)
    private var editingSubject = true

    init {
        orientation = VERTICAL
        buildUI()
    }

    private val brightnessSlider = SeekBar(context)
    private val contrastSlider   = SeekBar(context)
    private val saturationSlider = SeekBar(context)
    private val hueSlider        = SeekBar(context)
    private val sharpnessSlider  = SeekBar(context)

    private fun buildUI() {
        // Layer toggle buttons
        val layerToggle = LinearLayout(context).apply { orientation = HORIZONTAL }
        val btnSubject = Button(context).apply {
            text = "Subject"
            setOnClickListener { editingSubject = true; syncSlidersToParams() }
        }
        val btnBackground = Button(context).apply {
            text = "Background"
            setOnClickListener { editingSubject = false; syncSlidersToParams() }
        }
        layerToggle.addView(btnSubject)
        layerToggle.addView(btnBackground)
        addView(layerToggle)

        // Sliders
        addLabeledSlider("Brightness", brightnessSlider, 0, 200, 100)
        addLabeledSlider("Contrast",   contrastSlider,   0, 300, 100)
        addLabeledSlider("Saturation", saturationSlider, 0, 300, 100)
        addLabeledSlider("Hue",        hueSlider,        0, 360, 180)
        addLabeledSlider("Sharpness",  sharpnessSlider,  0, 100,   0)

        brightnessSlider.setOnSeekBarChangeListener(buildListener { v ->
            updateParams { copy(brightness = (v - 100) / 100f) }
        })
        contrastSlider.setOnSeekBarChangeListener(buildListener { v ->
            updateParams { copy(contrast = v / 100f) }
        })
        saturationSlider.setOnSeekBarChangeListener(buildListener { v ->
            updateParams { copy(saturation = v / 100f) }
        })
        hueSlider.setOnSeekBarChangeListener(buildListener { v ->
            updateParams { copy(hue = (v - 180).toFloat()) }
        })
        sharpnessSlider.setOnSeekBarChangeListener(buildListener { v ->
            updateParams { copy(sharpness = v / 100f) }
        })
    }

    private fun addLabeledSlider(label: String, slider: SeekBar, min: Int, max: Int, default: Int) {
        addView(TextView(context).apply { text = label })
        slider.max = max
        slider.progress = default
        addView(slider)
    }

    private fun buildListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) { if (fromUser) onChange(v) }
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    private fun updateParams(transform: Adjustments.LayerParams.() -> Adjustments.LayerParams) {
        if (editingSubject) {
            subjectParams = subjectParams.transform()
            onSubjectParamsChanged?.invoke(subjectParams)
        } else {
            backgroundParams = backgroundParams.transform()
            onBackgroundParamsChanged?.invoke(backgroundParams)
        }
    }

    private fun syncSlidersToParams() {
        val p = if (editingSubject) subjectParams else backgroundParams
        brightnessSlider.progress = ((p.brightness * 100) + 100).toInt()
        contrastSlider.progress   = (p.contrast * 100).toInt()
        saturationSlider.progress = (p.saturation * 100).toInt()
        hueSlider.progress        = (p.hue + 180).toInt()
        sharpnessSlider.progress  = (p.sharpness * 100).toInt()
    }
}

// =============================================================================
// BackgroundPicker — bottom sheet with image/video/color/blur tabs
// =============================================================================

class BackgroundPicker @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onColorSelected: ((Int) -> Unit)? = null
    var onImageSelected: ((Bitmap) -> Unit)? = null
    var onVideoSelected: ((android.net.Uri) -> Unit)? = null
    var onBlurSelected:  ((Float) -> Unit)? = null

    init {
        orientation = VERTICAL
        buildUI()
    }

    private fun buildUI() {
        val tabRow = LinearLayout(context).apply { orientation = HORIZONTAL }

        Button(context).apply {
            text = "Color"
            setOnClickListener { onColorSelected?.invoke(android.graphics.Color.parseColor("#1A1A2E")) }
        }.also { tabRow.addView(it) }

        Button(context).apply {
            text = "Blur"
            setOnClickListener { onBlurSelected?.invoke(20f) }
        }.also { tabRow.addView(it) }

        // Image + Video pickers are wired via ActivityResultLauncher in EditorFragment
        Button(context).apply {
            text = "Image"
            tag = "image_picker"
        }.also { tabRow.addView(it) }

        Button(context).apply {
            text = "Video"
            tag = "video_picker"
        }.also { tabRow.addView(it) }

        addView(tabRow)
    }
}

// =============================================================================
// EffectsPanel — glow, feather, flip, speed controls
// =============================================================================

class EffectsPanel @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onGlowEnabledChanged:   ((Boolean) -> Unit)? = null
    var onGlowIntensityChanged: ((Float) -> Unit)? = null
    var onFeatherChanged:       ((Float) -> Unit)? = null
    var onFlipChanged:          ((Boolean) -> Unit)? = null

    init {
        orientation = VERTICAL
        buildUI()
    }

    private fun buildUI() {
        // Glow toggle + intensity
        val glowRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        val glowCheck = CheckBox(context).apply {
            text = "Glow"
            setOnCheckedChangeListener { _, isChecked -> onGlowEnabledChanged?.invoke(isChecked) }
        }
        val glowSlider = SeekBar(context).apply {
            max = 100
            progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                    if (fromUser) onGlowIntensityChanged?.invoke(v / 100f)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        glowRow.addView(glowCheck)
        glowRow.addView(glowSlider)
        addView(glowRow)

        // Edge feather slider
        addView(TextView(context).apply { text = "Edge Feather" })
        val featherSlider = SeekBar(context).apply {
            max = 8
            progress = 2
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                    if (fromUser) onFeatherChanged?.invoke(v.toFloat())
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        addView(featherSlider)

        // Flip toggle
        val flipBtn = Button(context).apply {
            text = "Flip Subject"
            setOnClickListener { onFlipChanged?.invoke(true) }
        }
        addView(flipBtn)
    }
}
