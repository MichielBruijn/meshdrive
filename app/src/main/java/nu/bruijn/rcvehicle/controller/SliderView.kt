package nu.bruijn.rcvehicle.controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Self-centering single-axis slider - replaces the original 2-axis JoystickView
 * per direct request: steering is left/right only, throttle is forward/backward
 * only, so a circular omnidirectional stick was misleading (the unused axis could
 * still be dragged). [vertical] picks the axis: true for a throttle-style up/down
 * bar, false for a steering-style left/right bar. Reports a single normalized
 * value in -1.0..1.0 via onMove (screen down/right = positive; callers negate if
 * "up" should mean positive, as the throttle slider does).
 */
class SliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var vertical: Boolean = true
    var onMove: ((value: Float) -> Unit)? = null

    private var knobPos = 0f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 255, 255, 255)
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val thickness = (if (vertical) w else h) * 0.6f
        val track = if (vertical) {
            RectF(w / 2f - thickness / 2f, 0f, w / 2f + thickness / 2f, h)
        } else {
            RectF(0f, h / 2f - thickness / 2f, w, h / 2f + thickness / 2f)
        }
        canvas.drawRoundRect(track, thickness / 2f, thickness / 2f, trackPaint)

        val knobRadius = thickness * 0.7f
        val travel = (if (vertical) h else w) / 2f - knobRadius
        val cx = if (vertical) w / 2f else w / 2f + knobPos * travel
        val cy = if (vertical) h / 2f + knobPos * travel else h / 2f
        canvas.drawCircle(cx, cy, knobRadius, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val pos = if (vertical) {
                    (event.y - height / 2f) / (height / 2f)
                } else {
                    (event.x - width / 2f) / (width / 2f)
                }
                knobPos = pos.coerceIn(-1f, 1f)
                invalidate()
                onMove?.invoke(knobPos)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                knobPos = 0f
                invalidate()
                onMove?.invoke(0f)
            }
        }
        return true
    }
}
