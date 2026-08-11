package com.beepbeep.defense

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class JoystickView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onMove: ((dx: Float, dz: Float) -> Unit)? = null

    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var knobX = 0f
    private var knobY = 0f

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 100, 180, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 100, 180, 255)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        textSize = 30f
        textAlign = Paint.Align.CENTER
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        centerX = w / 2f
        centerY = h / 2f
        radius = minOf(w, h) / 2f * 0.85f
        knobX = centerX
        knobY = centerY
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(centerX, centerY, radius, basePaint)
        canvas.drawCircle(centerX, centerY, radius, ringPaint)
        canvas.drawText("↑", centerX, centerY - radius * 0.55f, labelPaint)
        canvas.drawText("↓", centerX, centerY + radius * 0.65f, labelPaint)
        canvas.drawText("←", centerX - radius * 0.6f, centerY + 10f, labelPaint)
        canvas.drawText("→", centerX + radius * 0.6f, centerY + 10f, labelPaint)
        canvas.drawCircle(knobX, knobY, radius * 0.35f, knobPaint)
    }

    // ✅ 팀원 코드 추가: 게임패드 아날로그 스틱 연동
    fun setExternalInput(dx: Float, dz: Float) {
        knobX = centerX + dx * radius
        knobY = centerY - dz * radius
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val dx = event.x - centerX
                val dy = event.y - centerY
                val dist = sqrt(dx * dx + dy * dy)
                if (dist <= radius) {
                    knobX = event.x; knobY = event.y
                } else {
                    val angle = atan2(dy, dx)
                    knobX = centerX + cos(angle) * radius
                    knobY = centerY + sin(angle) * radius
                }
                onMove?.invoke(
                    (knobX - centerX) / radius,
                    -((knobY - centerY) / radius)
                )
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                knobX = centerX; knobY = centerY
                onMove?.invoke(0f, 0f)
            }
        }
        invalidate()
        return true
    }
}