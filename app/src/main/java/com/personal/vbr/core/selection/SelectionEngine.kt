package com.personal.vbr.core.selection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.util.Log

/**
 * Manages the user-refined selection mask.
 *
 * SEPARATION FROM VIDEO PIPELINE:
 *  SelectionEngine owns a completely independent Bitmap that lives alongside
 *  (not inside) the video pipeline. When the user paints a refinement, it updates
 *  this mask. FramePipeline reads it on the next frame. They never share state
 *  directly — the handoff is [getRefinedMask] which returns a copy.
 *
 * TOOL COORDINATION:
 *  Only one tool can be active at a time. Activating a new tool auto-deactivates
 *  the previous one. Tool results (filled paths, brush strokes) are committed to
 *  [currentMask] and pushed onto [undoStack].
 *
 * THREAD SAFETY:
 *  All mutations happen on the main thread (tools are driven by touch events).
 *  [getRefinedMask] is called from the GPU thread and returns a snapshot copy.
 */
class SelectionEngine(
    private val width: Int,
    private val height: Int
) {

    companion object {
        private const val TAG = "SelectionEngine"
    }

    private val undoStack = UndoStack(maxDepth = 20)

    // The current refined mask (ARGB_8888 for easy Canvas drawing)
    // White (0xFFFFFFFF) = include, Black (0xFF000000) = exclude
    private var currentMask: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val maskCanvas  = Canvas(currentMask)

    // Active tool reference
    private var activeTool: SelectionTool? = null

    // Whether the AI mask has been applied as the base
    private var hasAiBase = false

    val lassoTool    = LassoTool(width, height)
    val magneticTool = MagneticLassoTool(width, height)
    val brushTool    = BrushTool(width, height)

    // ---------------------------------------------------------------------------
    // AI mask initialisation
    // ---------------------------------------------------------------------------

    /**
     * Set the initial mask from the AI segmentation result.
     * This becomes the starting point for all manual refinements.
     */
    fun setAiMask(aiMask: Bitmap) {
        pushUndo()
        maskCanvas.drawBitmap(aiMask, 0f, 0f, null)
        hasAiBase = true
        Log.d(TAG, "AI mask applied as base")
    }

    // ---------------------------------------------------------------------------
    // Tool activation
    // ---------------------------------------------------------------------------

    fun activateLasso()         { activeTool = lassoTool;    Log.d(TAG, "Lasso activated") }
    fun activateMagneticLasso() { activeTool = magneticTool; Log.d(TAG, "MagneticLasso activated") }
    fun activateBrush()         { activeTool = brushTool;    Log.d(TAG, "Brush activated") }
    fun deactivateTool()        { activeTool = null }

    fun getActiveTool(): SelectionTool? = activeTool

    // ---------------------------------------------------------------------------
    // Tool result application
    // ---------------------------------------------------------------------------

    /**
     * Commit a tool's result to the mask.
     * [toolMask] is an ARGB_8888 bitmap where white = include, black = exclude.
     * [mode] controls whether the result adds to or removes from the selection.
     */
    fun applyToolResult(toolMask: Bitmap, mode: ToolMode) {
        pushUndo()

        val paint = Paint().apply {
            xfermode = when (mode) {
                ToolMode.ADD    -> null  // SRC_OVER — white pixels add to selection
                ToolMode.REMOVE -> android.graphics.PorterDuffXfermode(
                    PorterDuff.Mode.DST_OUT
                )
            }
        }
        maskCanvas.drawBitmap(toolMask, 0f, 0f, paint)
        Log.d(TAG, "Tool result applied: mode=$mode")
    }

    // ---------------------------------------------------------------------------
    // Undo
    // ---------------------------------------------------------------------------

    private fun pushUndo() {
        undoStack.push(currentMask)
    }

    fun undo(): Boolean {
        val previous = undoStack.pop() ?: return false
        maskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        maskCanvas.drawBitmap(previous, 0f, 0f, null)
        previous.recycle()
        Log.d(TAG, "Undo applied. Stack depth: ${undoStack.depth()}")
        return true
    }

    fun canUndo(): Boolean = undoStack.depth() > 0

    // ---------------------------------------------------------------------------
    // Mask access (thread-safe snapshot)
    // ---------------------------------------------------------------------------

    /**
     * Returns a snapshot copy of the current mask for use in the pipeline.
     * Thread-safe: always returns a new Bitmap so the GPU thread never races
     * against a tool paint operation on the main thread.
     */
    fun getRefinedMask(): Bitmap = currentMask.copy(Bitmap.Config.ALPHA_8, false)

    /**
     * Returns the live mask for rendering in SelectionOverlay.
     * Only call from the main thread.
     */
    fun getLiveMask(): Bitmap = currentMask

    fun reset() {
        maskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        undoStack.clear()
        hasAiBase = false
    }

    fun destroy() {
        currentMask.recycle()
        undoStack.clear()
        lassoTool.destroy()
        magneticTool.destroy()
        brushTool.destroy()
    }

    enum class ToolMode { ADD, REMOVE }
}

// Marker interface for type safety
interface SelectionTool {
    fun destroy()
}
