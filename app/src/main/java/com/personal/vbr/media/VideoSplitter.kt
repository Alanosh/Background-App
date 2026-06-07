package com.personal.vbr.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Trims and splits video by remuxing — no re-encoding.
 *
 * PERFORMANCE:
 *  Remuxing copies raw compressed data (H.264 NAL units) from source to output.
 *  A 1-minute 720p clip processes in ~2-3 seconds on CPU, vs ~30-60 seconds for re-encode.
 *  File size is proportional to the trimmed duration — no inflation.
 *
 * LIMITATION:
 *  Trim start points snap to the nearest keyframe (I-frame) in the source.
 *  This is standard behaviour — trim precision is limited by the source's keyframe interval.
 *  Typical keyframe interval is 1-2 seconds at 30fps.
 *
 * OUTPUT:
 *  Saves MP4 to [outputDir]. Filename includes timestamps for easy identification.
 */
class VideoSplitter(private val context: Context) {

    companion object {
        private const val TAG = "VideoSplitter"
        private const val BUFFER_SIZE = 1024 * 1024  // 1MB copy buffer
    }

    /**
     * Trim a video to [startMs]..[endMs] range.
     *
     * @param sourceUri   Input video URI
     * @param startMs     Trim start in milliseconds
     * @param endMs       Trim end in milliseconds (-1 = end of file)
     * @param outputDir   Directory to write output file
     * @return            Output file, or null on failure
     */
    suspend fun trim(
        sourceUri: Uri,
        startMs: Long,
        endMs: Long,
        outputDir: File
    ): File? = withContext(Dispatchers.IO) {
        val outputFile = File(outputDir, "trimmed_${startMs}_${endMs}.mp4")

        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, sourceUri, null)

            val muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            // Add all tracks (video + audio)
            val trackMapping = mutableMapOf<Int, Int>()   // source index → muxer index
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                trackMapping[i] = muxer.addTrack(format)
            }

            muxer.start()

            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()
            val endUs = if (endMs < 0) Long.MAX_VALUE else endMs * 1000L

            // Process each track separately
            for ((sourceTrack, muxerTrack) in trackMapping) {
                extractor.selectTrack(sourceTrack)
                extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                val startUs = extractor.sampleTime  // actual seek position (nearest keyframe)
                var samplesWritten = 0

                while (true) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    val sampleTimeUs = extractor.sampleTime
                    if (sampleTimeUs > endUs) break

                    bufferInfo.apply {
                        offset             = 0
                        size               = sampleSize
                        presentationTimeUs = sampleTimeUs - startUs  // rebase to 0
                        flags              = extractor.sampleFlags
                    }

                    muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                    extractor.advance()
                    samplesWritten++
                }

                extractor.unselectTrack(sourceTrack)
                Log.d(TAG, "Track $sourceTrack: $samplesWritten samples written")
            }

            muxer.stop()
            muxer.release()
            extractor.release()

            Log.i(TAG, "Trim complete: ${outputFile.name} (${outputFile.length() / 1024}KB)")
            outputFile

        } catch (e: Exception) {
            Log.e(TAG, "Trim failed", e)
            outputFile.delete()
            null
        }
    }

    /**
     * Split a video into multiple segments at [splitPointsMs].
     *
     * Example: splitPointsMs = [30000, 60000] splits a 90s video into
     * three clips: 0-30s, 30-60s, 60-90s.
     *
     * @return List of output files (may be shorter than expected on partial failure)
     */
    suspend fun split(
        sourceUri: Uri,
        splitPointsMs: List<Long>,
        outputDir: File
    ): List<File> = withContext(Dispatchers.IO) {

        if (splitPointsMs.isEmpty()) {
            Log.w(TAG, "No split points provided")
            return@withContext emptyList()
        }

        // Build segment ranges
        val sortedPoints = splitPointsMs.sorted()
        val segments = mutableListOf<Pair<Long, Long>>()
        segments.add(0L to sortedPoints.first())
        for (i in 0 until sortedPoints.size - 1) {
            segments.add(sortedPoints[i] to sortedPoints[i + 1])
        }
        segments.add(sortedPoints.last() to -1L)

        val results = mutableListOf<File>()
        segments.forEachIndexed { index, (start, end) ->
            val outputFile = File(outputDir, "segment_${index + 1}_${start}to${end}.mp4")
            val file = trim(sourceUri, start, end, outputDir)
            if (file != null) results.add(file)
        }

        Log.i(TAG, "Split complete: ${results.size}/${segments.size} segments created")
        results
    }
}
