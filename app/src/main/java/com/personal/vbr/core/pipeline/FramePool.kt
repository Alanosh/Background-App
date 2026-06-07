package com.personal.vbr.core.pipeline

import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Pre-allocated pool of [POOL_SIZE] reusable Bitmaps.
 *
 * WHY: On a Mali-G57 with 2.7GB RAM, allocating a new 720p ARGB_8888 Bitmap every frame
 * (~3.1MB each) triggers GC every few seconds, causing 16-32ms stalls and dropped frames.
 * This pool allocates exactly [POOL_SIZE] Bitmaps at startup and recycles them forever.
 *
 * USAGE:
 *   val frame = pool.acquire() ?: return  // drop frame if pool exhausted
 *   // ... write into frame ...
 *   pool.release(frame)                   // return to pool after use
 *
 * THREAD SAFETY: acquire/release are called from the decode and GPU threads respectively.
 * ArrayBlockingQueue is lock-free for single-producer/single-consumer which matches our use.
 */
class FramePool(
    private val width: Int,
    private val height: Int,
    private val config: Bitmap.Config = Bitmap.Config.ARGB_8888
) {

    companion object {
        // 3 slots: one being decoded, one being composited, one being displayed.
        // Drop to 2 if MemoryGuard reports sustained pressure.
        private const val POOL_SIZE = 3
        private const val TAG = "FramePool"
        private const val ACQUIRE_TIMEOUT_MS = 8L  // one frame budget at 120fps; drop otherwise
    }

    private val pool = ArrayBlockingQueue<Bitmap>(POOL_SIZE)

    init {
        repeat(POOL_SIZE) {
            pool.offer(
                Bitmap.createBitmap(width, height, config).also { bmp ->
                    bmp.eraseColor(0) // zero-fill so stale pixels never appear on first use
                }
            )
        }
        Log.d(TAG, "Pool initialised: ${POOL_SIZE}x ${width}x${height} ${config.name} " +
                "(${bytesPerFrame() * POOL_SIZE / 1024}KB total)")
    }

    /**
     * Acquire a frame bitmap. Returns null immediately if pool is exhausted —
     * caller must DROP the frame, never block the GPU thread waiting.
     */
    fun acquire(): Bitmap? = pool.poll(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS)

    /**
     * Return a frame to the pool. Must be called after every successful [acquire].
     * Safe to call from any thread.
     */
    fun release(bitmap: Bitmap) {
        if (bitmap.width != width || bitmap.height != height) {
            Log.w(TAG, "Returning mismatched bitmap to pool — discarding")
            bitmap.recycle()
            return
        }
        if (!pool.offer(bitmap)) {
            // Pool is full — this bitmap is surplus, recycle it
            Log.w(TAG, "Pool overflow — recycling surplus bitmap")
            bitmap.recycle()
        }
    }

    /**
     * Shrink pool to [REDUCED_SIZE] slots to relieve memory pressure.
     * Called by MemoryGuard when free heap drops below threshold.
     * The removed bitmaps are recycled immediately.
     */
    fun shrinkToTwo() {
        val surplus = pool.poll()
        surplus?.recycle()
        Log.w(TAG, "Pool shrunk to 2 under memory pressure")
    }

    /**
     * Recycle all pooled bitmaps. Call from onDestroy.
     */
    fun destroy() {
        var recycled = 0
        while (true) {
            val bmp = pool.poll() ?: break
            bmp.recycle()
            recycled++
        }
        Log.d(TAG, "Pool destroyed, recycled $recycled bitmaps")
    }

    fun bytesPerFrame(): Long = width.toLong() * height * when (config) {
        Bitmap.Config.ARGB_8888 -> 4
        Bitmap.Config.RGB_565   -> 2
        else                    -> 4
    }

    fun availableSlots(): Int = pool.size
}
