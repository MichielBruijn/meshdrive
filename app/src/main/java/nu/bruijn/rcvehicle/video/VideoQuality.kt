package nu.bruijn.rcvehicle.video

/**
 * Selectable video quality presets, all 4:3 like the original fixed 640x480 default
 * so ControllerDriveActivity's aspect-ratio math doesn't need to change per preset.
 * Sent controller -> vehicle as a single byte index (ControlFrame's TYPE_QUALITY) -
 * see VideoSender.setQuality() for how the vehicle applies a change live, and
 * ControllerDriveActivity's auto-quality loop for how a preset gets picked
 * automatically from decoded FPS when bandwidth is tight.
 *
 * [fps] used to be a single value fixed for the whole session (VideoSender's own
 * start() parameter, never varied by setQuality()) - now per-preset instead, so
 * ULOW can ask for less than the others without touching them.
 */
enum class VideoQuality(val width: Int, val height: Int, val bitrate: Int, val fps: Int, val label: String) {
    // Below LOW, not a smaller step on the same ladder - per direct request,
    // battery/data: 10fps and half the bitrate (vs. the others' 20fps/80kbps),
    // and VehicleForegroundService drops the vehicle's own ambient audio
    // entirely while this is selected (ControllerDriveActivity's Mute button/
    // mic talk-back are a separate, user-controlled channel, not touched by
    // this). SAME resolution as LOW, deliberately - confirmed live as a real
    // bug trying anything smaller: 256x192 (4:3, 16-aligned exactly like this
    // file's other entries) still got rejected outright by the Fairphone 5's
    // camera HAL ("capture session config failed"), same as 160x120 below
    // - this HAL apparently only accepts a fixed set of stream sizes, not just
    // "anything 16-aligned". Bitrate/fps are pure encoder rate-control knobs
    // with no HAL resolution-support risk at all (same reasoning LOW's own
    // bitrate-only cut below already relies on) - the only genuinely
    // HAL-safe way to go lower than LOW on this hardware.
    ULOW(320, 240, 40_000, 10, "Ultra Low"),
    // Was 320x240 @ 300kbps, then briefly 160x120 @ 100kbps - confirmed on real
    // hardware that 160x120 breaks video entirely (Medium/High still worked, Low
    // alone didn't - no capture-session error either, just silently no video).
    // 120 isn't a multiple of 16 (240/480/960 all are, and HIGH's own doc already
    // flags 16-alignment as the thing camera/encoder HALs are picky about here) -
    // almost certainly the same class of HAL quirk as HIGH's 960x720 issue below,
    // just failing silently instead of via onConfigureFailed this time. Resolution
    // back to the confirmed-working 320x240; bitrate alone (a pure encoder
    // rate-control knob, no HAL resolution-support risk at all) does the actual
    // size reduction instead - 80kbps is a hard cut from the original 300kbps.
    LOW(320, 240, 80_000, 20, "Low"),
    MEDIUM(640, 480, 800_000, 20, "Medium"),
    // Was 960x720 - confirmed on real hardware that this non-standard size fails to
    // even start on the Fairphone 5's camera (capture session configuration rejected
    // by the HAL entirely, not just lower quality - total loss of video). 1280x960 is
    // exactly 2x VGA in each dimension, a much more commonly-supported encoder target.
    HIGH(1280, 960, 1_500_000, 20, "High"),
    ;

    companion object {
        fun fromIndex(index: Int): VideoQuality = entries.getOrElse(index) { MEDIUM }
    }
}
