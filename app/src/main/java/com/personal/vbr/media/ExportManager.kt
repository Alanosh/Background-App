package com.personal.vbr.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.personal.vbr.core.pipeline.FramePipeline
import com.personal.vbr.core.compositing.BackgroundRenderer
import com.personal.vbr.core.compositing.Compositor
import com.personal.vbr.core.compositing.EffectsProcessor
import com.personal.vbr.core.segmentation.MaskProcessor
import com.personal.vbr.core.segmentation.SegmentationEngine
import com.personal.vbr.core.pipeline.FramePool
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer

/**
 * Orchestrates the full export pipeline:
 *   decode source → segment → composite → encode output
 *   + copy audio track (no re-encoding)
 *
 * EXPORT THREAD:
 *  Export runs entirely on [Dispatchers.IO], independent of the preview pipeline.
 *  The preview can continue playing during export (they use separate FramePool instances).
 *
 * AUDIO:
 *  AudioTrackCopier logic is inlined here — it's called once from one place.
 *  Audio is remuxed without re-encoding for quality + speed.
 *
 * PROGRESS:
 *  Reports [0f..1f] via [onProgress]. Calls [onComplete] with the output URI on success,
 *  or [onError] with a message on failure.
 */
class ExportManager(
    private val context: Context,
    private val sourceUri: Uri,
    private val segmentationEngine: SegmentationEngine,
    private val backgroundRenderer: BackgroundRenderer
) {

    companion object {
        private const val TAG = "ExportManager"
    }

    var onProgress: ((Float) -> Unit)? = null
    var onComplete: ((Uri) -> Unit)? = null
    var onError:    ((String) -> Unit)? = null

    private var exportJob: Job? = null
    private val exportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Begin export at [resolution].
     *
     * @param resolution  Output resolution enum from VideoEncoder
     * @param trimStartMs If > 0, export starts from this timestamp
     * @param trimEndMs   If > 0, export ends at this timestamp
     */
    fun startExport(
        resolution: VideoEncoder.ExportResolution = VideoEncoder.ExportResolution.P720,
        trimStartMs: Long = 0L,
        trimEndMs: Long = -1L
    ) {
        exportJob = exportScope.launch {
            try {
                export(resolution, trimStartMs, trimEndMs)
            } catch (e: CancellationException) {
                Log.d(TAG, "Export cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                withContext(Dispatchers.Main) { onError?.invoke(e.message ?: "Export failed") }
            }
        }
    }

    private suspend fun export(
        resolution: VideoEncoder.ExportResolution,
        trimStartMs: Long,
        trimEndMs: Long
    ) {
        // Create temp file — we move to MediaStore on success
        val tempFile = File(context.cacheDir, "vbr_export_${System.currentTimeMillis()}.mp4")
        val encoder = VideoEncoder(tempFile, resolution)

        // Separate export-only FramePool — never touches preview pool
        val exportPool = FramePool(
            FramePipeline.PROCESS_WIDTH,
            FramePipeline.PROCESS_HEIGHT
        )

        val maskProcessor = MaskProcessor()
        val compositor = Compositor()
        val effectsProcessor = EffectsProcessor()

        try {
            encoder.prepare()

            // Set up source extractor for video decode
            val extractor = MediaExtractor()
            extractor.setDataSource(context, sourceUri, null)

            val videoTrack = findVideoTrack(extractor)
            val audioTrack = findAudioTrack(extractor)

            // Add audio track to muxer (no re-encode)
            if (audioTrack >= 0) {
                encoder.addAudioTrack(extractor.getTrackFormat(audioTrack))
            }

            val format = extractor.getTrackFormat(videoTrack)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            val endUs = if (trimEndMs > 0) trimEndMs * 1000L else durationUs

            extractor.selectTrack(videoTrack)
            if (trimStartMs > 0) {
                extractor.seekTo(trimStartMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            var framesProcessed = 0
            val estimatedTotalFrames = ((endUs - trimStartMs * 1000L) / 1_000_000f * 30).toInt()

            // --- Main export loop ---
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endUs) break

                // Decode frame using MediaMetadataRetriever for simplicity
                // Production: use full MediaCodec pipeline for performance
                val frameMs = sampleTimeUs / 1000L
                val rawFrame = decodeFrameAt(frameMs) ?: run {
                    extractor.advance()
                    continue
                }

                // Segment + composite
                val segResult = segmentationEngine.segment(rawFrame)
                val mask = maskProcessor.process(segResult)
                val background = backgroundRenderer.getFrameFor(frameMs)

                val outputFrame = exportPool.acquire() ?: run {
                    rawFrame.recycle()
                    extractor.advance()
                    continue
                }

                compositor.composite(rawFrame, mask, background, outputFrame, com.personal.vbr.core.adjustment.Adjustments.Params())
                encoder.encodeFrame(outputFrame, sampleTimeUs - trimStartMs * 1000L)

                exportPool.release(outputFrame)
                rawFrame.recycle()
                extractor.advance()

                framesProcessed++
                val progress = framesProcessed.toFloat() / estimatedTotalFrames
                withContext(Dispatchers.Main) { onProgress?.invoke(progress.coerceIn(0f, 0.95f)) }
            }

            // --- Copy audio track ---
            if (audioTrack >= 0) {
                copyAudioTrack(extractor, audioTrack, encoder, trimStartMs, trimEndMs)
            }

            extractor.release()
            encoder.finishEncoding()

            // Move temp file to MediaStore (visible in gallery on all Android 10-14 versions)
            val outputUri = saveToMediaStore(tempFile, resolution)

            withContext(Dispatchers.Main) {
                onProgress?.invoke(1f)
                onComplete?.invoke(outputUri)
            }

            Log.i(TAG, "Export complete → $outputUri")

        } finally {
            exportPool.destroy()
            compositor.destroy()
            effectsProcessor.destroy()
            maskProcessor.destroy()
            tempFile.delete()
        }
    }

    /**
     * Copy audio samples from [audioTrack] to encoder without re-encoding.
     * This is the "AudioTrackCopier" logic — inlined here as it has no other callers.
     */
    private fun copyAudioTrack(
        extractor: MediaExtractor,
        audioTrack: Int,
        encoder: VideoEncoder,
        trimStartMs: Long,
        trimEndMs: Long
    ) {
        extractor.selectTrack(audioTrack)
        if (trimStartMs > 0) {
            extractor.seekTo(trimStartMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }

        val buffer = ByteBuffer.allocate(512 * 1024)  // 512KB audio buffer
        val bufferInfo = MediaCodec.BufferInfo()
        val endUs = if (trimEndMs > 0) trimEndMs * 1000L else Long.MAX_VALUE

        while (true) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            val sampleTimeUs = extractor.sampleTime
            if (sampleTimeUs > endUs) break

            bufferInfo.apply {
                offset             = 0
                size               = sampleSize
                presentationTimeUs = sampleTimeUs - trimStartMs * 1000L
                flags              = extractor.sampleFlags
            }

            encoder.writeAudioChunk(buffer, bufferInfo)
            extractor.advance()
        }

        extractor.unselectTrack(audioTrack)
        Log.d(TAG, "Audio track copied")
    }

    private fun decodeFrameAt(timestampMs: Long): android.graphics.Bitmap? {
        return try {
            android.media.MediaMetadataRetriever().use { r ->
                r.setDataSource(context, sourceUri)
                r.getFrameAtTime(timestampMs * 1000L,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (e: Exception) { null }
    }

    private fun saveToMediaStore(file: File, resolution: VideoEncoder.ExportResolution): Uri {
        val filename = "VBR_${System.currentTimeMillis()}_${resolution.name}.mp4"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/VideoBackgroundRemoval")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
            context.contentResolver.openOutputStream(uri)!!.use { out ->
                file.inputStream().copyTo(out)
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            uri
        } else {
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val outputDir = File(moviesDir, "VideoBackgroundRemoval").also { it.mkdirs() }
            val dest = File(outputDir, filename)
            file.copyTo(dest, overwrite = true)
            Uri.fromFile(dest)
        }
    }

    private fun findVideoTrack(ext: MediaExtractor): Int =
        (0 until ext.trackCount).first { i ->
            ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }

    private fun findAudioTrack(ext: MediaExtractor): Int =
        (0 until ext.trackCount).indexOfFirst { i ->
            ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }

    fun cancel() {
        exportJob?.cancel()
    }

    fun destroy() {
        exportScope.cancel()
    }
}
