package nu.bruijn.rcvehicle.control

/**
 * Vehicle side: drops control frames that arrive late. ServoLink's failsafe only reacts to
 * silence, but a TCP-peered mesh under bad coverage queues instead of drops: frames keep
 * arriving, seconds behind, and the vehicle executed them (field test abroad, 2026-11: "not
 * steerable anymore, drove straight into something").
 *
 * The phones' clocks aren't synced, so d = arrival - sent is clock offset + one-way delay. The
 * lowest d seen is the fastest path; a frame more than [MAX_EXTRA_DELAY_MS] above it is stale.
 * Not thread-safe: only ControlLink.listen()'s receive loop calls it.
 */
class ControlFreshness {
    private var floorMs = Long.MAX_VALUE
    private var floorSetAtMs = 0L
    private var lastSentAtMs = Long.MIN_VALUE
    private var lastFreshAtMs = 0L

    /** The last control frame was dropped for arriving late - reported back in every Pong. */
    var lagging = false
        private set

    /** New controller session (another phone, or the same one after a reboot). */
    fun reset() {
        floorMs = Long.MAX_VALUE
        lastSentAtMs = Long.MIN_VALUE
        lagging = false
    }

    /** True if this frame may drive. [sentAtMs] is the controller's elapsedRealtime(), [nowMs] the vehicle's. */
    fun accept(sentAtMs: Long, nowMs: Long, throttle: Float): Boolean {
        if (sentAtMs <= lastSentAtMs) return false // reordered or duplicate
        lastSentAtMs = sentAtMs
        val d = nowMs - sentAtMs
        // Creeps up 1 ms per 10 s, so clock drift between the phones (tens of ppm) never adds up to false staleness.
        val floor = if (floorMs == Long.MAX_VALUE) d else floorMs + (nowMs - floorSetAtMs) / 10_000
        val withinBound = d - floor <= MAX_EXTRA_DELAY_MS
        // Path got permanently slower (other peer/route): accept the new lag after a while, but only on a
        // neutral-throttle frame, so the car never pulls away on a held, laggy command.
        val resync = !withinBound && nowMs - lastFreshAtMs > RESYNC_AFTER_MS && throttle == 0f
        if (!withinBound && !resync) {
            lagging = true
            return false
        }
        if (d <= floor || resync) {
            floorMs = d
            floorSetAtMs = nowMs
        }
        lastFreshAtMs = nowMs
        lagging = false
        return true
    }

    companion object {
        // Same bar as ServoLink's silence failsafe: a command this much later than usual is as bad as none.
        const val MAX_EXTRA_DELAY_MS = 300L
        const val RESYNC_AFTER_MS = 3_000L
    }
}
