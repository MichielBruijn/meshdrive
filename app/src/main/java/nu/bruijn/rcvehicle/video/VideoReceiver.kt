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

    var onError: ((String) -> Unit)? = null

    /** Fires only when the vehicle's reported orientation actually changes. */
    var onRotationChanged: ((Int) -> Unit)? = null

    /** Fires roughly once/sec with the decoded-and-rendered frame rate, for a debug overlay. */
    var onFps: ((Float) -> Unit)? = null
    private var fpsFrameCount = 0
    private var fpsWindowStartMs = 0L

    fun start(port: Int, outputSurface: Surface, width: Int = 640, height: Int = 480) {
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
        fpsFrameCount = 0
        fpsWindowStartMs = 0L
    }
}
