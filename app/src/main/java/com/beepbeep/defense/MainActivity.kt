package com.beepbeep.defense

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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

    private val rotMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var baseAzimuth: Float? = null
    private var smoothedHeading = 0f

    // 감도 배율 (1.0 = 원래, 높을수록 작은 회전에도 크게 반응)
    private val HEADING_SENSITIVITY = 2.0f
    // 저역통과 필터 계수 (0.0 = 완전 고정, 1.0 = 필터 없음)
    private val SMOOTH_FACTOR = 0.2f

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

            // 저역통과 필터로 노이즈 제거
            smoothedHeading += (amplified - smoothedHeading) * SMOOTH_FACTOR

            gameEngine.updateHeadingDirectly(smoothedHeading)
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

        gameEngine = GameEngine(this)
        gameEngine.init()

        sensorManager = getSystemService(SensorManager::class.java)

        gameEngine.onHeadingChanged = { deg ->
            runOnUiThread { binding.fieldView.updateHeading(deg) }
        }

        setupButtons()
        observeGameState()
    }

    private fun registerOrientationSensor() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: return
        sensorManager.registerListener(orientationListener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun setupButtons() {
        binding.btnStart.setOnClickListener  { gameEngine.launchRandom(getDifficulty()) }
        binding.btnCatch.setOnClickListener  { gameEngine.onCatchPressed() }
        binding.btnReset.setOnClickListener  {
            gameEngine.resetToIdle()
            baseAzimuth = null  // 리셋 시 기준 방향 재보정
        }

        binding.joystickView.onMove = { dx, dz ->
            gameEngine.joystickDx = dx
            gameEngine.joystickDz = -dz
        }
    }

    private fun getDifficulty() = when (binding.spinnerDifficulty.selectedItemPosition) {
        0 -> 0.3f; 1 -> 0.5f; 2 -> 0.8f; else -> 0.5f
    }

    private fun observeGameState() {
        lifecycleScope.launch {
            gameEngine.state.collectLatest { state ->
                binding.fieldView.update(
                    ball = state.ball, defX = state.defenderX,
                    defZ = state.defenderZ, isFlying = state.phase == GamePhase.LAUNCHED
                )
                binding.tvScore.text = "성공: ${state.score} / ${state.totalAttempts}"
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
                val catchEnabled = state.phase == GamePhase.LAUNCHED || state.phase == GamePhase.LANDED
                binding.btnCatch.isEnabled = catchEnabled
                binding.btnCatch.alpha = if (catchEnabled) 1f else 0.4f
                binding.tvDebug.text = state.debugInfo
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerOrientationSensor()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(orientationListener)
    }

    // ─── 블루투스 게임패드 아날로그 스틱 ────────────────────────────────
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
                    KeyEvent.KEYCODE_BUTTON_B -> { gameEngine.resetToIdle(); baseAzimuth = null; return true }
                    // X 버튼 → 랜덤 발사
                    KeyEvent.KEYCODE_BUTTON_X -> { gameEngine.launchRandom(getDifficulty()); return true }
                }
            }
            return true  // 게임패드 키는 시스템에 넘기지 않음
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        gameEngine.release()
    }
}
