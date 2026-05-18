package com.beepbeep.defense.batting

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

class SwingGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        val REF_WAYPOINTS: List<Pair<Long, Float>> = listOf(
            -1000L to 33f,
            -950L  to 33f,
            -900L  to 33f,
            -850L  to 33f,
            -800L  to 33f,
            -750L  to 33f,
            -700L  to 34f,
            -650L  to 35f,
            -600L  to 37f,
            -550L  to 39f,
            -500L  to 41f,
            -450L  to 43f,
            -400L  to 45f,
            -350L  to 46f,
            -300L  to 47f,
            -250L  to 47f,
            -200L  to 47f,
            -150L  to 46f,
            -100L  to 44f,
            -50L   to 12f,
             0L    to -27f,
             50L   to  4f,
             100L  to 25f,
             150L  to 28f,
             200L  to 27f
        )
        const val REF_PITCH_ANGLE_DEG = -27f
    }

    // ── 데이터 ──────────────────────────────────────────────
    private var pitchHistory: List<Pair<Long, Float>> = emptyList()
    private var hitTimeMs: Long = -1L
    private var requiredPitch: Float = 0f
    private var hitActualPitch: Float = 0f
    private var tolerance: Float = 10f
    private var pitchTtsStartMs: Long = -1L
    private var pitchWindowStartMs: Long = -1L
    private var hitWindowOpenMs: Long = -1L
    private var hitWindowCloseMs: Long = -1L
    private var winOpenAngleDeg: Float = Float.NaN
    private var winCloseAngleDeg: Float = Float.NaN
    private var minSearchStartMs: Long = -1L
    private var minAnglePointMs: Long = -1L
    private var minAnglePointDeg: Float = Float.NaN
    private val phaseBoundaries = mutableListOf<Pair<Long, String>>()

    // ── Paint ────────────────────────────────────────────────
    private val bgPaint = Paint().apply {
        color = Color.argb(255, 10, 20, 35)
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 255, 255, 255)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val trajectoryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 100, 180, 255)
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val reqLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 251, 191, 36)
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)
    }

    private val tolBandPaint = Paint().apply {
        color = Color.argb(35, 251, 191, 36)
    }

    private val refCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 74, 222, 128)
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(12f, 6f), 0f)
    }

    private val hitLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 74, 222, 128)
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 7f), 0f)
    }

    private val hitDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 74, 222, 128)
        style = Paint.Style.FILL
    }

    private val hitDotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val windowBandPaint = Paint().apply {
        color = Color.argb(40, 74, 222, 128)
    }

    private val minSearchLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 167, 139, 250)   // 보라색 — 측정 시작 선
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f, 5f), 0f)
    }

    private val minAngleDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 251, 146, 60)    // 오렌지 — 최저각 점
        style = Paint.Style.FILL
    }

    private val minAngleDotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val phaseBoundaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 200, 200, 200)
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }

    private val pitchTtsStartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 253, 224, 71)   // 노란색 — PITCH 발화 시작
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f, 5f), 0f)
    }

    private val pitchTtsEndPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 251, 191, 36)   // 진한 노란색 — PITCH 발화 종료
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f, 5f), 0f)
    }

    private val windowOpenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 74, 222, 128)
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)
    }

    private val windowClosePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 248, 113, 113)
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        textSize = 22f
        textAlign = Paint.Align.RIGHT
    }

    private val summaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private var showSummary = true

    // ── 공개 API ─────────────────────────────────────────────

    fun setShowSummary(show: Boolean) {
        showSummary = show
        invalidate()
    }

    fun setTolerance(deg: Float) {
        tolerance = deg
        invalidate()
    }

    fun setPitchTtsStart(ms: Long) {
        pitchTtsStartMs = ms
        invalidate()
    }

    fun setPitchWindowStart(ms: Long) {
        pitchWindowStartMs = ms
        invalidate()
    }

    fun setHitWindowOpen(ms: Long) {
        hitWindowOpenMs = ms
        invalidate()
    }

    fun setHitWindowClose(ms: Long) {
        hitWindowCloseMs = ms
        invalidate()
    }

    fun setWindowAngles(openDeg: Float, closeDeg: Float) {
        winOpenAngleDeg  = openDeg
        winCloseAngleDeg = closeDeg
        invalidate()
    }

    fun setMinAngleSearch(startMs: Long) {
        minSearchStartMs = startMs
        invalidate()
    }

    fun setMinAngle(timeMs: Long, deg: Float) {
        minAnglePointMs  = timeMs
        minAnglePointDeg = deg
        invalidate()
    }

    fun setPhaseBoundaries(vararg boundaries: Pair<Long, String>) {
        phaseBoundaries.clear()
        phaseBoundaries.addAll(boundaries)
        invalidate()
    }

    fun setLiveSource(history: ArrayList<Pair<Long, Float>>, reqPitch: Float) {
        pitchHistory     = history
        requiredPitch    = reqPitch
        hitTimeMs        = -1L
        hitActualPitch   = 0f
        pitchTtsStartMs  = -1L
        pitchWindowStartMs = -1L
        hitWindowOpenMs  = -1L
        hitWindowCloseMs = -1L
        winOpenAngleDeg  = Float.NaN
        winCloseAngleDeg = Float.NaN
        minSearchStartMs = -1L
        minAnglePointMs  = -1L
        minAnglePointDeg = Float.NaN
        phaseBoundaries.clear()
    }

    fun setHitTime(hitMs: Long) {
        hitTimeMs = hitMs
        hitActualPitch = if (hitMs >= 0L && pitchHistory.isNotEmpty()) {
            ArrayList(pitchHistory).minByOrNull { abs(it.first - hitMs) }?.second ?: requiredPitch
        } else 0f
    }

    fun setData(
        history: List<Pair<Long, Float>>,
        hitMs: Long,
        reqPitch: Float
    ) {
        pitchHistory     = history
        hitTimeMs        = hitMs
        requiredPitch    = reqPitch
        hitActualPitch   = if (hitMs >= 0L && history.isNotEmpty()) {
            history.minByOrNull { abs(it.first - hitMs) }?.second ?: reqPitch
        } else 0f
        // 마커 초기화 — 이전 투구의 값이 남지 않도록
        pitchTtsStartMs    = -1L
        pitchWindowStartMs = -1L
        hitWindowOpenMs    = -1L
        hitWindowCloseMs   = -1L
        winOpenAngleDeg    = Float.NaN
        winCloseAngleDeg   = Float.NaN
        minSearchStartMs   = -1L
        minAnglePointMs    = -1L
        minAnglePointDeg   = Float.NaN
        phaseBoundaries.clear()
        invalidate()
    }

    // ── 그리기 ───────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val padL = 68f
        val padR = 20f
        val padT = 44f
        val padB = 72f
        val gW = w - padL - padR
        val gH = h - padT - padB

        // 데이터 없으면 빈 축만 그리고 종료 (투구 간 공백에 그래프가 사라지지 않도록)
        if (pitchHistory.isEmpty()) {
            canvas.drawLine(padL, padT, padL, padT + gH, axisPaint)
            canvas.drawLine(padL, padT + gH, padL + gW, padT + gH, axisPaint)
            return
        }

        // 스냅샷으로 복사해서 그리기 — clear()와의 동시 접근 방어
        val snapshot = ArrayList(pitchHistory)
        if (snapshot.isEmpty()) return

        val maxMs = snapshot.maxOf { it.first }.coerceAtLeast(1L)

        val dataMin = snapshot.minOf { it.second }
        val dataMax = snapshot.maxOf { it.second }
        val pad = 20f
        val yMin = minOf(dataMin, requiredPitch - tolerance) - pad
        val yMax = maxOf(dataMax, requiredPitch + tolerance) + pad

        fun sx(ms: Long)   = padL + (ms.toFloat() / maxMs) * gW
        fun sy(deg: Float) = padT + gH - ((deg - yMin) / (yMax - yMin)) * gH

        // ── 타격 윈도우 음영 ─────────────────────────────────
        if (hitWindowOpenMs >= 0L) {
            val openX  = sx(hitWindowOpenMs).coerceIn(padL, padL + gW)
            val closeX = if (hitWindowCloseMs >= 0L)
                sx(hitWindowCloseMs).coerceIn(padL, padL + gW)
            else
                padL + gW
            canvas.drawRect(openX, padT, closeX, padT + gH, windowBandPaint)
        }

        // ── 허용 밴드 ───────────────────────────────────────
        val bandTop = sy(requiredPitch + tolerance).coerceAtLeast(padT)
        val bandBot = sy(requiredPitch - tolerance).coerceAtMost(padT + gH)
        canvas.drawRect(padL, bandTop, padL + gW, bandBot, tolBandPaint)

        // ── 그리드 선 (10°마다) ─────────────────────────────
        var g = (yMin / 10f).toInt() * 10f
        while (g <= yMax) {
            val gy = sy(g)
            if (gy >= padT && gy <= padT + gH) {
                canvas.drawLine(padL, gy, padL + gW, gy, gridPaint)
                canvas.drawText("${g.toInt()}°", padL - 6f, gy + 8f, axisLabelPaint)
            }
            g += 10f
        }

        // ── 축 ──────────────────────────────────────────────
        canvas.drawLine(padL, padT, padL, padT + gH, axisPaint)
        canvas.drawLine(padL, padT + gH, padL + gW, padT + gH, axisPaint)

        axisLabelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("0", padL, padT + gH + 22f, axisLabelPaint)
        canvas.drawText("${maxMs / 2}ms", padL + gW / 2f, padT + gH + 22f, axisLabelPaint)
        canvas.drawText("${maxMs}ms", padL + gW, padT + gH + 22f, axisLabelPaint)
        axisLabelPaint.textAlign = Paint.Align.RIGHT

        // ── 구간 경계선 ──────────────────────────────────────
        phaseBoundaries.forEach { (ms, label) ->
            if (ms > 0L) {
                val bx = sx(ms).coerceIn(padL, padL + gW)
                canvas.drawLine(bx, padT, bx, padT + gH, phaseBoundaryPaint)
                labelPaint.color     = Color.argb(160, 200, 200, 200)
                labelPaint.textSize  = 19f
                labelPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(label, bx + 4f, padT + 20f, labelPaint)
                labelPaint.textSize  = 17f
                canvas.drawText("${ms}ms", bx + 4f, padT + 38f, labelPaint)
            }
        }

        // ── PITCH TTS 시작 수직선 (노란색) ──────────────────
        if (pitchTtsStartMs >= 0L) {
            val px = sx(pitchTtsStartMs).coerceIn(padL, padL + gW)
            canvas.drawLine(px, padT, px, padT + gH, pitchTtsStartPaint)
            labelPaint.color     = Color.argb(200, 253, 224, 71)
            labelPaint.textSize  = 19f
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("PITCH↑", px + 4f, padT + 48f, labelPaint)
            labelPaint.textSize  = 17f
            canvas.drawText("${pitchTtsStartMs}ms", px + 4f, padT + 66f, labelPaint)
        }

        // ── PITCH TTS 종료 수직선 (진한 노란색) ─────────────
        if (pitchWindowStartMs >= 0L) {
            val ex = sx(pitchWindowStartMs).coerceIn(padL, padL + gW)
            canvas.drawLine(ex, padT, ex, padT + gH, pitchTtsEndPaint)
            labelPaint.color     = Color.argb(200, 251, 191, 36)
            labelPaint.textSize  = 19f
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("PITCH↓", ex - 4f, padT + 48f, labelPaint)
            labelPaint.textSize  = 17f
            canvas.drawText("${pitchWindowStartMs}ms", ex - 4f, padT + 66f, labelPaint)
        }

        // ── 필요 각도 점선 ───────────────────────────────────
        val rqY = sy(requiredPitch)
        canvas.drawLine(padL, rqY, padL + gW, rqY, reqLinePaint)
        labelPaint.color     = Color.argb(200, 251, 191, 36)
        labelPaint.textSize  = 22f
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("필요 ${requiredPitch.toInt()}° ±${tolerance.toInt()}°", padL + gW - 4f, rqY - 6f, labelPaint)

        // ── 논문 기준 궤적 (초록 점선) ───────────────────────
        if (pitchWindowStartMs >= 0L) {
            val refPath = Path()
            var started = false
            for ((relMs, deg) in REF_WAYPOINTS) {
                val absMs = pitchWindowStartMs + relMs
                if (absMs < 0L) continue
                val px = sx(absMs).coerceIn(padL, padL + gW)
                val py = sy(deg).coerceIn(padT, padT + gH)
                if (!started) { refPath.moveTo(px, py); started = true }
                else refPath.lineTo(px, py)
            }
            if (started) canvas.drawPath(refPath, refCurvePaint)
        }

        // ── 타격 윈도우 OPEN / CLOSE 수직선 ─────────────────
        if (hitWindowOpenMs >= 0L) {
            val openX = sx(hitWindowOpenMs).coerceIn(padL, padL + gW)
            canvas.drawLine(openX, padT, openX, padT + gH, windowOpenPaint)
            labelPaint.color     = Color.argb(220, 74, 222, 128)
            labelPaint.textSize  = 20f
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("OPEN", openX + 4f, padT + 48f, labelPaint)
            labelPaint.textSize  = 17f
            canvas.drawText("${hitWindowOpenMs}ms", openX + 4f, padT + 66f, labelPaint)
        }
        if (hitWindowCloseMs >= 0L) {
            val closeX = sx(hitWindowCloseMs).coerceIn(padL, padL + gW)
            canvas.drawLine(closeX, padT, closeX, padT + gH, windowClosePaint)
            labelPaint.color     = Color.argb(220, 248, 113, 113)
            labelPaint.textSize  = 20f
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("CLOSE", closeX - 4f, padT + 48f, labelPaint)
            labelPaint.textSize  = 17f
            canvas.drawText("${hitWindowCloseMs}ms", closeX - 4f, padT + 66f, labelPaint)
        }

        // ── 스윙 궤적 ────────────────────────────────────────
        if (snapshot.size >= 2) {
            val path = Path()
            snapshot.forEachIndexed { i, (ms, deg) ->
                val px = sx(ms)
                val py = sy(deg).coerceIn(padT, padT + gH)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, trajectoryPaint)
        }

        // ── 최저각 측정 시작 수직선 (보라색) ────────────────
        if (minSearchStartMs >= 0L) {
            val mx = sx(minSearchStartMs).coerceIn(padL, padL + gW)
            canvas.drawLine(mx, padT, mx, padT + gH, minSearchLinePaint)
            labelPaint.color     = Color.argb(200, 167, 139, 250)
            labelPaint.textSize  = 19f
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("측정↓", mx + 3f, padT + 66f, labelPaint)
            labelPaint.textSize  = 17f
            canvas.drawText("${minSearchStartMs}ms", mx + 3f, padT + 84f, labelPaint)
        }

        // ── 최저각 마커 (오렌지 점) ──────────────────────────
        if (minAnglePointMs >= 0L && !minAnglePointDeg.isNaN()) {
            val mx = sx(minAnglePointMs).coerceIn(padL, padL + gW)
            val my = sy(minAnglePointDeg).coerceIn(padT, padT + gH)
            canvas.drawCircle(mx, my, 11f, minAngleDotPaint)
            canvas.drawCircle(mx, my, 11f, minAngleDotBorderPaint)
            labelPaint.color     = Color.argb(230, 251, 146, 60)
            labelPaint.textSize  = 20f
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("최저 %.0f°".format(minAnglePointDeg), mx + 13f, my - 6f, labelPaint)
        }

        // ── 히트 마커 ────────────────────────────────────────
        if (hitTimeMs >= 0L) {
            val hx = sx(hitTimeMs)
            val hy = sy(hitActualPitch).coerceIn(padT, padT + gH)

            canvas.drawLine(hx, padT, hx, padT + gH, hitLinePaint)
            canvas.drawCircle(hx, hy, 13f, hitDotPaint)
            canvas.drawCircle(hx, hy, 13f, hitDotBorderPaint)

            labelPaint.color     = Color.argb(230, 74, 222, 128)
            labelPaint.textSize  = 24f
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("HIT", hx + 10f, padT + 30f, labelPaint)
            labelPaint.textSize  = 17f
            canvas.drawText("${hitTimeMs}ms", hx + 10f, padT + 50f, labelPaint)
        }

        // ── 제목 ─────────────────────────────────────────────
        labelPaint.color     = Color.WHITE
        labelPaint.textSize  = 27f
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("스윙 궤적", padL + gW / 2f, padT - 12f, labelPaint)

        // ── 하단 요약 텍스트 (윈도우 구간 각도 변화) ────────
        if (showSummary) {
            val summaryText = if (!winOpenAngleDeg.isNaN() && !winCloseAngleDeg.isNaN()) {
                "윈도우 각도  %.0f°  →  %.0f°  (Δ%.0f°)"
                    .format(winOpenAngleDeg, winCloseAngleDeg, winCloseAngleDeg - winOpenAngleDeg)
            } else {
                ""
            }
            val diff = abs(hitActualPitch - requiredPitch)
            summaryPaint.color = when {
                hitTimeMs < 0L   -> Color.argb(200, 248, 113, 113)
                diff < tolerance -> Color.argb(220, 74, 222, 128)
                else             -> Color.argb(220, 251, 191, 36)
            }
            if (summaryText.isNotEmpty()) {
                canvas.drawText(summaryText, padL + gW / 2f, padT + gH + 58f, summaryPaint)
            }
        }
    }
}
