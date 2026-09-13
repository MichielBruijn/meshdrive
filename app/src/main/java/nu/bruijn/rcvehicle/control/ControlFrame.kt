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
    private const val TYPE_PING: Byte = 0x03
    private const val TYPE_PONG: Byte = 0x04
    private const val TYPE_QUALITY: Byte = 0x05
    private const val TYPE_CAMERA: Byte = 0x06
    private const val CONTROL_SIZE = 9
    private const val TIMESTAMP_SIZE = 9 // type + long

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

    /** Latency probe (debug overlay): controller sends Ping, vehicle echoes back a Pong
     * carrying the same timestamp so the controller can compute round-trip time without
     * needing a separate sequence-matching scheme. */
    fun encodePing(sentAtMs: Long): ByteArray = encodeTimestamp(TYPE_PING, sentAtMs)
    fun encodePong(sentAtMs: Long): ByteArray = encodeTimestamp(TYPE_PONG, sentAtMs)

    /** Controller -> vehicle: switch the video encoder to VideoQuality preset [presetIndex]. */
    fun encodeQuality(presetIndex: Int): ByteArray = byteArrayOf(TYPE_QUALITY, presetIndex.toByte())

    /** Controller -> vehicle: switch which physical camera streams video - "rear view" toggle. */
    fun encodeCamera(useFront: Boolean): ByteArray = byteArrayOf(TYPE_CAMERA, if (useFront) 1 else 0)

    private fun encodeTimestamp(type: Byte, value: Long): ByteArray {
        val bb = ByteBuffer.allocate(TIMESTAMP_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(type)
        bb.putLong(value)
        return bb.array()
    }

    sealed class Decoded {
        data class Control(val steer: Float, val throttle: Float, val seq: Int) : Decoded()
        data class SteerLimit(val enabled: Boolean) : Decoded()
        data class Ping(val sentAtMs: Long) : Decoded()
        data class Pong(val sentAtMs: Long) : Decoded()
        data class Quality(val presetIndex: Int) : Decoded()
        data class Camera(val useFront: Boolean) : Decoded()
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
            TYPE_PING -> {
                if (length != TIMESTAMP_SIZE) return null
                Decoded.Ping(ByteBuffer.wrap(bytes, 1, 8).order(ByteOrder.LITTLE_ENDIAN).long)
            }
            TYPE_PONG -> {
                if (length != TIMESTAMP_SIZE) return null
                Decoded.Pong(ByteBuffer.wrap(bytes, 1, 8).order(ByteOrder.LITTLE_ENDIAN).long)
            }
            TYPE_QUALITY -> {
                if (length != 2) return null
                Decoded.Quality(bytes[1].toInt())
            }
            TYPE_CAMERA -> {
                if (length != 2) return null
                Decoded.Camera(bytes[1] == 1.toByte())
            }
            else -> null
        }
    }
}
