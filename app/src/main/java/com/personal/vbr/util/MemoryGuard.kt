package com.personal.vbr.util

import android.util.Log

/**
 * Watches Runtime free heap and signals the pipeline to pause before OOM.
 *
 * THRESHOLDS (tuned for 2.7GB device with ~1.5GB app budget):
 *  - WARNING  < 200MB free → log, shrink FramePool to 2 slots
 *  - CRITICAL < 100MB free → pause frame ingestion entirely
 *  - FATAL    <  50MB free → force GC, drop all non-essential bitmaps
 *
 * Usage: call [isCritical] before accepting each frame in FramePipeline.
 * Call [checkAndAct] periodically (every ~60 frames) for proactive management.
 */
object MemoryGuard {

    private const val TAG = "MemoryGuard"

    private const val WARNING_BYTES  = 200L * 1024 * 1024   // 200MB
    private const val CRITICAL_BYTES = 100L * 1024 * 1024   // 100MB
    private const val FATAL_BYTES    =  50L * 1024 * 1024   //  50MB

    enum class MemoryLevel { OK, WARNING, CRITICAL, FATAL }

    private var lastLevel = MemoryLevel.OK

    // Callbacks registered by pipeline components
    var onWarning:  (() -> Unit)? = null
    var onCritical: (() -> Unit)? = null
    var onFatal:    (() -> Unit)? = null

    fun freeBytes(): Long {
        val rt = Runtime.getRuntime()
        return rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
    }

    fun currentLevel(): MemoryLevel = when {
        freeBytes() < FATAL_BYTES    -> MemoryLevel.FATAL
        freeBytes() < CRITICAL_BYTES -> MemoryLevel.CRITICAL
        freeBytes() < WARNING_BYTES  -> MemoryLevel.WARNING
        else                         -> MemoryLevel.OK
    }

    /** Fast check called before every frame — no allocation, no logging. */
    fun isCritical(): Boolean = freeBytes() < CRITICAL_BYTES

    /**
     * Full check with callbacks. Call every 60 frames (~2s at 30fps).
     * Triggers level-change callbacks only when level transitions, not every call.
     */
    fun checkAndAct() {
        val level = currentLevel()
        if (level == lastLevel) return

        lastLevel = level
        Log.w(TAG, "Memory level → $level (${freeBytes() / 1024 / 1024}MB free)")

        when (level) {
            MemoryLevel.WARNING  -> onWarning?.invoke()
            MemoryLevel.CRITICAL -> { System.gc(); onCritical?.invoke() }
            MemoryLevel.FATAL    -> { System.gc(); onFatal?.invoke() }
            MemoryLevel.OK       -> Log.i(TAG, "Memory pressure relieved")
        }
    }

    fun logStats() {
        val rt = Runtime.getRuntime()
        Log.d(TAG,
            "Heap: max=${rt.maxMemory()/1024/1024}MB " +
            "total=${rt.totalMemory()/1024/1024}MB " +
            "free=${rt.freeMemory()/1024/1024}MB " +
            "available=${freeBytes()/1024/1024}MB"
        )
    }
}
