package com.beepbeep.defense

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class GrowthChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class ChartPoint(
        val label: String,
        val value: Float,
        val isPartial: Boolean = false
    )

    private val dataLines  = mutableListOf<List<ChartPoint>>()
    private val lineColors = mutableListOf<Int>()
    private val lineLabels = mutableListOf<String>()
    var onPointClick: ((lineIndex: Int, pointIndex: Int) -> Unit)? = null

    private val linePaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 14f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dashPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f; pathEffect = DashPathEffect(floatArrayOf(16f, 10f), 0f) }
    private val dotPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.WHITE }
    private val partialRing  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val textPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCCCCCC.toInt(); textSize = 36f; textAlign = Paint.Align.CENTER }
    private val valuePaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 48f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val gridPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2A2A.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f }
    private val axisPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF555555.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val legendPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 32f }
    private val bgPaint      = Paint().apply { color = 0xFF111111.toInt() }
    private val hintPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF555555.toInt(); textSize = 26f; textAlign = Paint.Align.CENTER }
    private val noDataPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF888888.toInt(); textSize = 48f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    private var selectedLine  = -1
    private var selectedPoint = -1

    private var touchStartX = 0f
    private var touchStartY = 0f
    private val SWIPE_THRESHOLD = 40f

    private val padL = 50f
    private val padR = 50f
    private val padT = 80f
    private val padB = 140f

    private val NAN_Y_FRAC = 0.95f

    fun setData(lines: List<List<ChartPoint>>, colors: List<Int>, labels: List<String>) {
        dataLines.clear(); dataLines.addAll(lines)
        lineColors.clear(); lineColors.addAll(colors)
        lineLabels.clear(); lineLabels.addAll(labels)
        selectedLine = -1; selectedPoint = -1
        invalidate()

        isFocusable = true
        isClickable = true
        setOnClickListener {
            if (dataLines.isEmpty()) return@setOnClickListener
            val pointCount = dataLines.firstOrNull()?.size ?: return@setOnClickListener
            if (pointCount == 0) return@setOnClickListener

            if (selectedPoint < 0) {
                selectedLine  = 0
                selectedPoint = 0
            } else {
                val nextPoint = (selectedPoint + 1) % pointCount
                if (nextPoint == 0) {
                    val nextLine = (selectedLine + 1) % dataLines.size
                    selectedLine  = nextLine
                    selectedPoint = 0
                } else {
                    selectedPoint = nextPoint
                }
            }
            invalidate()
            onPointClick?.invoke(selectedLine, selectedPoint)
        }
    }

    private fun getYPos(value: Float, chartH: Float, yMin: Float, yRange: Float): Float {
        return if (value.isNaN()) {
            padT + chartH * NAN_Y_FRAC
        } else {
            val yFrac = (value - yMin) / yRange
            padT + chartH * (1f - yFrac)
        }
    }

    private fun textAlignFor(i: Int, size: Int): Paint.Align = when (i) {
        0        -> Paint.Align.LEFT
        size - 1 -> Paint.Align.RIGHT
        else     -> Paint.Align.CENTER
    }

    // 다른 선들과 겹치지 않도록 텍스트 y 위치 동적 계산
    private fun getTextY(
        yPos: Float,
        li: Int,
        i: Int,
        chartH: Float,
        yMin: Float,
        yRange: Float
    ): Float {
        val aboveY = yPos - 65f
        val belowY = yPos + 80f

        // 다른 선들의 같은 x 위치 y값 수집
        val otherYPositions = dataLines.indices
            .filter { it != li }
            .mapNotNull { otherLi ->
                val v = dataLines[otherLi].getOrNull(i)?.value ?: return@mapNotNull null
                getYPos(v, chartH, yMin, yRange)
            }

        // 위쪽에 다른 선이나 텍스트와 60px 이상 거리 있으면 위에 배치
        val aboveClear = otherYPositions.none { kotlin.math.abs(it - aboveY) < 60f }
        // 아래쪽도 확인
        val belowClear = otherYPositions.none { kotlin.math.abs(it - belowY) < 60f }

        return when {
            aboveClear -> aboveY
            belowClear -> belowY
            // 둘 다 겹치면 점에서 더 멀리
            else -> if (li % 2 == 0) aboveY - 40f else belowY + 40f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataLines.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val chartW = w - padL - padR
        val chartH = h - padT - padB

        val allValues = dataLines.flatten().map { it.value }.filter { !it.isNaN() }
        if (allValues.isEmpty()) return
        val minV  = allValues.min()
        val maxV  = allValues.max()
        val range = if (maxV - minV < 0.001f) 1f else maxV - minV
        val yMin  = minV - range * 0.15f
        val yMax  = maxV + range * 0.15f
        val yRange= yMax - yMin

        val xLabels    = dataLines.firstOrNull()?.map { it.label } ?: return
        val pointCount = xLabels.size
        if (pointCount < 1) return
        val xStep = if (pointCount > 1) chartW / (pointCount - 1) else chartW / 2

        // 그리드
        for (i in 0..3) {
            val yFrac = i / 3f
            val yPos  = padT + chartH * (1f - yFrac)
            canvas.drawLine(padL, yPos, padL + chartW, yPos, gridPaint)
        }

        // 축
        canvas.drawLine(padL, padT, padL, padT + chartH, axisPaint)
        canvas.drawLine(padL, padT + chartH, padL + chartW, padT + chartH, axisPaint)

        // x축 레이블 (줄바꿈 처리)
        for (i in xLabels.indices) {
            val xPos = padL + (if (pointCount > 1) i * xStep else chartW / 2)
            textPaint.textAlign = textAlignFor(i, xLabels.size)
            val lines = xLabels[i].split("\n")
            lines.forEachIndexed { lineIdx, line ->
                canvas.drawText(line, xPos, padT + chartH + 65f + lineIdx * 40f, textPaint)
            }
        }
        textPaint.textAlign = Paint.Align.CENTER

        // 각 선
        for (li in dataLines.indices) {
            val pts   = dataLines[li]
            val color = lineColors.getOrElse(li) { Color.WHITE }
            linePaint.color = color
            dotPaint.color  = color
            dashPaint.color = color

            // 선 그리기
            for (i in 1 until pts.size) {
                val x1 = padL + (if (pointCount > 1) (i-1) * xStep else chartW / 2)
                val x2 = padL + (if (pointCount > 1) i * xStep else chartW / 2)
                val y1 = getYPos(pts[i-1].value, chartH, yMin, yRange)
                val y2 = getYPos(pts[i].value, chartH, yMin, yRange)

                val isNanSegment = pts[i].value.isNaN() || pts[i-1].value.isNaN()
                val isPartialSeg = pts[i].isPartial || pts[i-1].isPartial

                if (isNanSegment || isPartialSeg) {
                    val dp = Path(); dp.moveTo(x1, y1); dp.lineTo(x2, y2)
                    canvas.drawPath(dp, dashPaint)
                } else {
                    val sp = Path(); sp.moveTo(x1, y1); sp.lineTo(x2, y2)
                    canvas.drawPath(sp, linePaint)
                }
            }

            // 점 그리기
            for (i in pts.indices) {
                val xPos = padL + (if (pointCount > 1) i * xStep else chartW / 2)
                val yPos = getYPos(pts[i].value, chartH, yMin, yRange)
                val isSelected = li == selectedLine && i == selectedPoint
                val radius = if (isSelected) 44f else 32f
                val isNan = pts[i].value.isNaN()
                val textY = getTextY(yPos, li, i, chartH, yMin, yRange)

                when {
                    isNan && pts[i].isPartial -> {
                        dotPaint.color = 0xFF444444.toInt()
                        canvas.drawCircle(xPos, yPos, radius, dotPaint)
                        partialRing.color = color
                        canvas.drawCircle(xPos, yPos, radius, partialRing)
                        if (isSelected) {
                            valuePaint.color = color
                            valuePaint.textAlign = textAlignFor(i, pts.size)
                            canvas.drawText("진행중", xPos, textY, valuePaint)
                            valuePaint.textAlign = Paint.Align.CENTER
                        }
                    }
                    isNan -> {
                        dotPaint.color = 0xFF333333.toInt()
                        canvas.drawCircle(xPos, yPos, radius, dotPaint)
                        dotRingPaint.color = 0xFF666666.toInt()
                        canvas.drawCircle(xPos, yPos, radius, dotRingPaint)
                        if (isSelected) {
                            noDataPaint.color = 0xFF888888.toInt()
                            noDataPaint.textAlign = textAlignFor(i, pts.size)
                            canvas.drawText("기록없음", xPos, textY, noDataPaint)
                            noDataPaint.textAlign = Paint.Align.CENTER
                        }
                    }
                    pts[i].isPartial -> {
                        dotPaint.color = 0xFF444444.toInt()
                        canvas.drawCircle(xPos, yPos, radius, dotPaint)
                        partialRing.color = color
                        canvas.drawCircle(xPos, yPos, radius, partialRing)
                        if (isSelected) {
                            valuePaint.color = color
                            valuePaint.textAlign = textAlignFor(i, pts.size)
                            canvas.drawText("진행중", xPos, textY, valuePaint)
                            valuePaint.textAlign = Paint.Align.CENTER
                        }
                    }
                    else -> {
                        dotPaint.color = color
                        canvas.drawCircle(xPos, yPos, radius, dotPaint)
                        dotRingPaint.color = Color.WHITE
                        canvas.drawCircle(xPos, yPos, radius, dotRingPaint)
                        if (isSelected) {
                            val valStr = if (pts[i].value >= 100f) "%.0f".format(pts[i].value)
                            else "%.2f".format(pts[i].value)
                            valuePaint.color = color
                            valuePaint.textAlign = textAlignFor(i, pts.size)
                            canvas.drawText(valStr, xPos, textY, valuePaint)
                            valuePaint.textAlign = Paint.Align.CENTER
                        }
                    }
                }
            }
        }

        // 범례
        var legendX = padL
        for (li in lineLabels.indices) {
            legendPaint.color = lineColors.getOrElse(li) { Color.WHITE }
            legendPaint.style = Paint.Style.FILL
            canvas.drawRect(legendX, 18f, legendX + 30f, 44f, legendPaint)
            legendPaint.color = 0xFFDDDDDD.toInt()
            canvas.drawText(lineLabels[li], legendX + 36f, 44f, legendPaint)
            legendX += legendPaint.measureText(lineLabels[li]) + 60f
        }

        // 스와이프 힌트
        if (selectedPoint >= 0) {
            val pointCount2 = dataLines.firstOrNull()?.size ?: 0
            val hint = when {
                selectedPoint == 0 && pointCount2 > 1               -> "→ 밀어서 다음"
                selectedPoint == pointCount2 - 1 && pointCount2 > 1 -> "← 밀어서 이전"
                pointCount2 > 1                                      -> "← → 밀어서 이동"
                else -> ""
            }
            if (hint.isNotEmpty()) canvas.drawText(hint, w / 2f, h - 8f, hintPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY

                if (kotlin.math.abs(dx) >= SWIPE_THRESHOLD &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                    if (selectedPoint >= 0 && dataLines.isNotEmpty()) {
                        val pointCount = dataLines.firstOrNull()?.size ?: return true
                        val newPoint = if (dx < 0) {
                            (selectedPoint + 1).coerceAtMost(pointCount - 1)
                        } else {
                            (selectedPoint - 1).coerceAtLeast(0)
                        }
                        if (newPoint != selectedPoint) {
                            selectedPoint = newPoint
                            invalidate()
                            onPointClick?.invoke(selectedLine, selectedPoint)
                        }
                    }
                    return true
                }

                if (dataLines.isEmpty()) return true

                val chartW     = width.toFloat() - padL - padR
                val chartH     = height.toFloat() - padT - padB
                val pointCount = dataLines.firstOrNull()?.size ?: return true
                val xStep      = if (pointCount > 1) chartW / (pointCount - 1) else chartW / 2
                val tx = touchStartX; val ty = touchStartY

                val allValues = dataLines.flatten().map { it.value }.filter { !it.isNaN() }
                if (allValues.isEmpty()) return true
                val minV  = allValues.min(); val maxV = allValues.max()
                val range = if (maxV - minV < 0.001f) 1f else maxV - minV
                val yMin  = minV - range * 0.15f
                val yMax  = maxV + range * 0.15f
                val yRange= yMax - yMin

                var closestPointIdx = 0
                var minXDist = Float.MAX_VALUE
                for (i in 0 until pointCount) {
                    val xPos = padL + (if (pointCount > 1) i * xStep else chartW / 2)
                    val xDist = kotlin.math.abs(tx - xPos)
                    if (xDist < minXDist) { minXDist = xDist; closestPointIdx = i }
                }

                var closestDist  = Float.MAX_VALUE
                var closestLine  = -1
                var closestPoint = -1

                for (li in dataLines.indices) {
                    val i = closestPointIdx
                    val xPos = padL + (if (pointCount > 1) i * xStep else chartW / 2)
                    val yPos = getYPos(dataLines[li][i].value, chartH, yMin, yRange)
                    val dist = kotlin.math.sqrt(
                        (tx - xPos) * (tx - xPos) + (ty - yPos) * (ty - yPos)
                    )
                    if (dist < 80f && dist < closestDist) {
                        closestDist  = dist
                        closestLine  = li
                        closestPoint = i
                    }
                }

                if (closestLine >= 0) {
                    selectedLine  = closestLine
                    selectedPoint = closestPoint
                    invalidate()
                    onPointClick?.invoke(closestLine, closestPoint)
                } else {
                    selectedLine  = -1
                    selectedPoint = -1
                    invalidate()
                }
            }
        }
        return true
    }
}
