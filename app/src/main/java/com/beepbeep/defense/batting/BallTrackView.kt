package com.beepbeep.defense.batting

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

/**
 * BallTrackView
 *
 * 타격 시뮬레이터 중앙 뷰. 아래 요소를 그린다.
 *   - 공의 3D 원근감 접근 (APPROACH): 멀리서→가까이 포물선 궤적, 크기 증가
 *   - 스트라이크존 사각형: 공이 도달할 높이를 세 구역(낮음/중간/높음)으로 표시
 *   - 수비수(사람) + FOV 삼각형 (헤드트래킹 방향)
 *   - 공 날아감 (DIVERGE): 타격 후 베이스 방향으로 발산
 *
 * updateBall(panX, progress, phase, pitchY) 로 외부에서 상태 갱신.
 * → res/layout/activity_base_run_reaction.xml 의 <...BallTrackView> 태그로 삽입됨
 */
class BallTrackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ─────────────────────────────────────────
    // 상태 변수 (외부 updateBall / updateHeading 으로 갱신)
    // ─────────────────────────────────────────
    private var ballPanX: Float     = 0f   // 수평 위치 -1~+1
    private var ballProgress: Float = 0f   // 접근/발산 진행률 0~1
    private var currentPhase: BallPhase = BallPhase.APPROACH
    private var headingDeg: Float   = 0f   // 수비수 FOV 방향 (도)
    private var pitchY: Float       = 0.5f // 공 수직 위치: 0=낮음, 0.5=중간, 1.0=높음

    private val trail = ArrayDeque<Pair<Float, Float>>() // 공 궤적 이전 위치 큐 (최대 35개)

    private var showStrikeZone = true // 스트라이크존 표시 여부. setShowStrikeZone(false) 로 숨김

    // ─────────────────────────────────────────
    // Paint 객체
    // ─────────────────────────────────────────
    private val fieldPaint = Paint().apply {
        color = Color.argb(255, 10, 20, 35) // #0A1423 어두운 네이비 배경
        style = Paint.Style.FILL
    }
    private val centerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(30, 255, 255, 255) // 반투명 흰색 중앙선
        style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 148, 163, 184) // 베이스→수비수 점선
        style = Paint.Style.STROKE; strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f)
    }

    // 공
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 251, 191, 36); style = Paint.Style.FILL // 앰버 공 본체
    }
    private val ballGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 251, 191, 36); style = Paint.Style.FILL  // 공 글로우
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL // 색상은 onDraw에서 동적 설정
    }

    // 베이스
    private val base1Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 220, 38, 38); style = Paint.Style.FILL  // 1루: 빨강
    }
    private val base3Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 37, 99, 235); style = Paint.Style.FILL  // 3루: 파랑
    }

    // 수비수(사람)
    private val playerBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 80, 200, 120); style = Paint.Style.FILL
    }
    private val playerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 50, 220, 100)
        style = Paint.Style.STROKE; strokeWidth = 2f
    }

    // FOV (시야각) 삼각형
    private val fovFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(35, 255, 220, 50); style = Paint.Style.FILL
    }
    private val fovLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 220, 50)
        style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val fovCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 220, 50)
        style = Paint.Style.STROKE; strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
    }

    // 라벨
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 200, 210, 220)
        textSize = 22f; textAlign = Paint.Align.CENTER
    }

    // 스트라이크존
    private val szBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255)  // 반투명 흰 테두리
        style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val szFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(12, 200, 200, 255)  // 연한 파란 배경
        style = Paint.Style.FILL
    }
    private val szDivPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 200, 200, 255)  // 구역 구분선
        style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val szMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 100, 200, 255) // 현재 공 목표 마커 (파란 원)
        style = Paint.Style.FILL
    }
    private val szLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 200, 220, 255)
        textSize = 16f; textAlign = Paint.Align.CENTER
    }

    // ─────────────────────────────────────────
    // 좌표 변환
    // ─────────────────────────────────────────
    /** panX(-1~+1) → 픽셀 X */
    private fun toScreenX(panX: Float): Float = width * (panX + 1f) / 2f

    /** progress(0~1) → 픽셀 Y (APPROACH 단계 기본: 투수 위치 → 수비수 위치) */
    private fun toScreenY(progress: Float): Float {
        val topY    = height * 0.12f  // 투수 위치 (화면 상단 12%)
        val playerY = height * 0.78f  // 수비수 위치 (화면 78%)
        return topY + progress * (playerY - topY)
    }

    /**
     * APPROACH 단계에서 pitchY 를 반영한 공의 픽셀 Y 계산.
     *
     * 원근감 적용:
     *   - progress=0 → 투수 위치(화면 상단), pitchY 무시 (모두 같은 점에서 출발)
     *   - progress=1 → 스트라이크존 내 해당 pitchY 높이에 도달
     *
     * @param progress 0~1 (접근 진행률)
     * @param pY       0~1 (공 수직 위치: 0=낮은 공, 0.5=중간, 1.0=높은 공)
     */
    private fun toScreenYWithPitch(progress: Float, pY: Float): Float {
        val topY = height * 0.12f           // 투수(수렴점) Y
        val szBottom = height * 0.70f       // 스트라이크존 하단 (낮은 공)
        val szTop    = height * 0.45f       // 스트라이크존 상단 (높은 공)
        // pitchY=0 → szBottom, pitchY=1 → szTop
        val targetY = szBottom - pY * (szBottom - szTop)
        // progress=0 에서는 topY, progress=1 에서는 targetY 에 도달
        return topY + progress * (targetY - topY)
    }

    // ─────────────────────────────────────────
    // onDraw
    // ─────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w  = width.toFloat()
        val h  = height.toFloat()
        val cx = w / 2f

        // ── 배경 ─────────────────────────────────────────────
        canvas.drawRect(0f, 0f, w, h, fieldPaint)
        canvas.drawLine(cx, 0f, cx, h, centerLinePaint) // 중앙 세로선

        // ── 수비수 위치 ───────────────────────────────────────
        val playerX = cx
        val playerY = h * 0.78f

        // ── 베이스 위치 ───────────────────────────────────────
        val base3X = w * 0.12f; val base3Y = h * 0.10f
        val base1X = w * 0.88f; val base1Y = h * 0.10f

        // 베이스→수비수 점선
        canvas.drawLine(base3X, base3Y, playerX, playerY, linePaint)
        canvas.drawLine(base1X, base1Y, playerX, playerY, linePaint)

        // 3루 베이스
        drawDiamond(canvas, base3X, base3Y, 16f, base3Paint)
        labelPaint.color = Color.argb(180, 100, 140, 255)
        canvas.drawText("3루", base3X, base3Y + 34f, labelPaint)

        // 1루 베이스
        drawDiamond(canvas, base1X, base1Y, 16f, base1Paint)
        labelPaint.color = Color.argb(180, 255, 100, 100)
        canvas.drawText("1루", base1X, base1Y + 34f, labelPaint)
        labelPaint.color = Color.argb(180, 200, 210, 220)

        // ── FOV 삼각형 ────────────────────────────────────────
        drawFov(canvas, playerX, playerY)

        // ── 스트라이크존 ──────────────────────────────────────
        // 공이 APPROACH 중이고 showStrikeZone=true 일 때만 표시
        if (showStrikeZone && currentPhase == BallPhase.APPROACH) {
            drawStrikeZone(canvas, cx)
        }

        // ── 공 + 궤적 ─────────────────────────────────────────
        if (ballProgress > 0f) {
            // 현재 공 픽셀 좌표 계산
            val bx: Float
            val by: Float
            when (currentPhase) {
                BallPhase.APPROACH -> {
                    bx = toScreenX(ballPanX)
                    by = toScreenYWithPitch(ballProgress, pitchY)
                }
                BallPhase.DIVERGE -> {
                    bx = toScreenX(ballPanX)
                    by = toScreenY(ballProgress)
                }
            }

            // 궤적 큐에 현재 위치 추가
            trail.add(Pair(bx, by))
            if (trail.size > 35) trail.removeFirst()

            // 궤적 점 그리기 (오래된 것 → 반투명/작음)
            trail.forEachIndexed { i, (tx, ty) ->
                val alpha = (i.toFloat() / trail.size * 160).toInt()
                trailPaint.color = Color.argb(alpha, 251, 191, 36)
                val r = 2f + i.toFloat() / trail.size * 5f
                canvas.drawCircle(tx, ty, r, trailPaint)
            }

            // 공 본체: APPROACH 중에는 3D 원근감으로 크기 변화
            val ballRadius = when (currentPhase) {
                BallPhase.APPROACH -> 3f + ballProgress * 15f  // 멀리=3px, 가까이=18px
                BallPhase.DIVERGE  -> 10f                       // 발산 시 고정 크기
            }
            val glowRadius = ballRadius * 1.9f

            canvas.drawCircle(bx, by, glowRadius, ballGlowPaint) // 글로우 (뒤)
            canvas.drawCircle(bx, by, ballRadius,  ballPaint)     // 본체 (앞)
        }

        // ── 수비수 그리기 ──────────────────────────────────────
        drawPlayer(canvas, playerX, playerY)
    }

    /**
     * 스트라이크존 사각형을 그린다.
     *
     * 세 구역(낮음/중간/높음)으로 나뉘며,
     * 현재 pitchY 에 해당하는 구역을 파란 마커로 강조한다.
     *
     * @param cx   뷰 가로 중앙 X
     */
    private fun drawStrikeZone(canvas: Canvas, cx: Float) {
        val szW      = width  * 0.11f  // 스트라이크존 반폭
        val szBottom = height * 0.70f  // 낮은 공 도착 Y
        val szTop    = height * 0.45f  // 높은 공 도착 Y
        val szHeight = szBottom - szTop
        val third    = szHeight / 3f   // 각 구역 높이

        val left  = cx - szW
        val right = cx + szW

        // 배경 채우기
        canvas.drawRect(left, szTop, right, szBottom, szFillPaint)

        // 구역 구분선 (낮음/중간, 중간/높음 경계)
        canvas.drawLine(left, szTop + third,       right, szTop + third,       szDivPaint)
        canvas.drawLine(left, szTop + third * 2f,  right, szTop + third * 2f,  szDivPaint)

        // 구역 라벨
        szLabelPaint.color = Color.argb(100, 200, 220, 255)
        canvas.drawText("높",  right + 14f, szTop    + third / 2f + 6f, szLabelPaint)
        canvas.drawText("중",  right + 14f, szTop + third + third / 2f + 6f, szLabelPaint)
        canvas.drawText("낮",  right + 14f, szTop + third * 2f + third / 2f + 6f, szLabelPaint)

        // 테두리
        canvas.drawRect(left, szTop, right, szBottom, szBorderPaint)

        // 현재 pitchY 에 해당하는 목표 마커 (파란 원)
        val markerY = szBottom - pitchY * szHeight
        // pitchY=0(낮음)→szBottom, pitchY=1(높음)→szTop
        canvas.drawCircle(cx, markerY, 7f, szMarkerPaint)

        // "투구" 화살표 표시: 마커 왼쪽에 ▶ 형태
        val arrPaint = szMarkerPaint
        val arrowPath = Path().apply {
            moveTo(left - 6f, markerY - 7f)
            lineTo(left - 6f, markerY + 7f)
            lineTo(left + 2f, markerY)
            close()
        }
        canvas.drawPath(arrowPath, arrPaint)
    }

    // ─────────────────────────────────────────
    // FOV 삼각형 (수비수 시야각)
    // ─────────────────────────────────────────
    private fun drawFov(canvas: Canvas, cx: Float, cy: Float) {
        val fovLength = height * 0.28f
        val fovAngle  = 28f
        val baseDeg   = -headingDeg - 90f
        val baseRad   = Math.toRadians(baseDeg.toDouble())
        val leftRad   = Math.toRadians((baseDeg - fovAngle).toDouble())
        val rightRad  = Math.toRadians((baseDeg + fovAngle).toDouble())

        val centerX = (cx + fovLength * cos(baseRad)).toFloat()
        val centerY = (cy + fovLength * sin(baseRad)).toFloat()
        val leftX   = (cx + fovLength * cos(leftRad)).toFloat()
        val leftY   = (cy + fovLength * sin(leftRad)).toFloat()
        val rightX  = (cx + fovLength * cos(rightRad)).toFloat()
        val rightY  = (cy + fovLength * sin(rightRad)).toFloat()

        val path = Path().apply {
            moveTo(cx, cy)
            lineTo(leftX, leftY)
            val rect = RectF(cx - fovLength, cy - fovLength, cx + fovLength, cy + fovLength)
            arcTo(rect, baseDeg - fovAngle, fovAngle * 2, false)
            lineTo(cx, cy); close()
        }
        canvas.drawPath(path, fovFillPaint)
        canvas.drawLine(cx, cy, leftX,   leftY,   fovLinePaint)
        canvas.drawLine(cx, cy, rightX,  rightY,  fovLinePaint)
        canvas.drawLine(cx, cy, centerX, centerY, fovCenterPaint)
    }

    // ─────────────────────────────────────────
    // 수디수(사람) 그리기
    // ─────────────────────────────────────────
    private fun drawPlayer(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy - 18f, 10f, playerBodyPaint)  // 머리
        canvas.drawCircle(cx, cy - 18f, 10f, playerStrokePaint)

        val bodyPath = Path().apply {
            moveTo(cx, cy - 8f); lineTo(cx - 10f, cy + 16f)
            lineTo(cx + 10f, cy + 16f); close()
        }
        canvas.drawPath(bodyPath, playerBodyPaint)   // 몸통 채우기
        canvas.drawPath(bodyPath, playerStrokePaint) // 몸통 외곽선

        canvas.drawLine(cx - 10f, cy, cx - 20f, cy + 10f, playerStrokePaint) // 왼팔
        canvas.drawLine(cx + 10f, cy, cx + 20f, cy + 10f, playerStrokePaint) // 오른팔
        canvas.drawLine(cx - 5f, cy + 16f, cx - 10f, cy + 30f, playerStrokePaint) // 왼다리
        canvas.drawLine(cx + 5f, cy + 16f, cx + 10f, cy + 30f, playerStrokePaint) // 오른다리
    }

    // ─────────────────────────────────────────
    // 마름모(베이스) 그리기
    // ─────────────────────────────────────────
    private fun drawDiamond(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
        val path = Path().apply {
            moveTo(cx, cy - size); lineTo(cx + size, cy)
            lineTo(cx, cy + size); lineTo(cx - size, cy); close()
        }
        canvas.drawPath(path, paint)
    }

    // ─────────────────────────────────────────
    // 외부 API
    // ─────────────────────────────────────────

    /**
     * 공 위치 갱신.
     *
     * @param panX     수평 위치 -1(좌)~+1(우)
     * @param progress 진행률 0(시작)~1(도착/완료)
     * @param phase    BallPhase.APPROACH 또는 DIVERGE
     * @param pitchY   공 수직 위치 0(낮음)~1(높음). 기본값 0.5(중간)
     *                 BaseRunReactionActivity 에서 게임 시작 시 랜덤 선택하여 전달
     */
    fun updateBall(panX: Float, progress: Float, phase: BallPhase, pitchY: Float = 0.5f) {
        ballPanX     = panX
        ballProgress = progress
        currentPhase = phase
        this.pitchY  = pitchY
        invalidate()
    }

    /** 수비수 FOV 방향 갱신. orientationListener 에서 runOnUiThread 를 통해 호출. */
    fun updateHeading(deg: Float) {
        headingDeg = deg
        invalidate()
    }

    /** 스트라이크존 표시 여부 설정. SwingTestActivity 에서 false 로 호출해 숨긴다. */
    fun setShowStrikeZone(show: Boolean) {
        showStrikeZone = show
        invalidate()
    }

    /** 전체 상태 초기화. startGame() / onBasePressed() 에서 호출. */
    fun reset() {
        ballPanX     = 0f
        ballProgress = 0f
        currentPhase = BallPhase.APPROACH
        pitchY       = 0.5f
        trail.clear()
        invalidate()
    }
}
