package com.beepbeep.defense

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.beepbeep.defense.databinding.ActivityMainBinding
import com.beepbeep.defense.game.GameEngine
import com.beepbeep.defense.game.GamePhase
import com.beepbeep.defense.game.HitDirection
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var gameEngine: GameEngine
    private lateinit var sensorManager: SensorManager

    private val rotMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var baseAzimuth: Float? = null

    private val orientationListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            SensorManager.getOrientation(rotMatrix, orientation)
            val azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()

            if (baseAzimuth == null) baseAzimuth = azimuthDeg

            var rel = azimuthDeg - (baseAzimuth ?: azimuthDeg)
            while (rel > 180f)  rel -= 360f
            while (rel < -180f) rel += 360f

            gameEngine.updateHeadingDirectly(-rel)
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

    private var pitchCount = 10

    private fun setupButtons() {
        binding.btnLeft.setOnClickListener   { gameEngine.launchBall(HitDirection.LEFT,   getDifficulty()) }
        binding.btnCenter.setOnClickListener { gameEngine.launchBall(HitDirection.CENTER, getDifficulty()) }
        binding.btnRight.setOnClickListener  { gameEngine.launchBall(HitDirection.RIGHT,  getDifficulty()) }
        binding.btnCatch.setOnClickListener  { gameEngine.onCatchPressed() }
        binding.btnReset.setOnClickListener  {
            gameEngine.resetToIdle()
            baseAzimuth = null
        }

        binding.btnPitchMinus.setOnClickListener {
            if (pitchCount > 1) {
                pitchCount--
                binding.tvPitchCount.text = pitchCount.toString()
            }
        }
        binding.btnPitchPlus.setOnClickListener {
            if (pitchCount < 30) {
                pitchCount++
                binding.tvPitchCount.text = pitchCount.toString()
            }
        }
        binding.btnStartTraining.setOnClickListener {
            gameEngine.startTraining(pitchCount, getDifficulty())
            baseAzimuth = null
        }

        binding.joystickView.onMove = { dx, dz ->
            gameEngine.joystickDx = dx
            gameEngine.joystickDz = -dz
        }

        binding.btnSwingTest.setOnClickListener {
            startActivity(android.content.Intent(
                this,
                com.beepbeep.defense.batting.SwingTestActivity::class.java
            ))
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
                binding.tvScore.text = if (state.isTrainingMode || state.phase == GamePhase.TRAINING_COMPLETE) {
                    "투구 ${state.currentPitchNum}/${state.targetPitches} | 성공 ${state.score}"
                } else {
                    "성공: ${state.score} / ${state.totalAttempts}"
                }
                binding.tvPhase.text = when (state.phase) {
                    GamePhase.IDLE              -> "🎯 시작 버튼을 누르세요"
                    GamePhase.LAUNCHED          -> "🔊 비프음 방향으로 이동!"
                    GamePhase.LANDED            -> "📍 착지! CATCH 누르세요!"
                    GamePhase.CAUGHT            -> "✅ 포구 성공!"
                    GamePhase.RESULT            -> "❌ 포구 실패"
                    GamePhase.TRAINING_COMPLETE -> "🏆 훈련 완료!"
                }
                val catchEnabled = state.phase == GamePhase.LAUNCHED || state.phase == GamePhase.LANDED
                binding.btnCatch.isEnabled = catchEnabled
                binding.btnCatch.alpha = if (catchEnabled) 1f else 0.4f
                val inTraining = state.isTrainingMode
                binding.btnLeft.isEnabled = !inTraining
                binding.btnCenter.isEnabled = !inTraining
                binding.btnRight.isEnabled = !inTraining
                binding.btnStartTraining.isEnabled = !inTraining
                binding.btnPitchMinus.isEnabled = !inTraining
                binding.btnPitchPlus.isEnabled = !inTraining
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

    override fun onDestroy() {
        super.onDestroy()
        gameEngine.release()
    }
}
