package com.beepbeep.defense

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.beepbeep.defense.databinding.ActivityMainBinding
import com.beepbeep.defense.game.GameEngine
import com.beepbeep.defense.game.GamePhase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var gameEngine: GameEngine
    private lateinit var sensorManager: SensorManager
    private lateinit var inputManager: InputManager

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            val dev = InputDevice.getDevice(deviceId) ?: return
            if ((dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
                runOnUiThread { binding.switchFakeController.isChecked = true }
                // 블루투스 연결 시 오디오 라우팅이 바뀔 수 있어 AudioTrack 재초기화
                gameEngine.reinitAudio()
            }
        }
        override fun onInputDeviceChanged(deviceId: Int) {}
        override fun onInputDeviceRemoved(deviceId: Int) {
            if (!isRealGamepadConnected()) {
                runOnUiThread { binding.switchFakeController.isChecked = false }
                gameEngine.speakControllerDisconnected()
            }
        }
    }

    private val rotMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var baseAzimuth: Float? = null
    private var controllerCheckedOnStart = false   // 앱 시작 시 1회 안내용
    private var smoothedHeading = 0f
    private var prevAmplified   = 0f   // 이전 프레임 값 (각속도 계산용)
    private var headingVelocity = 0f   // 스무딩된 절대 각속도 (°/update)
    private var signedVelocity  = 0f   // 스무딩된 부호 있는 각속도 (예측 보상용)

    private var ballCount = 5   // 공 개수 (1~20)
    private var prevHatX  = 0f  // D패드 HAT 축 엣지 감지용

    // 감도 배율 (1.0 = 실제 회전과 1:1)
    private val HEADING_SENSITIVITY = 1.0f
    // 적응형 스무딩: 각속도(회전 속도) 기반 — 빠를수록 즉시 반응, 느릴수록 노이즈 억제

    private val orientationListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            SensorManager.getOrientation(rotMatrix, orientation)
            val azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()

            if (baseAzimuth == null) baseAzimuth = azimuthDeg

            var rel = azimuthDeg - (baseAzimuth ?: azimuthDeg)
            while (rel > 180f)  rel -= 360f
            while (rel < -180f) rel += 360f

            // 감도 적용 후 ±180° 범위로 제한
            val amplified = (-rel * HEADING_SENSITIVITY).coerceIn(-180f, 180f)

            // 각속도(°/update) 계산
            val instantVel       = abs(amplified - prevAmplified)
            val instantSignedVel = amplified - prevAmplified
            headingVelocity = headingVelocity * 0.4f + instantVel       * 0.6f
            signedVelocity  = signedVelocity  * 0.3f + instantSignedVel * 0.7f
            prevAmplified   = amplified

            // 각속도에 따라 스무딩 강도 조정
            // ~2ms 업데이트 기준: 90°/200ms → update당 ~0.9°
            val factor = when {
                headingVelocity > 1.2f -> 1.00f  // 빠른 스냅 → 스무딩 없이 즉시
                headingVelocity > 0.5f -> 0.80f  // 보통 회전 → 빠른 추종
                headingVelocity > 0.1f -> 0.30f  // 느린 회전 → 부드럽게
                else                   -> 0.08f  // 정지/떨림 → 강하게 억제
            }
            smoothedHeading += (amplified - smoothedHeading) * factor

            // 예측 보상: 회전 속도에 비례해 적용 → 멈추면 자동으로 0이 됨
            // predictionScale: 빠르게 돌 때 1.0, 멈출 때 0.0 (부드럽게 꺼짐)
            val predictionScale = (headingVelocity / 1.2f).coerceIn(0f, 1f)
            val predicted = (smoothedHeading + signedVelocity * 12f * predictionScale).coerceIn(-180f, 180f)
            gameEngine.updateHeadingDirectly(predicted)
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

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        gameEngine    = GameEngine(this)
        inputManager  = getSystemService(InputManager::class.java)
        gameEngine.init()

        sensorManager = getSystemService(SensorManager::class.java)

        gameEngine.onHeadingChanged = { deg ->
            runOnUiThread { binding.fieldView.updateHeading(deg) }
        }

        gameEngine.onSessionComplete = { result ->
            runOnUiThread { showSessionResultDialog(result) }
        }

        setupButtons()
        observeGameState()
    }

    private fun registerOrientationSensor() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: return
        sensorManager.registerListener(orientationListener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
    }

    // 실제 하드웨어 게임패드 연결 여부 (스위치 무관)
    private fun isRealGamepadConnected(): Boolean =
        InputDevice.getDeviceIds().any { id ->
            val dev = InputDevice.getDevice(id) ?: return@any false
            (dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        }

    // 스위치 포함 연결 여부 (시작 버튼 판단용)
    private fun isGamepadConnected(): Boolean =
        binding.switchFakeController.isChecked || isRealGamepadConnected()

    private fun setupButtons() {
        binding.btnStart.setOnClickListener {
            if (!isGamepadConnected()) {
                gameEngine.speakControllerWarning()
                return@setOnClickListener
            }
            val state = gameEngine.state.value
            if (state.phase == GamePhase.IDLE) {
                // 시작할 때마다 현재 방향을 트래킹 원점으로 설정
                baseAzimuth = null
                if (gameEngine.isSessionComplete() || state.totalAttempts == 0) {
                    gameEngine.startSession(getBallCount(), getDifficulty())
                } else {
                    gameEngine.launchRandom(getDifficulty())
                }
            }
        }
        binding.btnCatch.setOnClickListener { gameEngine.onCatchPressed() }

        binding.btnBallCountDown.setOnClickListener { adjustBallCount(-1) }
        binding.btnBallCountUp.setOnClickListener   { adjustBallCount(+1) }

        binding.joystickView.onMove = { dx, dz ->
            gameEngine.joystickDx = dx
            gameEngine.joystickDz = -dz
        }
    }

    private fun adjustBallCount(delta: Int) {
        if (!binding.btnBallCountDown.isEnabled) return  // 훈련 중 변경 불가
        ballCount = (ballCount + delta).coerceIn(1, 20)
        binding.tvBallCount.text = ballCount.toString()
        gameEngine.speakBallCount(ballCount)
    }

    private fun adjustDifficulty(delta: Int) {
        val next = (binding.spinnerDifficulty.selectedItemPosition + delta).coerceIn(0, 2)
        binding.spinnerDifficulty.setSelection(next)
        gameEngine.speakDifficulty(next)
    }

    private fun getDifficulty() = when (binding.spinnerDifficulty.selectedItemPosition) {
        0 -> 0.3f; 1 -> 0.5f; 2 -> 0.8f; else -> 0.5f
    }

    private fun getBallCount(): Int = ballCount

    private fun observeGameState() {
        lifecycleScope.launch {
            gameEngine.state.collectLatest { state ->
                binding.fieldView.update(
                    ball = state.ball, defX = state.defenderX,
                    defZ = state.defenderZ, isFlying = state.phase == GamePhase.LAUNCHED
                )
                binding.tvScore.text = if (state.targetBallCount > 0)
                    "성공: ${state.score} / ${state.totalAttempts} (목표 ${state.targetBallCount}회)"
                else
                    "성공: ${state.score} / ${state.totalAttempts}"
                binding.tvPhase.text = when (state.phase) {
                    GamePhase.IDLE     -> "🎯 시작 버튼을 누르세요"
                    GamePhase.LAUNCHED -> "🔊 비프음 방향으로 이동!"
                    GamePhase.LANDED   -> "📍 착지! CATCH 누르세요!"
                    GamePhase.CAUGHT   -> {
                        val t = state.catchTimeMs?.let { "%.1f".format(it / 1000.0) }
                        if (t != null) "✅ 포구 성공!  ${t}s" else "✅ 포구 성공!"
                    }
                    GamePhase.RESULT   -> "❌ 포구 실패"
                }
                // 시작 버튼: IDLE일 때만 활성 (CAUGHT/RESULT 중 눌러서 세션 조기 시작 방지)
                val startEnabled = state.phase == GamePhase.IDLE
                binding.btnStart.isEnabled = startEnabled
                binding.btnStart.alpha = if (startEnabled) 1f else 0.5f

                val catchEnabled = state.phase == GamePhase.LAUNCHED || state.phase == GamePhase.LANDED
                binding.btnCatch.isEnabled = catchEnabled
                binding.btnCatch.alpha = if (catchEnabled) 1f else 0.4f

                // 세션 시작 후 공 개수 변경 불가 (세션 완료/미시작 시 다시 활성화)
                val ballCountEditable = state.totalAttempts == 0 && state.phase == GamePhase.IDLE
                binding.btnBallCountDown.isEnabled = ballCountEditable
                binding.btnBallCountUp.isEnabled   = ballCountEditable
                binding.tvBallCount.alpha = if (ballCountEditable) 1f else 0.4f
                binding.btnBallCountDown.alpha = if (ballCountEditable) 1f else 0.4f
                binding.btnBallCountUp.alpha   = if (ballCountEditable) 1f else 0.4f
                binding.tvDebug.text = state.debugInfo
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerOrientationSensor()
        inputManager.registerInputDeviceListener(inputDeviceListener, null)
        // 실제 연결 상태를 스위치에 반영
        binding.switchFakeController.isChecked = isRealGamepadConnected()
        // 앱 시작 후 최초 1회 컨트롤러 연결 상태 안내
        if (!controllerCheckedOnStart) {
            controllerCheckedOnStart = true
            if (!isGamepadConnected()) {
                gameEngine.speakControllerWarning()
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
            // 오른쪽 스틱 폴백 (왼쪽 스틱 입력이 없을 때)
            val rx = getCenteredAxis(event, MotionEvent.AXIS_Z)
            val ry = getCenteredAxis(event, MotionEvent.AXIS_RZ)

            val dx = if (abs(lx) > 0.01f) lx else rx
            val dy = if (abs(ly) > 0.01f) ly else ry

            gameEngine.joystickDx = dx
            gameEngine.joystickDz = dy

            // JoystickView 시각 동기화
            binding.joystickView.setExternalInput(dx, -dy)

            // D패드를 HAT 축으로 전달하는 게임패드 처리 (엣지 감지)
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            if (prevHatX == 0f && hatX == -1f) adjustDifficulty(-1)
            if (prevHatX == 0f && hatX ==  1f) adjustDifficulty(+1)
            prevHatX = hatX

            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun getCenteredAxis(event: MotionEvent, axis: Int): Float {
        val device = event.device ?: return 0f
        val range = device.getMotionRange(axis, event.source) ?: return 0f
        val value = event.getAxisValue(axis)
        // 데드존 처리 (±0.15)
        return if (abs(value) > range.flat.coerceAtLeast(0.15f)) value else 0f
    }

    // ─── 블루투스 게임패드 버튼 ──────────────────────────────────────────
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    // A 버튼 → CATCH
                    KeyEvent.KEYCODE_BUTTON_A -> { gameEngine.onCatchPressed(); return true }
                    // B 버튼 → RESET
                    KeyEvent.KEYCODE_BUTTON_B -> { gameEngine.fullReset(); baseAzimuth = null; return true }
                    // X 버튼 → 발사
                    KeyEvent.KEYCODE_BUTTON_X -> {
                        val st = gameEngine.state.value
                        if (st.phase == GamePhase.IDLE) {
                            if (gameEngine.isSessionComplete() || st.totalAttempts == 0)
                                gameEngine.startSession(getBallCount(), getDifficulty())
                            else
                                gameEngine.launchRandom(getDifficulty())
                        }
                        return true
                    }
                    // L1 → 공 개수 감소
                    KeyEvent.KEYCODE_BUTTON_L1 -> { adjustBallCount(-1); return true }
                    // R1 → 공 개수 증가
                    KeyEvent.KEYCODE_BUTTON_R1 -> { adjustBallCount(+1); return true }
                    // D패드 좌 → 난이도 감소
                    KeyEvent.KEYCODE_DPAD_LEFT  -> { adjustDifficulty(-1); return true }
                    // D패드 우 → 난이도 증가
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { adjustDifficulty(+1); return true }
                }
            }
            return true  // 게임패드 키는 시스템에 넘기지 않음
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

        android.app.AlertDialog.Builder(this)
            .setTitle("🏅 훈련 결과")
            .setMessage(msg)
            .setPositiveButton("확인") { _, _ -> gameEngine.stopSpeak() }
            .setOnCancelListener { gameEngine.stopSpeak() }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        gameEngine.release()
    }
}
