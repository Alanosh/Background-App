package com.personal.vbr.core.selection

import android.graphics.Bitmap
import android.util.Log
import java.util.ArrayDeque

/**
 * Fixed-depth undo history for mask bitmaps.
 *
 * MEMORY:
 *  Each snapshot is a full ALPHA_8 copy of the mask (~0.9MB at 720p).
 *  At depth=20 that's ~18MB — acceptable on our target device.
 *  Snapshots beyond [maxDepth] are recycled immediately (oldest first).
 *
 * THREAD: Main thread only.
 */
class UndoStack(private val maxDepth: Int = 20) {

    private val stack = ArrayDeque<Bitmap>(maxDepth)

    fun push(mask: Bitmap) {
        // Trim oldest entry if at capacity
        if (stack.size >= maxDepth) {
            val oldest = stack.pollLast()
            oldest?.recycle()
            Log.d("UndoStack", "Max depth reached — oldest entry recycled")
        }
        stack.push(mask.copy(Bitmap.Config.ALPHA_8, false))
    }

    /** Returns the most recent snapshot and removes it from the stack. Caller owns it. */
    fun pop(): Bitmap? = stack.pollFirst()

    fun depth(): Int = stack.size

    fun clear() {
        while (stack.isNotEmpty()) {
            stack.pollFirst()?.recycle()
        }
    }
}
