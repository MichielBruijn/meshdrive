package nu.bruijn.rcvehicle.video

import java.nio.ByteBuffer

/**
 * Minimal fragmentation for MediaCodec output buffers over UDP - one encoded
 * access unit can be much larger than a safe UDP payload, so each is split into
 * fixed-size fragments carrying [frameId, fragIndex, fragCount] so the receiver
 * can reassemble it. If any fragment of a frame is lost, that whole frame is
 * dropped (VideoReceiver) - acceptable since KEY_I_FRAME_INTERVAL keeps keyframes
 * frequent, so a dropped frame is a brief glitch, not a stuck picture.
 *
 * `rotationDegrees` rides along in every fragment's header (redundant per fragment,
 * but tiny, and means the receiver always has a current value tied directly to the
 * stream it's decoding rather than needing a separate, loss-prone message channel)
 * - the vehicle's device orientation, so the controller can rotate its display to
 * match instead of always showing the camera's raw (fixed sensor mount) orientation.
 */
object VideoPacket {
    const val PORT = 7862
    private const val HEADER_SIZE = 9 // frameId:u32, fragIndex:u16, fragCount:u16, rotation:u8
    const val MAX_PAYLOAD = 1200 // stays under common path MTUs after the header

    fun fragment(frameId: Int, data: ByteArray, rotationDegrees: Int): List<ByteArray> {
        val fragCount = (data.size + MAX_PAYLOAD - 1) / MAX_PAYLOAD
        return (0 until fragCount).map { i ->
            val start = i * MAX_PAYLOAD
            val end = minOf(start + MAX_PAYLOAD, data.size)
            val payload = end - start
            val buf = ByteBuffer.allocate(HEADER_SIZE + payload)
            buf.putInt(frameId)
            buf.putShort(i.toShort())
            buf.putShort(fragCount.toShort())
            buf.put(rotationDegrees.toByte())
            buf.put(data, start, payload)
            buf.array()
        }
    }

    data class Header(val frameId: Int, val fragIndex: Int, val fragCount: Int, val rotationDegrees: Int)

    fun parseHeader(packet: ByteArray, length: Int): Header? {
        if (length < HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(packet, 0, HEADER_SIZE)
        val frameId = buf.int
        val fragIndex = buf.short.toInt() and 0xFFFF
        val fragCount = buf.short.toInt() and 0xFFFF
        val rotationDegrees = buf.get().toInt() and 0xFF
        return Header(frameId, fragIndex, fragCount, rotationDegrees)
    }

    const val PAYLOAD_OFFSET = HEADER_SIZE
}
