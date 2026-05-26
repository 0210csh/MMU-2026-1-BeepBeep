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
        val value: Float,          // Float.NaN → 기록 없음
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
    private val notePaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF888888.toInt(); textSize = 28f; textAlign = Paint.Align.LEFT }
    private val hintPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF555555.toInt(); textSize = 26f; textAlign = Paint.Align.CENTER }
    // NaN 포인트 "없음" 텍스트용
    private val nanTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF888888.toInt(); textSize = 32f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    // NaN 포인트 X 표시용
    private val nanDotPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = 0xFF666666.toInt() }

    private var selectedLine  = -1
    private var selectedPoint = -1

    private var touchStartX = 0f
    private var touchStartY = 0f
    private val SWIPE_THRESHOLD = 40f

    private val padL = 50f
    private val padR = 50f
    private val padT = 80f
    private val padB = 100f

    fun setData(lines: List<List<ChartPoint>>, colors: List<Int>, labels: List<String>) {
        dataLines.clear(); dataLines.addAll(lines)
        lineColors.clear(); lineColors.addAll(colors)
        lineLabels.clear(); lineLabels.addAll(labels)
        selectedLine = -1; selectedPoint = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataLines.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val chartW = w - padL - padR
        val chartH = h - padT - padB

        // NaN은 0으로 대체하여 y축 범위 계산
        val allValues = dataLines.flatten().map { if (it.value.isNaN()) 0f else it.value }
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

        // x축 레이블
        for (i in xLabels.indices) {
            val xPos = padL + (if (pointCount > 1) i * xStep else chartW / 2)
            textPaint.textAlign = when (i) {
                0              -> Paint.Align.LEFT
                xLabels.size-1 -> Paint.Align.RIGHT
                else           -> Paint.Align.CENTER
            }
            canvas.drawText(xLabels[i], xPos, padT + chartH + 65f, textPaint)
        }
        textPaint.textAlign = Paint.Align.CENTER

        // 각 선
        for (li in dataLines.indices) {
            val pts   = dataLines[li]
            val color = lineColors.getOrElse(li) { Color.WHITE }
            linePaint.color = color
            dotPaint.color  = color
            dashPaint.color = color

            // NaN → 값 0으로 치환한 y좌표 헬퍼
            fun resolvedY(pt: ChartPoint): Float {
                val v = if (pt.value.isNaN()) 0f else pt.value
                return padT + chartH * (1f - (v - yMin) / yRange)
            }

            // 일반 선 — NaN 포인트는 점선 구간으로 넘기고 solid에서 끊기
            val solidPath = Path()
            var solidStarted = false
            for (i in pts.indices) {
                if (pts[i].value.isNaN() || pts[i].isPartial) { solidStarted = false; continue }
                val xPos = padL + (if (pointCount > 1) i * xStep else chartW / 2)
                val yPos = resolvedY(pts[i])
                if (!solidStarted) { solidPath.moveTo(xPos, yPos); solidStarted = true }
                else solidPath.lineTo(xPos, yPos)
            }
            canvas.drawPath(solidPath, linePaint)

            // 점선 — partial 구간 OR NaN 포함 구간 모두 점선으로 연결
            for (i in 1 until pts.size) {
                val prevNan = pts[i-1].value.isNaN()
                val currNan = pts[i].value.isNaN()
                val isDash  = pts[i].isPartial || pts[i-1].isPartial || prevNan || currNan
                if (!isDash) continue
                val x1 = padL + (if (pointCount > 1) (i-1) * xStep else chartW / 2)
                val y1 = resolvedY(pts[i-1])
                val x2 = padL + (if (pointCount > 1) i * xStep else chartW / 2)
                val y2 = resolvedY(pts[i])
                val dp = Path(); dp.moveTo(x1, y1); dp.lineTo(x2, y2)
                canvas.drawPath(dp, dashPaint)
            }

            // 점 & NaN 표시
            for (i in pts.indices) {
                val xPos = padL + (if (pointCount > 1) i * xStep else chartW / 2)

                // ── NaN: y=0 위치에 partial 스타일 점선 원만 표시 ──
                if (pts[i].value.isNaN()) {
                    val yPos0 = padT + chartH * (1f - (0f - yMin) / yRange)
                    val r = if (li == selectedLine && i == selectedPoint) 44f else 32f
                    dotPaint.color = 0xFF444444.toInt()
                    canvas.drawCircle(xPos, yPos0, r, dotPaint)
                    partialRing.color = color
                    canvas.drawCircle(xPos, yPos0, r, partialRing)
                    if (li == selectedLine && i == selectedPoint) {
                        valuePaint.color = 0xFF888888.toInt()
                        valuePaint.textAlign = when (i) {
                            0            -> Paint.Align.LEFT
                            pts.size - 1 -> Paint.Align.RIGHT
                            else         -> Paint.Align.CENTER
                        }
                        canvas.drawText("기록없음", xPos, yPos0 - r - 16f, valuePaint)
                        valuePaint.textAlign = Paint.Align.CENTER
                    }
                    continue
                }

                val yFrac = (pts[i].value - yMin) / yRange
                val yPos  = padT + chartH * (1f - yFrac)
                val isSelected = li == selectedLine && i == selectedPoint
                val radius = if (isSelected) 44f else 32f

                if (pts[i].isPartial) {
                    dotPaint.color = 0xFF444444.toInt()
                    canvas.drawCircle(xPos, yPos, radius, dotPaint)
                    partialRing.color = color
                    canvas.drawCircle(xPos, yPos, radius, partialRing)
                    dotPaint.color = color
                } else {
                    dotPaint.color = color
                    canvas.drawCircle(xPos, yPos, radius, dotPaint)
                    canvas.drawCircle(xPos, yPos, radius, dotRingPaint)
                }

                if (isSelected) {
                    // 마지막 포인트이고 partial이면 숫자 대신 "기록없음" 표시
                    val isLastPartial = pts[i].isPartial && i == pts.size - 1
                    val valStr = if (isLastPartial) "기록없음"
                    else if (pts[i].value >= 100f) "%.0f".format(pts[i].value)
                    else "%.2f".format(pts[i].value)
                    valuePaint.color = if (isLastPartial) 0xFF888888.toInt() else color
                    // 첫 포인트는 왼쪽 정렬, 마지막은 오른쪽 정렬, 나머지는 가운데
                    valuePaint.textAlign = when (i) {
                        0            -> Paint.Align.LEFT
                        pts.size - 1 -> Paint.Align.RIGHT
                        else         -> Paint.Align.CENTER
                    }
                    canvas.drawText(valStr, xPos, yPos - 42f, valuePaint)
                    valuePaint.textAlign = Paint.Align.CENTER  // 원복
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

        // partial 안내
        val hasPartial = dataLines.flatten().any { it.isPartial }
        if (hasPartial) canvas.drawText("* 20판 미만 묶음 (점선)", padL, padT - 12f, notePaint)

        // 포인트 선택 시 스와이프 힌트
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

                // ── 스와이프 ──────────────────────────────────────
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

                // ── 탭: 가장 가까운 포인트 찾기 ──────────────────
                if (dataLines.isEmpty()) return true

                val chartW     = width.toFloat() - padL - padR
                val chartH     = height.toFloat() - padT - padB
                val pointCount = dataLines.firstOrNull()?.size ?: return true
                val xStep      = if (pointCount > 1) chartW / (pointCount - 1) else chartW / 2
                val tx = touchStartX; val ty = touchStartY

                // onDraw와 동일하게 NaN→0으로 치환해 yMin/yRange 계산
                val allValues = dataLines.flatten().map { if (it.value.isNaN()) 0f else it.value }
                if (allValues.isEmpty()) return true
                val minV   = allValues.min(); val maxV = allValues.max()
                val range  = if (maxV - minV < 0.001f) 1f else maxV - minV
                val yMin   = minV - range * 0.15f; val yMax = maxV + range * 0.15f
                val yRange = yMax - yMin

                // x축 가장 가까운 포인트 인덱스
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
                    val pt = dataLines[li][i]
                    val xPos = padL + (if (pointCount > 1) i * xStep else chartW / 2)
                    // NaN 포인트도 탭 가능하도록 — y는 값 0 위치 기준
                    val v    = if (pt.value.isNaN()) 0f else pt.value
                    val yPos = padT + chartH * (1f - (v - yMin) / yRange)
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