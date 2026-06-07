package com.personal.vbr.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.*

/**
 * Pre-decodes a background video into a bounded LRU frame cache.
 *
 * WHY THIS EXISTS:
 *  Running two MediaCodec decoders simultaneously (subject + background) on a
 *  Mali-G57 causes hardware resource contention and drops the effective frame rate
 *  to ~12fps. This cache pre-decodes the background video in its entirety (or up
 *  to [MAX_CACHED_FRAMES] frames) on a background thread, storing scaled-down bitmaps.
 *  During playback, [getFrame] is an O(1) cache lookup — zero decode cost.
 *
 * MEMORY:
 *  Background frames are stored at 320x180 (one-quarter 720p area).
 *  320 * 180 * 4 bytes = 225KB per frame.
 *  At 100 frames cached: 22.5MB — fits comfortably in our budget.
 *  Compositor scales the retrieved frame to output size, which is fine for backgrounds.
 *
 * LOOPING:
 *  When [getFrame] is called beyond the cached duration, it wraps the timestamp
 *  via modulo. Short background clips loop seamlessly.
 */
class BackgroundFrameCache(private val context: Context) {

    companion object {
        private const val TAG = "BgFrameCache"

        // Cached resolution — quarter area of 720p
        private const val CACHE_WIDTH  = 320
        private const val CACHE_HEIGHT = 180

        // Maximum frames to pre-decode. At 30fps this covers ~3.3 seconds of loop.
        private const val MAX_CACHED_FRAMES = 100

        // LRU size in bytes
        private val MAX_CACHE_BYTES = MAX_CACHED_FRAMES * CACHE_WIDTH * CACHE_HEIGHT * 4
    }

    // timestamp (ms) → scaled Bitmap
    private val cache = object : LruCache<Long, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int =
            value.byteCount
        override fun entryRemoved(evicted: Boolean, key: Long, old: Bitmap, new: Bitmap?) {
            old.recycle()
        }
    }

    private var cacheDurationMs: Long = 0L
    private var isReady = false

    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var onProgress: ((Float) -> Unit)? = null
    var onReady: (() -> Unit)? = null

    /**
     * Begin pre-decoding [uri] into the cache.
     * Non-blocking — reports progress via [onProgress] and completion via [onReady].
     */
    fun prepare(uri: Uri) {
        isReady = false
        cache.evictAll()

        cacheScope.launch {
            try {
                decodeIntoCache(uri)
                isReady = true
                withContext(Dispatchers.Main) { onReady?.invoke() }
                Log.i(TAG, "Background cache ready: ${cache.size()} frames, " +
                        "${cacheDurationMs}ms duration")
            } catch (e: Exception) {
                Log.e(TAG, "Background cache failed", e)
            }
        }
    }

    private suspend fun decodeIntoCache(uri: Uri) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        val videoTrack = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: return

        extractor.selectTrack(videoTrack)
        val format = extractor.getTrackFormat(videoTrack)
        val durationUs = format.getLong(MediaFormat.KEY_DURATION)
        cacheDurationMs = durationUs / 1000L

        val frameIntervalUs = durationUs / MAX_CACHED_FRAMES.toLong()
        var frameCount = 0

        // Seek to evenly-spaced timestamps and decode one frame per seek
        for (i in 0 until MAX_CACHED_FRAMES) {
            val targetUs = i * frameIntervalUs
            extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val timestampMs = extractor.sampleTime / 1000L

            // Decode single frame at this position
            val bitmap = decodeSingleFrame(context, uri, timestampMs) ?: continue

            // Scale to cache resolution
            val scaled = if (bitmap.width != CACHE_WIDTH || bitmap.height != CACHE_HEIGHT) {
                val s = Bitmap.createScaledBitmap(bitmap, CACHE_WIDTH, CACHE_HEIGHT, true)
                bitmap.recycle()
                s
            } else bitmap

            cache.put(timestampMs, scaled)
            frameCount++

            val progress = frameCount.toFloat() / MAX_CACHED_FRAMES
            withContext(Dispatchers.Main) { onProgress?.invoke(progress) }

            yield() // Don't starve other coroutines
        }

        extractor.release()
        Log.d(TAG, "Cached $frameCount frames (${cache.size() / 1024}KB used)")
    }

    /**
     * Decode a single frame at [timestampMs] using a fresh extractor.
     * Intentionally simple — this runs in the background pre-decode pass.
     */
    private fun decodeSingleFrame(context: Context, uri: Uri, timestampMs: Long): Bitmap? {
        return try {
            // Use MediaMetadataRetriever for single-frame extraction — simpler than codec setup
            android.media.MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(
                    timestampMs * 1000L,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame decode failed at ${timestampMs}ms", e)
            null
        }
    }

    /**
     * Get the background bitmap for a given [timestampMs].
     * Wraps the timestamp if it exceeds the cached duration (loop behaviour).
     * Returns null if cache is not yet ready.
     */
    fun getFrame(timestampMs: Long): Bitmap? {
        if (!isReady) return null

        val loopedMs = if (cacheDurationMs > 0) timestampMs % cacheDurationMs else timestampMs

        // Find nearest cached frame key
        val keys = cache.snapshot().keys.sorted()
        if (keys.isEmpty()) return null

        val nearest = keys.minByOrNull { kotlin.math.abs(it - loopedMs) } ?: return null
        return cache.get(nearest)
    }

    fun destroy() {
        cacheScope.cancel()
        cache.evictAll()
        Log.d(TAG, "BackgroundFrameCache destroyed")
    }
}
