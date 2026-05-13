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
    val isTrainingMode: Boolean = false,
    val targetPitches: Int = 0,
    val currentPitchNum: Int = 0,
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

    private var isTrainingMode = false
    private var targetPitches = 0
    private var currentPitchNum = 0
    private var trainingDifficulty = 0.5f

    private var targetBallCount = 5
    private var sessionActive   = false
    private val catchTimes      = mutableListOf<Long>()

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

        val headingRad = Math.toRadians(-audioEngine.currentHeadingDeg.toDouble())
        val cosH = cos(headingRad).toFloat()
        val sinH = sin(headingRad).toFloat()

        val worldDx = joystickDx * cosH - joystickDz * sinH
        val worldDz = joystickDx * sinH + joystickDz * cosH

        defX = (defX + worldDx * MOVE_SPEED).coerceIn(-40f, 40f)
        defZ = (defZ + worldDz * MOVE_SPEED).coerceIn(10f, 50f)

        if (phase == GamePhase.LAUNCHED || phase == GamePhase.LANDED) {
            val relX = ballSim.currentPos.x - defX
            val relZ = ballSim.currentPos.z - defZ
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

    fun startSession(ballCount: Int, difficulty: Float = 0.5f) {
        if (_state.value.phase != GamePhase.IDLE) return
        targetBallCount = ballCount
        sessionActive   = true
        score           = 0
        attempts        = 0
        catchTimes.clear()
        _state.value = _state.value.copy(
            score = 0, totalAttempts = 0, targetBallCount = ballCount)
        launchRandom(difficulty)
    }

    fun speakControllerWarning() {
        speak("컨트롤러가 연결되지 않았습니다. 블루투스 컨트롤러를 연결한 후 시작 버튼을 눌러주세요")
    }

    // ✅ 팀원 코드: 컨트롤러 연결 안내
    fun speakControllerConnected() {
        speak("컨트롤러가 연결되었습니다")
    }

    fun speakControllerDisconnected() {
        speak("컨트롤러 연결이 끊어졌습니다")
    }

    fun speakBallCount(count: Int) {
        speak("현재 훈련 횟수 ${toNativeKorean(count)}개")
    }

    fun speakDifficulty(position: Int) {
        val name = when (position) {
            0 -> "쉬움"
            1 -> "보통"
            2 -> "어려움"
            else -> "보통"
        }
        speak("현재 난이도는 $name")
    }

    fun isSessionComplete() = sessionActive && attempts >= targetBallCount

    // ✅ 팀원 코드: 오디오 재초기화
    fun reinitAudio() {
        val wasBeeping = _state.value.phase == GamePhase.LAUNCHED || _state.value.phase == GamePhase.LANDED
        audioEngine.reinitAudioTrack()
        if (wasBeeping) audioEngine.startBeep()
    }

    fun fullReset() {
        audioEngine.stopBeep()
        sessionActive = false
        isTrainingMode = false
        score = 0; attempts = 0; catchTimes.clear()
        defX = 0f; defZ = 25f
        _state.value = GameState()
    }

    fun launchBall(direction: HitDirection, difficulty: Float = 0.5f) {
        val phase = _state.value.phase
        if (phase != GamePhase.IDLE && phase != GamePhase.RESULT) return
        ballSim.launch(direction, difficulty)
        audioEngine.startBeep()
        launchTime = System.currentTimeMillis()
        _state.value = _state.value.copy(
            phase = GamePhase.LAUNCHED, lastResult = null, catchTimeMs = null)
        speak("공이 날아옵니다! 비프음 방향으로 이동하세요")
    }

    fun launchRandom(difficulty: Float = 0.5f) {
        val phase = _state.value.phase
        if (phase != GamePhase.IDLE && phase != GamePhase.RESULT) return
        if (isSessionComplete()) return
        ballSim.launchRandom(difficulty)
        audioEngine.startBeep()
        launchTime = System.currentTimeMillis()
        _state.value = _state.value.copy(
            phase = GamePhase.LAUNCHED, lastResult = null, catchTimeMs = null)
    }

    fun startTraining(totalPitches: Int, difficulty: Float) {
        val phase = _state.value.phase
        if (phase != GamePhase.IDLE && phase != GamePhase.TRAINING_COMPLETE) return
        isTrainingMode = true
        targetPitches = totalPitches
        currentPitchNum = 0
        trainingDifficulty = difficulty
        score = 0
        attempts = 0
        defX = 0f
        defZ = 25f
        _state.value = GameState(
            isTrainingMode = true,
            targetPitches = totalPitches,
            defenderX = defX,
            defenderZ = defZ
        )
        launchNextBall()
    }

    private fun launchNextBall() {
        currentPitchNum++
        val dir = HitDirection.entries.random()
        ballSim.launch(dir, trainingDifficulty)
        audioEngine.startBeep()
        launchTime = System.currentTimeMillis()
        _state.value = _state.value.copy(
            phase = GamePhase.LAUNCHED,
            lastResult = null,
            catchTimeMs = null,
            currentPitchNum = currentPitchNum
        )
        speak("${currentPitchNum}번째 공이 날아옵니다")
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
            val timeSec = "%.1f".format(elapsedMs / 1000.0)
            _state.value = _state.value.copy(
                phase = GamePhase.CAUGHT,
                score = score,
                totalAttempts = attempts,
                lastResult = CatchResult.SUCCESS,
                catchTimeMs = elapsedMs,
                debugInfo = "✅ 포구 성공! (${timeSec}s)"
            )
            speak("포구 성공! ${msToKoreanTime(elapsedMs)}!")
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
            if (isTrainingMode) {
                if (currentPitchNum < targetPitches) {
                    launchNextBall()
                } else {
                    isTrainingMode = false
                    _state.value = _state.value.copy(
                        phase = GamePhase.TRAINING_COMPLETE,
                        debugInfo = "🏆 훈련 완료! ${score}/${targetPitches} 성공"
                    )
                    speak("훈련 완료! ${targetPitches}번 중 ${score}번 성공했습니다")
                }
            } else if (isSessionComplete()) {
                speakSessionSummary()
                sessionActive = false
                resetToIdle(sessionDone = true)
            } else {
                resetToIdle()
            }
        }
    }

    fun resetToIdle(sessionDone: Boolean = false) {
        isTrainingMode = false
        currentPitchNum = 0
        targetPitches = 0
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
            score = 0
            attempts = 0
            _state.value = GameState(defenderX = defX, defenderZ = defZ)
        }
    }

    private fun speakSessionSummary() {
        val avgMs      = if (catchTimes.isNotEmpty()) catchTimes.average().toLong() else null
        val bestMs     = catchTimes.minOrNull()
        val worstMs    = catchTimes.maxOrNull()
        val successRate = if (targetBallCount > 0) score.toFloat() / targetBallCount * 100f else 0f

        val msg = buildString {
            append("훈련 종료. ")
            append("총 ${targetBallCount}회 중 ${score}회 포구 성공. ")
            if (avgMs != null) append("평균 포구 시간 ${msToKoreanTime(avgMs)}.")
            else append("포구 성공이 없었습니다.")
        }
        speak(msg)

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
            "생성일시"       to com.google.firebase.Timestamp.now(),
            "목표횟수"       to targetBallCount,
            "성공횟수"       to score,
            "실패횟수"       to (targetBallCount - score),
            "성공률"         to successRate,
            "평균반응속도"   to (avgMs ?: -1L),
            "최단반응속도"   to (bestMs ?: -1L),
            "최장반응속도"   to (worstMs ?: -1L),
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

        val count = pref.getInt("count", 0)
        editor.putInt("count", minOf(count + 1, 10))
        editor.putFloat("sum_success_rate", pref.getFloat("sum_success_rate", 0f) + successRate)
        editor.putFloat("sum_reaction",     pref.getFloat("sum_reaction",     0f) + (avgMs?.toFloat() ?: 0f))
        editor.putFloat("sum_success",      pref.getFloat("sum_success",      0f) + score.toFloat())

        val totalCount = pref.getInt("total_count", 0) + 1
        editor.putInt("total_count", totalCount)
        editor.putFloat("total_sum_success_rate", pref.getFloat("total_sum_success_rate", 0f) + successRate)
        editor.putFloat("total_sum_reaction",     pref.getFloat("total_sum_reaction",     0f) + (avgMs?.toFloat() ?: 0f))
        editor.putFloat("total_sum_success",      pref.getFloat("total_sum_success",      0f) + score.toFloat())

        editor.apply()
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

    // ✅ 팀원 코드: TTS 중지
    fun stopSpeak() {
        tts?.stop()
    }

    private fun toKorean(n: Int): String {
        if (n == 0) return "영"
        val u = arrayOf("", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
        val t = arrayOf("", "십", "이십", "삼십", "사십", "오십", "육십", "칠십", "팔십", "구십")
        return if (n < 10) u[n]
        else t[n / 10] + (if (n % 10 != 0) u[n % 10] else "")
    }

    // ✅ 팀원 코드: 순우리말 읽기
    private fun toNativeKorean(n: Int): String {
        val ones = arrayOf("", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉")
        return when {
            n in 1..9   -> ones[n]
            n == 10     -> "열"
            n in 11..19 -> "열${ones[n % 10]}"
            n == 20     -> "스물"
            else        -> "$n"
        }
    }

    private fun msToKoreanTime(ms: Long): String {
        val formatted = "%.1f".format(ms / 1000.0)
        val parts = formatted.split(".")
        val secs  = parts[0].toInt()
        val dec   = parts[1].toInt()
        return if (dec == 0) "${toKorean(secs)}초"
        else "${toKorean(secs)}점${toKorean(dec)}초"
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