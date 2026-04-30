package com.beepbeep.defense.batting
// 이 파일이 속한 패키지. SwingTestActivity 와 같은 패키지이므로 import 없이 함께 사용됨

// ── Android 프레임워크 import ──────────────────────────────────────────────
import android.content.Context      // View 생성자에 필요한 앱 컨텍스트
import android.graphics.*           // Canvas, Paint, Color, Typeface 등 그래픽 클래스 전체
import android.util.AttributeSet   // XML 레이아웃에서 커스텀 뷰 생성 시 전달되는 속성 집합
import android.view.View            // 커스텀 뷰의 기반 클래스

/**
 * SwingBallView
 *
 * SwingTestActivity 에 배치되는 커스텀 뷰.
 * 공이 화면 상단에서 타격존(하단)을 향해 내려오는 모습과,
 * 타격 이펙트 애니메이션을 그린다.
 *
 * 외부에서 호출하는 함수:
 *   setProgress(p)       - 공의 수직 위치(0.0~1.0) 설정
 *   setHitZoneActive(b)  - 타격존 활성/비활성 표시
 *   showHitEffect()      - 타격 성공 플래시 애니메이션 시작
 *   reset()              - 초기 상태로 복원
 *
 * → res/layout/activity_swing_test.xml 의 <com.beepbeep.defense.batting.SwingBallView> 태그로 삽입됨
 */
class SwingBallView @JvmOverloads constructor(
// @JvmOverloads: XML inflate 시 Context + AttributeSet 생성자 자동 생성

    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var progress: Float = 0f
    // 공의 수직 진행률. 0.0=상단(공이 멀리 있음), 1.0=타격존 도달.
    // SwingTestActivity.gameJob 에서 setProgress() 로 갱신됨

    private var hitZoneActive: Boolean = false
    // 타격 윈도우(600ms) 활성 여부. true 시 타격존 테두리가 노란색으로 강조됨.
    // SwingTestActivity 에서 setHitZoneActive() 로 설정됨

    // 타격 이펙트 변수
    private var hitEffectAlpha: Int = 0
    // 타격 이펙트 원의 투명도 (0=완전 투명, 220=거의 불투명).
    // showHitEffect() 로 초기값 설정, onDraw() 마다 6씩 감소하여 서서히 사라짐

    private var hitEffectRadius: Float = 0f
    // 타격 이펙트 원의 반지름 (픽셀).
    // showHitEffect() 에서 20f 로 초기화, onDraw() 마다 4f 씩 커져 팽창 효과

    // 공 크기 변수
    private val ballMinRadius = 8f
    // progress=0 (멀리 있을 때) 의 공 반지름 (픽셀). 작아서 멀리 있는 느낌

    private val ballMaxRadius = 18f
    // progress=1 (타격존 도달 시) 의 공 반지름 (픽셀). 커져서 가까이 온 느낌

    private val trail = ArrayDeque<Pair<Float, Float>>()
    // 공의 이전 위치 궤적 큐. 최대 30개 유지.
    // BallTrackView 와 동일한 방식으로 꼬리 효과를 구현

    // ── Paint 객체 ────────────────────────────────────────
    private val bgPaint = Paint().apply {
    // 배경 전체 채우기용

        color = Color.argb(255, 10, 20, 35)
        // #0A1423 어두운 네이비. BallTrackView 와 동일한 배경색
    }

    private val centerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    // 중앙 세로 가이드 선

        color = Color.argb(25, 255, 255, 255)
        // alpha=25 의 매우 연한 흰색
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    // 공 본체 (노란 원)

        color = Color.argb(230, 251, 191, 36)
        // #FBBF24 앰버색, alpha=230
        style = Paint.Style.FILL
    }

    private val ballGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    // 공 글로우 효과 (더 큰 반투명 원)

        color = Color.argb(55, 251, 191, 36)
        // 같은 색, alpha=55 (연한 빛)
        style = Paint.Style.FILL
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    // 궤적 점 Paint. color 는 onDraw() 에서 동적으로 설정

        style = Paint.Style.FILL
    }

    private val hitZoneFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    // 타격존 사각형 내부 채우기. color 는 onDraw() 에서 hitZoneActive 에 따라 결정

        style = Paint.Style.FILL
    }

    private val hitZoneStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    // 타격존 사각형 테두리. color 는 onDraw() 에서 hitZoneActive 에 따라 결정

        style = Paint.Style.STROKE
        strokeWidth = 3f     // 3픽셀 굵기 테두리
    }

    private val hitZoneLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    // 타격존 내부 "SWING ZONE" 텍스트

        textSize = 20f
        textAlign = Paint.Align.CENTER      // 가로 중앙 정렬
        typeface = Typeface.DEFAULT_BOLD    // 굵은 글씨
        // color 는 onDraw() 에서 hitZoneActive 에 따라 결정
    }

    private val hitEffectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    // 타격 이펙트 원. color 는 onDraw() 에서 hitEffectAlpha 로 동적 설정

        style = Paint.Style.FILL
    }

    // ─────────────────────────────────────────────────────
    // onDraw: invalidate() 호출 시마다 실행되어 뷰를 다시 그림
    // ─────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()    // 뷰 픽셀 너비
        val h = height.toFloat()   // 뷰 픽셀 높이
        val cx = w / 2f            // 가로 중앙 X

        // 배경 채우기
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 중앙 수직 가이드 선
        canvas.drawLine(cx, 0f, cx, h, centerLinePaint)

        // ── 타격존 영역 (뷰 하단 78%~93%) ────────────────────────
        val zoneTop    = h * 0.78f                        // 타격존 상단 Y
        val zoneBottom = h * 0.93f                        // 타격존 하단 Y
        val zoneCenterY = (zoneTop + zoneBottom) / 2f     // 타격존 수직 중앙 Y

        val zoneColor = if (hitZoneActive) 0xFFFBBF24.toInt() else 0xFF475569.toInt()
        // hitZoneActive=true → 노란색(#FBBF24), false → 회색(#475569)

        hitZoneFillPaint.color = Color.argb(
            if (hitZoneActive) 50 else 15,
            // 활성 시 alpha=50 (더 밝은 배경), 비활성 시 alpha=15 (연한 배경)
            Color.red(zoneColor), Color.green(zoneColor), Color.blue(zoneColor)
            // zoneColor 에서 RGB 값 추출하여 alpha 만 조정
        )
        hitZoneStrokePaint.color = zoneColor   // 테두리색을 zoneColor 로 설정
        hitZoneLabelPaint.color  = zoneColor   // 텍스트색을 zoneColor 로 설정
        hitZoneLabelPaint.alpha  = if (hitZoneActive) 230 else 100
        // 활성 시 텍스트 더 진하게(230), 비활성 시 흐리게(100)

        canvas.drawRoundRect(w * 0.08f, zoneTop, w * 0.92f, zoneBottom, 12f, 12f, hitZoneFillPaint)
        // 타격존 내부 채우기. 좌우 8% 여백, 모서리 반지름 12f (둥근 사각형)

        canvas.drawRoundRect(w * 0.08f, zoneTop, w * 0.92f, zoneBottom, 12f, 12f, hitZoneStrokePaint)
        // 타격존 테두리 그리기

        canvas.drawText("SWING ZONE", cx, zoneCenterY + 7f, hitZoneLabelPaint)
        // 타격존 중앙에 "SWING ZONE" 텍스트. +7f: 텍스트 기준선 보정

        // ── 공 위치 계산 ─────────────────────────────────────────
        val ballTopY    = h * 0.06f    // 공이 시작하는 Y (뷰 상단 6%)
        val ballTargetY = zoneCenterY  // 공이 도달하는 Y (타격존 중앙)
        val by = ballTopY + progress * (ballTargetY - ballTopY)
        // 선형 보간: progress=0 → ballTopY, progress=1 → ballTargetY
        val bx = cx
        // 공은 항상 가로 중앙

        // ── 공 궤적 그리기 ────────────────────────────────────────
        if (progress > 0f) {
        // progress=0 이면 공이 아직 출발 전 → 궤적 없음

            trail.add(Pair(bx, by))
            // 현재 위치를 궤적 큐에 추가

            if (trail.size > 30) trail.removeFirst()
            // 최대 30개 유지 (BallTrackView 는 35개, SwingBallView 는 30개)

            trail.forEachIndexed { i, (tx, ty) ->
                val a = (i.toFloat() / trail.size * 130).toInt()
                // 오래된 점일수록 alpha 낮음 (0~130 범위)

                val r = 2f + i.toFloat() / trail.size * 5f
                // 오래된 점일수록 작음 (2~7f)

                trailPaint.color = Color.argb(a, 251, 191, 36)
                // 앰버색, 동적 alpha 설정

                canvas.drawCircle(tx, ty, r, trailPaint)
                // 각 궤적 점 그리기
            }

            // ── 공 글로우 (progress 에 따라 크기 변함) ─────────────
            val glowR = ballMinRadius * 2 + progress * (ballMaxRadius * 2 - ballMinRadius * 2)
            // progress=0 → glowR=16f, progress=1 → glowR=36f (선형 보간)
            canvas.drawCircle(bx, by, glowR, ballGlowPaint)
            // 글로우 원 그리기 (본체보다 먼저 그려 뒤에 깔림)

            // ── 공 본체 (progress 에 따라 크기 변함) ───────────────
            val ballR = ballMinRadius + progress * (ballMaxRadius - ballMinRadius)
            // progress=0 → ballR=8f(작음/멀리), progress=1 → ballR=18f(큼/가까이)
            canvas.drawCircle(bx, by, ballR, ballPaint)
            // 공 본체 원 그리기
        }

        // ── 타격 이펙트 (showHitEffect() 호출 후 재생됨) ─────────
        if (hitEffectAlpha > 0) {
        // hitEffectAlpha > 0 인 동안 이펙트 애니메이션 진행

            hitEffectPaint.color = Color.argb(hitEffectAlpha, 255, 220, 50)
            // 밝은 노란색 원, alpha=hitEffectAlpha (점점 투명해짐)

            canvas.drawCircle(cx, ballTargetY, hitEffectRadius, hitEffectPaint)
            // 타격 위치(타격존 중앙)에 원 그리기

            hitEffectAlpha  = (hitEffectAlpha - 6).coerceAtLeast(0)
            // 매 프레임 alpha 6씩 감소. coerceAtLeast(0): 음수 방지

            hitEffectRadius += 4f
            // 매 프레임 반지름 4f 씩 증가 → 원이 팽창하며 사라짐

            if (hitEffectAlpha > 0) invalidate()
            // 아직 이펙트가 남아 있으면 다음 프레임 재그리기 요청
            // alpha=0 이 되면 invalidate() 안 함 → 애니메이션 자동 종료
        }
    }

    // ─────────────────────────────────────────────────────
    // 외부 API (SwingTestActivity 에서 호출)
    // ─────────────────────────────────────────────────────
    fun setProgress(p: Float) {
    // 공의 수직 위치 갱신. SwingTestActivity.gameJob 에서 withContext(Dispatchers.Main) 으로 호출

        progress = p
        invalidate()   // 공 위치가 바뀌었으므로 재그리기 요청
    }

    fun setHitZoneActive(active: Boolean) {
    // 타격존 활성/비활성 설정.
    // SwingTestActivity.gameJob 에서 타격 윈도우 시작/종료 시 호출

        hitZoneActive = active
        invalidate()   // 타격존 색상이 바뀌므로 재그리기
    }

    fun showHitEffect() {
    // 타격 성공 시 이펙트 애니메이션 시작.
    // SwingTestActivity.showResult() 에서 swingDetected=true 일 때 호출

        hitEffectAlpha  = 220    // 초기 alpha 값 (거의 불투명)
        hitEffectRadius = 20f    // 초기 반지름
        invalidate()             // onDraw() 를 바로 호출하여 첫 프레임 그리기
        // 이후 onDraw() 내에서 alpha>0 이면 계속 invalidate() 를 재귀 호출 → 애니메이션 루프
    }

    fun reset() {
    // 전체 초기화. SwingTestActivity.startGame() 에서 새 라운드 시작 전 호출

        progress        = 0f     // 공 위치 초기화 (공 숨김)
        hitZoneActive   = false  // 타격존 비활성
        hitEffectAlpha  = 0      // 이펙트 초기화
        hitEffectRadius = 0f
        trail.clear()            // 궤적 큐 비우기
        invalidate()             // 빈 상태로 재그리기
    }
}
