package com.aistudio.voicenote.cvtr.audio

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encapsulates raw Opus audio packets into a compliant Ogg Opus bitstream (.ogg container)
 * as specified in RFC 7845.
 */
class OggOpusWriter(private val outputStream: OutputStream) {

    private val serialNumber = (System.currentTimeMillis() and 0x7FFFFFFFL).toInt()
    private var pageSequenceNumber = 0
    private var granulePosition = 0L
    private val pendingPackets = mutableListOf<ByteArray>()
    private var pendingSamples = 0L
    private var pendingPayloadBytes = 0
    private var pendingLacingSegments = 0
    private val maxPayloadBytes = 4000
    private var audioPageWritten = false
    private var endOfStreamWritten = false

    companion object {
        private val CRC_TABLE = IntArray(256).apply {
            for (i in 0 until 256) {
                var r = i shl 24
                for (j in 0 until 8) {
                    r = if ((r and -0x80000000) != 0) {
                        (r shl 1) xor 0x04c11db7
                    } else {
                        r shl 1
                    }
                }
                this[i] = r
            }
        }

        fun calculateCrc(data: ByteArray, offset: Int, length: Int): Int {
            var crc = 0
            for (i in offset until (offset + length)) {
                val byteVal = data[i].toInt() and 0xFF
                crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor byteVal) and 0xFF]
            }
            return crc
        }
    }

    /**
     * Writes the Beginning-Of-Stream (BOS) Identification Header Page (OpusHead).
     */
    fun writeHeader(sampleRate: Int = 48000, channels: Int = 1, preSkipSamples: Int = 0) {
        val opusHead = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray(Charsets.US_ASCII)) // Magic
            put(1.toByte())                                // Version
            put(channels.toByte())                         // Channels: 1 (mono)
            putShort(preSkipSamples.coerceIn(0, 65_535).toShort())
            putInt(sampleRate)                             // Original Sample Rate
            putShort(0.toShort())                          // Output Gain
            put(0.toByte())                                // Channel Mapping Family: 0 (mono or stereo)
        }.array()

        writePage(
            headerType = 0x02, // BOS
            granule = 0L,
            packets = listOf(opusHead)
        )

        // Write Comments / Tags Header Page (OpusTags)
        val vendor = "VoiceNoteConverter 1.0".toByteArray(Charsets.UTF_8)
        val opusTags = ByteBuffer.allocate(8 + 4 + vendor.size + 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusTags".toByteArray(Charsets.US_ASCII)) // Magic
            putInt(vendor.size)                            // Vendor string length
            put(vendor)                                    // Vendor string
            putInt(0)                                      // User comments list count: 0
        }.array()

        writePage(
            headerType = 0x00,
            granule = 0L,
            packets = listOf(opusTags)
        )
    }

    /**
     * Writes an audio data page containing one or more Opus packets.
     */
    fun writeAudioPacket(packetData: ByteArray, samplesInPacket: Long, isLast: Boolean = false) {
        require(packetData.isNotEmpty()) { "Opus packet tidak boleh kosong" }
        require(samplesInPacket > 0L) { "Durasi paket Opus harus positif" }
        val packetSegments = lacingSegmentCount(packetData.size)
        if (pendingPackets.isNotEmpty() &&
            (pendingPayloadBytes + packetData.size > maxPayloadBytes ||
                pendingLacingSegments + packetSegments > 255)
        ) {
            flushAudioPage(isLast = false)
        }
        pendingPackets += packetData
        pendingSamples += samplesInPacket
        pendingPayloadBytes += packetData.size
        pendingLacingSegments += packetSegments
        if (isLast) flushAudioPage(isLast = true)
    }

    private fun flushAudioPage(isLast: Boolean, finalGranulePosition: Long? = null) {
        if (pendingPackets.isEmpty()) return
        val decodedEndGranule = granulePosition + pendingSamples
        val pageGranule = if (isLast && finalGranulePosition != null) {
            finalGranulePosition.coerceIn(granulePosition, decodedEndGranule)
        } else {
            decodedEndGranule
        }
        writePage(if (isLast) 0x04 else 0x00, pageGranule, pendingPackets.toList())
        granulePosition = pageGranule
        audioPageWritten = true
        endOfStreamWritten = isLast
        pendingPackets.clear()
        pendingSamples = 0L
        pendingPayloadBytes = 0
        pendingLacingSegments = 0
    }

    private fun writePage(headerType: Int, granule: Long, packets: List<ByteArray>) {
        val totalSegments = mutableListOf<Int>()
        for (packet in packets) {
            var len = packet.size
            while (len >= 255) {
                totalSegments.add(255)
                len -= 255
            }
            totalSegments.add(len)
        }

        val segmentCount = totalSegments.size
        val packetBytesSize = packets.sumOf { it.size }
        val pageSize = 27 + segmentCount + packetBytesSize

        val buffer = ByteBuffer.allocate(pageSize).order(ByteOrder.LITTLE_ENDIAN)
        // 1. Capture pattern: "OggS"
        buffer.put(0x4f.toByte())
        buffer.put(0x67.toByte())
        buffer.put(0x67.toByte())
        buffer.put(0x53.toByte())
        // 2. Stream structure version: 0
        buffer.put(0.toByte())
        // 3. Header type flag
        buffer.put(headerType.toByte())
        // 4. Granule position
        buffer.putLong(granule)
        // 5. Bitstream serial number
        buffer.putInt(serialNumber)
        // 6. Page sequence number
        buffer.putInt(pageSequenceNumber++)
        // 7. Checksum placeholder (0 for computation)
        val checksumPos = buffer.position()
        buffer.putInt(0)
        // 8. Number of page segments
        buffer.put(segmentCount.toByte())
        // 9. Segment table
        for (seg in totalSegments) {
            buffer.put(seg.toByte())
        }
        // 10. Packet payload data
        for (packet in packets) {
            buffer.put(packet)
        }

        val pageBytes = buffer.array()
        val calculatedCrc = calculateCrc(pageBytes, 0, pageSize)
        ByteBuffer.wrap(pageBytes, checksumPos, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(calculatedCrc)

        outputStream.write(pageBytes)
    }

    fun close(finalGranulePosition: Long? = null) {
        if (pendingPackets.isNotEmpty()) {
            flushAudioPage(isLast = true, finalGranulePosition = finalGranulePosition)
        } else if (audioPageWritten && !endOfStreamWritten) {
            val finalGranule = finalGranulePosition
                ?.coerceAtLeast(granulePosition)
                ?: granulePosition
            writePage(headerType = 0x04, granule = finalGranule, packets = emptyList())
            granulePosition = finalGranule
            endOfStreamWritten = true
        }
        outputStream.flush()
        outputStream.close()
    }

    private fun lacingSegmentCount(packetSize: Int): Int = packetSize / 255 + 1
}
