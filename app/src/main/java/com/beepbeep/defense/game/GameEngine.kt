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
        private const val TICK_MS = 8L           // 16ms → 8ms: 좌표 업데이트 2배 빠름 (125Hz)
        private const val MOVE_SPEED = 0.1f      // 0.2f → 0.1f: 실제 이동속도 동일 (0.1×125Hz = 12.5/s)
        // BT LE Audio 레이턴시 보상
        const val BT_AUDIO_PREDICT_MS  = 40f     // 공 궤적 예측 (ms)
        private const val BT_DEF_PREDICT_TICKS = 12  // 수비수 이동 추가 예측 (6틱 × 8ms = 48ms)
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
    private val catchTimes       = mutableListOf<Long>()
    private val perCatchRecords  = mutableListOf<HashMap<String, Any?>>()

    var isTutorialMode: Boolean = false

    @Volatile var joystickDx = 0f
    @Volatile var joystickDz = 0f

    /**
     * 조이스틱 입력 시 즉시 호출 — tick() 16ms 대기 없이 오디오 위치를 바로 갱신
     * 수비수가 다음 tick에서 이동할 위치를 미리 예측해 relX/relZ 계산
     */
    fun setJoystick(dx: Float, dz: Float) {
        joystickDx = dx
        joystickDz = dz
        val phase = _state.value.phase
        if (phase != GamePhase.LAUNCHED && phase != GamePhase.LANDED) return
        val headingRad = Math.toRadians(-audioEngine.currentHeadingDeg.toDouble())
        val cosH = cos(headingRad).toFloat()
        val sinH = sin(headingRad).toFloat()
        val worldDx = dx * cosH - dz * sinH
        val worldDz = dx * sinH + dz * cosH
        // 수비수: 1틱 이동(nextDef) + BT_DEF_PREDICT_TICKS 추가 예측
        val nextDefX = (defX + worldDx * MOVE_SPEED).coerceIn(-40f, 40f)
        val nextDefZ = (defZ + worldDz * MOVE_SPEED).coerceIn(10f, 50f)
        val audioDefX = (nextDefX + worldDx * MOVE_SPEED * BT_DEF_PREDICT_TICKS).coerceIn(-40f, 40f)
        val audioDefZ = (nextDefZ + worldDz * MOVE_SPEED * BT_DEF_PREDICT_TICKS).coerceIn(10f, 50f)
        val audioPos = ballSim.positionAhead(BT_AUDIO_PREDICT_MS)
        audioEngine.updateBallPosition(audioPos.x - audioDefX, audioPos.y, audioPos.z - audioDefZ)
    }

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

        // 오디오용 수비수 예측 위치: 현재 이동 후 + BT 레이턴시만큼 추가 이동 예측
        val audioDefX = (defX + worldDx * MOVE_SPEED * BT_DEF_PREDICT_TICKS).coerceIn(-40f, 40f)
        val audioDefZ = (defZ + worldDz * MOVE_SPEED * BT_DEF_PREDICT_TICKS).coerceIn(10f, 50f)

        if (phase == GamePhase.LAUNCHED) {
            val stillFlying = ballSim.update(deltaMs)
            val audioPos = ballSim.positionAhead(BT_AUDIO_PREDICT_MS)
            audioEngine.updateBallPosition(x = audioPos.x - audioDefX, y = audioPos.y, z = audioPos.z - audioDefZ)

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
            // 착지 후: 공은 고정, 수비수 이동만 예측
            audioEngine.updateBallPosition(
                x = ballSim.currentPos.x - audioDefX,
                y = ballSim.currentPos.y,
                z = ballSim.currentPos.z - audioDefZ)
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
        perCatchRecords.clear()
        _state.value = _state.value.copy(
            score = 0, totalAttempts = 0, targetBallCount = ballCount, phase = GamePhase.IDLE)
        if (ttsReady) {
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?)  { gameScope.launch { launchRandom(difficulty) } }
                override fun onError(utteranceId: String?) { gameScope.launch { launchRandom(difficulty) } }
            })
            tts?.speak("훈련 시작", TextToSpeech.QUEUE_FLUSH, null, "training_start")
        } else {
            launchRandom(difficulty)
        }
    }

    fun launchNextInSession(difficulty: Float = 0.5f) {
        if (!sessionActive) return
        if (isSessionComplete()) return
        if (_state.value.phase != GamePhase.IDLE) return
        ballSim.launchRandom(difficulty)
        // startBeep() 전에 시작 위치 설정 → 첫 청크부터 올바른 HRTF 위치 적용
        val startPos = ballSim.positionAhead(BT_AUDIO_PREDICT_MS)
        audioEngine.updateBallPosition(startPos.x - defX, startPos.y, startPos.z - defZ)
        audioEngine.startBeep()
        launchTime = System.currentTimeMillis()
        _state.value = _state.value.copy(
            phase = GamePhase.LAUNCHED, lastResult = null, catchTimeMs = null)
    }

    fun launchRandom(difficulty: Float = 0.5f) {
        if (isSessionComplete()) return
        ballSim.launchRandom(difficulty)
        // startBeep() 전에 시작 위치 설정 → 첫 청크부터 올바른 HRTF 위치 적용
        val startPos = ballSim.positionAhead(BT_AUDIO_PREDICT_MS)
        audioEngine.updateBallPosition(startPos.x - defX, startPos.y, startPos.z - defZ)
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
            perCatchRecords.add(hashMapOf(
                "회차"     to attempts,
                "결과"     to "성공",
                "반응속도" to elapsedMs
            ))
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
            perCatchRecords.add(hashMapOf(
                "회차"     to attempts,
                "결과"     to "실패",
                "반응속도" to -1L
            ))
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
                // 포구 결과 TTS가 끝날 때까지 대기 후 훈련 결과 출력
                while (tts?.isSpeaking == true) delay(100)
                withContext(Dispatchers.Main) {
                    speakSessionSummary()
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
            score = 0; attempts = 0; catchTimes.clear(); perCatchRecords.clear()
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

    fun forceStopSession(silent: Boolean = false) {
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
                perCatchRecords.add(hashMapOf(
                    "회차"     to attempts,
                    "결과"     to "성공",
                    "반응속도" to elapsedMs
                ))
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
                perCatchRecords.add(hashMapOf(
                    "회차"     to attempts,
                    "결과"     to "실패",
                    "반응속도" to -1L
                ))
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

        if (!silent && (phase == GamePhase.LAUNCHED || phase == GamePhase.LANDED)) {
            // 공 판정 결과를 잠깐 보여준 후 결과창
            gameScope.launch {
                delay(500L)
                withContext(Dispatchers.Main) {
                    speakSessionSummary()   // 메인 스레드 호출 → runOnUiThread 동기 실행 → 다이얼로그 즉시 표시
                    resetToIdle(sessionDone = true)
                }
            }
        } else {
            // silent 모드이거나 대기 중 → 즉시 처리
            speakSessionSummary(silent)
            resetToIdle(sessionDone = true)
        }
    }

    fun isSessionComplete() = sessionActive && attempts >= targetBallCount

    private fun speakSessionSummary(silent: Boolean = false) {
        val avgMs      = if (catchTimes.isNotEmpty()) catchTimes.average().toLong() else null
        val bestMs     = catchTimes.minOrNull()
        val worstMs    = catchTimes.maxOrNull()
        val successRate = if (targetBallCount > 0) score.toFloat() / targetBallCount * 100f else 0f

        val sb = StringBuilder("훈련 종료. 총 ${targetBallCount}회 중 ${score}회 성공.")
        sb.append(" 성공률 ${"%.0f".format(successRate)}퍼센트.")
        if (avgMs  != null) sb.append(" 평균 ${msToKoreanTime(avgMs)}.")
        if (bestMs != null) sb.append(" 최단 시간 ${msToKoreanTime(bestMs)}.")
        if (worstMs != null) sb.append(" 최장 시간 ${msToKoreanTime(worstMs)}.")
        if (!silent) speak(sb.toString())

        if (!isTutorialMode) {
            uploadToFirebase(avgMs, bestMs, worstMs, successRate)
            saveToSharedPreferences(avgMs, successRate)
        }

        if (!silent) onSessionComplete?.invoke(
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
            "생성일시" to com.google.firebase.Timestamp.now(),
            "목표횟수" to targetBallCount,
            "종합결과" to hashMapOf(
                "성공횟수"     to score,
                "실패횟수"     to (targetBallCount - score),
                "성공률"       to successRate,
                "평균반응속도" to (avgMs ?: -1L),
                "최단반응속도" to (bestMs ?: -1L),
                "최장반응속도" to (worstMs ?: -1L)
            )
        )

        // resetToIdle()이 비동기 콜백보다 먼저 perCatchRecords를 clear할 수 있으므로 미리 복사
        val catchSnapshot = ArrayList(perCatchRecords)

        val sessionRef = db.collection("users")
            .document(userId)
            .collection("수비훈련기록")
            .document(sessionId)

        sessionRef.set(sessionData)
            .addOnSuccessListener {
                android.util.Log.d("Firebase", "수비 훈련 기록 업로드 성공")
                catchSnapshot.forEachIndexed { index, record ->
                    sessionRef.collection("포구별기록")
                        .document("${index + 1}번포구")
                        .set(record)
                }
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
        val fb = if (dz > 0f) "뒤"   else "앞"

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
        // 시각적 예측값 → FieldView 콜백 (오디오는 updateAudioHeading으로 별도 처리)
        onHeadingChanged?.invoke(deg)
    }

    /** 오디오 전용 빠른 필터 헤딩 — updateHeadingDirectly의 시각 예측값과 분리 */
    fun updateAudioHeading(deg: Float) {
        audioEngine.updateHeading(deg)
    }

    /** 센서에서 받은 pitch/roll(라디안)을 오디오 엔진에 전달 */
    fun updateAudioPitchRoll(pitchRad: Float, rollRad: Float) {
        audioEngine.updatePitchRoll(pitchRad, rollRad)
    }

    /** 현재 pitch/roll을 중립 기준으로 캡처 — 세션 시작(헤딩 리셋) 시 호출 */
    fun captureAudioNeutralOrientation() {
        audioEngine.captureNeutralPitchRoll()
    }

    fun fullReset() {
        audioEngine.stopBeep()
        sessionActive = false
        isTutorialMode = false
        score = 0; attempts = 0; catchTimes.clear(); perCatchRecords.clear()
        defX = 0f; defZ = 25f
        _state.value = GameState()
    }

    fun speakControllerWarning()      { speak("컨트롤러를 연결해주세요") }
    fun speakControllerConnected()    { speak("컨트롤러가 연결되었습니다") }
    fun speakControllerDisconnected() { speak("컨트롤러연결이 끊겼습니다.") }
    fun speakBallCount(count: Int)    { speak("${count}회") }
    fun speakDifficulty(pos: Int) {
        val name = when (pos) { 0 -> "쉬움"; 1 -> "보통"; 2 -> "어려움"; else -> "보통" }
        speak("난이도 $name")
    }
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