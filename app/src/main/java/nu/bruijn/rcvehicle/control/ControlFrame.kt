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
    private const val TYPE_TELEMETRY_ENABLE: Byte = 0x07
    private const val TYPE_TELEMETRY: Byte = 0x08
    private const val TYPE_STEER_LIMIT_CONFIG: Byte = 0x09
    private const val TYPE_BUSY: Byte = 0x0A
    private const val CONTROL_SIZE = 9
    private const val TIMESTAMP_SIZE = 9 // type + long
    private const val TELEMETRY_SIZE = 1 + 1 + 4 + 8 + 8 // type, hasFix, speed float, lat/lon doubles
    private const val STEER_LIMIT_CONFIG_SIZE = 1 + 4 + 4 // type, maxSpeedKmh float, reductionFraction float

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

    /** Controller -> vehicle: start/stop GPS and sending it back - debug-only overlay,
     * so the vehicle doesn't burn battery on location fixes nobody's looking at. */
    fun encodeTelemetryEnable(enabled: Boolean): ByteArray = byteArrayOf(TYPE_TELEMETRY_ENABLE, if (enabled) 1 else 0)

    /** Vehicle -> controller: current GPS fix, for the controller's debug speed/GPS
     * overlay. [hasFix] distinguishes "no GPS fix yet" from "stationary at 0 km/h". */
    fun encodeTelemetry(hasFix: Boolean, speedKmh: Float, lat: Double, lon: Double): ByteArray {
        val bb = ByteBuffer.allocate(TELEMETRY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(TYPE_TELEMETRY)
        bb.put(if (hasFix) 1 else 0)
        bb.putFloat(speedKmh)
        bb.putDouble(lat)
        bb.putDouble(lon)
        return bb.array()
    }

    /** Controller -> vehicle: tunes the speed-based steering limiter (debug-only
     * sliders) - see VehicleActivity.applySpeedSteerLimit()'s doc for the math
     * these two feed into. */
    fun encodeSteerLimitConfig(maxSpeedKmh: Float, reductionFraction: Float): ByteArray {
        val bb = ByteBuffer.allocate(STEER_LIMIT_CONFIG_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(TYPE_STEER_LIMIT_CONFIG)
        bb.putFloat(maxSpeedKmh)
        bb.putFloat(reductionFraction)
        return bb.array()
    }

    /** Vehicle -> controller: sent instead of acting on a Control frame from
     * anyone other than whoever's currently driving - see ControlLink.listen()'s
     * ownership-lock doc for why. */
    fun encodeBusy(): ByteArray = byteArrayOf(TYPE_BUSY)

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
        data class TelemetryEnable(val enabled: Boolean) : Decoded()
        data class Telemetry(val hasFix: Boolean, val speedKmh: Float, val lat: Double, val lon: Double) : Decoded()
        data class SteerLimitConfig(val maxSpeedKmh: Float, val reductionFraction: Float) : Decoded()
        data object Busy : Decoded()
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
            TYPE_TELEMETRY_ENABLE -> {
                if (length != 2) return null
                Decoded.TelemetryEnable(bytes[1] == 1.toByte())
            }
            TYPE_TELEMETRY -> {
                if (length != TELEMETRY_SIZE) return null
                val bb = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)
                bb.position(1)
                val hasFix = bb.get() == 1.toByte()
                val speedKmh = bb.float
                val lat = bb.double
                val lon = bb.double
                Decoded.Telemetry(hasFix, speedKmh, lat, lon)
            }
            TYPE_STEER_LIMIT_CONFIG -> {
                if (length != STEER_LIMIT_CONFIG_SIZE) return null
                val bb = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)
                bb.position(1)
                val maxSpeedKmh = bb.float
                val reductionFraction = bb.float
                Decoded.SteerLimitConfig(maxSpeedKmh, reductionFraction)
            }
            TYPE_BUSY -> {
                if (length != 1) return null
                Decoded.Busy
            }
            else -> null
        }
    }
}
