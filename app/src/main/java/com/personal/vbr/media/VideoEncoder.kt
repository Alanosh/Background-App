package com.personal.vbr.media

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Hardware H.264 encoder using MediaCodec.
 *
 * RESOLUTION STRATEGY:
 *  - Internal processing at 720p.
 *  - Export at user-selected resolution (480p / 720p / 1080p).
 *  - 1080p export upscales from 720p using bilinear scaling — acceptable quality.
 *
 * BITRATE TARGETS (to keep file sizes sane):
 *  - 480p  → 1.5 Mbps
 *  - 720p  → 3.0 Mbps
 *  - 1080p → 5.0 Mbps
 *  These produce roughly 11MB / 22MB / 37MB per minute respectively.
 *
 * AUDIO:
 *  Audio is never re-encoded — [ExportManager] passes the raw audio track
 *  directly to the muxer to preserve quality and save ~50% export time.
 */
class VideoEncoder(
    private val outputFile: File,
    private val targetResolution: ExportResolution = ExportResolution.P720
) {

    enum class ExportResolution(val width: Int, val height: Int, val bitrateBps: Int) {
        P480 (854,  480,  1_500_000),
        P720 (1280, 720,  3_000_000),
        P1080(1920, 1080, 5_000_000);

        fun estimatedMbPerMinute(): Int = (bitrateBps / 8 * 60) / 1_000_000
    }

    companion object {
        private const val TAG = "VideoEncoder"
        private const val MIME_TYPE   = "video/avc"     // H.264
        private const val FRAME_RATE  = 30
        private const val I_FRAME_INTERVAL = 2          // keyframe every 2 seconds
        private const val TIMEOUT_US  = 10_000L
    }

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()

    @Volatile var isEncoding = false

    /**
     * Initialise encoder and muxer. Call before [encodeFrame].
     */
    fun prepare() {
        val format = MediaFormat.createVideoFormat(
            MIME_TYPE,
            targetResolution.width,
            targetResolution.height
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE,   targetResolution.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            // Baseline profile for maximum device compatibility
            setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        }

        codec = MediaCodec.createEncoderByType(MIME_TYPE).also { c ->
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
        }

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        isEncoding = true

        Log.i(TAG, "Encoder prepared: ${targetResolution.name} " +
                "${targetResolution.width}x${targetResolution.height} " +
                "@${targetResolution.bitrateBps / 1000}kbps")
    }

    /**
     * Add an audio track to the muxer (from the source video — no re-encoding).
     * Must be called before [encodeFrame] writes the first video frame.
     */
    fun addAudioTrack(audioFormat: MediaFormat) {
        val mux = muxer ?: return
        audioTrackIndex = mux.addTrack(audioFormat)
        Log.d(TAG, "Audio track added at index $audioTrackIndex")
    }

    /**
     * Encode a single frame bitmap. Upscales from 720p if target is 1080p.
     *
     * @param bitmap      ARGB_8888 frame at processing resolution (may be upscaled internally)
     * @param timestampUs Presentation timestamp in microseconds
     */
    fun encodeFrame(bitmap: Bitmap, timestampUs: Long) {
        val c = codec ?: return

        // Upscale if needed
        val encodeBitmap = if (bitmap.width != targetResolution.width ||
                                bitmap.height != targetResolution.height) {
            MediaUtils.scaleBitmap(bitmap, targetResolution.width, targetResolution.height)
        } else bitmap

        val inputIndex = c.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex < 0) {
            Log.w(TAG, "No input buffer available — frame dropped")
            if (encodeBitmap !== bitmap) encodeBitmap.recycle()
            return
        }

        val inputBuffer: ByteBuffer = c.getInputBuffer(inputIndex)!!
        MediaUtils.bitmapToYuv420(encodeBitmap, inputBuffer)
        c.queueInputBuffer(inputIndex, 0, inputBuffer.position(), timestampUs, 0)

        if (encodeBitmap !== bitmap) encodeBitmap.recycle()

        drainEncoder(false)
    }

    /**
     * Write a raw audio chunk to the muxer (no re-encoding).
     */
    fun writeAudioChunk(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!muxerStarted || audioTrackIndex < 0) return
        muxer?.writeSampleData(audioTrackIndex, data, info)
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val c = codec ?: return
        val mux = muxer ?: return

        if (endOfStream) {
            c.signalEndOfInputStream()
        }

        while (true) {
            val outputIndex = c.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) Log.w(TAG, "Format changed after muxer started")
                    else {
                        videoTrackIndex = mux.addTrack(c.outputFormat)
                        mux.start()
                        muxerStarted = true
                        Log.d(TAG, "Muxer started, video track: $videoTrackIndex")
                    }
                }
                outputIndex >= 0 -> {
                    if (!muxerStarted) {
                        Log.w(TAG, "Encoder output before muxer started — dropping")
                        c.releaseOutputBuffer(outputIndex, false)
                        continue
                    }
                    val outputBuffer: ByteBuffer = c.getOutputBuffer(outputIndex)!!
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                        bufferInfo.size > 0) {
                        mux.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                    }
                    c.releaseOutputBuffer(outputIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    fun finishEncoding() {
        drainEncoder(true)
        muxer?.stop()
        muxer?.release()
        codec?.stop()
        codec?.release()
        isEncoding = false
        Log.i(TAG, "Encoding complete: ${outputFile.name} (${outputFile.length() / 1024}KB)")
    }

    fun destroy() {
        if (isEncoding) finishEncoding()
    }
}
