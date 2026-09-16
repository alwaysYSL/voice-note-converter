package com.aistudio.voicenote.cvtr.editor.work

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class OggValidationResult(
    val valid: Boolean,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val totalSamples: Long = 0L,
    val pageCount: Int = 0,
    val error: String? = null,
)

/**
 * Validates an Ogg Opus stream by decoding the full container structure from BOS to EOS
 * in compliance with RFC 7845 and RFC 3533.
 */
internal object OggOpusValidator {
    private val OGG_MAGIC = byteArrayOf(0x4f, 0x67, 0x67, 0x53) // "OggS"
    private val OPUS_HEAD_MAGIC = "OpusHead".toByteArray(Charsets.US_ASCII)

    fun validateFile(file: File): OggValidationResult {
        if (!file.exists() || !file.isFile || file.length() < 27L) {
            return OggValidationResult(false, error = "File does not exist or is too small")
        }
        return try {
            FileInputStream(file).use { validateStream(it) }
        } catch (error: Throwable) {
            OggValidationResult(false, error = error.message ?: "Validation failed with exception")
        }
    }

    fun validateStream(input: InputStream): OggValidationResult {
        var pageCount = 0
        var foundBos = false
        var foundEos = false
        var sampleRate = 0
        var channels = 0
        var lastGranule = 0L
        var expectedSeq = 0

        val headerBuffer = ByteArray(27)
        while (true) {
            val readHeader = readFully(input, headerBuffer)
            if (readHeader == 0) break // normal EOF
            if (readHeader < 27) {
                return OggValidationResult(false, error = "Truncated Ogg page header at page $pageCount")
            }

            if (!headerBuffer.copyOfRange(0, 4).contentEquals(OGG_MAGIC)) {
                return OggValidationResult(false, error = "Missing OggS magic at page $pageCount")
            }

            val headerType = headerBuffer[5].toInt() and 0xFF
            val granule = ByteBuffer.wrap(headerBuffer, 6, 8).order(ByteOrder.LITTLE_ENDIAN).long
            val seq = ByteBuffer.wrap(headerBuffer, 18, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val segmentCount = headerBuffer[26].toInt() and 0xFF

            if (pageCount == 0) {
                if ((headerType and 0x02) == 0) {
                    return OggValidationResult(false, error = "First page is not marked BOS")
                }
                foundBos = true
                expectedSeq = seq
            } else {
                if (seq != expectedSeq) {
                    return OggValidationResult(false, error = "Discontinuous page sequence: expected $expectedSeq, got $seq")
                }
            }
            expectedSeq++

            val segmentTable = ByteArray(segmentCount)
            val readSegments = readFully(input, segmentTable)
            if (readSegments < segmentCount) {
                return OggValidationResult(false, error = "Truncated segment table at page $pageCount")
            }

            var payloadBytes = 0
            for (seg in segmentTable) {
                payloadBytes += (seg.toInt() and 0xFF)
            }

            val payload = ByteArray(payloadBytes)
            val readPayload = readFully(input, payload)
            if (readPayload < payloadBytes) {
                return OggValidationResult(false, error = "Truncated page payload at page $pageCount")
            }

            if (pageCount == 0) {
                if (payloadBytes < 19) {
                    return OggValidationResult(false, error = "OpusHead payload is too small")
                }
                if (!payload.copyOfRange(0, 8).contentEquals(OPUS_HEAD_MAGIC)) {
                    return OggValidationResult(false, error = "Missing OpusHead magic in first page")
                }
                channels = payload[9].toInt() and 0xFF
                sampleRate = ByteBuffer.wrap(payload, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
            }

            if ((headerType and 0x04) != 0) {
                foundEos = true
                lastGranule = granule
            }

            pageCount++
        }

        if (!foundBos) {
            return OggValidationResult(false, error = "No BOS page found")
        }
        if (!foundEos) {
            return OggValidationResult(false, error = "No EOS page found (file is truncated)")
        }
        if (lastGranule <= 0L) {
            return OggValidationResult(false, error = "No audio samples reported in EOS granule")
        }

        return OggValidationResult(
            valid = true,
            sampleRate = sampleRate,
            channels = channels,
            totalSamples = lastGranule,
            pageCount = pageCount,
        )
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val count = input.read(buffer, total, buffer.size - total)
            if (count < 0) break
            total += count
        }
        return total
    }
}
