package nu.bruijn.rcvehicle.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Receives VideoSender's UDP fragments, reassembles complete access units, and
 * feeds them to a Surface-output H.264 decoder for direct rendering. See
 * VideoSender's doc for why this exists instead of a WebRTC video track.
 */
class VideoReceiver {
    private var socket: DatagramSocket? = null
    private var decoder: MediaCodec? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var receiveJob: Job? = null
    private val pending = HashMap<Int, Array<ByteArray?>>()
    private val pendingRotation = HashMap<Int, Int>()
    private var lastRotation = -1

    // Frames complete in COMPLETION order, not necessarily frameId order - UDP can
    // reorder packets, more so over a cellular link than a local network. Most frames
    // are P-frames (KEY_I_FRAME_INTERVAL is seconds, not every frame - VideoSender),
    // which predict off the frame immediately before them in encode order; feeding one
    // out of order breaks that prediction and corrupts everything after it until the
    // next keyframe. Confirmed on real hardware as garbled video/artifacts specifically
    // over 4G/5G (much rarer on wifi, where reordering is uncommon) - fixed by simply
    // never feeding a frameId at or below one already fed, i.e. treating a late/
    // reordered frame the same as a lost one instead of decoding it anyway.
    private var lastFedFrameId = -1

    var onError: ((String) -> Unit)? = null

    /** Fires only when the vehicle's reported orientation actually changes. */
    var onRotationChanged: ((Int) -> Unit)? = null

    /** Fires roughly once/sec with the decoded-and-rendered frame rate, for a debug overlay. */
    var onFps: ((Float) -> Unit)? = null
    private var fpsFrameCount = 0
    private var fpsWindowStartMs = 0L

    // Recording (ControllerDriveActivity's Record button) - VideoRecorder remuxes the
    // exact same access units fed to the decoder directly into an .mp4, no re-encoding.
    // width/height are remembered here since VideoRecorder needs them for its track
    // format and this is the one place that already knows them.
    private var recorder: VideoRecorder? = null
    private var videoWidth = 640
    private var videoHeight = 480

    fun startRecording(session: MuxerSession, onError: (String) -> Unit) {
        val r = VideoRecorder()
        r.onError = onError
        r.start(session)
        recorder = r
    }

    /** True if anything was actually recorded (no video ever arrived otherwise). */
    fun stopRecording(): Boolean = recorder?.stop().also { recorder = null } ?: false

    fun start(port: Int, outputSurface: Surface, width: Int = 640, height: Int = 480) {
        videoWidth = width
        videoHeight = height
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, outputSurface, null, 0)
        codec.start()
        decoder = codec

        val sock = DatagramSocket(port)
        socket = sock

        receiveJob = scope.launch {
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)
            while (isActive) {
                try {
                    sock.receive(packet)
                    handlePacket(packet.data.copyOf(packet.length))
                } catch (e: Exception) {
                    if (!isActive) break
                    onError?.invoke("receive error: ${e.message}")
                }
            }
        }
    }

    private fun handlePacket(data: ByteArray) {
        val header = VideoPacket.parseHeader(data, data.size) ?: return
        val slots = pending.getOrPut(header.frameId) { arrayOfNulls(header.fragCount) }
        pendingRotation[header.frameId] = header.rotationDegrees
        if (header.fragIndex !in slots.indices) return
        slots[header.fragIndex] = data.copyOfRange(VideoPacket.PAYLOAD_OFFSET, data.size)

        if (slots.all { it != null }) {
            pending.remove(header.frameId)
            val rotation = pendingRotation.remove(header.frameId) ?: 0
            if (header.frameId <= lastFedFrameId) {
                // Reordered/late - a newer frame already went to the decoder. See
                // lastFedFrameId's doc: decoding this now would corrupt everything
                // after it, so it's dropped exactly like a genuinely lost frame.
                return
            }
            lastFedFrameId = header.frameId
            if (rotation != lastRotation) {
                lastRotation = rotation
                onRotationChanged?.invoke(rotation)
            }
            val assembled = ByteArray(slots.sumOf { it!!.size })
            var offset = 0
            for (part in slots) {
                part!!.copyInto(assembled, offset)
                offset += part.size
            }
            feedDecoder(assembled)
            try {
                recorder?.feed(assembled, videoWidth, videoHeight)
            } catch (e: Exception) {
                // Never let a recording-side problem take down the receive loop that
                // live playback depends on - see MuxerSession's doc for the bug this
                // guards against.
                onError?.invoke("record: ${e.message}")
            }
        }

        // Drop stale incomplete frames so a lost fragment can't leak memory forever.
        pending.keys.filter { it < header.frameId - 5 }.forEach {
            pending.remove(it)
            pendingRotation.remove(it)
        }
    }

    private fun feedDecoder(data: ByteArray) {
        val codec = decoder ?: return
        try {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val inputBuffer = codec.getInputBuffer(index) ?: return
                inputBuffer.clear()
                inputBuffer.put(data)
                codec.queueInputBuffer(index, 0, data.size, System.nanoTime() / 1000, 0)
            }
            val info = MediaCodec.BufferInfo()
            var outIndex = codec.dequeueOutputBuffer(info, 0)
            while (outIndex >= 0) {
                codec.releaseOutputBuffer(outIndex, true) // true = render straight to the Surface
                countFrameForFps()
                outIndex = codec.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            Log.e("VideoReceiver", "decode error", e)
        }
    }

    private fun countFrameForFps() {
        val now = SystemClock.elapsedRealtime()
        if (fpsWindowStartMs == 0L) fpsWindowStartMs = now
        fpsFrameCount++
        val elapsed = now - fpsWindowStartMs
        if (elapsed >= 1000) {
            onFps?.invoke(fpsFrameCount * 1000f / elapsed)
            fpsFrameCount = 0
            fpsWindowStartMs = now
        }
    }

    fun stop() {
        recorder?.stop()
        recorder = null
        receiveJob?.cancel()
        socket?.close()
        socket = null
        decoder?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        decoder = null
        pending.clear()
        pendingRotation.clear()
        lastRotation = -1
        lastFedFrameId = -1
        fpsFrameCount = 0
        fpsWindowStartMs = 0L
    }
}
