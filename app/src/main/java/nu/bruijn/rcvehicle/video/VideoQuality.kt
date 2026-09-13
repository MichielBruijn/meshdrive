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
    LOW(320, 240, 300_000, "Low"),
    MEDIUM(640, 480, 800_000, "Medium"),
    HIGH(960, 720, 1_500_000, "High"),
    ;

    companion object {
        fun fromIndex(index: Int): VideoQuality = entries.getOrElse(index) { MEDIUM }
    }
}
