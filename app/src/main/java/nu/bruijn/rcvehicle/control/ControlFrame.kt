package nu.bruijn.rcvehicle.control

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Same 9-byte wire format originally designed for the WebRTC data channel (plan
 * section 4) - kept as-is now that the control path is a plain UDP socket instead
 * (see ControlLink doc for why). type(1) + steer int16(2) + throttle int16(2) +
 * seq uint32(4), little-endian, steer/throttle scaled by 1000.
 */
object ControlFrame {
    const val SIZE = 9
    private const val TYPE_CONTROL: Byte = 0x01

    fun encode(steer: Float, throttle: Float, seq: Int): ByteArray {
        val bb = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(TYPE_CONTROL)
        bb.putShort((steer.coerceIn(-1f, 1f) * 1000).toInt().toShort())
        bb.putShort((throttle.coerceIn(-1f, 1f) * 1000).toInt().toShort())
        bb.putInt(seq)
        return bb.array()
    }

    data class Decoded(val steer: Float, val throttle: Float, val seq: Int)

    fun decode(bytes: ByteArray, length: Int): Decoded? {
        if (length != SIZE || bytes[0] != TYPE_CONTROL) return null
        val bb = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(1)
        val steer = bb.short / 1000f
        val throttle = bb.short / 1000f
        val seq = bb.int
        return Decoded(steer, throttle, seq)
    }
}
