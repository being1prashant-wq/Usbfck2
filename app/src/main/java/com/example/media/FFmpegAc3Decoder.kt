package com.example.media

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Robust cross-platform software decoder powered by FFmpegKit for handling
 * AC3 (Dolby Digital), E-AC-3, and multi-channel audio streams
 * during video playback.
 */
class FFmpegAc3Decoder(private val context: Context) {

    companion object {
        const val TAG = "FFmpegAc3Decoder"

        fun isAc3Codec(codec: String?): Boolean {
            if (codec == null) return false
            val lower = codec.lowercase().trim()
            return lower == "ac3" || lower == "eac3" || lower == "ac-3" || lower == "eac-3" ||
                   lower.contains("dolby") || lower == "dca" || lower == "truehd" || lower == "dtshd"
        }
    }

    data class AudioStreamInfo(
        val index: Int,
        val codec: String,
        val title: String,
        val language: String,
        val channels: Int?,
        val sampleRate: String?,
        val isAc3: Boolean
    )

    data class MediaProbeResult(
        val hasAc3: Boolean,
        val audioStreams: List<AudioStreamInfo>,
        val videoCodec: String?,
        val durationMs: Long
    )

    @Volatile
    private var currentExecutionId: Long = -1L

    /**
     * Inspect media file or stream cache using FFprobeKit to detect AC3 audio streams.
     */
    suspend fun probeMedia(filePath: String): MediaProbeResult = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists() || file.length() < 1024) {
                return@withContext MediaProbeResult(false, emptyList(), null, 0L)
            }

            val session = FFprobeKit.getMediaInformation(filePath)
            val info = session.mediaInformation ?: return@withContext MediaProbeResult(false, emptyList(), null, 0L)

            val audioStreams = mutableListOf<AudioStreamInfo>()
            var hasAc3 = false
            var videoCodec: String? = null

            var audioCounter = 1
            for (stream in info.streams) {
                if (stream.type.equals("video", ignoreCase = true)) {
                    if (videoCodec == null) {
                        videoCodec = stream.codec
                    }
                } else if (stream.type.equals("audio", ignoreCase = true)) {
                    val codec = stream.codec ?: "unknown"
                    val isAc3 = isAc3Codec(codec)
                    if (isAc3) hasAc3 = true

                    val lang = stream.getStringProperty("language") ?: "und"
                    val title = stream.getStringProperty("title")
                        ?: "Track $audioCounter (${codec.uppercase()}${if (isAc3) " [FFmpeg SW]" else ""})"

                    audioStreams.add(
                        AudioStreamInfo(
                            index = stream.index?.toInt() ?: (audioCounter - 1),
                            codec = codec,
                            title = title,
                            language = lang,
                            channels = stream.getStringProperty("channels")?.toIntOrNull(),
                            sampleRate = stream.sampleRate,
                            isAc3 = isAc3
                        )
                    )
                    audioCounter++
                }
            }

            val durationSec = info.duration?.toDoubleOrNull() ?: 0.0
            val durationMs = (durationSec * 1000).toLong()

            Log.i(TAG, "Probed media $filePath: hasAc3=$hasAc3, audioStreams=${audioStreams.size}")
            MediaProbeResult(
                hasAc3 = hasAc3,
                audioStreams = audioStreams,
                videoCodec = videoCodec,
                durationMs = durationMs
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error probing media file: $filePath", e)
            MediaProbeResult(false, emptyList(), null, 0L)
        }
    }

    /**
     * Decode an AC3 audio stream from input file to a standalone AAC (.m4a) file
     * using FFmpegKit's software decoder.
     */
    suspend fun decodeAudioStream(
        inputPath: String,
        audioStreamIndex: Int,
        sessionId: Long
    ): File? = withContext(Dispatchers.IO) {
        val outputFile = File(context.cacheDir, "ffmpeg_ac3_audio_${sessionId}_stream_${audioStreamIndex}.m4a")
        if (outputFile.exists()) {
            outputFile.delete()
        }

        try {
            // -y: overwrite
            // -i: input file
            // -map 0:$audioStreamIndex: select target audio stream
            // -vn: disable video recording
            // -c:a aac: encode software decoded audio to standard AAC
            // -b:a 192k: clean 192kbps bitrate
            val cmd = "-y -i \"$inputPath\" -map 0:$audioStreamIndex -vn -c:a aac -b:a 192k \"${outputFile.absolutePath}\""
            Log.i(TAG, "Executing FFmpegKit audio decode: $cmd")

            val session = FFmpegKit.execute(cmd)
            currentExecutionId = session.sessionId

            if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0) {
                Log.i(TAG, "FFmpegKit AC3 software decoding succeeded: ${outputFile.length()} bytes")
                outputFile
            } else {
                Log.w(TAG, "FFmpegKit audio decoding failed with return code: ${session.returnCode}")
                if (outputFile.exists()) outputFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during FFmpegKit AC3 audio decoding", e)
            if (outputFile.exists()) outputFile.delete()
            null
        }
    }

    /**
     * Remux the video container: copies video bitstream without re-encoding,
     * and decodes the AC3 audio stream into AAC.
     */
    suspend fun remuxVideoWithAac(
        inputPath: String,
        sessionId: Long,
        audioStreamIndex: Int = -1
    ): File? = withContext(Dispatchers.IO) {
        val outputFile = File(context.cacheDir, "ffmpeg_ac3_remux_${sessionId}.mp4")
        if (outputFile.exists()) {
            outputFile.delete()
        }

        try {
            val mapArgs = if (audioStreamIndex >= 0) {
                "-map 0:v:0 -map 0:$audioStreamIndex"
            } else {
                "-map 0"
            }
            val cmd = "-y -i \"$inputPath\" $mapArgs -c:v copy -c:a aac -b:a 192k \"${outputFile.absolutePath}\""
            Log.i(TAG, "Executing FFmpegKit video remux: $cmd")

            val session = FFmpegKit.execute(cmd)
            currentExecutionId = session.sessionId

            if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0) {
                Log.i(TAG, "FFmpegKit remux succeeded: ${outputFile.length()} bytes")
                outputFile
            } else {
                Log.w(TAG, "FFmpegKit remux failed with return code: ${session.returnCode}")
                if (outputFile.exists()) outputFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during FFmpegKit remux", e)
            if (outputFile.exists()) outputFile.delete()
            null
        }
    }

    /**
     * Cancel any running FFmpegKit task.
     */
    fun cancel() {
        try {
            if (currentExecutionId != -1L) {
                FFmpegKit.cancel(currentExecutionId)
            } else {
                FFmpegKit.cancel()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling FFmpegKit", e)
        }
        currentExecutionId = -1L
    }

    /**
     * Clean up temporary decoded and remuxed files.
     */
    fun cleanupTempFiles(keepSessionId: Long = -1L) {
        try {
            val files = context.cacheDir.listFiles { _, name ->
                name.startsWith("ffmpeg_ac3_")
            }
            files?.forEach { file ->
                if (keepSessionId <= 0L || !file.name.contains("_${keepSessionId}")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning FFmpeg temp files", e)
        }
    }
}
