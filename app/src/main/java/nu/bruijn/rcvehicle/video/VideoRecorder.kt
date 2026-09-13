package nu.bruijn.rcvehicle.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

/**
 * Remuxes the raw H.264 access units VideoReceiver is already receiving straight
 * into an .mp4 file - no re-encoding needed (the decoder renders straight to a
 * Surface with no accessible intermediate buffer to re-encode from anyway, and
 * re-encoding a re-decoded stream would waste quality and battery for nothing).
 *
 * The stream itself is Annex-B (start-code-delimited NAL units, as VideoSender's
 * hardware encoder produces it), but MediaMuxer.writeSampleData() expects AVCC
 * (4-byte big-endian length prefixes instead) - this does that conversion. SPS/PPS
 * (VideoSender's KEY_PREPEND_HEADER_TO_SYNC_FRAMES guarantees they precede every
 * keyframe) become the track's codec-specific data instead of being written as
 * samples, since addTrack()/start() must happen before the first writeSampleData()
 * call - recording effectively starts at the first keyframe seen after start().
 */
class VideoRecorder {
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var started = false
    private var startTimeUs = -1L
    private var spsBytes: ByteArray? = null
    private var ppsBytes: ByteArray? = null

    var onError: ((String) -> Unit)? = null

    /** Takes an already-constructed MediaMuxer rather than a path - lets the caller
     * decide how the output is actually created (a plain file, or a MediaStore
     * entry's FileDescriptor so a recording shows up in the gallery like any other
     * video - see ControllerDriveActivity's setUpRecordButton()), without this class
     * needing to know anything about scoped storage/permissions. */
    fun start(muxer: MediaMuxer) {
        this.muxer = muxer
        videoTrackIndex = -1
        started = false
        startTimeUs = -1L
        spsBytes = null
        ppsBytes = null
    }

    /** [data] is one complete, reassembled access unit - the same thing
     * VideoReceiver.feedDecoder() gets, called from the same place. */
    fun feed(data: ByteArray, width: Int, height: Int) {
        val m = muxer ?: return
        var isKeyFrame = false
        val samples = mutableListOf<ByteArray>()

        for (nal in splitNalUnits(data)) {
            if (nal.isEmpty()) continue
            when (nal[0].toInt() and 0x1F) {
                7 -> spsBytes = nal // SPS
                8 -> ppsBytes = nal // PPS
                5 -> {
                    isKeyFrame = true
                    samples.add(nal)
                } // IDR slice
                else -> samples.add(nal)
            }
        }

        if (!started) {
            val sps = spsBytes ?: return // wait for a keyframe - it carries SPS/PPS with it
            val pps = ppsBytes ?: return
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(sps)))
                setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(pps)))
            }
            try {
                videoTrackIndex = m.addTrack(format)
                m.start()
            } catch (e: Exception) {
                onError?.invoke("could not start recording: ${e.message}")
                return
            }
            started = true
            startTimeUs = System.nanoTime() / 1000
        }

        if (samples.isEmpty()) return
        val presentationTimeUs = System.nanoTime() / 1000 - startTimeUs
        for (sample in samples) {
            val buffer = ByteBuffer.allocate(4 + sample.size)
            buffer.putInt(sample.size) // AVCC: big-endian NAL length prefix, not a start code
            buffer.put(sample)
            buffer.flip()
            val info = MediaCodec.BufferInfo().apply {
                set(0, buffer.remaining(), presentationTimeUs, if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            }
            try {
                m.writeSampleData(videoTrackIndex, buffer, info)
            } catch (e: Exception) {
                onError?.invoke("write failed: ${e.message}")
            }
        }
    }

    /** True if anything was actually written (a keyframe never arrived otherwise). */
    fun stop(): Boolean {
        val wasStarted = started
        try {
            if (started) muxer?.stop()
        } catch (_: Exception) {
        }
        try {
            muxer?.release()
        } catch (_: Exception) {
        }
        muxer = null
        started = false
        return wasStarted
    }

    private fun withStartCode(nal: ByteArray): ByteArray {
        val result = ByteArray(4 + nal.size)
        result[3] = 1
        nal.copyInto(result, 4)
        return result
    }

    /** Splits an Annex-B buffer into individual NAL units (type byte + payload, start
     * code stripped). Matches the 3-byte "00 00 01" pattern only - a leading extra zero
     * from a 4-byte start code just ends up as one harmless trailing zero byte on the
     * previous NAL, which encoders/decoders already routinely pad with anyway. */
    private fun splitNalUnits(data: ByteArray): List<ByteArray> {
        val startCodePositions = mutableListOf<Int>()
        val nalStarts = mutableListOf<Int>()
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                startCodePositions.add(i)
                nalStarts.add(i + 3)
                i += 3
            } else {
                i++
            }
        }
        return nalStarts.mapIndexed { idx, start ->
            val end = if (idx + 1 < startCodePositions.size) startCodePositions[idx + 1] else data.size
            if (end > start) data.copyOfRange(start, end) else ByteArray(0)
        }
    }
}
