package nu.bruijn.rcvehicle.audio

import java.nio.ByteBuffer

/**
 * One UDP packet = one raw (non-ADTS) AAC-LC access unit - no fragmentation needed
 * (AAC-LC frames at this bitrate are well under a safe UDP payload, unlike
 * VideoPacket's H.264 access units). sampleRateIndex/channelConfig ride along in
 * every packet's header, same reasoning as VideoPacket's per-fragment rotation:
 * the receiver always has a current, self-contained decoder config tied directly
 * to the stream instead of a separate, loss-prone one-time config message.
 * Values follow the standard MPEG-4 AudioSpecificConfig tables (AAC-LC, 2-byte
 * config: 5 bits object type=2, 4 bits sampling frequency index, 4 bits channel
 * config, 3 bits padding).
 */
object AudioPacket {
    const val PORT = 7863
    private const val HEADER_SIZE = 6 // seq:u32, sampleRateIndex:u8, channelConfig:u8

    fun encode(seq: Int, sampleRateIndex: Int, channelConfig: Int, data: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + data.size)
        buf.putInt(seq)
        buf.put(sampleRateIndex.toByte())
        buf.put(channelConfig.toByte())
        buf.put(data)
        return buf.array()
    }

    data class Header(val seq: Int, val sampleRateIndex: Int, val channelConfig: Int)

    fun parseHeader(packet: ByteArray, length: Int): Header? {
        if (length < HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(packet, 0, HEADER_SIZE)
        val seq = buf.int
        val sampleRateIndex = buf.get().toInt() and 0xFF
        val channelConfig = buf.get().toInt() and 0xFF
        return Header(seq, sampleRateIndex, channelConfig)
    }

    const val PAYLOAD_OFFSET = HEADER_SIZE

    private val SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
        16000, 12000, 11025, 8000, 7350,
    )

    fun sampleRateIndexFor(sampleRate: Int): Int = SAMPLE_RATES.indexOf(sampleRate).let { if (it >= 0) it else 8 }

    fun sampleRateFor(index: Int): Int = SAMPLE_RATES.getOrElse(index) { 16000 }

    /** Builds the 2-byte AAC-LC AudioSpecificConfig MediaCodec's decoder needs as csd-0
     * when fed raw (non-ADTS) access units - see this object's class doc for the layout. */
    fun buildAacLcConfig(sampleRateIndex: Int, channelConfig: Int): ByteArray {
        val objectType = 2 // AAC LC
        val b0 = ((objectType shl 3) or (sampleRateIndex shr 1)).toByte()
        val b1 = (((sampleRateIndex and 0x1) shl 7) or (channelConfig shl 3)).toByte()
        return byteArrayOf(b0, b1)
    }
}
