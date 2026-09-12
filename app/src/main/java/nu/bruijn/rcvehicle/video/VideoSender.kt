package nu.bruijn.rcvehicle.video

import android.content.Context
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
 */
class VideoSender(private val context: Context) {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var socket: DatagramSocket? = null
    private var remoteAddress: InetAddress? = null
    private var remotePort: Int = 0
    private var frameId = 0

    private val cameraThread = HandlerThread("VideoSenderCamera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    var onError: ((String) -> Unit)? = null

    fun start(
        remoteHost: String,
        remotePort: Int,
        width: Int = 640,
        height: Int = 480,
        bitrate: Int = 800_000,
        fps: Int = 20,
    ) {
        this.remotePort = remotePort
        remoteAddress = InetAddress.getByName(remoteHost)
        socket = DatagramSocket()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
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

    private fun openCamera(surface: Surface) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: run {
            onError?.invoke("no camera found")
            return
        }

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
        for (chunk in VideoPacket.fragment(id, data)) {
            try {
                sock.send(DatagramPacket(chunk, chunk.size, addr, remotePort))
            } catch (_: Exception) {
                // best-effort, same reasoning as ControlLink - a lost fragment drops
                // this frame on the receiving end, the next one recovers
            }
        }
    }

    fun stop() {
        captureSession?.close()
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
        socket?.close()
        socket = null
        cameraThread.quitSafely()
    }
}
