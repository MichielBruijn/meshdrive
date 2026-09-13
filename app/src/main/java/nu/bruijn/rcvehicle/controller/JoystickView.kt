package nu.bruijn.rcvehicle.controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Self-centering 2-axis touch joystick (plan section 7) - hand-rolled canvas view
 * rather than a library, since the behavior is just "track touch within a circle,
 * clamp to its edge, snap back to center on release". Reports normalized x/y in
 * -1.0..1.0 via onMove; callers pick whichever axis they care about (the
 * controller screen uses x from the left stick for steering, y from the right
 * stick for throttle).
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onMove: ((x: Float, y: Float) -> Unit)? = null

    private var knobX = 0f
    private var knobY = 0f

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 255, 255, 255)
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = min(width, height) / 2f
        val knobRadius = baseRadius * 0.4f
        canvas.drawCircle(cx, cy, baseRadius, basePaint)
        canvas.drawCircle(
            cx + knobX * (baseRadius - knobRadius),
            cy + knobY * (baseRadius - knobRadius),
            knobRadius,
            knobPaint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val cx = width / 2f
                val cy = height / 2f
                val maxRadius = min(width, height) / 2f
                var dx = (event.x - cx) / maxRadius
                var dy = (event.y - cy) / maxRadius
                val dist = hypot(dx, dy)
                if (dist > 1f) {
                    dx /= dist
                    dy /= dist
                }
                knobX = dx
                knobY = dy
                invalidate()
                onMove?.invoke(knobX, knobY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                knobX = 0f
                knobY = 0f
                invalidate()
                onMove?.invoke(0f, 0f)
            }
        }
        return true
    }
}
