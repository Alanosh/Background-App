package com.personal.vbr.core.selection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/**
 * Brush tool — paint add/remove strokes directly onto the mask.
 *
 * The brush renders onto an intermediate Bitmap which is committed to
 * SelectionEngine when the user lifts their finger (ACTION_UP).
 * This means the live stroke is rendered only in SelectionOverlay
 * and never touches the pipeline until committed.
 */
class BrushTool(
    private val width: Int,
    private val height: Int
) : SelectionTool {

    var brushRadius: Float = 30f     // pixels; user-adjustable via EffectsPanel
    var mode: SelectionEngine.ToolMode = SelectionEngine.ToolMode.ADD

    // Live stroke layer — shown in SelectionOverlay during drawing
    private val strokeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val strokeCanvas = Canvas(strokeBitmap)
    private val strokePath   = Path()

    private val addPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap  = Paint.Cap.ROUND
    }

    private val removePaint = Paint(addPaint).apply {
        color = Color.BLACK
    }

    fun startStroke(x: Float, y: Float) {
        strokePath.reset()
        strokeBitmap.eraseColor(Color.TRANSPARENT)
        strokePath.moveTo(x, y)
    }

    fun continueStroke(x: Float, y: Float) {
        strokePath.lineTo(x, y)
        strokeCanvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        val paint = if (mode == SelectionEngine.ToolMode.ADD) addPaint else removePaint
        paint.strokeWidth = brushRadius * 2f
        strokeCanvas.drawPath(strokePath, paint)
    }

    /**
     * Finalise and return the committed stroke bitmap.
     * Caller passes this to [SelectionEngine.applyToolResult].
     */
    fun commitStroke(): Bitmap {
        val result = strokeBitmap.copy(Bitmap.Config.ARGB_8888, false)
        strokePath.reset()
        strokeBitmap.eraseColor(Color.TRANSPARENT)
        return result
    }

    /** Live preview bitmap for SelectionOverlay rendering. */
    fun getLiveStroke(): Bitmap = strokeBitmap

    override fun destroy() {
        strokeBitmap.recycle()
    }
}
