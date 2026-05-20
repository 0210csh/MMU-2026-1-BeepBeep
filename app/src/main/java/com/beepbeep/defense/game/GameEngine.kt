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
    val debugInfo: String = "시작 버튼을 누르세요",
    val targetBallCount: Int = 0
)

enum class GamePhase { IDLE, LAUNCHED, LANDED, CAUGHT, RESULT, TRAINING_COMPLETE }
enum class CatchResult { SUCCESS, MISS }

data class SessionResult(
    val target    : Int,
    val success   : Int,
    val miss      : Int,
    val avgTimeMs : Long?,
    val bestTimeMs: Long?,
    val worstTimeMs: Long?
)

class GameEngine(private val context: Context) {

    companion object {
        private const val TICK_MS = 16L
        private const val MOVE_SPEED = 0.2f
    }

    var onSessionComplete: ((SessionResult) -> Unit)? = null
    var onTtsReady: (() -> Unit)? = null
    private val audioEngine = SpatialAudioEngine(context)

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

    private var targetBallCount = 5
    var sessionActive   = false
        private set
    private val catchTimes      = mutableListOf<Long>()

    @Volatile var joystickDx = 0f
    @Volatile var joystickDz = 0f

    fun init() {
        audioEngine.init()
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                ttsReady = true
                onTtsReady?.invoke()
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

        val headingRad = Math.toRadians(-audioEngine.currentHeadingDeg.toDouble())
        val cosH = cos(headingRad).toFloat()
        val sinH = sin(headingRad).toFloat()

        val worldDx = joystickDx * cosH - joystickDz * sinH
        val worldDz = joystickDx * sinH + joystickDz * cosH

        defX = (defX + worldDx * MOVE_SPEED).coerceIn(-40f, 40f)
        defZ = (defZ + worldDz * MOVE_SPEED).coerceIn(10f, 50f)

        if (phase == GamePhase.LAUNCHED) {
            val stillFlying = ballSim.update(deltaMs)
            val relX = ballSim.currentPos.x - defX
            val relZ = ballSim.currentPos.z - defZ
            audioEngine.updateBallPosition(x = relX, y = ballSim.currentPos.y, z = relZ)

            if (!stillFlying) {
                _state.value = _state.value.copy(
                    phase = GamePhase.LANDED,
                    ball = ballSim.currentPos,
                    defenderX = defX,
                    defenderZ = defZ,
                    debugInfo = buildDebug() + "\n💨 착지!"
                )
            } else {
                _state.value = _state.value.copy(
                    ball = ballSim.currentPos,
                    defenderX = defX,
                    defenderZ = defZ,
                    debugInfo = buildDebug()
                )
            }
        } else if (phase == GamePhase.LANDED) {
            val relX = ballSim.currentPos.x - defX
            val relZ = ballSim.currentPos.z - defZ
            audioEngine.updateBallPosition(x = relX, y = ballSim.currentPos.y, z = relZ)
            _state.value = _state.value.copy(
                defenderX = defX,
                defenderZ = defZ,
                debugInfo = buildDebug()
            )
        }
    }

    fun startSession(ballCount: Int) {
        if (_state.value.phase != GamePhase.IDLE) return
        targetBallCount = ballCount
        sessionActive   = true
        score           = 0
        attempts        = 0
        catchTimes.clear()
        _state.value = _state.value.copy(
            score = 0, totalAttempts = 0, targetBallCount = ballCount, phase = GamePhase.IDLE)
        if (ttsReady) {
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?)  { gameScope.launch { launchRandom() } }
                override fun onError(utteranceId: String?) { gameScope.launch { launchRandom() } }
            })
            tts?.speak("훈련 시작", TextToSpeech.QUEUE_FLUSH, null, "training_start")
        } else {
            launchRandom()
        }
    }

    fun launchNextInSession() {
        if (!sessionActive) return
        if (isSessionComplete()) return
        if (_state.value.phase != GamePhase.IDLE) return
        ballSim.launchRandom(0.5f)
        audioEngine.startBeep()
        launchTime = System.currentTimeMillis()
        _state.value = _state.value.copy(
            phase = GamePhase.LAUNCHED, lastResult = null, catchTimeMs = null)
    }

    fun launchRandom() {
        if (isSessionComplete()) return
        ballSim.launchRandom(0.5f)
        audioEngine.startBeep()
        launchTime = System.currentTimeMillis()
        _state.value = _state.value.copy(
            phase = GamePhase.LAUNCHED, lastResult = null, catchTimeMs = null)
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
            catchTimes.add(elapsedMs)
            _state.value = _state.value.copy(
                phase = GamePhase.CAUGHT,
                score = score,
                totalAttempts = attempts,
                lastResult = CatchResult.SUCCESS,
                catchTimeMs = elapsedMs,
                debugInfo = "✅ 포구 성공! (${"%.1f".format(elapsedMs / 1000.0)}s)"
            )
            speak("포구 성공! ${msToKoreanTime(elapsedMs)}!")
        } else {
            val dist = ballSim.distanceToDefender(defX, defZ)
            val dir  = getBallDirection(ballSim.currentPos.x, ballSim.currentPos.z, defX, defZ)
            _state.value = _state.value.copy(
                phase = GamePhase.RESULT,
                score = score,
                totalAttempts = attempts,
                lastResult = CatchResult.MISS,
                catchTimeMs = null,
                debugInfo = "❌ 포구 실패 (${"%.1f".format(dist)}m, $dir)"
            )
            speak("포구 실패. $dir 방향으로 ${"%.0f".format(dist)}미터 차이였습니다")
        }

        gameScope.launch {
            delay(2000)
            if (isSessionComplete()) {
                withContext(Dispatchers.Main) {
                    speakSessionSummary()   // 메인 스레드 호출 → runOnUiThread 동기 실행 → 다이얼로그 즉시 표시
                    sessionActive = false
                    resetToIdle(sessionDone = true)
                }
            } else {
                withContext(Dispatchers.Main) {
                    resetToIdle(sessionDone = false)
                }
            }
        }
    }

    fun resetToIdle(sessionDone: Boolean = false) {
        defX = 0f; defZ = 25f
        if (sessionDone) {
            score = 0; attempts = 0; catchTimes.clear()
            _state.value = _state.value.copy(
                phase = GamePhase.IDLE,
                score = 0, totalAttempts = 0, targetBallCount = 0,
                defenderX = defX, defenderZ = defZ,
                debugInfo = "시작 버튼을 누르세요"
            )
        } else {
            _state.value = _state.value.copy(
                phase = GamePhase.IDLE,
                score = score,
                totalAttempts = attempts,
                defenderX = defX,
                defenderZ = defZ,
                debugInfo = "${attempts}/${targetBallCount}판 완료. 시작 버튼을 누르세요"
            )
        }
    }

    fun forceStopSession() {
        val phase = _state.value.phase
        if (!sessionActive) return

        // 케이스 1: 공이 날고 있거나 착지한 상태 → 즉시 포구 판정
        if (phase == GamePhase.LAUNCHED || phase == GamePhase.LANDED) {
            attempts++
            val caught    = ballSim.checkCatch(defX, defZ)
            val elapsedMs = System.currentTimeMillis() - launchTime
            audioEngine.stopBeep()

            if (caught) {
                score++
                catchTimes.add(elapsedMs)
                _state.value = _state.value.copy(
                    phase         = GamePhase.CAUGHT,
                    score         = score,
                    totalAttempts = attempts,
                    lastResult    = CatchResult.SUCCESS,
                    catchTimeMs   = elapsedMs,
                    debugInfo     = "✅ 포구 성공! (조기종료)"
                )
            } else {
                val dist = ballSim.distanceToDefender(defX, defZ)
                val dir  = getBallDirection(ballSim.currentPos.x, ballSim.currentPos.z, defX, defZ)
                _state.value = _state.value.copy(
                    phase         = GamePhase.RESULT,
                    score         = score,
                    totalAttempts = attempts,
                    lastResult    = CatchResult.MISS,
                    catchTimeMs   = null,
                    debugInfo     = "❌ 포구 실패 (조기종료, ${"%.1f".format(dist)}m, $dir)"
                )
            }
        }

        // 실제 완료 횟수 기준으로 통계 계산
        targetBallCount = attempts
        sessionActive   = false

        if (phase == GamePhase.LAUNCHED || phase == GamePhase.LANDED) {
            // 공 판정 결과를 잠깐 보여준 후 결과창
            gameScope.launch {
                delay(500L)
                withContext(Dispatchers.Main) {
                    speakSessionSummary()   // 메인 스레드 호출 → runOnUiThread 동기 실행 → 다이얼로그 즉시 표시
                    resetToIdle(sessionDone = true)
                }
            }
        } else {
            // 대기 중 → 이미 메인 스레드이므로 즉시 결과창
            speakSessionSummary()
            resetToIdle(sessionDone = true)
        }
    }

    fun isSessionComplete() = sessionActive && attempts >= targetBallCount

    private fun speakSessionSummary() {
        val avgMs      = if (catchTimes.isNotEmpty()) catchTimes.average().toLong() else null
        val bestMs     = catchTimes.minOrNull()
        val worstMs    = catchTimes.maxOrNull()
        val successRate = if (targetBallCount > 0) score.toFloat() / targetBallCount * 100f else 0f

        val sb = StringBuilder("훈련 종료. 총 ${targetBallCount}회 중 ${score}회 성공.")
        sb.append(" 성공률 ${"%.0f".format(successRate)}퍼센트.")
        if (avgMs  != null) sb.append(" 평균 ${msToKoreanTime(avgMs)}.")
        if (bestMs != null) sb.append(" 최단 시간 ${msToKoreanTime(bestMs)}.")
        if (worstMs != null) sb.append(" 최장 시간 ${msToKoreanTime(worstMs)}.")
        speak(sb.toString())

        uploadToFirebase(avgMs, bestMs, worstMs, successRate)
        saveToSharedPreferences(avgMs, successRate)

        onSessionComplete?.invoke(
            SessionResult(
                target      = targetBallCount,
                success     = score,
                miss        = targetBallCount - score,
                avgTimeMs   = avgMs,
                bestTimeMs  = bestMs,
                worstTimeMs = worstMs
            )
        )
    }

    private fun uploadToFirebase(
        avgMs: Long?, bestMs: Long?, worstMs: Long?, successRate: Float
    ) {
        val userId = context.getSharedPreferences("UserInfo", Context.MODE_PRIVATE)
            .getString("id", "anonymous") ?: "anonymous"

        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val sessionId = System.currentTimeMillis().toString()

        val sessionData = hashMapOf(
            "생성일시"         to com.google.firebase.Timestamp.now(),
            "목표횟수"         to targetBallCount,
            "성공횟수"         to score,
            "실패횟수"         to (targetBallCount - score),
            "성공률"           to successRate,
            "평균반응속도"     to (avgMs ?: -1L),
            "최단반응속도"     to (bestMs ?: -1L),
            "최장반응속도"     to (worstMs ?: -1L),
            "개인반응속도목록" to catchTimes
        )

        db.collection("users")
            .document(userId)
            .collection("수비훈련기록")
            .document(sessionId)
            .set(sessionData)
            .addOnSuccessListener {
                android.util.Log.d("Firebase", "수비 훈련 기록 업로드 성공")
            }
            .addOnFailureListener { e ->
                android.util.Log.e("Firebase", "업로드 실패: ${e.message}")
            }
    }

    private fun saveToSharedPreferences(avgMs: Long?, successRate: Float) {
        val userId = context.getSharedPreferences("UserInfo", Context.MODE_PRIVATE)
            .getString("id", "anonymous") ?: "anonymous"

        val pref   = context.getSharedPreferences("DefenseStats_$userId", Context.MODE_PRIVATE)
        val editor = pref.edit()

        val failCount = (targetBallCount - score).toFloat()

        // 최근 10판
        val count = pref.getInt("count", 0)
        editor.putInt("count", minOf(count + 1, 10))
        editor.putFloat("sum_success_rate", pref.getFloat("sum_success_rate", 0f) + successRate)
        editor.putFloat("sum_reaction",     pref.getFloat("sum_reaction",     0f) + (avgMs?.toFloat() ?: 0f))
        editor.putFloat("sum_success",      pref.getFloat("sum_success",      0f) + score.toFloat())
        editor.putFloat("sum_fail",         pref.getFloat("sum_fail",         0f) + failCount)

        // 전체 판수
        val totalCount = pref.getInt("total_count", 0) + 1
        editor.putInt("total_count", totalCount)
        editor.putFloat("total_sum_success_rate", pref.getFloat("total_sum_success_rate", 0f) + successRate)
        editor.putFloat("total_sum_reaction",     pref.getFloat("total_sum_reaction",     0f) + (avgMs?.toFloat() ?: 0f))
        editor.putFloat("total_sum_success",      pref.getFloat("total_sum_success",      0f) + score.toFloat())
        editor.putFloat("total_sum_fail",         pref.getFloat("total_sum_fail",         0f) + failCount)

        editor.apply()
    }

    private fun buildDebug(): String {
        val b = ballSim.currentPos
        val dist = ballSim.distanceToDefender(defX, defZ)
        val heading = audioEngine.currentHeadingDeg
        return "공(${b.x.f}, ${b.z.f}) 수비수(${defX.f}, ${defZ.f}) 거리:${dist.f}m\n헤딩:${heading.f}°"
    }

    private val Float.f get() = "%.1f".format(this)

    /** 수비수 기준으로 공이 어느 방향에 있는지 반환 (앞 = 외야 방향, 뒤 = 홈 방향) */
    private fun getBallDirection(ballX: Float, ballZ: Float, defX: Float, defZ: Float): String {
        val dx    = ballX - defX
        val dz    = ballZ - defZ
        val absDx = kotlin.math.abs(dx)
        val absDz = kotlin.math.abs(dz)

        val lr = if (dx < 0f) "왼쪽" else "오른쪽"
        val fb = if (dz > 0f) "앞"   else "뒤"

        return when {
            absDx < 1f && absDz < 1f  -> "정면"        // 거의 정면
            absDx < absDz * 0.4f      -> fb             // 앞/뒤가 지배적
            absDz < absDx * 0.4f      -> lr             // 좌/우가 지배적
            else                       -> "$lr $fb"      // 대각선
        }
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    fun stopSpeak() { tts?.stop() }

    private fun msToKoreanTime(ms: Long): String {
        val formatted = "%.1f".format(ms / 1000.0)
        val parts = formatted.split(".")
        return if (parts.size == 2) "${parts[0]}점${parts[1]}초" else "${formatted}초"
    }

    fun updateHeadingDirectly(deg: Float) {
        audioEngine.updateHeading(deg)
        onHeadingChanged?.invoke(deg)
    }

    fun speakControllerWarning()      { speak("컨트롤러를 연결해주세요") }
    fun speakControllerConnected()    { speak("컨트롤러 연결됨") }
    fun speakControllerDisconnected() { speak("컨트롤러 연결 끊김") }
    fun speakBallCount(count: Int)    { speak("${count}회") }
    fun reinitAudio() {
        val wasBeeping = _state.value.phase == GamePhase.LAUNCHED || _state.value.phase == GamePhase.LANDED
        audioEngine.reinitAudioTrack()
        if (wasBeeping) audioEngine.startBeep()
    }

    fun release() {
        gameScope.cancel()
        audioEngine.release()
        tts?.stop()
        tts?.shutdown()
    }
}