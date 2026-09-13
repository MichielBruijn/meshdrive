package nu.bruijn.rcvehicle.video

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Captures the back camera via Camera2, encodes it to H.264 with the hardware
 * encoder (camera writes straight into the encoder's input Surface - no manual
 * YUV copying), and sends the result over UDP to the controller's Yggdrasil
 * address. Replaces the originally-planned WebRTC video track for the same
 * reason as ControlLink replaced the WebRTC data channel: stream-webrtc-android's
 * ICE never surfaces the Yggdrasil tun interface on a cellular-only phone.
 *
 * KEY_PREPEND_HEADER_TO_SYNC_FRAMES makes the encoder repeat SPS/PPS before every
 * keyframe's output buffer, so the decoder can (re)initialize correctly even if
 * it missed the very first frame or a keyframe's fragments were partially lost.
 *
 * The camera's raw output is always in its fixed sensor mounting orientation,
 * regardless of how the phone is currently held - encoded frames are sent as-is
 * (rotating the actual pixel data would need a GLES transform step before the
 * encoder, real work for no benefit here). Instead, the raw accelerometer is read
 * directly to determine the phone's current physical orientation, and that's used
 * to compute the rotation the *receiver* should apply for correct, non-sideways
 * display (see VideoPacket's doc).
 *
 * Deliberately NOT using OrientationEventListener here: it did drive this at one
 * point, but its clockwise-vs-counterclockwise convention is easy to get backwards
 * against the sensorOrientation formula below (Surface.ROTATION_* is
 * counterclockwise) - that bug shipped once already (portrait was fine, landscape
 * came out flipped 180 degrees, exactly what a CW/CCW sign inversion looks like:
 * it swaps the 90/270 cases and leaves 0/180 untouched). Deriving the angle
 * straight from gravity's direction in the accelerometer's own axes removes the
 * ambiguity entirely - see the sensor listener below for the physical reasoning.
 */
class VideoSender(private val context: Context) {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var socket: DatagramSocket? = null
    private var remoteAddress: InetAddress? = null
    private var remotePort: Int = 0
    private var frameId = 0
    private var sensorOrientation = 90
    @Volatile private var displayRotationDegrees = 0

    private val cameraThread = HandlerThread("VideoSenderCamera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    // Not a property initializer, same reason as everything else deferred to
    // start(): context.getSystemService() throws if called before the hosting
    // Activity's onCreate() has finished, and VideoSender is itself constructed
    // as a property initializer on VehicleActivity - i.e. before that Activity's
    // own onCreate() runs.
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // atan2(ax, ay) of the raw accelerometer reading gives the device's own
    // counter-clockwise rotation from its natural (portrait-up) orientation,
    // verified against gravity's direction in device-local axes for all four
    // snapped positions:
    //   natural (top up):        (ax,ay) ~= (0, +g)  -> atan2 = 0
    //   rotated 90 CCW (top left):(ax,ay) ~= (+g, 0)  -> atan2 = 90
    //   upside down:              (ax,ay) ~= (0, -g)  -> atan2 = 180
    //   rotated 90 CW (top right):(ax,ay) ~= (-g, 0)  -> atan2 = 270
    // That matches Surface.ROTATION_* semantics (counter-clockwise from natural)
    // exactly, so it plugs straight into the same sensorOrientation formula that
    // was already here.
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val ax = event.values[0]
            val ay = event.values[1]
            // Close to flat (phone lying on a table/dashboard) - direction is
            // unreliable, keep whatever rotation we already had instead of
            // jittering to a wrong snap.
            if (sqrt(ax * ax + ay * ay) < 3.0f) return
            val ccw = (Math.toDegrees(atan2(ax.toDouble(), ay.toDouble())).toFloat() + 360f) % 360f
            val snappedCcw = (((ccw + 45f) / 90f).toInt() * 90) % 360
            displayRotationDegrees = (sensorOrientation - snappedCcw + 360) % 360
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    var onError: ((String) -> Unit)? = null

    private var currentQuality = VideoQuality.MEDIUM
    private var fps = 20

    fun start(
        remoteHost: String,
        remotePort: Int,
        quality: VideoQuality = VideoQuality.MEDIUM,
        fps: Int = 20,
    ) {
        this.remotePort = remotePort
        this.fps = fps
        remoteAddress = InetAddress.getByName(remoteHost)
        socket = DatagramSocket()

        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI) }

        openPipeline(quality)
    }

    /**
     * Switches the live encoder+camera pipeline to a different [VideoQuality] preset
     * without touching the UDP socket, remote address, or orientation tracking - only
     * called after start(). Driven by ControllerDriveActivity's resolution picker /
     * auto-quality loop, arriving here via VehicleActivity's ControlLink callback.
     * No-op if already at that quality (avoids a pointless camera restart if the
     * controller re-sends the current preset).
     */
    fun setQuality(quality: VideoQuality) {
        if (quality == currentQuality && encoder != null) return
        closePipeline()
        openPipeline(quality)
    }

    private fun openPipeline(quality: VideoQuality) {
        currentQuality = quality
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, quality.width, quality.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, quality.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    val data = ByteArray(info.size)
                    buffer.get(data)
                    sendFrame(data)
                }
                codec.releaseOutputBuffer(index, false)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e("VideoSender", "encoder error", e)
                onError?.invoke("encoder error: ${e.message}")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
        }, cameraHandler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()
        encoder = codec

        try {
            openCamera(inputSurface)
        } catch (e: SecurityException) {
            onError?.invoke("camera permission not granted")
        }
    }

    private fun closePipeline() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        encoder?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        encoder = null
    }

    private fun openCamera(surface: Surface) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: run {
            onError?.invoke("no camera found")
            return
        }
        sensorOrientation = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                builder.addTarget(surface)
                camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        session.setRepeatingRequest(builder.build(), null, cameraHandler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onError?.invoke("capture session config failed")
                    }
                }, cameraHandler)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                onError?.invoke("camera error: $error")
            }
        }, cameraHandler)
    }

    private fun sendFrame(data: ByteArray) {
        val sock = socket ?: return
        val addr = remoteAddress ?: return
        val id = frameId++
        for (chunk in VideoPacket.fragment(id, data, displayRotationDegrees)) {
            try {
                sock.send(DatagramPacket(chunk, chunk.size, addr, remotePort))
            } catch (_: Exception) {
                // best-effort, same reasoning as ControlLink - a lost fragment drops
                // this frame on the receiving end, the next one recovers
            }
        }
    }

    fun stop() {
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(sensorListener)
        closePipeline()
        socket?.close()
        socket = null
        cameraThread.quitSafely()
    }
}
