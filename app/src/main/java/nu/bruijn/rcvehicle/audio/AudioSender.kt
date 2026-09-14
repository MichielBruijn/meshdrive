package nu.bruijn.rcvehicle.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Captures a mic, encodes it AAC-LC with the hardware/software codec MediaCodec
 * picks, and sends it over UDP - same transport philosophy as VideoSender (best-
 * effort UDP over Yggdrasil, no fallback signaling server). Used in both
 * directions of the controller<->vehicle voice link, each with its own instance,
 * [audioSource], and AudioPacket port (VehicleActivity's ambient/engine-noise mic
 * feed uses CAMCORDER since it should sound like whatever the camera's pointed
 * at; ControllerDriveActivity's talk-back mic uses VOICE_COMMUNICATION, Android's
 * source for real-time two-way voice, which gets echo cancellation/noise
 * suppression on hardware that supports it).
 *
 * Fixed mono/16kHz/32kbps: plenty for engine noise and spoken warnings, and small
 * enough to not compete meaningfully with the video stream's bandwidth budget on a
 * cellular uplink, which is the tighter constraint here (VideoSender's doc).
 *
 * start()/stop() are repeatable (mute toggling stops/restarts capture rather than
 * just dropping packets - see ControllerDriveActivity's Mute button doc for why) -
 * the encoder's callback HandlerThread is recreated each start() rather than
 * reused, since a HandlerThread that's been quitSafely()'d can't take a new
 * Looper-bound callback afterward.
 */
class AudioSender(private val audioSource: Int = MediaRecorder.AudioSource.MIC) {
    private var audioRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var socket: DatagramSocket? = null
    private var remoteAddress: InetAddress? = null
    private var remotePort: Int = 0
    private var seq = 0
    private var audioThread: HandlerThread? = null

    var onError: ((String) -> Unit)? = null

    fun start(remoteHost: String, remotePort: Int) {
        this.remotePort = remotePort
        remoteAddress = InetAddress.getByName(remoteHost)
        socket = DatagramSocket()
        seq = 0

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBufSize <= 0) {
            onError?.invoke("AudioRecord: sample rate not supported")
            return
        }
        val record = try {
            AudioRecord(audioSource, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize * 2)
        } catch (e: SecurityException) {
            onError?.invoke("RECORD_AUDIO permission not granted")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            onError?.invoke("AudioRecord failed to initialize")
            record.release()
            return
        }
        audioRecord = record

        val thread = HandlerThread("AudioSenderCodec").apply { start() }
        audioThread = thread
        val audioHandler = Handler(thread.looper)

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                val inputBuffer = codec.getInputBuffer(index) ?: return
                val read = audioRecord?.read(inputBuffer, inputBuffer.capacity()) ?: -1
                if (read > 0) {
                    codec.queueInputBuffer(index, 0, read, System.nanoTime() / 1000, 0)
                } else {
                    codec.queueInputBuffer(index, 0, 0, 0, 0)
                }
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    val data = ByteArray(info.size)
                    buffer.get(data)
                    sendFrame(data)
                }
                codec.releaseOutputBuffer(index, false)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e("AudioSender", "encoder error", e)
                onError?.invoke("audio encoder error: ${e.message}")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
        }, audioHandler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        encoder = codec

        record.startRecording()
    }

    private fun sendFrame(data: ByteArray) {
        val sock = socket ?: return
        val addr = remoteAddress ?: return
        val packet = AudioPacket.encode(seq++, SAMPLE_RATE_INDEX, CHANNEL_CONFIG, data)
        try {
            sock.send(DatagramPacket(packet, packet.size, addr, remotePort))
        } catch (_: Exception) {
            // best-effort, same as VideoSender - a lost audio packet is a brief gap, not fatal
        }
    }

    fun stop() {
        audioRecord?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        audioRecord = null
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
        audioThread?.quitSafely()
        audioThread = null
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = 1 // mono, MPEG-4 channelConfiguration value
        const val BIT_RATE = 32_000
        val SAMPLE_RATE_INDEX = AudioPacket.sampleRateIndexFor(SAMPLE_RATE)
    }
}
