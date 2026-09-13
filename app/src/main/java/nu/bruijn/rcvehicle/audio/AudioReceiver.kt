package nu.bruijn.rcvehicle.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer

/**
 * Receives AudioSender's UDP packets, decodes them for live playback through
 * AudioTrack, and (while a recording is active) hands the same raw AAC access
 * units to AudioRecorderTrack for muxing - same "no video, no audio" -> "no
 * decode/record" symmetry, and same drop-late-packets-instead-of-corrupting-state
 * policy, as VideoReceiver.
 */
class AudioReceiver {
    private var socket: DatagramSocket? = null
    private var decoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var receiveJob: Job? = null
    private var lastFedSeq = -1
    private var decoderSampleRateIndex = -1

    var onError: ((String) -> Unit)? = null

    /** Set true the moment the first packet arrives - lets ControllerDriveActivity
     * know audio is actually flowing before deciding to gate a recording's video
     * track on it (MuxerSession.expectAudio) - see that class's doc for why. */
    @Volatile var hasReceivedAudio = false
        private set

    /** Silences live playback only - decoding and (if a recording is active) the
     * recorded track are unaffected, same as muting a phone call doesn't stop it. */
    @Volatile var muted = false

    private var recorder: AudioRecorderTrack? = null

    fun startRecording(session: nu.bruijn.rcvehicle.video.MuxerSession) {
        val r = AudioRecorderTrack()
        r.start(session)
        recorder = r
    }

    /** True if anything was actually recorded. */
    fun stopRecording(): Boolean = recorder?.stop().also { recorder = null } ?: false

    fun start(port: Int) {
        socket = DatagramSocket(port)

        val minBufSize = AudioTrack.getMinBufferSize(AudioSender.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AudioSender.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBufSize, 4096) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()

        val sock = socket!!
        receiveJob = scope.launch {
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)
            while (isActive) {
                try {
                    sock.receive(packet)
                    handlePacket(packet.data.copyOf(packet.length))
                } catch (e: Exception) {
                    if (!isActive) break
                    onError?.invoke("audio receive error: ${e.message}")
                }
            }
        }
    }

    private fun handlePacket(data: ByteArray) {
        val header = AudioPacket.parseHeader(data, data.size) ?: return
        if (header.seq <= lastFedSeq) return // reordered/duplicate - see class doc
        lastFedSeq = header.seq
        hasReceivedAudio = true
        val payload = data.copyOfRange(AudioPacket.PAYLOAD_OFFSET, data.size)

        ensureDecoder(header.sampleRateIndex, header.channelConfig)
        feedDecoder(payload)
        recorder?.feed(payload, header.sampleRateIndex, header.channelConfig)
    }

    private fun ensureDecoder(sampleRateIndex: Int, channelConfig: Int) {
        if (decoder != null && decoderSampleRateIndex == sampleRateIndex) return
        decoder?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        decoderSampleRateIndex = sampleRateIndex
        val sampleRate = AudioPacket.sampleRateFor(sampleRateIndex)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelConfig).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(AudioPacket.buildAacLcConfig(sampleRateIndex, channelConfig)))
        }
        try {
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, 0)
            codec.start()
            decoder = codec
        } catch (e: Exception) {
            onError?.invoke("audio decoder init failed: ${e.message}")
            decoder = null
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
                val outputBuffer = codec.getOutputBuffer(outIndex)
                if (outputBuffer != null && info.size > 0 && !muted) {
                    val pcm = ByteArray(info.size)
                    outputBuffer.get(pcm)
                    audioTrack?.write(pcm, 0, pcm.size)
                }
                codec.releaseOutputBuffer(outIndex, false)
                outIndex = codec.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            Log.e("AudioReceiver", "decode error", e)
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
        decoderSampleRateIndex = -1
        audioTrack?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        audioTrack = null
        lastFedSeq = -1
        hasReceivedAudio = false
    }
}
