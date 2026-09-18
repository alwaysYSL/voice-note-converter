package com.aistudio.voicenote.cvtr.editor.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.aistudio.voicenote.cvtr.audio.StreamingPitchShifter
import com.aistudio.voicenote.cvtr.editor.model.AudioTrackClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class EditorAudioEngine(
    private val onPlayheadUpdated: (Long) -> Unit,
    private val onPlaybackFinished: () -> Unit
) {

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private val isPlaying = AtomicBoolean(false)
    private var currentPositionMs = 0L

    companion object {
        const val SAMPLE_RATE = 48000
        const val FRAME_DURATION_MS = 20L // 20ms frames
        const val SAMPLES_PER_FRAME = (SAMPLE_RATE * FRAME_DURATION_MS / 1000L).toInt() // 960 samples

        /**
         * Clamps 32-bit mixed integers into 16-bit PCM ShortArray.
         */
        fun applySoftLimiter(sumBuffer: IntArray): ShortArray {
            val result = ShortArray(sumBuffer.size)
            for (i in sumBuffer.indices) {
                result[i] = sumBuffer[i].coerceIn(-32768, 32767).toShort()
            }
            return result
        }

        /**
         * Reads PCM slice for a single clip at a given global timeline position.
         */
        fun readTrackPcmSlice(
            clip: AudioTrackClip,
            timelinePositionMs: Long,
            durationMs: Long
        ): ShortArray {
            val targetSampleCount = (SAMPLE_RATE * durationMs / 1000L).toInt()
            val result = ShortArray(targetSampleCount)

            val clipStart = clip.startOffsetMs
            val clipEnd = clip.timelineEndMs

            // Check if frame overlaps with clip's active timeline window
            val frameEndMs = timelinePositionMs + durationMs
            if (frameEndMs <= clipStart || timelinePositionMs >= clipEnd) {
                return result // Return silence
            }

            if (!clip.pcmCacheFile.exists() || clip.pcmCacheFile.length() < 2) {
                return result
            }

            val overlapStartMs = maxOf(timelinePositionMs, clipStart)
            val overlapEndMs = minOf(frameEndMs, clipEnd)

            val offsetInClipMs = (overlapStartMs - clipStart) + clip.trimStartMs
            val samplesToRead = ((overlapEndMs - overlapStartMs) * SAMPLE_RATE / 1000L).toInt()

            val insertIndexInFrame = ((overlapStartMs - timelinePositionMs) * SAMPLE_RATE / 1000L).toInt()

            val raf = RandomAccessFile(clip.pcmCacheFile, "r")
            try {
                val startByte = offsetInClipMs * SAMPLE_RATE * 2 / 1000L
                if (startByte < clip.pcmCacheFile.length()) {
                    raf.seek(startByte)
                    val byteBuffer = ByteBuffer.allocate(samplesToRead * 2).order(ByteOrder.LITTLE_ENDIAN)
                    val bytesRead = raf.read(byteBuffer.array())
                    if (bytesRead > 0) {
                        val availableShorts = bytesRead / 2
                        val shortBuffer = byteBuffer.asShortBuffer()
                        val gain = clip.volumeGain

                        for (i in 0 until minOf(availableShorts, targetSampleCount - insertIndexInFrame)) {
                            val rawSample = shortBuffer.get()
                            val scaled = (rawSample * gain).toInt().coerceIn(-32768, 32767).toShort()
                            result[insertIndexInFrame + i] = scaled
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                raf.close()
            }

            // Apply Pitch Shifter if needed
            if (clip.pitchSemitones != 0f) {
                return applyPitchShift(result, clip.pitchSemitones)
            }

            return result
        }

        /**
         * Mixes active tracks into a single PCM frame.
         */
        fun mixTimelineFrame(
            tracks: List<AudioTrackClip>,
            timelinePositionMs: Long,
            frameDurationMs: Long
        ): ShortArray {
            val frameSampleCount = (SAMPLE_RATE * frameDurationMs / 1000L).toInt()
            val sumBuffer = IntArray(frameSampleCount)

            for (track in tracks) {
                val trackSlice = readTrackPcmSlice(track, timelinePositionMs, frameDurationMs)
                for (i in trackSlice.indices) {
                    sumBuffer[i] += trackSlice[i].toInt()
                }
            }

            return applySoftLimiter(sumBuffer)
        }

        private fun applyPitchShift(samples: ShortArray, semitones: Float): ShortArray {
            if (semitones == 0f || samples.isEmpty()) return samples
            return try {
                StreamingPitchShifter(
                    sampleRate = SAMPLE_RATE,
                    channels = 1,
                    semitones = semitones
                ).use { shifter ->
                    val processed = shifter.process(samples)
                    if (processed.isNotEmpty()) {
                        val result = ShortArray(samples.size)
                        val copyLen = minOf(processed.size, samples.size)
                        System.arraycopy(processed, 0, result, 0, copyLen)
                        result
                    } else {
                        samples
                    }
                }
            } catch (_: Throwable) {
                samples
            }
        }
    }

    fun startPlayback(
        coroutineScope: CoroutineScope,
        tracks: List<AudioTrackClip>,
        startPositionMs: Long,
        totalDurationMs: Long
    ) {
        stopPlayback()

        val activeTracks = tracks.filter { it.activeDurationMs > 0 }
        if (activeTracks.isEmpty() || totalDurationMs <= 0) {
            onPlaybackFinished()
            return
        }

        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBufferSize, SAMPLES_PER_FRAME * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        isPlaying.set(true)
        currentPositionMs = startPositionMs

        playbackJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                while (isActive && isPlaying.get() && currentPositionMs < totalDurationMs) {
                    val mixedFrame = mixTimelineFrame(activeTracks, currentPositionMs, FRAME_DURATION_MS)
                    audioTrack?.write(mixedFrame, 0, mixedFrame.size)

                    currentPositionMs += FRAME_DURATION_MS
                    onPlayheadUpdated(minOf(currentPositionMs, totalDurationMs))
                }
            } finally {
                if (currentPositionMs >= totalDurationMs) {
                    onPlaybackFinished()
                }
            }
        }
    }

    fun stopPlayback() {
        isPlaying.set(false)
        playbackJob?.cancel()
        playbackJob = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
    }

    fun release() {
        stopPlayback()
    }
}