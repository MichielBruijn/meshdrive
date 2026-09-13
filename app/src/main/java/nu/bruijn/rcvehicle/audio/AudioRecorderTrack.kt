package nu.bruijn.rcvehicle.audio

import android.media.MediaCodec
import android.media.MediaFormat
import nu.bruijn.rcvehicle.video.MuxerSession
import java.nio.ByteBuffer

/**
 * Remuxes the raw AAC-LC access units AudioReceiver is already receiving straight
 * into the recording's audio track - no re-encoding, same reasoning and same
 * shared-MuxerSession approach as VideoRecorder (see its doc). Unlike H.264, one
 * AAC access unit needs no NAL splitting/AVCC conversion - MediaMuxer takes it
 * as-is, one writeSampleData() call per frame.
 */
class AudioRecorderTrack {
    private var session: MuxerSession? = null
    private var audioTrackIndex = -1
    private var started = false
    private var startTimeUs = -1L

    fun start(session: MuxerSession) {
        this.session = session
        audioTrackIndex = -1
        started = false
        startTimeUs = -1L
    }

    fun feed(data: ByteArray, sampleRateIndex: Int, channelConfig: Int) {
        val s = session ?: return
        if (!started) {
            val sampleRate = AudioPacket.sampleRateFor(sampleRateIndex)
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelConfig).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(AudioPacket.buildAacLcConfig(sampleRateIndex, channelConfig)))
            }
            audioTrackIndex = s.addAudioTrack(format)
            started = true
            startTimeUs = System.nanoTime() / 1000
        }
        if (audioTrackIndex < 0) return // added too late - MuxerSession already started video-only
        val buffer = ByteBuffer.wrap(data)
        val info = MediaCodec.BufferInfo().apply {
            set(0, data.size, System.nanoTime() / 1000 - startTimeUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
        }
        s.writeSample(audioTrackIndex, buffer, info)
    }

    /** True if this track ever got as far as being added. */
    fun stop(): Boolean {
        val wasStarted = started
        session = null
        started = false
        return wasStarted
    }
}
