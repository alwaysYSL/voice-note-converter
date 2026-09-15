package com.aistudio.voicenote.cvtr.audio

internal interface StreamingAudioEncoder {
    val preSkipSamples: Int?
    val backendName: String
    fun write(samples: ShortArray)
    fun finish()
    fun release()
}

internal sealed interface EncoderAvailability {
    data class Available(val backendName: String) : EncoderAvailability
    data object Missing : EncoderAvailability
    data class Broken(val cause: Throwable) : EncoderAvailability
}

internal object OpusEncoderProbe {
    fun probeSoftware(): EncoderAvailability {
        var handle = 0L
        return try {
            handle = SoftwareOpusJni.nativeCreate(
                sampleRate = 48_000,
                channels = 1,
                bitrate = 32_000,
                complexity = 5
            )
            if (handle == 0L) return EncoderAvailability.Missing
            val packet = SoftwareOpusJni.nativeEncode(
                handle = handle,
                samples = ShortArray(960),
                frameSize = 960,
                maxPacketBytes = 4_000
            )
            if (opusPacketDurationSamples(packet) != 960L) {
                EncoderAvailability.Broken(
                    IllegalStateException("Probe libopus menghasilkan paket tidak valid")
                )
            } else {
                EncoderAvailability.Available("libopus 1.5.2")
            }
        } catch (missing: UnsatisfiedLinkError) {
            EncoderAvailability.Missing
        } catch (error: Throwable) {
            EncoderAvailability.Broken(error)
        } finally {
            if (handle != 0L) SoftwareOpusJni.nativeDestroy(handle)
        }
    }
}


internal object SoftwareOpusJni {
    init {
        System.loadLibrary("pitchshifter")
    }

    external fun nativeCreate(
        sampleRate: Int,
        channels: Int,
        bitrate: Int,
        complexity: Int
    ): Long

    external fun nativeGetLookahead(handle: Long): Int
    external fun nativeEncode(
        handle: Long,
        samples: ShortArray,
        frameSize: Int,
        maxPacketBytes: Int
    ): ByteArray
    external fun nativeDestroy(handle: Long)
}

internal class SoftwareOpusEncoder(
    private val oggWriter: OggOpusWriter,
    private val checkActive: () -> Unit,
    bitrateKbps: Int = 32,
) : StreamingAudioEncoder {
    private val bitrate = bitrateKbps.coerceIn(32, 64) * 1_000
    private var handle = SoftwareOpusJni.nativeCreate(
        sampleRate = SAMPLE_RATE,
        channels = CHANNELS,
        bitrate = bitrate,
        complexity = COMPLEXITY
    ).also { check(it != 0L) { "Encoder Opus software tidak dapat dibuat." } }
    private val frame = ShortArray(FRAME_SAMPLES)
    private var frameSamples = 0
    private var released = false

    override val preSkipSamples: Int = SoftwareOpusJni.nativeGetLookahead(handle)
    override val backendName: String = "libopus 1.5.2"

    init {
        oggWriter.writeHeader(
            sampleRate = SAMPLE_RATE,
            channels = CHANNELS,
            preSkipSamples = preSkipSamples
        )
    }

    override fun write(samples: ShortArray) {
        check(!released) { "Encoder Opus software sudah ditutup." }
        var sourceOffset = 0
        while (sourceOffset < samples.size) {
            checkActive()
            val copied = minOf(FRAME_SAMPLES - frameSamples, samples.size - sourceOffset)
            samples.copyInto(
                destination = frame,
                destinationOffset = frameSamples,
                startIndex = sourceOffset,
                endIndex = sourceOffset + copied
            )
            frameSamples += copied
            sourceOffset += copied
            if (frameSamples == FRAME_SAMPLES) encodeFrame()
        }
    }

    override fun finish() {
        try {
            if (frameSamples > 0) {
                frame.fill(0, frameSamples)
                encodeFrame()
            }
        } finally {
            release()
        }
    }

    override fun release() {
        if (released) return
        if (handle != 0L) {
            SoftwareOpusJni.nativeDestroy(handle)
            handle = 0L
        }
        released = true
    }

    private fun encodeFrame() {
        checkActive()
        val packet = SoftwareOpusJni.nativeEncode(
            handle = handle,
            samples = frame,
            frameSize = FRAME_SAMPLES,
            maxPacketBytes = MAX_PACKET_BYTES
        )
        check(packet.isNotEmpty()) { "Encoder Opus software menghasilkan paket kosong." }
        oggWriter.writeAudioPacket(packet, samplesInPacket = FRAME_SAMPLES.toLong())
        frameSamples = 0
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        const val COMPLEXITY = 5
        const val FRAME_SAMPLES = 960
        const val MAX_PACKET_BYTES = 4_000
    }
}
