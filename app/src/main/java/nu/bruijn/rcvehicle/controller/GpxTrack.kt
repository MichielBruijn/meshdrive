package nu.bruijn.rcvehicle.controller

import nu.bruijn.rcvehicle.control.ControlFrame
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Accumulates GPS trackpoints for the duration of one recording, written out as a
 * standard .gpx sidecar file alongside the .mp4 (ControllerDriveActivity) - per
 * direct request, a much cheaper way to keep the vehicle's whole route/speed with
 * a recording than actually rendering the GPS/speed HUD into the video's pixels,
 * which would need a full decode/draw/re-encode pipeline in place of the current
 * direct remux (declined, see updateTelemetryOverlay()'s doc). Only ever gets
 * points while GPS/speed telemetry is actually flowing (ControlFrame.TelemetryEnable) -
 * nothing to track otherwise, same one-shot-fix limitation MuxerSession's
 * [location] geotag already has, just accumulated over the whole recording instead
 * of a single point.
 */
class GpxTrack(private val name: String) {
    private data class Point(val lat: Double, val lon: Double, val speedKmh: Float, val atMs: Long)

    private val points = mutableListOf<Point>()

    fun addPoint(telemetry: ControlFrame.Decoded.Telemetry) {
        if (!telemetry.hasFix) return
        points.add(Point(telemetry.lat, telemetry.lon, telemetry.speedKmh, System.currentTimeMillis()))
    }

    val isEmpty: Boolean get() = points.isEmpty()

    fun toGpxXml(): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"MeshDrive\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk>\n    <name>${escape(name)}</name>\n    <trkseg>\n")
        for (p in points) {
            sb.append("      <trkpt lat=\"${p.lat}\" lon=\"${p.lon}\">\n")
            sb.append("        <time>${iso.format(Date(p.atMs))}</time>\n")
            // GPX's <speed> convention is meters/second, not km/h.
            sb.append("        <speed>${String.format(Locale.US, "%.2f", p.speedKmh / 3.6f)}</speed>\n")
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
