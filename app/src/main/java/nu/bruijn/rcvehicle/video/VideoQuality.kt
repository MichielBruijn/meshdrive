package nu.bruijn.rcvehicle.video

/**
 * Selectable video quality presets, all 4:3 like the original fixed 640x480 default
 * so ControllerDriveActivity's aspect-ratio math doesn't need to change per preset.
 * Sent controller -> vehicle as a single byte index (ControlFrame's TYPE_QUALITY) -
 * see VideoSender.setQuality() for how the vehicle applies a change live, and
 * ControllerDriveActivity's auto-quality loop for how a preset gets picked
 * automatically from decoded FPS when bandwidth is tight.
 */
enum class VideoQuality(val width: Int, val height: Int, val bitrate: Int, val label: String) {
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
    LOW(320, 240, 80_000, "Low"),
    MEDIUM(640, 480, 800_000, "Medium"),
    // Was 960x720 - confirmed on real hardware that this non-standard size fails to
    // even start on the Fairphone 5's camera (capture session configuration rejected
    // by the HAL entirely, not just lower quality - total loss of video). 1280x960 is
    // exactly 2x VGA in each dimension, a much more commonly-supported encoder target.
    HIGH(1280, 960, 1_500_000, "High"),
    ;

    companion object {
        fun fromIndex(index: Int): VideoQuality = entries.getOrElse(index) { MEDIUM }
    }
}
