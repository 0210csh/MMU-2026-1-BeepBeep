package com.beepbeep.defense.batting

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/**
 * SwingGraphView
 *
 * 스윙 구간(hitWindowActive 동안)의 피치각 궤적을 2D 그래프로 그린다.
 * X축: 시간(ms), Y축: 피치각(도)
 * - 파란 선: 실제 폰 기울기 궤적
 * - 노란 점선: 필요 각도 + 허용 밴드(±25°)
 * - 초록 선·점: 공이 맞은(hit) 시점 마커
 *
 * 외부 API:
 *   setData(history, hitMs, reqPitch) — 데이터 설정 후 자동 재그리기
 */
class SwingGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── 데이터 ──────────────────────────────────────────────
    private var pitchHistory: List<Pair<Long, Float>> = emptyList()
    // (ms_from_window_start, pitch_deg) 쌍의 목록. setData() 로 설정됨

    private var hitTimeMs: Long = -1L
    // 스윙 감지 순간의 타임스탬프(ms). -1이면 스윙 없음

    private var requiredPitch: Float = 0f
    // 이번 투구의 필요 각도(도). 노란 점선으로 표시됨

    private var hitActualPitch: Float = 0f
    // hitTimeMs 시점의 실제 피치각. 초록 점 위치 결정

    private val TOLERANCE = 25f
    // 허용 오차(도). BaseRunReactionActivity.PITCH_TOLERANCE 와 동일

    // ── Paint ────────────────────────────────────────────────
    private val bgPaint = Paint().apply {
        color = Color.argb(255, 10, 20, 35)
        // 앱 전체 배경색 #0A1423
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
        // 파란색 계열 — 폰 궤적
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val reqLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 251, 191, 36)
        // 앰버 노란색 — 필요 각도
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)
    }

    private val tolBandPaint = Paint().apply {
        color = Color.argb(35, 251, 191, 36)
        // 허용 밴드 채우기 (반투명 노란색)
    }

    private val hitLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 74, 222, 128)
        // 초록색 — 히트 시점 수직선
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 7f), 0f)
    }

    private val hitDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 74, 222, 128)
        style = Paint.Style.FILL
        // 초록 채움 — 히트 시점 점
    }

    private val hitDotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
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

    // ── 공개 API ─────────────────────────────────────────────

    fun setLiveSource(history: ArrayList<Pair<Long, Float>>, reqPitch: Float) {
        pitchHistory  = history
        requiredPitch = reqPitch
        hitTimeMs     = -1L
        hitActualPitch = 0f
    }

    fun setHitTime(hitMs: Long) {
        hitTimeMs = hitMs
        hitActualPitch = if (hitMs >= 0L && pitchHistory.isNotEmpty()) {
            pitchHistory.minByOrNull { abs(it.first - hitMs) }?.second ?: requiredPitch
        } else 0f
    }

    fun setData(
        history: List<Pair<Long, Float>>,
        hitMs: Long,
        reqPitch: Float
    ) {
        pitchHistory = history
        hitTimeMs    = hitMs
        requiredPitch = reqPitch
        hitActualPitch = if (hitMs >= 0L && history.isNotEmpty()) {
            history.minByOrNull { abs(it.first - hitMs) }?.second ?: reqPitch
        } else 0f
        invalidate()
    }

    // ── 그리기 ───────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        if (pitchHistory.isEmpty()) return

        // 그래프 영역 여백
        val padL = 68f    // Y축 레이블 공간
        val padR = 20f
        val padT = 44f    // 제목 공간
        val padB = 72f    // 요약 텍스트 공간
        val gW = w - padL - padR
        val gH = h - padT - padB

        val maxMs = pitchHistory.maxOf { it.first }.coerceAtLeast(1L)

        // Y 범위: 실제 데이터 + 필요각도 + 허용밴드를 모두 포함하도록 동적 산출, 여백 20° 추가
        val dataMin = pitchHistory.minOf { it.second }
        val dataMax = pitchHistory.maxOf { it.second }
        val pad = 20f
        val yMin = minOf(dataMin, requiredPitch - TOLERANCE) - pad
        val yMax = maxOf(dataMax, requiredPitch + TOLERANCE) + pad

        fun sx(ms: Long)  = padL + (ms.toFloat() / maxMs) * gW
        fun sy(deg: Float) = padT + gH - ((deg - yMin) / (yMax - yMin)) * gH

        // ── 허용 밴드 ───────────────────────────────────────
        val bandTop = sy(requiredPitch + TOLERANCE).coerceAtLeast(padT)
        val bandBot = sy(requiredPitch - TOLERANCE).coerceAtMost(padT + gH)
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
        canvas.drawLine(padL, padT, padL, padT + gH, axisPaint)              // Y축
        canvas.drawLine(padL, padT + gH, padL + gW, padT + gH, axisPaint)   // X축

        // X축 레이블 (시작 / 중간 / 끝)
        axisLabelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("0", padL, padT + gH + 22f, axisLabelPaint)
        canvas.drawText("${maxMs / 2}ms", padL + gW / 2f, padT + gH + 22f, axisLabelPaint)
        canvas.drawText("${maxMs}ms", padL + gW, padT + gH + 22f, axisLabelPaint)
        axisLabelPaint.textAlign = Paint.Align.RIGHT

        // ── 필요 각도 점선 ───────────────────────────────────
        val rqY = sy(requiredPitch)
        canvas.drawLine(padL, rqY, padL + gW, rqY, reqLinePaint)
        labelPaint.color    = Color.argb(200, 251, 191, 36)
        labelPaint.textSize = 22f
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("필요 ${requiredPitch.toInt()}°", padL + gW - 4f, rqY - 6f, labelPaint)

        // ── 스윙 궤적 ────────────────────────────────────────
        if (pitchHistory.size >= 2) {
            val path = Path()
            pitchHistory.forEachIndexed { i, (ms, deg) ->
                val px = sx(ms)
                val py = sy(deg).coerceIn(padT, padT + gH)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, trajectoryPaint)
        }

        // ── 히트 마커 ────────────────────────────────────────
        if (hitTimeMs >= 0L) {
            val hx = sx(hitTimeMs)
            val hy = sy(hitActualPitch).coerceIn(padT, padT + gH)

            canvas.drawLine(hx, padT, hx, padT + gH, hitLinePaint)
            canvas.drawCircle(hx, hy, 13f, hitDotPaint)
            canvas.drawCircle(hx, hy, 13f, hitDotBorderPaint)

            // "HIT" 레이블
            labelPaint.color    = Color.argb(230, 74, 222, 128)
            labelPaint.textSize = 24f
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("HIT", hx + 10f, padT + 30f, labelPaint)
        }

        // ── 제목 ─────────────────────────────────────────────
        labelPaint.color    = Color.WHITE
        labelPaint.textSize = 27f
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("스윙 궤적", padL + gW / 2f, padT - 12f, labelPaint)

        // ── 하단 요약 텍스트 ─────────────────────────────────
        val diff = abs(hitActualPitch - requiredPitch)
        val summaryText = if (hitTimeMs >= 0L) {
            "실제 %.0f°  /  필요 %.0f°  /  오차 %.0f°".format(hitActualPitch, requiredPitch, diff)
        } else {
            "스윙 없음  /  필요 각도 %.0f°".format(requiredPitch)
        }
        summaryPaint.color = when {
            hitTimeMs < 0L         -> Color.argb(200, 248, 113, 113)  // 빨강 — 스윙 없음
            diff < TOLERANCE       -> Color.argb(220, 74, 222, 128)   // 초록 — 범위 안
            else                   -> Color.argb(220, 251, 191, 36)   // 노랑 — 범위 밖
        }
        canvas.drawText(summaryText, padL + gW / 2f, padT + gH + 58f, summaryPaint)
    }
}
