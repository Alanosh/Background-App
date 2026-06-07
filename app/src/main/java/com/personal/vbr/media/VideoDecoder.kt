package com.personal.vbr.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import android.view.Surface
import com.personal.vbr.core.pipeline.FramePool
import com.personal.vbr.core.pipeline.FramePipeline
import kotlinx.coroutines.*
import java.nio.ByteBuffer

/**
 * Decodes video frames using MediaCodec (hardware-accelerated on Mali-G57)
 * and pushes them into [FramePipeline] via [FramePool].
 *
 * THREAD MODEL:
 *  - Runs on a dedicated [Dispatchers.IO] coroutine (decode thread).
 *  - Never blocks the main or GPU thread.
 *  - Frame rate is governed by [targetFps] — excess frames are dropped before
 *    even reaching the FramePool to avoid decode→pipeline backpressure.
 *
 * MEMORY:
 *  - Reads directly into FramePool bitmaps via ImageReader/Canvas.
 *  - Maximum two MediaCodec output buffers held at once (MediaCodec default).
 *  - Seek operations flush the codec buffer queue to prevent stale frame delivery.
 *
 * SCRUBBING:
 *  Call [seekTo] to jump to a timestamp. The decoder will resume from the nearest
 *  sync frame. Expect 1-3 frames of latency before the new position renders.
 */
class VideoDecoder(
    private val context: Context,
    private val pipeline: FramePipeline,
    private val framePool: FramePool
) {

    companion object {
        private const val TAG = "VideoDecoder"
        private const val TIMEOUT_US = 10_000L   // 10ms codec dequeue timeout
        private const val TARGET_FPS = 30
        private const val FRAME_INTERVAL_MS = 1000L / TARGET_FPS
    }

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var decodeJob: Job? = null
    private val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Playback state
    @Volatile private var isPlaying = false
    @Volatile private var seekPendingMs: Long = -1L
    @Volatile private var playbackSpeedMultiplier = 1.0f

    // Video metadata
    var durationMs: Long = 0L; private set
    var videoWidth: Int = 0;   private set
    var videoHeight: Int = 0;  private set
    var totalFrames: Int = 0;  private set

    /**
     * Load a video URI and prepare for playback.
     * Call from a background coroutine.
     */
    suspend fun prepare(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val ext = MediaExtractor().also { extractor = it }
            ext.setDataSource(context, uri, null)

            val videoTrackIndex = findVideoTrack(ext) ?: run {
                Log.e(TAG, "No video track found in $uri")
                return@withContext false
            }

            ext.selectTrack(videoTrackIndex)
            val format = ext.getTrackFormat(videoTrackIndex)

            videoWidth  = format.getInteger(MediaFormat.KEY_WIDTH)
            videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            durationMs  = format.getLong(MediaFormat.KEY_DURATION) / 1000L
            totalFrames = estimateTotalFrames(format, durationMs)

            Log.i(TAG, "Video prepared: ${videoWidth}x${videoHeight}, ${durationMs}ms, ~$totalFrames frames")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Prepare failed", e)
            false
        }
    }

    /**
     * Start decoding and feeding frames to the pipeline.
     * Uses a PixelCopy-based approach to get Bitmap data from the codec's Surface.
     *
     * NOTE: We decode at 720p internally. If source is higher resolution,
     * we request the codec to scale down via KEY_WIDTH/KEY_HEIGHT hints.
     * Not all codecs honour this, so we also scale in software if needed.
     */
    fun startPlayback() {
        if (isPlaying) return
        isPlaying = true

        decodeJob = decodeScope.launch {
            decodeLoop()
        }
    }

    private suspend fun decodeLoop() {
        val ext = extractor ?: return
        val mime = ext.getTrackFormat(0).getString(MediaFormat.KEY_MIME) ?: return

        try {
            val c = MediaCodec.createDecoderByType(mime).also { codec = it }

            // Request 720p output from codec if source is larger
            val format = ext.getTrackFormat(0).apply {
                setInteger(MediaFormat.KEY_WIDTH,  FramePipeline.PROCESS_WIDTH)
                setInteger(MediaFormat.KEY_HEIGHT, FramePipeline.PROCESS_HEIGHT)
            }

            c.configure(format, null, null, 0)
            c.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var lastFrameTimeMs = 0L

            while (isPlaying) {
                // Handle pending seek
                val seekMs = seekPendingMs
                if (seekMs >= 0) {
                    performSeek(c, ext, seekMs)
                    seekPendingMs = -1L
                    lastFrameTimeMs = 0L
                }

                // Feed input
                if (!inputDone) {
                    val inputIndex = c.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuf: ByteBuffer = c.getInputBuffer(inputIndex)!!
                        val sampleSize = ext.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            c.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val ptsUs = ext.sampleTime
                            c.queueInputBuffer(inputIndex, 0, sampleSize, ptsUs, 0)
                            ext.advance()
                        }
                    }
                }

                // Drain output
                val outputIndex = c.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputIndex >= 0) {
                    val ptsMs = bufferInfo.presentationTimeUs / 1000L

                    // Frame rate throttle — drop frames to maintain target FPS
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastFrameTimeMs
                    val adjustedInterval = (FRAME_INTERVAL_MS / playbackSpeedMultiplier).toLong()

                    if (elapsed >= adjustedInterval) {
                        // Render this frame
                        renderOutputBuffer(c, outputIndex, bufferInfo, ptsMs)
                        lastFrameTimeMs = now
                    } else {
                        // Drop frame — release buffer without rendering
                        c.releaseOutputBuffer(outputIndex, false)
                    }

                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "Output format changed: ${c.outputFormat}")
                }

                // End of stream
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    Log.d(TAG, "End of stream reached")
                    isPlaying = false
                    break
                }

                // Yield to allow other coroutines (seek requests, pause) to run
                yield()
            }

        } catch (e: CancellationException) {
            Log.d(TAG, "Decode loop cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Decode loop error", e)
        } finally {
            codec?.stop()
            codec?.release()
            codec = null
        }
    }

    /**
     * Render a decoded output buffer into a FramePool bitmap.
     * Uses Image API for zero-copy pixel access where available.
     */
    private fun renderOutputBuffer(
        codec: MediaCodec,
        outputIndex: Int,
        info: MediaCodec.BufferInfo,
        timestampMs: Long
    ) {
        val frame = framePool.acquire()
        if (frame == null) {
            // Pool exhausted — release buffer, drop frame
            codec.releaseOutputBuffer(outputIndex, false)
            return
        }

        try {
            // For software decode output: get the image and copy to bitmap
            // For hardware decode: use Surface-based path
            // Simplified version using codec's Image API (API 29+)
            val image = codec.getOutputImage(outputIndex)
            if (image != null) {
                // Convert YUV image to ARGB bitmap
                MediaUtils.yuv420ToBitmap(image, frame)
                image.close()
            }
            codec.releaseOutputBuffer(outputIndex, false)

            // Scale down to 720p if source was larger
            val finalFrame = if (frame.width != FramePipeline.PROCESS_WIDTH ||
                                  frame.height != FramePipeline.PROCESS_HEIGHT) {
                MediaUtils.scaleBitmap(frame, FramePipeline.PROCESS_WIDTH, FramePipeline.PROCESS_HEIGHT)
            } else {
                frame
            }

            pipeline.submitFrame(finalFrame, timestampMs, totalFrames)

        } catch (e: Exception) {
            Log.e(TAG, "renderOutputBuffer error", e)
            framePool.release(frame)
            codec.releaseOutputBuffer(outputIndex, false)
        }
    }

    private fun performSeek(codec: MediaCodec, ext: MediaExtractor, seekMs: Long) {
        codec.flush()
        ext.seekTo(seekMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        Log.d(TAG, "Seeked to ${seekMs}ms")
    }

    fun seekTo(timestampMs: Long) {
        seekPendingMs = timestampMs
    }

    fun pause() {
        isPlaying = false
    }

    fun resume() {
        if (!isPlaying) startPlayback()
    }

    fun setPlaybackSpeed(multiplier: Float) {
        playbackSpeedMultiplier = multiplier.coerceIn(0.25f, 4.0f)
    }

    private fun findVideoTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return null
    }

    private fun estimateTotalFrames(format: MediaFormat, durationMs: Long): Int {
        val frameRate = try { format.getInteger(MediaFormat.KEY_FRAME_RATE) } catch (e: Exception) { 30 }
        return ((durationMs / 1000.0) * frameRate).toInt()
    }

    fun destroy() {
        isPlaying = false
        decodeJob?.cancel()
        decodeScope.cancel()
        codec?.stop()
        codec?.release()
        extractor?.release()
        Log.d(TAG, "VideoDecoder destroyed")
    }
}
