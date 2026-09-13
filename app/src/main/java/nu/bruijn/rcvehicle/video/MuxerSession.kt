package nu.bruijn.rcvehicle.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.nio.ByteBuffer

/**
 * Wraps one MediaMuxer shared between VideoRecorder and AudioRecorderTrack so
 * neither of them - nor the Record button's start/stop, which runs on the UI
 * thread while both feed from their own background receive loops - ever calls
 * into the same MediaMuxer instance concurrently. Confirmed on real hardware as
 * the cause of a corrupted recording (SPS/PPS came out unparseable, every frame
 * "missing picture" in ffprobe) and, worse, the live decode surface going black
 * afterward too - a torn writeSampleData()/stop() race, not a stream problem.
 * Every caller goes through this single lock instead of touching the muxer
 * directly.
 *
 * MediaMuxer requires ALL tracks to be added before start() is called. Video's
 * track can only be added once a real keyframe carries real SPS/PPS; audio's
 * format is known upfront, so [addAudioTrack] can happen the moment recording
 * starts. [expectAudio] should only be set once audio packets are actually
 * observed arriving (see AudioReceiver.hasReceivedAudio) - otherwise, if the
 * vehicle's mic permission was denied, video would wait forever for an audio
 * track that will never come. Even then, [AUDIO_GRACE_MS] is a hard backstop:
 * if audio doesn't show up promptly, start video-only rather than record
 * nothing at all.
 */
class MuxerSession(private val muxer: MediaMuxer) {
    private val lock = Any()
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var videoTrackAddedAtMs = 0L

    /** Set before the first frame is fed - only true if audio was already flowing. */
    var expectAudio = false

    fun addVideoTrack(format: MediaFormat): Int = synchronized(lock) {
        videoTrack = muxer.addTrack(format)
        videoTrackAddedAtMs = SystemClock.elapsedRealtime()
        maybeStart()
        videoTrack
    }

    fun addAudioTrack(format: MediaFormat): Int = synchronized(lock) {
        if (started) return -1 // too late - video-only already started, see maybeStart()'s grace timeout
        audioTrack = muxer.addTrack(format)
        maybeStart()
        audioTrack
    }

    private fun maybeStart() {
        if (started || videoTrack < 0) return
        val audioReady = !expectAudio || audioTrack >= 0
        val graceExpired = SystemClock.elapsedRealtime() - videoTrackAddedAtMs > AUDIO_GRACE_MS
        if (audioReady || graceExpired) {
            muxer.start()
            started = true
        }
    }

    fun writeSample(trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (!started) maybeStart() // driven by whichever track is actively feeding right now
            if (!started || trackIndex < 0) return
            try {
                muxer.writeSampleData(trackIndex, buffer, info)
            } catch (_: Exception) {
                // best-effort, same as everywhere else here - a dropped sample beats a crash
            }
        }
    }

    /** True if muxer.start() was ever actually reached, i.e. something real is in the file. */
    fun stop(): Boolean = synchronized(lock) {
        val wasStarted = started
        try {
            if (started) muxer.stop()
        } catch (_: Exception) {
        }
        try {
            muxer.release()
        } catch (_: Exception) {
        }
        started = false
        wasStarted
    }

    private companion object {
        const val AUDIO_GRACE_MS = 1500L
    }
}
