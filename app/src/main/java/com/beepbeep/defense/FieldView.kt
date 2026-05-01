package com.beepbeep.defense

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.beepbeep.defense.game.BallPosition
import kotlin.math.*

class FieldView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var ballPos = BallPosition(0f, 0f, 0f, 0f)
    var defenderX = 0f
    var defenderZ = 25f
    var isBallFlying = false
    var headingDeg: Float = 0f

    private val trail = ArrayDeque<Pair<Float, Float>>()

    private val fieldPaint = Paint().apply { color = Color.argb(255, 30, 80, 30); style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
    }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 80, 160, 255); style = Paint.Style.FILL }
    private val defPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 50, 220, 100); style = Paint.Style.FILL }
    private val catchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(50, 50, 220, 100); style = Paint.Style.FILL }
    private val catchRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 50, 220, 100); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 200, 200, 200); textSize = 22f; textAlign = Paint.Align.CENTER
    }
    private val fovFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 255, 220, 50); style = Paint.Style.FILL
    }
    private val fovLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 220, 50); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val fovCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 220, 50); style = Paint.Style.STROKE; strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(12f, 6f), 0f)
    }

    private val xRange = -40f..40f
    private val zRange = 0f..55f

    private fun sx(x: Float) = (x - xRange.start) / (xRange.endInclusive - xRange.start) * width
    private fun sy(z: Float) = (z - zRange.start) / (zRange.endInclusive - zRange.start) * height

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fieldPaint)

        canvas.drawLine(0f, sy(12f), width.toFloat(), sy(12f), linePaint)
        canvas.drawText("40ft 파울라인", width / 2f, sy(12f) - 6f, textPaint)
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), linePaint)

        if (isBallFlying) {
            trail.add(Pair(ballPos.x, ballPos.z))
            if (trail.size > 20) trail.removeFirst()
        }
        trail.forEachIndexed { i, (x, z) ->
            ballPaint.alpha = (i.toFloat() / trail.size * 120).toInt()
            canvas.drawCircle(sx(x), sy(z), 5f, ballPaint)
        }
        ballPaint.alpha = 230

        drawFov(canvas)

        val catchPx = 3f / (xRange.endInclusive - xRange.start) * width
        canvas.drawCircle(sx(defenderX), sy(defenderZ), catchPx, catchPaint)
        canvas.drawCircle(sx(defenderX), sy(defenderZ), catchPx, catchRingPaint)
        canvas.drawCircle(sx(defenderX), sy(defenderZ), 18f, defPaint)
        canvas.drawText("🧤", sx(defenderX), sy(defenderZ) + 8f, textPaint)

        if (ballPos.progress > 0f) {
            val r = 10f + ballPos.y * 1.5f
            canvas.drawCircle(sx(ballPos.x), sy(ballPos.z) + ballPos.y * 3f, r, ballPaint)
        }
    }

    private fun drawFov(canvas: Canvas) {
        val cx = sx(defenderX)
        val cy = sy(defenderZ)
        val fovLength = 120f
        val fovAngle = 30f

        // -headingDeg - 90f: 정면(0°) → 위쪽(투수 방향), 오른쪽 회전 → FOV가 오른쪽 이동
        val baseDeg = -headingDeg - 90f
        val baseRad = Math.toRadians(baseDeg.toDouble())
        val leftRad = Math.toRadians((baseDeg - fovAngle).toDouble())
        val rightRad = Math.toRadians((baseDeg + fovAngle).toDouble())

        val centerX = (cx + fovLength * cos(baseRad)).toFloat()
        val centerY = (cy + fovLength * sin(baseRad)).toFloat()
        val leftX = (cx + fovLength * cos(leftRad)).toFloat()
        val leftY = (cy + fovLength * sin(leftRad)).toFloat()
        val rightX = (cx + fovLength * cos(rightRad)).toFloat()
        val rightY = (cy + fovLength * sin(rightRad)).toFloat()

        val path = Path().apply {
            moveTo(cx, cy)
            lineTo(leftX, leftY)
            val rect = RectF(cx - fovLength, cy - fovLength, cx + fovLength, cy + fovLength)
            arcTo(rect, baseDeg - fovAngle, fovAngle * 2, false)
            lineTo(cx, cy)
            close()
        }
        canvas.drawPath(path, fovFillPaint)
        canvas.drawLine(cx, cy, leftX, leftY, fovLinePaint)
        canvas.drawLine(cx, cy, rightX, rightY, fovLinePaint)
        canvas.drawLine(cx, cy, centerX, centerY, fovCenterPaint)
    }

    fun update(ball: BallPosition, defX: Float, defZ: Float, isFlying: Boolean) {
        ballPos = ball
        defenderX = defX
        defenderZ = defZ
        isBallFlying = isFlying
        if (!isFlying) trail.clear()
        invalidate()
    }

    fun updateHeading(deg: Float) {
        headingDeg = deg
        invalidate()
    }
}