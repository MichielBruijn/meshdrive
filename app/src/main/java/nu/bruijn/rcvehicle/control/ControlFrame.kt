package nu.bruijn.rcvehicle.control

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire format for ControlLink's UDP socket. Two message types share one socket:
 * a 9-byte control frame sent continuously at the UI tick rate, and a 2-byte
 * steer-limit-toggle frame sent rarely (on checkbox change). Originally designed
 * for a WebRTC data channel (plan section 4) - kept as-is now that the transport
 * is a plain UDP socket instead (see ControlLink's doc).
 */
object ControlFrame {
    private const val TYPE_CONTROL: Byte = 0x01
    private const val TYPE_STEER_LIMIT: Byte = 0x02
    private const val CONTROL_SIZE = 9

    fun encodeControl(steer: Float, throttle: Float, seq: Int): ByteArray {
        val bb = ByteBuffer.allocate(CONTROL_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(TYPE_CONTROL)
        bb.putShort((steer.coerceIn(-1f, 1f) * 1000).toInt().toShort())
        bb.putShort((throttle.coerceIn(-1f, 1f) * 1000).toInt().toShort())
        bb.putInt(seq)
        return bb.array()
    }

    fun encodeSteerLimit(enabled: Boolean): ByteArray =
        byteArrayOf(TYPE_STEER_LIMIT, if (enabled) 1 else 0)

    sealed class Decoded {
        data class Control(val steer: Float, val throttle: Float, val seq: Int) : Decoded()
        data class SteerLimit(val enabled: Boolean) : Decoded()
    }

    fun decode(bytes: ByteArray, length: Int): Decoded? {
        if (length < 1) return null
        return when (bytes[0]) {
            TYPE_CONTROL -> {
                if (length != CONTROL_SIZE) return null
                val bb = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)
                bb.position(1)
                val steer = bb.short / 1000f
                val throttle = bb.short / 1000f
                val seq = bb.int
                Decoded.Control(steer, throttle, seq)
            }
            TYPE_STEER_LIMIT -> {
                if (length != 2) return null
                Decoded.SteerLimit(bytes[1] == 1.toByte())
            }
            else -> null
        }
    }
}
