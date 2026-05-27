package com.beepbeep.defense

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.beepbeep.defense.databinding.ActivityMainBinding
import com.beepbeep.defense.game.DefenseTtsManager
import com.beepbeep.defense.game.DefenseTutorialManager
import com.beepbeep.defense.game.GameEngine
import com.beepbeep.defense.game.GamePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    // ── 관리자 여부 ──────────────────────────────────────
    private var isAdmin = false

    // ── 관리자 전용 바인딩 (비관리자 레이아웃에는 없음) ──
    private var binding: ActivityMainBinding? = null

    private lateinit var gameEngine: GameEngine
    private lateinit var sensorManager: SensorManager
    private lateinit var inputManager: InputManager
    private var resultDialog: android.app.AlertDialog? = null

    // ── 튜토리얼 ─────────────────────────────────────────
    private lateinit var defenseTtsManager: DefenseTtsManager
    private lateinit var defenseTutorialManager: DefenseTutorialManager
    private val tutorialScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── 네트워크 콜백 (오프라인 → 복구 자동 동기화) ──────────
    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            tutorialScope.launch(Dispatchers.IO) {
                if (PendingUploadManager.hasPendingDefense(this@MainActivity)) {
                    PendingUploadManager.syncDefense(this@MainActivity)
                }
            }
        }
    }

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            val dev = InputDevice.getDevice(deviceId) ?: return
            if ((dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
                if (isAdmin) runOnUiThread { binding?.switchFakeController?.isChecked = true }
                if (defenseTutorialManager.isRunning) {
                    defenseTutorialManager.notifyControllerConnected()
                } else {
                    gameEngine.speakControllerConnected()
                    if (!defenseTutorialManager.isTutorialDone()) {
                        defenseTutorialManager.notifyControllerConnected()
                    }
                }
                updateSimpleBleStatus()
            }
        }
        override fun onInputDeviceChanged(deviceId: Int) {}
        override fun onInputDeviceRemoved(deviceId: Int) {
            if (!isRealGamepadConnected()) {
                if (isAdmin) runOnUiThread { binding?.switchFakeController?.isChecked = false }
                gameEngine.speakControllerDisconnected()
                updateSimpleBleStatus()
            }
        }
    }

    private val rotMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var baseAzimuth: Float? = null

    // ── 헤딩 필터 (시각 예측용) ──────────────────────────
    private var smoothedHeading      = 0f
    private var audioSmoothedHeading = 0f   // 오디오 전용 헤딩 (시각 예측값과 분리)
    private var prevAmplified   = 0f
    private var headingVelocity = 0f
    private var signedVelocity  = 0f
    private var audioSignedVel  = 0f   // 오디오 전용 속도 (더 빠른 감쇠, 오버슈트 방지)

    private var ballCount = 5
    private var wasInCatchRange = false   // 튜토리얼 4단계 캐치 범위 상태 추적
    private val HEADING_SENSITIVITY = 1.0f

    private val orientationListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            SensorManager.getOrientation(rotMatrix, orientation)
            val azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()

            if (baseAzimuth == null) baseAzimuth = azimuthDeg

            var rel = azimuthDeg - (baseAzimuth ?: azimuthDeg)
            while (rel > 180f)  rel -= 360f
            while (rel < -180f) rel += 360f

            val amplified        = (-rel * HEADING_SENSITIVITY).coerceIn(-180f, 180f)
            val instantVel       = abs(amplified - prevAmplified)
            val instantSignedVel = amplified - prevAmplified
            headingVelocity = headingVelocity * 0.4f + instantVel       * 0.6f
            signedVelocity  = signedVelocity  * 0.3f + instantSignedVel * 0.7f
            audioSignedVel  = audioSignedVel  * 0.15f + instantSignedVel * 0.85f  // 빠른 감쇠
            prevAmplified   = amplified

            val factor = when {
                headingVelocity > 1.2f -> 1.00f
                headingVelocity > 0.5f -> 0.80f
                headingVelocity > 0.1f -> 0.30f
                else                   -> 0.08f
            }
            smoothedHeading += (amplified - smoothedHeading) * factor

            val predictionScale = (headingVelocity / 1.2f).coerceIn(0f, 1f)
            val predicted = (smoothedHeading + signedVelocity * 12f * predictionScale).coerceIn(-180f, 180f)
            gameEngine.updateHeadingDirectly(predicted)

            // 오디오 헤딩: BT 레이턴시 보상 예측 (~40ms)
            // audioSignedVel: 빠른 감쇠(0.15) → 멈추면 즉시 수렴, ±20° 캡 → 오버슈트 방지
            val audioHeading = (amplified + audioSignedVel.coerceIn(-20f, 20f)).coerceIn(-180f, 180f)
            gameEngine.updateAudioHeading(audioHeading)

            // 3축 HRTF: pitch/roll 매 프레임 전달 (라디안 그대로)
            gameEngine.updateAudioPitchRoll(orientation[1], orientation[2])
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 100)
        }

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        // ── 관리자 여부 체크 (로그인 시 저장된 캐시 사용) ──
        isAdmin = getSharedPreferences("AdminCache", MODE_PRIVATE).getBoolean("isAdmin", false)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        if (isAdmin) {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding!!.root)
        } else {
            setContentView(R.layout.activity_main_simple)
        }

        gameEngine   = GameEngine(this)
        inputManager = getSystemService(InputManager::class.java)
        gameEngine.init()

        sensorManager = getSystemService(SensorManager::class.java)

        if (isAdmin) {
            gameEngine.onHeadingChanged = { deg ->
                runOnUiThread { binding?.fieldView?.updateHeading(deg) }
            }
            // TTS 준비 전까지 시작 버튼 잠금
            binding?.btnStart?.isEnabled = false
        }
        // TTS 준비 완료 콜백 — isAdmin 외부에서 설정해야 일반 사용자에도 적용됨
        gameEngine.onTtsReady = {
            runOnUiThread {
                if (isAdmin) binding?.btnStart?.isEnabled = true
                // TTS 준비 완료 시점에 컨트롤러 상태 안내 (onResume은 TTS 미준비 상태라 여기서 처리)
                if (isGamepadConnected()) {
                    if (defenseTutorialManager.isRunning) {
                        defenseTutorialManager.notifyControllerConnected()
                    } else if (defenseTutorialManager.isTutorialDone()) {
                        // 튜토리얼 완료 상태 → gameEngine이 직접 안내
                        gameEngine.speakControllerConnected()
                    }
                    // 튜토리얼 미완료 상태 → 튜토리얼 내부에서 안내하므로 생략
                } else {
                    gameEngine.speakControllerWarning()
                }
            }
        }

        gameEngine.onSessionComplete = { result ->
            if (!defenseTutorialManager.isRunning) {
                if (isAdmin) runOnUiThread { showSessionResultDialog(result) }
            } else {
                defenseTutorialManager.notifyStep3Done()
            }
        }

        // ── 튜토리얼 매니저 초기화 ───────────────────────
        defenseTtsManager = DefenseTtsManager(this)
        defenseTtsManager.init()

        defenseTutorialManager = DefenseTutorialManager(
            context               = this,
            ttsManager            = defenseTtsManager,
            scope                 = tutorialScope,
            isControllerConnected = { isGamepadConnected() },
            onStartPractice       = {
                gameEngine.isTutorialMode = true
                defenseTutorialManager.targetBallCount = ballCount
                gameEngine.startSession(ballCount, 0.5f)
            },
            onTutorialFinished    = {
                gameEngine.isTutorialMode = false
            }
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (gameEngine.sessionActive && !defenseTutorialManager.isRunning) {
                    gameEngine.forceStopSession(silent = true)
                    finish()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        if (isAdmin) setupButtons()
        observeGameState()

        if (intent.getBooleanExtra("start_tutorial", false)) {
            tutorialScope.launch {
                kotlinx.coroutines.delay(1_000L)
                // 로컬 우선, 없으면 Firebase 조회 — 완료된 사용자는 튜토리얼 skip
                val done = defenseTutorialManager.syncTutorialDoneFromFirebase()
                if (!done) {
                    defenseTutorialManager.startTutorial()
                }
            }
        }

        // ── 네트워크 콜백 등록 ──────────────────────────────
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
    }

    // ─────────────────────────────────────────────────────
    // 심플 UI 상태 업데이트 (일반 사용자)
    // ─────────────────────────────────────────────────────
    private fun updateSimpleStatus(text: String, bgColor: Int = 0xFF0A0A0A.toInt()) {}

    private fun updateSimpleBleStatus() {
        if (isAdmin) return
        val connected = isRealGamepadConnected()
        val battery   = getGamepadBattery()
        runOnUiThread {
            val color = if (connected) 0xFF4ADE80.toInt() else 0xFFF87171.toInt()
            val label = if (connected) "컨트롤러 연결됨" else "컨트롤러 미연결"
            findViewById<TextView>(R.id.tvSimpleBleIcon)?.setTextColor(color)
            findViewById<TextView>(R.id.tvSimpleBleStatus)?.text = label
            findViewById<TextView>(R.id.tvSimpleBleStatus)?.setTextColor(color)
            val tvBattery = findViewById<TextView>(R.id.tvSimpleBattery)
            if (battery >= 0) {
                tvBattery?.text = "배터리 $battery%"
                tvBattery?.visibility = View.VISIBLE
            } else {
                tvBattery?.visibility = View.GONE
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getGamepadBattery(): Int {
        return try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return -1
            adapter.bondedDevices.firstNotNullOfOrNull { device ->
                val level = device.javaClass.getMethod("getBatteryLevel").invoke(device) as? Int ?: -1
                if (level >= 0) level else null
            } ?: -1
        } catch (_: Exception) { -1 }
    }

    private fun registerOrientationSensor() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: return
        sensorManager.registerListener(orientationListener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
    }

    private fun isRealGamepadConnected(): Boolean =
        InputDevice.getDeviceIds().any { id ->
            val dev = InputDevice.getDevice(id) ?: return@any false
            (dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        }

    private fun isGamepadConnected(): Boolean =
        if (isAdmin) (binding?.switchFakeController?.isChecked ?: false) || isRealGamepadConnected()
        else isRealGamepadConnected()

    private fun setupButtons() {
        binding?.btnStart?.setOnClickListener {
            if (defenseTutorialManager.isRunning) return@setOnClickListener
            if (!isGamepadConnected()) {
                gameEngine.speakControllerWarning()
                return@setOnClickListener
            }
            val state = gameEngine.state.value
            if (state.phase == GamePhase.IDLE) {
                when {
                    gameEngine.isSessionComplete() || state.totalAttempts == 0 -> {
                        resetHeading()
                        gameEngine.startSession(getBallCount(), 0.5f)
                    }
                    else -> gameEngine.launchNextInSession(0.5f)
                }
            }
        }

        binding?.btnCatch?.setOnClickListener {
            if (defenseTutorialManager.isRunning && defenseTutorialManager.currentStep == 4) {
                gameEngine.onCatchPressed()
            } else if (!defenseTutorialManager.isRunning) {
                gameEngine.onCatchPressed()
            }
        }

        binding?.btnEarlyStop?.setOnClickListener { gameEngine.forceStopSession() }

        binding?.btnBallCountDown?.setOnClickListener { adjustBallCount(-1) }
        binding?.btnBallCountUp?.setOnClickListener   { adjustBallCount(+1) }

        binding?.joystickView?.onMove = { dx, dz ->
            gameEngine.setJoystick(dx, -dz)
        }
    }

    private fun adjustBallCount(delta: Int) {
        if (isAdmin && binding?.btnBallCountDown?.isEnabled == false) return
        ballCount = (ballCount + delta).coerceIn(1, 20)
        if (isAdmin) binding?.tvBallCount?.text = ballCount.toString()
        gameEngine.speakBallCount(ballCount)
    }

    private fun getBallCount(): Int = ballCount

    /** 버튼 누르는 순간의 방향을 정면(0°)으로 즉시 설정 */
    private fun resetHeading() {
        baseAzimuth          = Math.toDegrees(orientation[0].toDouble()).toFloat()
        smoothedHeading      = 0f
        audioSmoothedHeading = 0f
        prevAmplified        = 0f
        headingVelocity      = 0f
        signedVelocity       = 0f
        audioSignedVel       = 0f
        // 현재 pitch/roll을 중립으로 캡처 → 모자 착용 각도를 기준으로 상대 회전만 HRTF에 전달
        gameEngine.captureAudioNeutralOrientation()
    }

    private fun observeGameState() {
        lifecycleScope.launch {
            gameEngine.state.collectLatest { state ->
                if (isAdmin) {
                    // ── 관리자: 기존 UI 업데이트 ──
                    binding?.fieldView?.update(
                        ball = state.ball, defX = state.defenderX,
                        defZ = state.defenderZ, isFlying = state.phase == GamePhase.LAUNCHED
                    )
                    binding?.tvScore?.text = if (state.targetBallCount > 0)
                        "성공: ${state.score} / ${state.totalAttempts} (목표 ${state.targetBallCount}회)"
                    else
                        "성공: ${state.score} / ${state.totalAttempts}"

                    binding?.tvPhase?.text = when (state.phase) {
                        GamePhase.IDLE     -> "🎯 시작 버튼을 누르세요"
                        GamePhase.LAUNCHED -> "🔊 비프음 방향으로 이동!"
                        GamePhase.LANDED   -> "📍 착지! CATCH 누르세요!"
                        GamePhase.CAUGHT   -> {
                            val t = state.catchTimeMs?.let { "%.1f".format(it / 1000.0) }
                            if (t != null) "✅ 포구 성공!  ${t}s" else "✅ 포구 성공!"
                        }
                        GamePhase.RESULT            -> "❌ 포구 실패"
                        GamePhase.TRAINING_COMPLETE -> "🏆 훈련 완료!"
                    }

                    val startEnabled = state.phase == GamePhase.IDLE && !defenseTutorialManager.isRunning
                    binding?.btnStart?.isEnabled = startEnabled
                    binding?.btnStart?.alpha = if (startEnabled) 1f else 0.5f

                    val catchEnabled = state.phase == GamePhase.LAUNCHED || state.phase == GamePhase.LANDED
                    binding?.btnCatch?.isEnabled = catchEnabled
                    binding?.btnCatch?.alpha = if (catchEnabled) 1f else 0.4f

                    // 조기종료: 세션 진행 중이고 1회 이상 완료됐거나 공이 날고 있을 때
                    val earlyStopEnabled = gameEngine.sessionActive &&
                        (state.totalAttempts > 0 ||
                         state.phase == GamePhase.LAUNCHED ||
                         state.phase == GamePhase.LANDED)
                    binding?.btnEarlyStop?.isEnabled = earlyStopEnabled
                    binding?.btnEarlyStop?.alpha     = if (earlyStopEnabled) 1f else 0.4f

                    val ballCountEditable = state.totalAttempts == 0 && state.phase == GamePhase.IDLE
                    binding?.btnBallCountDown?.isEnabled = ballCountEditable
                    binding?.btnBallCountUp?.isEnabled   = ballCountEditable
                    binding?.tvBallCount?.alpha          = if (ballCountEditable) 1f else 0.4f
                    binding?.btnBallCountDown?.alpha     = if (ballCountEditable) 1f else 0.4f
                    binding?.btnBallCountUp?.alpha       = if (ballCountEditable) 1f else 0.4f
                    binding?.tvDebug?.text = state.debugInfo

                } else {
                    // ── 일반 사용자: 심플 UI 업데이트 ──
                    when (state.phase) {
                        GamePhase.IDLE     -> updateSimpleStatus("대기 중", 0xFF0A0A0A.toInt())
                        GamePhase.LAUNCHED -> updateSimpleStatus("공 날아오는 중!", 0xFF0A0A0A.toInt())
                        GamePhase.LANDED   -> updateSimpleStatus("착지!", 0xFFFBBF24.toInt())
                        GamePhase.CAUGHT   -> updateSimpleStatus("포구 성공!", 0xFF166534.toInt())
                        GamePhase.RESULT   -> updateSimpleStatus("포구 실패", 0xFF7F1D1D.toInt())
                        GamePhase.TRAINING_COMPLETE -> updateSimpleStatus("훈련 완료!", 0xFF0A0A0A.toInt())
                    }
                }

                // ── 튜토리얼 4단계: 캐치 범위 실시간 TTS ──────────────────
                if (defenseTutorialManager.isRunning && defenseTutorialManager.currentStep == 4) {
                    when (state.phase) {
                        GamePhase.LAUNCHED, GamePhase.LANDED -> {
                            val dx   = state.ball.x - state.defenderX
                            val dz   = state.ball.z - state.defenderZ
                            val dist = sqrt(dx * dx + dz * dz)
                            val inRange = dist <= 3.0f
                            if (inRange && !wasInCatchRange) {
                                wasInCatchRange = true
                                defenseTtsManager.speak("범위 안에 있습니다")
                            } else if (!inRange && wasInCatchRange) {
                                wasInCatchRange = false
                                defenseTtsManager.speak("범위를 벗어났습니다")
                            }
                        }
                        GamePhase.IDLE, GamePhase.CAUGHT, GamePhase.RESULT, GamePhase.TRAINING_COMPLETE -> {
                            wasInCatchRange = false
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerOrientationSensor()
        inputManager.registerInputDeviceListener(inputDeviceListener, null)
        if (isAdmin) {
            binding?.switchFakeController?.isChecked = isRealGamepadConnected()
        }
        updateSimpleBleStatus()
        // 펜딩 수비 기록 재시도 (화면 복귀 시)
        if (PendingUploadManager.hasPendingDefense(this) &&
            PendingUploadManager.isNetworkAvailable(this)) {
            tutorialScope.launch(Dispatchers.IO) {
                PendingUploadManager.syncDefense(this@MainActivity)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(orientationListener)
        inputManager.unregisterInputDeviceListener(inputDeviceListener)
    }

    // ─── 블루투스 게임패드 아날로그 스틱 + D패드 ────────────────────────
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            && event.action == MotionEvent.ACTION_MOVE) {
            val lx = getCenteredAxis(event, MotionEvent.AXIS_X)
            val ly = getCenteredAxis(event, MotionEvent.AXIS_Y)
            val rx = getCenteredAxis(event, MotionEvent.AXIS_Z)
            val ry = getCenteredAxis(event, MotionEvent.AXIS_RZ)
            val dx = if (abs(lx) > 0.01f) lx else rx
            val dy = if (abs(ly) > 0.01f) ly else ry
            gameEngine.setJoystick(dx, dy)
            if (isAdmin) binding?.joystickView?.setExternalInput(dx, -dy)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun getCenteredAxis(event: MotionEvent, axis: Int): Float {
        val device = event.device ?: return 0f
        val range = device.getMotionRange(axis, event.source) ?: return 0f
        val value = event.getAxisValue(axis)
        return if (abs(value) > range.flat.coerceAtLeast(0.05f)) value else 0f
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_A -> {
                        // 결과 다이얼로그 표시 중 → 닫기 (관리자)
                        val d = resultDialog
                        if (d != null && d.isShowing) {
                            d.dismiss(); gameEngine.stopSpeak(); resultDialog = null
                        } else if (defenseTutorialManager.isRunning && defenseTutorialManager.currentStep == 4) {
                            gameEngine.onCatchPressed()
                        } else if (!defenseTutorialManager.isRunning) {
                            gameEngine.onCatchPressed()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_BUTTON_B -> {
                        if (!defenseTutorialManager.isRunning) {
                            gameEngine.forceStopSession()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_BUTTON_X -> {
                        if (defenseTutorialManager.isRunning && defenseTutorialManager.currentStep == 4) {
                            // step4 포구 체험: 캐치/실패 후 X로 다음 공 발사
                            val st = gameEngine.state.value
                            if (st.phase == GamePhase.IDLE && !gameEngine.isSessionComplete()) {
                                gameEngine.launchNextInSession(0.5f)
                            }
                        } else if (defenseTutorialManager.isRunning) {
                            defenseTutorialManager.onXButton()
                        } else {
                            val st = gameEngine.state.value
                            if (st.phase == GamePhase.IDLE) {
                                if (gameEngine.isSessionComplete() || st.totalAttempts == 0) {
                                    resetHeading()
                                    gameEngine.startSession(getBallCount(), 0.5f)
                                } else {
                                    gameEngine.launchRandom(0.5f)
                                }
                            }
                        }
                        return true
                    }
                    // ── LB: 튜토리얼 아닐 때 or (2단계 + TTS 끝난 후)만 허용 ──
                    KeyEvent.KEYCODE_BUTTON_L1 -> {
                        if (!defenseTutorialManager.isRunning ||
                            (defenseTutorialManager.currentStep == 2 && !defenseTutorialManager.isSpeaking))
                            adjustBallCount(-1)
                        return true
                    }
                    // ── RB: 튜토리얼 아닐 때 or (2단계 + TTS 끝난 후)만 허용 ──
                    KeyEvent.KEYCODE_BUTTON_R1 -> {
                        if (!defenseTutorialManager.isRunning ||
                            (defenseTutorialManager.currentStep == 2 && !defenseTutorialManager.isSpeaking))
                            adjustBallCount(+1)
                        return true
                    }
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showSessionResultDialog(result: com.beepbeep.defense.game.SessionResult) {
        val rate = if (result.target > 0) result.success * 100 / result.target else 0
        fun fmtTime(ms: Long?) = if (ms != null) "%.1f초".format(ms / 1000.0) else "-"

        val msg = buildString {
            appendLine("━━━━━━━━━━━━━━━━")
            appendLine("🎯  총  시도     ${result.target}회")
            appendLine("✅  포구 성공    ${result.success}회")
            appendLine("❌  포구 실패    ${result.miss}회")
            appendLine("📊  성공률       ${rate}%")
            appendLine("━━━━━━━━━━━━━━━━")
            appendLine("⏱  평균 시간    ${fmtTime(result.avgTimeMs)}")
            appendLine("🏆  최단 시간    ${fmtTime(result.bestTimeMs)}")
            appendLine("🐢  최장 시간    ${fmtTime(result.worstTimeMs)}")
            append(    "━━━━━━━━━━━━━━━━")
        }

        resultDialog = android.app.AlertDialog.Builder(this)
            .setTitle("🏅 훈련 결과")
            .setMessage(msg)
            .setPositiveButton("확인") { _, _ -> gameEngine.stopSpeak(); resultDialog = null }
            .setOnCancelListener { gameEngine.stopSpeak(); resultDialog = null }
            .show()
            .also { dialog ->
                // 다이얼로그에 직접 키 리스너 등록 → A 버튼 한 번에 닫기
                dialog.setOnKeyListener { _, keyCode, event ->
                    if (event.source and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD
                        && keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A
                        && event.action == android.view.KeyEvent.ACTION_DOWN) {
                        dialog.dismiss()
                        gameEngine.stopSpeak()
                        resultDialog = null
                        true
                    } else false
                }
            }
    }

    override fun onDestroy() {
        if (gameEngine.sessionActive && !defenseTutorialManager.isRunning) {
            gameEngine.forceStopSession(silent = true)
        }
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
        gameEngine.release()
        defenseTtsManager.shutdown()
        tutorialScope.cancel()
    }
}
