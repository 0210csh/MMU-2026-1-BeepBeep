package com.beepbeep.defense.game

import kotlin.math.*
import kotlin.random.Random

data class BallPosition(
    val x: Float,
    val y: Float,
    val z: Float,
    val progress: Float
)

enum class HitDirection { LEFT, CENTER, RIGHT }

class BallSimulator {

    companion object {
        const val CATCH_RADIUS = 3.0f
    }

    private var targetX = 0f
    private var targetZ = 0f
    private var maxHeight = 0f
    private var totalTime = 0f
    private var elapsed = 0f

    var isFlying = false
        private set

    var currentPos = BallPosition(0f, 0f, 0f, 0f)
        private set

    fun launch(direction: HitDirection, difficulty: Float = 0.5f) {
        elapsed = 0f
        isFlying = true

        val (xMin, xMax) = when (direction) {
            HitDirection.LEFT   -> Pair(-38f, -10f)
            HitDirection.CENTER -> Pair(-12f, 12f)
            HitDirection.RIGHT  -> Pair(10f, 38f)
        }

        targetX = Random.nextFloat() * (xMax - xMin) + xMin
        targetZ = Random.nextFloat() * 20f * difficulty + 20f
        maxHeight = 8f - difficulty * 4f
        totalTime = 5000f - difficulty * 2500f
    }

    // ✅ 팀원 코드 추가: 랜덤 방향 발사
    fun launchRandom(difficulty: Float = 0.5f) {
        val dir = HitDirection.entries.random()
        launch(dir, difficulty)
    }

    fun update(deltaMs: Float): Boolean {
        if (!isFlying) return false
        elapsed += deltaMs
        val progress = (elapsed / totalTime).coerceIn(0f, 1f)

        val x = targetX * progress
        val z = targetZ * progress
        val y = if (progress < 1f) sin(progress * PI.toFloat()) * maxHeight else 0f

        currentPos = BallPosition(x, y, z, progress)

        if (progress >= 1f) {
            isFlying = false
            return false
        }
        return true
    }

    fun checkCatch(defenderX: Float, defenderZ: Float): Boolean {
        val dist = sqrt(
            (currentPos.x - defenderX).pow(2) +
                    (currentPos.z - defenderZ).pow(2)
        )
        return dist <= CATCH_RADIUS
    }

    fun distanceToDefender(defenderX: Float, defenderZ: Float): Float {
        return sqrt(
            (currentPos.x - defenderX).pow(2) +
                    (currentPos.z - defenderZ).pow(2)
        )
    }

    /**
     * 현재 시각으로부터 deltaMs 뒤의 공 위치 반환 (BT 레이턴시 보상용)
     * update()를 호출하지 않으므로 시뮬레이션 상태에 영향 없음
     */
    fun positionAhead(deltaMs: Float): BallPosition {
        if (!isFlying) return currentPos
        val futureProgress = ((elapsed + deltaMs) / totalTime).coerceIn(0f, 1f)
        val x = targetX * futureProgress
        val z = targetZ * futureProgress
        val y = if (futureProgress < 1f) sin(futureProgress * PI.toFloat()) * maxHeight else 0f
        return BallPosition(x, y, z, futureProgress)
    }

    private fun Float.pow(exp: Int) = toDouble().pow(exp).toFloat()
}