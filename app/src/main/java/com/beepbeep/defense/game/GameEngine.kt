package com.beepbeep.defense.game

import android.content.Context
import android.speech.tts.TextToSpeech
import com.beepbeep.defense.audio.SpatialAudioEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

data class GameState(
    val phase: GamePhase = GamePhase.IDLE,
    val ball: BallPosition = BallPosition(0f, 0f, 0f, 0f),
    val defenderX: Float = 0f,
    val defenderZ: Float = 25f,
    val score: Int = 0,
    val totalAttempts: Int = 0,
    val lastResult: CatchResult? = null,
    val catchTimeMs: Long? = null,
    val debugInfo: String = "시작 버튼을 누르세요"
)

enum class GamePhase { IDLE, LAUNCHED, LANDED, CAUGHT, RESULT }
enum class CatchResult { SUCCESS, MISS }

class GameEngine(private val context: Context) {

    companion object {
        private const val TICK_MS = 16L
        private const val MOVE_SPEED = 0.2f
    }

    private val audioEngine = SpatialAudioEngine(context)

    // ✅ onHeadingChanged 설정 시 audioEngine.updateHeading도 같이 호출
    var onHeadingChanged: ((Float) -> Unit)? = null
        set(value) {
            field = value
            audioEngine.onHeadingChanged = if (value != null) {
                { deg: Float ->
                    audioEngine.updateHeading(deg)
                    value(deg)
                }
            } else null
        }

    private val ballSim = BallSimulator()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val gameScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> = _state

    private var defX = 0f
    private var defZ = 25f
    private var score = 0
    private var attempts = 0
    private var launchTime = 0L

    @Volatile var joystickDx = 0f
    @Volatile var joystickDz = 0f

    fun init() {
        audioEngine.init()
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                ttsReady = true
            }
        }
        startGameLoop()
    }

    private fun startGameLoop() {
        gameScope.launch {
            var lastTime = System.currentTimeMillis()
            while (isActive) {
                val now = System.currentTimeMillis()
                val delta = (now - lastTime).toFloat()
                lastTime = now
                tick(delta)
                delay(TICK_MS)
            }
        }
    }

    private fun tick(deltaMs: Float) {
        val phase = _state.value.phase
        if (phase != GamePhase.LAUNCHED && phase != GamePhase.LANDED) return

        // ✅ 헤드트래킹 방향 기준으로 조이스틱 이동 변환
        // 버즈/Spatializer 센서 컨벤션: CCW(왼쪽)=양수, CW(오른쪽)=음수 → 부호 반전 필요
        val headingRad = Math.toRadians(-audioEngine.currentHeadingDeg.toDouble())
        val cosH = cos(headingRad).toFloat()
        val sinH = sin(headingRad).toFloat()

        // 조이스틱 입력을 heading 방향으로 회전 변환
        val worldDx = joystickDx * cosH - joystickDz * sinH
        val worldDz = joystickDx * sinH + joystickDz * cosH

        defX = (defX + worldDx * MOVE_SPEED).coerceIn(-40f, 40f)
        defZ = (defZ + worldDz * MOVE_SPEED).coerceIn(10f, 50f)

        if (phase == GamePhase.LAUNCHED || phase == GamePhase.LANDED) {
            // Resonance Audio 좌표계: X=오른쪽, Y=위, -Z=앞(투수 방향)
            val relX = ballSim.currentPos.x - defX
            val relZ = ballSim.currentPos.z - defZ           // 투수 방향 = 음수 (-Z) ✓
            audioEngine.updateBallPosition(x = relX, y = ballSim.currentPos.y, z = relZ)
        }

        if (phase == GamePhase.LAUNCHED) {
            val stillFlying = ballSim.update(deltaMs)
            val relX = ballSim.currentPos.x - defX
            val relZ = ballSim.currentPos.z - defZ
            android.util.Log.d("Audio", "relX=$relX relZ=$relZ y=${ballSim.currentPos.y}")
            audioEngine.updateBallPosition(x = relX, y = ballSim.currentPos.y, z = relZ)

            val debug = buildDebug()
            if (!stillFlying) {
                _state.value = _state.value.copy(
                    phase = GamePhase.LANDED,
                    ball = ballSim.currentPos,
                    defenderX = defX,
                    defenderZ = defZ,
                    debugInfo = "$debug\n💨 착지!"
                )
                speak("공이 착지했습니다")
            } else {
                _state.value = _state.value.copy(
                    ball = ballSim.currentPos,
                    defenderX = defX,
                    defenderZ = defZ,
                    debugInfo = debug
                )
            }
        } else {
            _state.value = _state.value.copy(
                defenderX = defX,
                defenderZ = defZ,
                debugInfo = buildDebug()
            )
        }
    }

    fun launchBall(direction: HitDirection, difficulty: Float = 0.5f) {
        val phase = _state.value.phase
        if (phase != GamePhase.IDLE && phase != GamePhase.RESULT) return
        ballSim.launch(direction, difficulty)
        audioEngine.startBeep()
        _state.value = _state.value.copy(phase = GamePhase.LAUNCHED, lastResult = null)
        speak("공이 날아옵니다! 비프음 방향으로 이동하세요")
    }

    fun launchRandom(difficulty: Float = 0.5f) {
        val phase = _state.value.phase
        if (phase != GamePhase.IDLE && phase != GamePhase.RESULT) return
        ballSim.launchRandom(difficulty)
        audioEngine.startBeep()
        launchTime = System.currentTimeMillis()
        _state.value = _state.value.copy(phase = GamePhase.LAUNCHED, lastResult = null, catchTimeMs = null)
        speak("공이 날아옵니다! 비프음 방향으로 이동하세요")
    }

    fun onCatchPressed() {
        val phase = _state.value.phase
        if (phase != GamePhase.LAUNCHED && phase != GamePhase.LANDED) return

        attempts++
        val caught = ballSim.checkCatch(defX, defZ)
        val elapsedMs = System.currentTimeMillis() - launchTime
        audioEngine.stopBeep()

        if (caught) {
            score++
            val timeSec = "%.1f".format(elapsedMs / 1000.0)
            _state.value = _state.value.copy(
                phase = GamePhase.CAUGHT,
                score = score,
                totalAttempts = attempts,
                lastResult = CatchResult.SUCCESS,
                catchTimeMs = elapsedMs,
                debugInfo = "✅ 포구 성공! (${timeSec}s)"
            )
            speak("포구 성공! ${timeSec}초!")
        } else {
            val dist = ballSim.distanceToDefender(defX, defZ)
            _state.value = _state.value.copy(
                phase = GamePhase.RESULT,
                score = score,
                totalAttempts = attempts,
                lastResult = CatchResult.MISS,
                catchTimeMs = null,
                debugInfo = "❌ 포구 실패 (거리: ${"%.1f".format(dist)}m)"
            )
            speak("포구 실패. 공까지 ${"%.0f".format(dist)}미터 차이였습니다")
        }

        gameScope.launch {
            delay(2000)
            audioEngine.init()
            resetToIdle()
        }
    }

    fun resetToIdle() {
        defX = 0f
        defZ = 25f
        _state.value = _state.value.copy(
            phase = GamePhase.IDLE,
            defenderX = defX,
            defenderZ = defZ,
            debugInfo = "시작 버튼을 누르세요"
        )
    }

    private fun buildDebug(): String {
        val b = ballSim.currentPos
        val dist = ballSim.distanceToDefender(defX, defZ)
        val heading = audioEngine.currentHeadingDeg
        return "공(${b.x.f}, ${b.z.f}) 수비수(${defX.f}, ${defZ.f}) 거리:${dist.f}m\n헤딩:${heading.f}°"
    }

    private val Float.f get() = "%.1f".format(this)

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }
    fun updateHeadingDirectly(deg: Float) {
        audioEngine.updateHeading(deg)
        onHeadingChanged?.invoke(deg)
    }
    fun release() {
        gameScope.cancel()
        audioEngine.release()
        tts?.stop()
        tts?.shutdown()
    }
}