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
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.atan2
import kotlin.math.sqrt
import nu.bruijn.rcvehicle.control.SignedFrame

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

    // Guards against the same real, confirmed-on-hardware crash AudioSender's own
    // [lock] doc describes: closePipeline() releasing the encoder from the caller's
    // thread (setQuality()/switchCamera(), off the ControlLink receive loop) while
    // the encoder's own MediaCodec.Callback, running on cameraHandler, is mid-call
    // into it. Lower-frequency here (quality/camera switches, not every mute
    // toggle) than AudioSender's case, which is presumably why this hadn't been
    // reported yet - same latent bug either way.
    private val encoderLock = Any()
    private var encoderStopped = true

    private var socket: DatagramSocket? = null
    private var remoteAddress: InetAddress? = null
    private var remotePort: Int = 0
    private lateinit var secret: ByteArray
    private var frameId = 0
    private var sensorOrientation = 90
    @Volatile private var displayRotationDegrees = 0

    // Recreated on every start(), not fixed for the instance's whole lifetime -
    // matches AudioSender's own audioThread (see its doc): a HandlerThread that's
    // been quitSafely()'d can't take a new Looper-bound callback afterward, so
    // this instance couldn't survive a stop()+start() cycle otherwise. Needed
    // once VehicleForegroundService started stopping video/audio entirely while
    // no controller's been seen in a while (battery, see its idle-watch doc) -
    // previously stop() was only ever called once, at the very end of a whole
    // session (VehicleActivity/VehicleForegroundService onDestroy()), so this
    // limitation never actually mattered before.
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

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
            // The front camera's sensor is mounted with the opposite handedness from
            // the back one (standard on phones, normally compensated by mirroring the
            // preview - deliberately NOT done here, see switchCamera()'s doc), which
            // flips the sign this composes with snappedCcw. Confirmed on real
            // hardware: with a single shared subtract-formula, front camera came out
            // correct in portrait (where the two signs happen to agree) but upside
            // down in both landscape orientations (where they don't) - the portrait/
            // landscape split matching exactly which snappedCcw values the two signs
            // agree/disagree at.
            displayRotationDegrees = if (useFrontCamera) {
                (sensorOrientation + snappedCcw) % 360
            } else {
                (sensorOrientation - snappedCcw + 360) % 360
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    var onError: ((String) -> Unit)? = null

    private var currentQuality = VideoQuality.MEDIUM

    /** [secret] is the PairingSecret shared with the controller that scanned this
     * vehicle's QR code (see SignedFrame/PairingSecret) - every fragment is tagged
     * with it so a stranger on the mesh can't watch this camera feed. Frame rate
     * is [quality]'s own now (VideoQuality.fps), not a separate parameter here -
     * see its doc for why (ULOW wants a different one from the rest). */
    fun start(
        remoteHost: String,
        remotePort: Int,
        secret: ByteArray,
        quality: VideoQuality = VideoQuality.MEDIUM,
    ) {
        this.remotePort = remotePort
        this.secret = secret
        remoteAddress = InetAddress.getByName(remoteHost)
        socket = DatagramSocket()
        cameraThread = HandlerThread("VideoSenderCamera").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)

        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI) }

        openPipeline(quality)
    }

    /**
     * Re-points an already-running pipeline at a new destination, without
     * touching the camera/encoder/socket at all - used when a different
     * controller takes over an already-connected vehicle (VehicleActivity's
     * maybeStartVideo()). Confirmed on real hardware as a real bug without
     * this: video kept streaming to whichever controller connected FIRST for
     * the rest of the session, even after control legitimately changed hands
     * (the old controller force-stopped, its ownership lock timed out,
     * someone else connected) - the new controller got working controls but
     * no video at all. Deliberately NOT a stop()+start() cycle even though
     * that's safe now (see cameraThread's doc) - restarting the camera/encoder
     * is real work and a visible glitch for no reason, when only where its
     * packets go actually needs to change.
     */
    fun retarget(remoteHost: String) {
        remoteAddress = InetAddress.getByName(remoteHost)
    }

    /**
     * Replaces just the UDP socket, touching nothing else - the camera/encoder
     * pipeline doesn't care about network routing at all, only sendFrame() does.
     * Called when the underlying Yggdrasil session has been torn down and
     * re-established (e.g. another app grabbed the OS VPN slot and it was
     * reclaimed afterwards - see YggdrasilVpnService's onRevoke() doc):
     * confirmed live as a real bug without this - Yggdrasil itself reports
     * "Connected" again just fine, but this socket was still bound against
     * whatever the OS's default route was at the moment it was created, and a
     * VPN interface being torn down and rebuilt (rather than just its
     * connectivity blipping) doesn't reliably get picked up by an already-open
     * socket the way an ordinary route change would. Only a full stop()+start()
     * (or, cheaper, this) recovered it in practice.
     */
    fun refreshSocket() {
        socket?.close()
        socket = DatagramSocket()
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
        if (socket == null) {
            // Not started yet - start() takes the quality; opening a pipeline here would
            // run without a camera thread/socket.
            currentQuality = quality
            return
        }
        if (quality == currentQuality && encoder != null) return
        closePipeline()
        openPipeline(quality)
    }

    private var useFrontCamera = false

    /**
     * "Rear view" toggle (ControllerDriveActivity): switches which of the phone's two
     * cameras streams video. Not a selfie mirror - the raw sensor image is sent as-is
     * either way (see openCamera()'s dynamic sensorOrientation lookup), which is the
     * correct behavior for an actual backward-facing view rather than a flipped one.
     */
    fun switchCamera(useFront: Boolean) {
        if (useFront == useFrontCamera && cameraDevice != null) return
        useFrontCamera = useFront
        closePipeline()
        openPipeline(currentQuality)
    }

    private fun openPipeline(quality: VideoQuality) {
        currentQuality = quality
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, quality.width, quality.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, quality.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, quality.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            // Encoder drops surplus frames itself - the camera may not offer a range at
            // exactly this fps (see pickFpsRange()).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, quality.fps.toFloat())
            }
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                synchronized(encoderLock) {
                    if (encoderStopped) return
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        val data = ByteArray(info.size)
                        buffer.get(data)
                        sendFrame(data)
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e("VideoSender", "encoder error", e)
                onError?.invoke("encoder error: ${e.message}")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
        }, cameraHandler!!)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()
        encoder = codec
        synchronized(encoderLock) { encoderStopped = false }

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
        // Blocks until any in-flight onOutputBufferAvailable call has finished (or,
        // once this returns, guarantees every future one is a no-op) before
        // anything below touches encoder - see encoderLock's doc.
        synchronized(encoderLock) { encoderStopped = true }
        encoder?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        encoder = null
    }

    /** Exact (fps, fps) if the camera offers it, else the tightest supported range that
     * contains fps (the encoder's KEY_MAX_FPS_TO_ENCODER trims the rest), else (fps, fps)
     * as before. */
    private fun pickFpsRange(characteristics: CameraCharacteristics, fps: Int): Range<Int> {
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return Range(fps, fps)
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { fps in it.lower..it.upper }.minByOrNull { it.upper - it.lower }
            ?: Range(fps, fps)
    }

    private fun openCamera(surface: Surface) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val desiredFacing = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == desiredFacing
        } ?: manager.cameraIdList.firstOrNull() ?: run {
            onError?.invoke("no camera found")
            return
        }
        val characteristics = manager.getCameraCharacteristics(cameraId)
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val fpsRange = pickFpsRange(characteristics, currentQuality.fps)

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                builder.addTarget(surface)
                // MediaFormat.KEY_FRAME_RATE (openPipeline() above) only tells the
                // encoder what to ASSUME for bitrate/keyframe-interval math - it
                // does not throttle how often frames actually arrive. Confirmed on
                // real hardware as a real bug: without this, the camera HAL kept
                // capturing (and the encoder kept encoding) at its own default rate
                // regardless of quality.fps, so Ultra Low's 15fps request measured
                // as ~30fps in practice. This is what actually caps the capture
                // rate. Same range on both ends of the Range so the HAL can't pick
                // anything else within it - falls back silently (leaving the HAL's
                // own default) if this exact fps isn't in the camera's supported
                // ranges, same tolerance as the rest of this class's HAL-quirk
                // handling (onConfigureFailed() below already falls back to a
                // lower preset on any capture-session rejection, fps included).
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                try {
                    camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(builder.build(), null, cameraHandler!!)
                        } catch (e: Exception) {
                            // Confirmed on real hardware as a real crash without this:
                            // the camera HAL can reject setRepeatingRequest() outright
                            // ("Function not implemented", CameraAccessException) even
                            // after successfully configuring the session - seen right
                            // after a previous camera session on this device had just
                            // been torn down (a transient HAL hiccup, not a rejection
                            // of this resolution the way onConfigureFailed() below
                            // handles), and this ran on its own HandlerThread, so an
                            // uncaught exception here took the whole app down. Report
                            // and give up cleanly instead.
                            onError?.invoke("camera setRepeatingRequest failed: ${e.message}")
                            session.close()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        // Not every camera HAL accepts every resolution as an encoder
                        // target - confirmed on real hardware (Fairphone 5 rejected
                        // 960x720 outright, "Set of requested inputs/outputs not
                        // supported by HAL", no video at all rather than just lower
                        // quality). Rather than hardcode which presets are safe on
                        // which device, just try the next lower one automatically -
                        // "always start at the highest that actually works" without
                        // needing to know in advance what that is.
                        val fallback = VideoQuality.entries.getOrNull(currentQuality.ordinal - 1)
                        if (fallback != null) {
                            onError?.invoke("${currentQuality.label} not supported by this camera, trying ${fallback.label}")
                            closePipeline()
                            openPipeline(fallback)
                        } else {
                            onError?.invoke("capture session config failed at ${currentQuality.label} (lowest preset)")
                        }
                    }
                }, cameraHandler!!)
                } catch (e: Exception) {
                    // Confirmed on real hardware as a real crash without this:
                    // createCaptureSession() itself can throw synchronously
                    // ("Error configuring streams: Broken pipe",
                    // CameraAccessException), not just fail asynchronously via
                    // onConfigureFailed() above - same transient-HAL-hiccup
                    // class of problem as setRepeatingRequest()'s doc above,
                    // just a different call site. Report and give up cleanly.
                    onError?.invoke("createCaptureSession failed: ${e.message}")
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                onError?.invoke("camera error: $error")
            }
            }, cameraHandler!!)
        } catch (e: Exception) {
            // Same transient-HAL-hiccup class as the other two call sites in
            // this function (see their docs) - openCamera() itself can also
            // throw synchronously (CameraAccessException: camera disabled/
            // already in use, or a SecurityException if permission was
            // revoked mid-session).
            onError?.invoke("openCamera failed: ${e.message}")
        }
    }

    private fun sendFrame(data: ByteArray) {
        val sock = socket ?: return
        val addr = remoteAddress ?: return
        val id = frameId++
        for (chunk in VideoPacket.fragment(id, data, displayRotationDegrees)) {
            try {
                val signed = SignedFrame.sign(chunk, secret)
                sock.send(DatagramPacket(signed, signed.size, addr, remotePort))
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
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }
}
