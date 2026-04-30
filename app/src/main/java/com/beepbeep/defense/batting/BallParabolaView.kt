package com.beepbeep.defense.batting

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

/**
 * BallParabolaView
 *
 * 공이 투수에서 타자까지 날아오는 포물선 궤적을 2D 그래프로 그린다.
 * X축: 거리 (투수 → 타자, m), Y축: 높이 (m)
 * - 노란 곡선: 공 포물선 궤적
 * - 파란 선: 배트 위치 (접촉 순간 높이)
 * - 노란 점: 목표 접촉 높이
 * - 반투명 파란 영역: 허용 타격 범위
 *
 * 외부 API:
 *   setData(pitcherDist, pitcherH, contactH, batH, arcH, isHit) — 데이터 설정 후 자동 재그리기
 */
class BallParabolaView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── 데이터 ──────────────────────────────────────────────
    private var pitcherDist      = 18.44f
    private var pitcherH         = 1.5f
    private var contactH         = 1.0f
    private var batH             = 1.0f
    private var arcH             = 0.3f
    private var isHit            = false
    private var hasData          = false
    private var liveMode         = false   // true: HIT/MISS 레이블·요약 숨김, 이동 공 표시
    private var liveBallProgress = -1f     // 0~1: 공 현재 위치. -1이면 이동 공 미표시

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

    private val batterLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 251, 191, 36)
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val contactDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 251, 191, 36)
        style = Paint.Style.FILL
    }

    private val batPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 100, 180, 255)
        strokeWidth = 7f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val hitZonePaint = Paint().apply {
        color = Color.argb(30, 100, 180, 255)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        textSize = 20f
        textAlign = Paint.Align.RIGHT
    }

    private val summaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    // ── 공개 API ─────────────────────────────────────────────

    /** 정적 결과 표시용. 다이얼로그에서 호출. liveMode=false → HIT/MISS 레이블 표시. */
    fun setData(
        pitcherDist: Float,
        pitcherH: Float,
        contactH: Float,
        batH: Float,
        arcH: Float,
        isHit: Boolean
    ) {
        this.pitcherDist = pitcherDist
        this.pitcherH    = pitcherH
        this.contactH    = contactH
        this.batH        = batH
        this.arcH        = arcH
        this.isHit       = isHit
        this.hasData     = true
        invalidate()
    }

    /** 라이브 모드 활성화. 게임 진행 중 인-레이아웃 뷰에 호출. HIT/MISS 레이블·요약 숨김. */
    fun setLiveMode() {
        liveMode = true
        postInvalidate()
    }

    /**
     * 공 이동 위치 업데이트. 게임 루프에서 매 프레임 호출.
     * @param p 0.0(투수)~1.0(타자). 포물선 위의 현재 공 위치를 노란 원으로 표시.
     */
    fun setBallProgress(p: Float) {
        liveBallProgress = p
        postInvalidate()  // 센서/코루틴 스레드에서 안전하게 재그리기 요청
    }

    /**
     * 배트 높이 실시간 업데이트. orientationListener(센서 스레드)에서 직접 호출 가능.
     * postInvalidate() 사용으로 스레드 안전.
     */
    fun updateLiveBat(newBatH: Float) {
        batH = newBatH
        if (hasData) postInvalidate()
    }

    // ── 그리기 ───────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawRect(0f, 0f, w, h, bgPaint)
        if (!hasData) return

        val padL = 68f
        val padR = 20f
        val padT = 44f
        val padB = 72f
        val gW   = w - padL - padR
        val gH   = h - padT - padB

        // Y 범위: 포물선 최고점 + 모든 높이 포함 + 여백
        val peakH = (pitcherH + contactH) / 2f + arcH
        val allH  = listOf(pitcherH, contactH, batH, peakH)
        val yMin  = (allH.minOrNull()!! - 0.3f).coerceAtLeast(0f)
        val yMax  = allH.maxOrNull()!! + 0.3f

        fun sx(z: Float)  = padL + (z / pitcherDist) * gW
        fun sy(hm: Float) = padT + gH - ((hm - yMin) / (yMax - yMin)) * gH

        // ── 그리드 (0.5m마다) ───────────────────────────────
        var g = (yMin * 2).toInt() * 0.5f
        while (g <= yMax) {
            val gy = sy(g)
            if (gy >= padT && gy <= padT + gH) {
                canvas.drawLine(padL, gy, padL + gW, gy, gridPaint)
                canvas.drawText("%.1fm".format(g), padL - 6f, gy + 7f, axisLabelPaint)
            }
            g += 0.5f
        }

        // ── 축 ──────────────────────────────────────────────
        canvas.drawLine(padL, padT, padL, padT + gH, axisPaint)
        canvas.drawLine(padL, padT + gH, padL + gW, padT + gH, axisPaint)

        // X축 레이블
        axisLabelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("투수", padL, padT + gH + 22f, axisLabelPaint)
        canvas.drawText("%.0fm".format(pitcherDist / 2f), padL + gW / 2f, padT + gH + 22f, axisLabelPaint)
        canvas.drawText("타자", padL + gW, padT + gH + 22f, axisLabelPaint)
        axisLabelPaint.textAlign = Paint.Align.RIGHT

        // ── 타자 위치 수직 점선 ─────────────────────────────
        val xBatter = padL + gW
        canvas.drawLine(xBatter, padT, xBatter, padT + gH, batterLinePaint)

        // ── 공 포물선 ────────────────────────────────────────
        val path = Path()
        val steps = 120
        for (i in 0..steps) {
            val t  = i.toFloat() / steps
            val hh = pitcherH + (contactH - pitcherH) * t + arcH * sin(PI.toFloat() * t)
            val px = sx(pitcherDist * t)
            val py = sy(hh).coerceIn(padT, padT + gH)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, ballPaint)

        // ── 타격 허용 범위 (반투명 파란 영역) ───────────────
        val HIT_ZONE = 0.2f
        val zoneTop = sy(contactH + HIT_ZONE).coerceIn(padT, padT + gH)
        val zoneBot = sy(contactH - HIT_ZONE).coerceIn(padT, padT + gH)
        val batLen  = gW * 0.07f
        canvas.drawRect(xBatter - batLen * 2.5f, zoneTop, xBatter, zoneBot, hitZonePaint)

        // ── 배트 위치 (파란 수평 선분) ───────────────────────
        val batY = sy(batH).coerceIn(padT, padT + gH)
        canvas.drawLine(xBatter - batLen * 2.5f, batY, xBatter, batY, batPaint)

        // ── 목표 접촉점 (노란 점) ────────────────────────────
        val contactY = sy(contactH).coerceIn(padT, padT + gH)
        canvas.drawCircle(xBatter, contactY, 11f, contactDotPaint)
        canvas.drawCircle(xBatter, contactY, 11f, Paint(Paint.ANTI_ALIAS_FLAG).also {
            it.color = Color.WHITE; it.style = Paint.Style.STROKE; it.strokeWidth = 2f
        })

        // ── 이동 중인 공 (라이브 모드 전용) ────────────────────
        if (liveBallProgress in 0f..1f) {
            val t   = liveBallProgress
            val lhh = pitcherH + (contactH - pitcherH) * t + arcH * sin(PI.toFloat() * t)
            val lbx = sx(pitcherDist * t)
            val lby = sy(lhh).coerceIn(padT, padT + gH)
            // 이동 공: 글로우 + 본체 두 겹으로 강조
            canvas.drawCircle(lbx, lby, 14f, Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.argb(60, 251, 191, 36); it.style = Paint.Style.FILL
            })
            canvas.drawCircle(lbx, lby, 8f, Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.argb(230, 251, 191, 36); it.style = Paint.Style.FILL
            })
        }

        // ── HIT / MISS 레이블 (결과 모드에서만) ─────────────
        if (!liveMode) {
            labelPaint.color     = if (isHit) Color.argb(230, 74, 222, 128) else Color.argb(220, 248, 113, 113)
            labelPaint.textSize  = 26f
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(if (isHit) "HIT" else "MISS", xBatter - 6f, padT + 30f, labelPaint)
        }

        // ── 제목 ─────────────────────────────────────────────
        labelPaint.color     = Color.WHITE
        labelPaint.textSize  = 27f
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("공 궤적", padL + gW / 2f, padT - 12f, labelPaint)

        // ── 하단 요약 텍스트 (결과 모드에서만) ──────────────
        if (!liveMode) {
            val diff = abs(batH - contactH)
            summaryPaint.color = if (isHit) Color.argb(220, 74, 222, 128) else Color.argb(220, 248, 113, 113)
            canvas.drawText(
                "배트 %.2fm  /  목표 %.2fm  /  차이 %.2fm".format(batH, contactH, diff),
                padL + gW / 2f, padT + gH + 58f, summaryPaint
            )
        }
    }
}
