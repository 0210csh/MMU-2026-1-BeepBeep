package com.beepbeep.defense.batting

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.beepbeep.defense.R
import com.beepbeep.defense.audio.SpatialAudioEngine
import com.beepbeep.defense.hardware.BleManager
import kotlinx.coroutines.*
import kotlin.math.*
import kotlin.random.Random
import java.util.Locale
import android.util.Log

class SwingTestActivity : AppCompatActivity() {

    companion object {
        private const val REQ_BLE_PERM = 100
    }

    private val BATTING_ANGLE_DEG = SwingGraphView.REF_PITCH_ANGLE_DEG
    private val pitchYPosition: Float =
        ((BATTING_ANGLE_DEG / 43f) + 0.5f).coerceIn(0.1f, 0.9f)

    // ── 관리자 여부 ──────────────────────────────────────
    private var isAdmin = false

    // ── 관리자 전용 뷰 (nullable — 비관리자 레이아웃에는 없음) ──
    private var tvStatus:             TextView?      = null
    private var tvResult:             TextView?      = null
    private var btnStart:             Button?        = null
    private var ballTrackView:        BallTrackView?  = null
    private var swingGraphView:       SwingGraphView? = null
    private var liveBallParabolaView: BallParabolaView? = null
    private var base1Container:       FrameLayout?   = null
    private var base3Container:       FrameLayout?   = null
    private var base1Glow:            View?          = null
    private var base3Glow:            View?          = null
    private var tvBase1Label:         TextView?      = null
    private var tvBase3Label:         TextView?      = null
    private var btnSwingPitchMinus:   Button?        = null
    private var btnSwingPitchPlus:    Button?        = null
    private var tvSwingPitchCount:    TextView?      = null
    private var tvSwingPitchProgress: TextView?      = null
    private var btnBleConnect:        Button?        = null
    private var tvBleStatus:          TextView?      = null
    private var tvBatteryLevel:       TextView?      = null
    private var btnResultView:        Button?        = null
    private var lastResultDialog:     android.app.AlertDialog? = null

    // ── 훈련 상태 ────────────────────────────────────────
    private var targetPitches   = 3
    private var currentPitchNum = 0
    private var successCount    = 0
    private var isTraining      = false
    private var hitCount        = 0
    private var foulCount       = 0
    private var strikeCount     = 0
    private val reactionTimes   = mutableListOf<Long>()

    private val perPitchRecords    = mutableListOf<HashMap<String, Any?>>()
    private val currentPitchRecord = HashMap<String, Any?>()

    private var phase2StartAbsMs: Long = 0L
    private var phase3StartAbsMs: Long = 0L
    private val perPitchFullHistory          = mutableListOf<ArrayList<Pair<Long, Float>>>()
    private val perPitchHitTimeRelFull       = mutableListOf<Long>()
    private val perPitchWinOpenRelFull       = mutableListOf<Long>()
    private val perPitchWinCloseRelFull      = mutableListOf<Long>()
    private val perPitchMinSearchRelFull     = mutableListOf<Long>()
    private val perPitchMinAngleRelFull      = mutableListOf<Long>()
    private val perPitchMinAngleDegList      = mutableListOf<Float>()
    private val perPitchPitchWinStartRelFull = mutableListOf<Long>()
    private val perPitchPitchTtsStartRelFull = mutableListOf<Long>()
    private val perPitchPhase2RelFull        = mutableListOf<Long>()
    private val perPitchPhase3RelFull        = mutableListOf<Long>()
    private val perPitchWinOpenDeg           = mutableListOf<Float>()
    private val perPitchWinCloseDeg          = mutableListOf<Float>()

    // ── Sensors ─────────────────────────────────────────
    private lateinit var sensorManager:  SensorManager
    private var linearAccelSensor:       Sensor? = null
    private var gyroscopeSensor:         Sensor? = null
    private var rotationSensor:          Sensor? = null
    private val rotMatrix   = FloatArray(9)
    private val orientation = FloatArray(3)
    private val gravity     = FloatArray(3)
    private val LP_ALPHA    = 0.8f

    @Volatile private var linearAccelMag:  Float = 0f
    @Volatile private var gyroMag:         Float = 0f
    @Volatile private var currentPitchDeg: Float = 0f

    private var setAngleThisPitch:   Float = 0f
    private val setToReadyHistory    = ArrayList<Pair<Long, Float>>()
    private val readyToPitchHistory  = ArrayList<Pair<Long, Float>>()
    private val pitchToEndHistory    = ArrayList<Pair<Long, Float>>()
    private val allSetAngles          = mutableListOf<Float>()
    private val perPitchPhase1Data   = mutableListOf<ArrayList<Pair<Long, Float>>>()
    private val perPitchPhase2Data   = mutableListOf<ArrayList<Pair<Long, Float>>>()
    private val perPitchPhase3Data   = mutableListOf<ArrayList<Pair<Long, Float>>>()
    @Volatile private var recordingPhase:       Int  = 0
    @Volatile private var phaseRecordStartTime: Long = 0L
    @Volatile private var hitTimePhase3Ms:      Long = -1L
    private val perPitchHitTimesPhase3 = mutableListOf<Long>()

    // ── 임계값 ──────────────────────────────────────────
    private val HIT_ACCEL_THRESHOLD  = 48f
    private val HIT_GYRO_THRESHOLD   = 30f
    private val MIN_ACCEL_THRESHOLD  = 48f
    private val MIN_GYRO_THRESHOLD   = 30f
    private val PITCH_TOLERANCE      = 15f

    // ── 물리 상수 ────────────────────────────────────────
    private val PITCHER_DIST     = 6.53f
    private val PITCHER_HEIGHT   = 1.0f
    private val BALL_ARC         = 0.3f
    private val BATTER_HEIGHT    = 1.0f
    private val BAT_REACH        = 0.6f
    private val HEIGHT_TOLERANCE = 0.2f

    // ── 게임 상태 ────────────────────────────────────────
    private var targetBase = 1

    @Volatile private var hitWindowActive:         Boolean = false
    @Volatile private var preWindowActive:         Boolean = false
    @Volatile private var postWindowActive:        Boolean = false
    @Volatile private var preWindowSwingDetected:  Boolean = false
    @Volatile private var postWindowSwingDetected: Boolean = false
    @Volatile private var minSwingDetected:        Boolean = false
    @Volatile private var windowOpenPitchDeg:      Float   = 0f
    @Volatile private var windowClosePitchDeg:     Float   = 0f
    @Volatile private var minAngleSearchActive:    Boolean = false
    @Volatile private var minBatAngleDeg:          Float   = Float.MAX_VALUE
    @Volatile private var minBatAngleAbsMs:        Long    = -1L
    @Volatile private var minBatAngleRelMs:        Long    = -1L

    private var hitWindowOpenAbsMs:           Long = -1L
    private var hitWindowCloseAbsMs:          Long = -1L
    private var graphHitWindowOpenRelMs:      Long = -1L
    private var graphHitWindowCloseRelMs:     Long = -1L
    private var graphMinSearchStartRelMs:     Long = -1L
    private var graphPitchWindowStartMs:      Long = -1L
    private var graphPitchTtsStartMs:         Long = -1L

    @Volatile private var testForceHit:    Boolean = false
    @Volatile private var swingDetected:   Boolean = false
    @Volatile private var swingIsHit:      Boolean = false
    @Volatile private var gangSpoken:      Boolean = false
    @Volatile private var swingPitchDeg:   Float   = 0f
    @Volatile private var swingWasStrong:  Boolean = false
    @Volatile private var swingBatHeight:  Float   = Float.NaN

    private var isWaitingForInput = false
    private var beepStartTime     = 0L

    private val pitchHistory = ArrayList<Pair<Long, Float>>()
    @Volatile private var pitchRecordStart: Long    = 0L
    @Volatile private var hitTimeRelMs:     Long    = -1L
    @Volatile private var isRecording:      Boolean = false

    @Volatile private var ballApproachProgress: Float = 0f
    @Volatile private var divProgress:          Float = 0f

    // ── 헤드트래킹 ──────────────────────────────────────
    private var baseAzimuth: Float? = null
    @Volatile private var currentHeadingDeg: Float = 0f

    // ── Coroutines ──────────────────────────────────────
    private val scope    = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var gameJob:  Job? = null
    private var audioJob: Job? = null

    private val ttsManager = SwingTtsManager(this)

    private var audioTrack: AudioTrack? = null
    private lateinit var spatialAudio: SpatialAudioEngine

    private lateinit var bleManager: BleManager
    @Volatile private var bleConnected = false
    private var bleGraphBaseMs:      Long    = 0L
    private var bleGraphPacketCount: Long    = 0L
    private var bleGraphStarted:     Boolean = false

    @Volatile private var prevBtn1 = false
    @Volatile private var prevBtn2 = false
    private var batBtn1Job: Job? = null
    private var batBtn2Job: Job? = null
    private var baseBeepTimeoutJob: Job? = null

    // ── 튜토리얼 ────────────────────────────────────────
    private lateinit var tutorialManager: SwingTutorialManager
    private var angleCalibJob: Job? = null

    // ── BT 오디오 기기 감지 ──────────────────────────────
    private lateinit var audioManager: AudioManager
    @Volatile private var btAudioLost = false
    private val btAudioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val hasBtAudio = removedDevices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            if (!hasBtAudio) return
            if (btAudioLost) return          // 중복 처리 방지
            if (!isTraining) return          // 훈련 중일 때만 처리
            btAudioLost = true
            runOnUiThread {
                earlyFinishTraining(speakTts = false, showSummary = false)
                scope.launch {
                    delay(200L)
                    ttsManager.speak("블루투스 이어폰 연결이 해제되어 훈련이 종료되었습니다.")
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────
    // 심플 UI 상태 업데이트 (일반 사용자)
    // ─────────────────────────────────────────────────────
    private fun updateSimpleStatus(text: String, bgColor: Int = 0xFF0A0A0A.toInt()) {
        if (isAdmin) return
        runOnUiThread {
            findViewById<TextView>(R.id.tvSimpleStatus)?.text = text
            findViewById<View>(R.id.simpleRootLayout)?.setBackgroundColor(bgColor)
            findViewById<TextView>(R.id.tvSimpleProgress)?.text =
                if (isTraining) "${currentPitchNum}/${targetPitches}" else ""
        }
    }

    // ─────────────────────────────────────────────────────
    // 스윙 감지 리스너
    // ─────────────────────────────────────────────────────
    private val swingListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (bleConnected) return
            when (event.sensor.type) {
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    linearAccelMag = magnitude(event.values); checkSwing()
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    gravity[0] = LP_ALPHA * gravity[0] + (1 - LP_ALPHA) * event.values[0]
                    gravity[1] = LP_ALPHA * gravity[1] + (1 - LP_ALPHA) * event.values[1]
                    gravity[2] = LP_ALPHA * gravity[2] + (1 - LP_ALPHA) * event.values[2]
                    linearAccelMag = magnitude(floatArrayOf(
                        event.values[0] - gravity[0],
                        event.values[1] - gravity[1],
                        event.values[2] - gravity[2]
                    ))
                    checkSwing()
                }
                Sensor.TYPE_GYROSCOPE -> { gyroMag = magnitude(event.values) }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    // ─────────────────────────────────────────────────────
    // 방향 감지 리스너
    // ─────────────────────────────────────────────────────
    private val orientationListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            SensorManager.getOrientation(rotMatrix, orientation)

            val azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
            val isFirstOrientFrame = (baseAzimuth == null)
            if (isFirstOrientFrame) baseAzimuth = azimuthDeg
            var rel = azimuthDeg - (baseAzimuth ?: azimuthDeg)
            while (rel > 180f)  rel -= 360f
            while (rel < -180f) rel += 360f
            currentHeadingDeg = -rel

            // 3축 HRTF: pitch/roll 매 프레임 전달. 첫 프레임에서 중립 캡처
            spatialAudio.updatePitchRoll(orientation[1], orientation[2])
            if (isFirstOrientFrame) spatialAudio.captureNeutralPitchRoll()

            if (bleConnected) return
            currentPitchDeg = Math.toDegrees(orientation[1].toDouble()).toFloat()

            if (minAngleSearchActive && currentPitchDeg < minBatAngleDeg) {
                minBatAngleDeg   = currentPitchDeg
                minBatAngleAbsMs = System.currentTimeMillis()
                minBatAngleRelMs = minBatAngleAbsMs - pitchRecordStart
            }

            if (isAdmin) {
                val batH = BATTER_HEIGHT + sin(currentPitchDeg * PI.toFloat() / 180f) * BAT_REACH
                liveBallParabolaView?.updateLiveBat(batH)
                if (isRecording) {
                    pitchHistory.add(Pair(System.currentTimeMillis() - pitchRecordStart, currentPitchDeg))
                    swingGraphView?.postInvalidate()
                }
            }

            val phaseElapsed = System.currentTimeMillis() - phaseRecordStartTime
            when (recordingPhase) {
                1 -> setToReadyHistory.add(Pair(phaseElapsed, currentPitchDeg))
                2 -> readyToPitchHistory.add(Pair(phaseElapsed, currentPitchDeg))
                3 -> pitchToEndHistory.add(Pair(phaseElapsed, currentPitchDeg))
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    // ─────────────────────────────────────────────────────
    // 스윙 판정
    // ─────────────────────────────────────────────────────
    private fun checkSwing() {
        val accel = linearAccelMag
        val gyro  = gyroMag
        val isStrongSwing = accel > HIT_ACCEL_THRESHOLD && gyro > HIT_GYRO_THRESHOLD
        val isAnySwing    = accel > HIT_ACCEL_THRESHOLD && gyro > HIT_GYRO_THRESHOLD
        val isMinSwing    = accel > MIN_ACCEL_THRESHOLD  && gyro > MIN_GYRO_THRESHOLD

        if ((preWindowActive || hitWindowActive || postWindowActive) && !minSwingDetected && isMinSwing)
            minSwingDetected = true
        if (preWindowActive && !preWindowSwingDetected && isAnySwing)
            preWindowSwingDetected = true

        if (hitWindowActive) {
            if (!swingDetected && isAnySwing) {
                swingDetected = true
                swingPitchDeg = currentPitchDeg
                if (hitTimeRelMs < 0L) {
                    hitTimeRelMs = System.currentTimeMillis() - pitchRecordStart
                    if (recordingPhase == 3) hitTimePhase3Ms = System.currentTimeMillis() - phaseRecordStartTime
                    if (isAdmin) {
                        swingGraphView?.setHitTime(hitTimeRelMs)
                        swingGraphView?.postInvalidate()
                    }
                }
            }
            if (!swingWasStrong && isStrongSwing) {
                swingWasStrong = true
                swingBatHeight = BATTER_HEIGHT + sin(currentPitchDeg * PI.toFloat() / 180f) * BAT_REACH
                if (abs(currentPitchDeg - BATTING_ANGLE_DEG) <= PITCH_TOLERANCE) swingIsHit = true
            }
        }
        if (postWindowActive && !postWindowSwingDetected && isAnySwing)
            postWindowSwingDetected = true
    }

    private fun startPhaseRecording() { phaseRecordStartTime = System.currentTimeMillis() }
    private fun stopPhaseRecording()  { recordingPhase = 0 }

    // ─────────────────────────────────────────────────────
    // onCreate
    // ─────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── 관리자 여부 체크 ──
        val userId = getSharedPreferences("UserInfo", MODE_PRIVATE)
            .getString("id", "anonymous") ?: "anonymous"
        isAdmin = userId == "123402"
        // 관리자: 가로 강제 (매니페스트 기본값 portrait → 1회 재생성 허용)
        // 일반 사용자: 매니페스트 portrait 그대로 → 재생성 없음
        if (isAdmin) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        if (isAdmin) {
            setContentView(R.layout.activity_swing_test)
            tvStatus              = findViewById(R.id.tvV2Status)
            tvResult              = findViewById(R.id.tvV2Result)
            btnStart              = findViewById(R.id.btnV2Start)
            ballTrackView         = findViewById(R.id.v2BallTrackView)
            swingGraphView        = findViewById(R.id.v2SwingGraphView)
            liveBallParabolaView  = findViewById(R.id.v2LiveParabolaView)
            base1Container        = findViewById(R.id.v2Base1Container)
            base3Container        = findViewById(R.id.v2Base3Container)
            base1Glow             = findViewById(R.id.v2Base1Glow)
            base3Glow             = findViewById(R.id.v2Base3Glow)
            tvBase1Label          = findViewById(R.id.v2TvBase1Label)
            tvBase3Label          = findViewById(R.id.v2TvBase3Label)
            btnSwingPitchMinus    = findViewById(R.id.btnSwingPitchMinus)
            btnSwingPitchPlus     = findViewById(R.id.btnSwingPitchPlus)
            tvSwingPitchCount     = findViewById(R.id.tvSwingPitchCount)
            tvSwingPitchProgress  = findViewById(R.id.tvSwingPitchProgress)
            btnBleConnect         = findViewById(R.id.btnBleConnect)
            tvBleStatus           = findViewById(R.id.tvBleStatus)
            tvBatteryLevel        = findViewById(R.id.tvBatteryLevel)
            btnResultView         = findViewById(R.id.btnResultView)
            btnResultView?.setOnClickListener { lastResultDialog?.show() }
            // 테스트용 정타 강제 스위치
            findViewById<android.widget.Switch>(R.id.switchTestForceHit)
                ?.setOnCheckedChangeListener { _, isChecked -> testForceHit = isChecked }
            ballTrackView?.setShowStrikeZone(false)
        } else {
            setContentView(R.layout.activity_swing_simple)
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        ttsManager.init {
            runOnUiThread {
                updateSimpleStatus("배트를 연결해주세요")
                if (hasBlePermissions()) startBleScan() else requestBlePermissions()
            }
        }

        tutorialManager = SwingTutorialManager(
            context              = this,
            ttsManager           = ttsManager,
            scope                = scope,
            onStartPracticePitch = {
                isTraining = true
                currentPitchNum = 1
                tutorialManager.targetPitchCount = targetPitches
                startGame()
                if (isAdmin) resetAndShowLiveGraphs()
            },
            onStartBaseBeep = {
                initAudioTrack()
                targetBase = if (Random.nextBoolean()) 1 else 3
                if (isAdmin) activateBothBases()
                startBaseBeep()
                isWaitingForInput = true
            },
            onTutorialFinished = {
                runOnUiThread {
                    isTraining    = false
                    baseAzimuth   = null
                    resetBaseVisuals()
                    updateSimpleStatus("양쪽 버튼을 눌러 훈련을 시작하세요")
                    if (isAdmin) {
                        tvStatus?.text                = ""
                        tvResult?.text                = ""
                        tvSwingPitchProgress?.text    = ""
                        btnStart?.isEnabled           = true
                        btnStart?.text                = "훈련 시작"
                        btnSwingPitchMinus?.isEnabled = true
                        btnSwingPitchPlus?.isEnabled  = true
                    }
                }
            },
            onStartAngleCalibration = {
                startAngleCalibration()
            },
            onStartFullTraining = {
                btAudioLost     = false
                baseAzimuth     = null
                isTraining      = true
                currentPitchNum = 1
                successCount    = 0
                hitCount        = 0
                foulCount       = 0
                strikeCount     = 0
                reactionTimes.clear()
                perPitchRecords.clear()
                tutorialManager.targetPitchCount = targetPitches
                startGame()
            }
        )

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        initAudioTrack()
        spatialAudio = SpatialAudioEngine(this)
        spatialAudio.init()
        bleManager = BleManager(this)
        bleManager.setCallback(bleCallback)

        if (isAdmin) {
            btnBleConnect?.setOnClickListener {
                if (bleConnected) bleManager.disconnect() else startBleScan()
            }
            btnSwingPitchMinus?.setOnClickListener {
                if (targetPitches > 1) { targetPitches--; tvSwingPitchCount?.text = targetPitches.toString() }
            }
            btnSwingPitchPlus?.setOnClickListener {
                if (targetPitches < 20) { targetPitches++; tvSwingPitchCount?.text = targetPitches.toString() }
            }
            btnStart?.setOnClickListener {
                if (tutorialManager.isRunning) return@setOnClickListener
                lastResultDialog?.dismiss()
                lastResultDialog = null
                btnResultView?.visibility = View.GONE
                baseAzimuth = null
                isTraining = true
                currentPitchNum = 1
                successCount = 0
                hitCount = 0
                foulCount = 0
                strikeCount = 0
                reactionTimes.clear()
                allSetAngles.clear()
                perPitchPhase1Data.clear()
                perPitchPhase2Data.clear()
                perPitchPhase3Data.clear()
                perPitchHitTimesPhase3.clear()
                perPitchRecords.clear()
                perPitchFullHistory.clear()
                perPitchHitTimeRelFull.clear()
                perPitchWinOpenRelFull.clear()
                perPitchWinCloseRelFull.clear()
                perPitchMinSearchRelFull.clear()
                perPitchMinAngleRelFull.clear()
                perPitchMinAngleDegList.clear()
                perPitchPitchWinStartRelFull.clear()
                perPitchPitchTtsStartRelFull.clear()
                perPitchPhase2RelFull.clear()
                perPitchPhase3RelFull.clear()
                perPitchWinOpenDeg.clear()
                perPitchWinCloseDeg.clear()
                btnSwingPitchMinus?.isEnabled = false
                btnSwingPitchPlus?.isEnabled  = false
                tvSwingPitchProgress?.text = "1/${targetPitches}"
                tutorialManager.targetPitchCount = targetPitches
                startGame()
                resetAndShowLiveGraphs()
            }
            base1Container?.setOnClickListener { onBasePressed(1) }
            base3Container?.setOnClickListener { onBasePressed(3) }
        }
    }

    // ─────────────────────────────────────────────────────
    // 게임 메인 루프
    // ─────────────────────────────────────────────────────
    private fun startGame() {
        btAudioLost         = false
        gameJob?.cancel()
        tvResult?.text      = ""
        ballTrackView?.reset()
        btnStart?.isEnabled = false
        isWaitingForInput   = false
        updateSimpleStatus("준비 중...")

        swingDetected             = false
        swingIsHit                = false
        gangSpoken                = false
        preWindowSwingDetected    = false
        postWindowSwingDetected   = false
        minSwingDetected          = false
        preWindowActive           = false
        postWindowActive          = false
        windowOpenPitchDeg        = 0f
        windowClosePitchDeg       = 0f
        minAngleSearchActive      = false
        minBatAngleDeg            = Float.MAX_VALUE
        minBatAngleAbsMs          = -1L
        minBatAngleRelMs          = -1L
        hitWindowOpenAbsMs        = -1L
        hitWindowCloseAbsMs       = -1L
        graphHitWindowOpenRelMs   = -1L
        graphHitWindowCloseRelMs  = -1L
        graphMinSearchStartRelMs  = -1L
        graphPitchWindowStartMs   = -1L
        graphPitchTtsStartMs      = -1L
        recordingPhase     = 0
        phase2StartAbsMs   = 0L
        phase3StartAbsMs   = 0L
        setAngleThisPitch  = 0f
        setToReadyHistory.clear()
        readyToPitchHistory.clear()
        pitchToEndHistory.clear()
        swingPitchDeg      = 0f
        swingWasStrong     = false
        swingBatHeight     = Float.NaN
        hitTimePhase3Ms    = -1L
        ballApproachProgress = 0f
        divProgress          = 0f
        pitchHistory.clear()
        hitTimeRelMs    = -1L
        isRecording     = false
        hitWindowActive = false
        initAudioTrack()
        targetBase = if (Random.nextBoolean()) 1 else 3
        resetBaseVisuals()
        currentPitchRecord.clear()
        currentPitchRecord["투구번호"]       = currentPitchNum
        currentPitchRecord["목표베이스"]     = targetBase
        currentPitchRecord["배트각도"]       = null
        currentPitchRecord["선택베이스"]     = null
        currentPitchRecord["베이스정답여부"] = null
        currentPitchRecord["주루반응속도"]   = null

        gameJob = scope.launch {
            // ━━━ 1단계: SET ━━━
            setAngleThisPitch = currentPitchDeg
            allSetAngles.add(setAngleThisPitch)
            // BLE: MCU 상태 리셋 후 측정 시작
            // 이전 세션이 끊어졌을 때 isMeasuring=true가 남아있으면 sendControl(1)이 무시되므로
            // 0→200ms→1 순서로 전송해 항상 올바른 상태에서 측정 시작
            if (bleConnected) {
                bleManager.sendControl(0)
                delay(200L)
                bleManager.sendControl(1)
            }
            recordingPhase = 1
            startPhaseRecording()
            withContext(Dispatchers.Main) {
                tvStatus?.text = "SET"
                tvStatus?.setTextColor(0xFF93C5FD.toInt())
                updateSimpleStatus("SET")
                ttsManager.speakEnglish("SET", speechRate = 1.2f)
            }
            delay(1000)

            // ━━━ 2단계: 공 접근 + READY/PITCH TTS ━━━
            val approachMs    = 1300L
            val readyDistM    = 10f * 0.3048f
            val readyProgress = ((PITCHER_DIST - readyDistM) / PITCHER_DIST).coerceIn(0f, 1f)
            val contactH      = BATTER_HEIGHT + sin(BATTING_ANGLE_DEG * PI.toFloat() / 180f) * BAT_REACH
            var pitchTtsJob: Job? = null
            var pitchCompletedMs  = -1L

            stopPhaseRecording()
            recordingPhase = 2
            startPhaseRecording()
            phase2StartAbsMs = phaseRecordStartTime
            preWindowActive = true
            pitchTtsJob = launch {
                withContext(Dispatchers.Main) {
                    tvStatus?.text = "READY"
                    tvStatus?.setTextColor(0xFF93C5FD.toInt())
                    updateSimpleStatus("READY")
                }
                ttsManager.speakTwoSequentially(
                    first  = "READY",
                    second = "PITCH",
                    locale = Locale.ENGLISH,
                    speechRate = 1.2f,
                    onFirstDone = {
                        val ttsStartMs = System.currentTimeMillis() - pitchRecordStart
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            graphPitchTtsStartMs = ttsStartMs
                            if (isAdmin) {
                                swingGraphView?.setPitchTtsStart(ttsStartMs)
                                tvStatus?.text = "PITCH"
                                tvStatus?.setTextColor(0xFFFBBF24.toInt())
                            }
                            updateSimpleStatus("PITCH", 0xFF78350F.toInt())
                        }
                    }
                )
                pitchCompletedMs = System.currentTimeMillis()
                preWindowActive  = false
                withContext(Dispatchers.Main) { tvStatus?.text = "" }
            }

            delay(500L)
            val tApproach = System.currentTimeMillis()

            // 타격: 6.5m 범위에서 접근감 극대화
            spatialAudio.gainCoeff = 0.30f
            spatialAudio.updateBallPosition(0f, 0f, -PITCHER_DIST)
            spatialAudio.updateHeading(currentHeadingDeg)
            spatialAudio.startBeep()

            // 루프 A: 공 애니메이션
            val animJob = launch {
                while (isActive) {
                    val elapsed  = System.currentTimeMillis() - tApproach
                    val progress = (elapsed.toFloat() / approachMs).coerceIn(0f, 1f)
                    ballApproachProgress = progress
                    val zPos = -(PITCHER_DIST * (1f - progress)).coerceAtLeast(0.5f)
                    spatialAudio.updateBallPosition(0f, 0f, zPos)
                    spatialAudio.updateHeading(currentHeadingDeg)
                    if (isAdmin) {
                        withContext(Dispatchers.Main) {
                            ballTrackView?.updateBall(0f, progress, BallPhase.APPROACH, pitchYPosition)
                            liveBallParabolaView?.setBallProgress(progress)
                        }
                    }
                    if (progress >= 1f) break
                    delay(16)
                }
            }

            // 루프 B: 타이밍 조건 체크
            while (isActive) {
                val elapsed = System.currentTimeMillis() - tApproach

                // -900ms: 최저 각도 측정 시작
                if (!minAngleSearchActive && elapsed >= approachMs - 900L) {
                    minBatAngleDeg           = Float.MAX_VALUE
                    minBatAngleAbsMs         = -1L
                    minAngleSearchActive     = true
                    graphMinSearchStartRelMs = System.currentTimeMillis() - pitchRecordStart
                    if (isAdmin) withContext(Dispatchers.Main) { swingGraphView?.setMinAngleSearch(graphMinSearchStartRelMs) }
                }

                // 타격 윈도우 오픈 (공 도착 160ms 전)
                if (!hitWindowActive && elapsed >= approachMs - 160L) {
                    swingDetected           = false
                    swingIsHit              = false
                    gangSpoken              = false
                    windowOpenPitchDeg      = currentPitchDeg
                    hitWindowOpenAbsMs      = System.currentTimeMillis()
                    graphHitWindowOpenRelMs = hitWindowOpenAbsMs - pitchRecordStart
                    hitWindowActive         = true
                    if (isAdmin) withContext(Dispatchers.Main) { swingGraphView?.setHitWindowOpen(graphHitWindowOpenRelMs) }
                }

                if (elapsed >= approachMs) break
                delay(1)
            }
            animJob.join()

            audioTrack?.pause(); audioTrack?.flush(); audioTrack?.play()

            stopPhaseRecording()
            recordingPhase = 3
            startPhaseRecording()
            phase3StartAbsMs = phaseRecordStartTime

            pitchTtsJob?.join()
            val TTS_BUFFER_OFFSET_MS = 190L
            graphPitchWindowStartMs = if (pitchCompletedMs >= 0L)
                (pitchCompletedMs - pitchRecordStart - TTS_BUFFER_OFFSET_MS)
                    .coerceAtLeast(graphPitchTtsStartMs + 50L)
            else 0L
            if (isAdmin) withContext(Dispatchers.Main) { swingGraphView?.setPitchWindowStart(graphPitchWindowStartMs) }

            // ━━━ 3단계: 타격 윈도우 마감 (+50ms) ━━━
            val deadline = System.currentTimeMillis() + 50L
            while (isActive && System.currentTimeMillis() < deadline) {
                if (swingDetected) break
                delay(8)
            }
            hitWindowActive          = false
            spatialAudio.stopBeep()
            hitWindowCloseAbsMs      = System.currentTimeMillis()
            graphHitWindowCloseRelMs = hitWindowCloseAbsMs - pitchRecordStart
            windowClosePitchDeg      = currentPitchDeg

            // 윈도우 닫히는 순간 스냅샷
            val snapMinAngleDeg   = minBatAngleDeg
            val snapMinAngleAbsMs = minBatAngleAbsMs

            withContext(Dispatchers.Main) {
                tvStatus?.text = ""
                if (isAdmin) {
                    swingGraphView?.setHitWindowClose(graphHitWindowCloseRelMs)
                    swingGraphView?.setWindowAngles(windowOpenPitchDeg, windowClosePitchDeg)
                }
            }
            if (bleConnected) bleManager.sendControl(0)

            // 윈도우 마감 직후 조기 정타 판정
            val earlyMinInWindow = snapMinAngleDeg < Float.MAX_VALUE &&
                    snapMinAngleAbsMs >= hitWindowOpenAbsMs &&
                    snapMinAngleAbsMs <= hitWindowCloseAbsMs
            val earlyAngleDiff   = if (snapMinAngleDeg < Float.MAX_VALUE)
                snapMinAngleDeg - BATTING_ANGLE_DEG else Float.MAX_VALUE

            if (earlyMinInWindow && swingWasStrong && abs(earlyAngleDiff) <= PITCH_TOLERANCE) {
                gangSpoken = true
                withContext(Dispatchers.Main) {
                    ttsManager.speak("깡")
                    tvStatus?.text = "소리 들어봐!"
                    tvStatus?.setTextColor(0xFFFBBF24.toInt())
                    updateSimpleStatus("정타!", 0xFF166534.toInt())
                }
                launch {
                    delay(100L)
                    if (tutorialManager.isRunning && tutorialManager.currentStep == 4) return@launch
                    withContext(Dispatchers.Main) { if (isAdmin) activateBothBases() }
                    startBaseBeep()
                    isWaitingForInput = true
                }
            }

            postWindowActive = true
            delay(1000L)
            postWindowActive = false
            minAngleSearchActive = false
            if (isAdmin) withContext(Dispatchers.Main) {
                if (minBatAngleAbsMs > 0L && minBatAngleDeg < Float.MAX_VALUE)
                    swingGraphView?.setMinAngle(minBatAngleRelMs, minBatAngleDeg)
            }

            delay(150L)
            isRecording = false
            stopPhaseRecording()
            if (setToReadyHistory.isNotEmpty())   perPitchPhase1Data.add(ArrayList(setToReadyHistory))
            if (readyToPitchHistory.isNotEmpty()) perPitchPhase2Data.add(ArrayList(readyToPitchHistory))
            if (pitchToEndHistory.isNotEmpty())   perPitchPhase3Data.add(ArrayList(pitchToEndHistory))
            perPitchHitTimesPhase3.add(hitTimePhase3Ms)

            val snapHitTime       = hitTimeRelMs
            val snapWinOpen       = graphHitWindowOpenRelMs
            val snapWinClose      = graphHitWindowCloseRelMs
            val snapMinSearch     = graphMinSearchStartRelMs
            val snapMinRel        = if (minBatAngleAbsMs > 0L && minBatAngleDeg < Float.MAX_VALUE) minBatAngleRelMs else -1L
            val snapMinDeg        = minBatAngleDeg
            val snapPitchWin      = graphPitchWindowStartMs
            val snapPitchTtsStart = graphPitchTtsStartMs
            val snapP2Rel         = phase2StartAbsMs - pitchRecordStart
            val snapP3Rel         = phase3StartAbsMs - pitchRecordStart
            val snapWinOpenDeg    = windowOpenPitchDeg
            val snapWinCloseDeg   = windowClosePitchDeg
            if (isAdmin) withContext(Dispatchers.Main) {
                perPitchFullHistory.add(ArrayList(pitchHistory))
                perPitchHitTimeRelFull.add(snapHitTime)
                perPitchWinOpenRelFull.add(snapWinOpen)
                perPitchWinCloseRelFull.add(snapWinClose)
                perPitchMinSearchRelFull.add(snapMinSearch)
                perPitchMinAngleRelFull.add(snapMinRel)
                perPitchMinAngleDegList.add(snapMinDeg)
                perPitchPitchWinStartRelFull.add(snapPitchWin)
                perPitchPitchTtsStartRelFull.add(snapPitchTtsStart)
                perPitchPhase2RelFull.add(snapP2Rel)
                perPitchPhase3RelFull.add(snapP3Rel)
                perPitchWinOpenDeg.add(snapWinOpenDeg)
                perPitchWinCloseDeg.add(snapWinCloseDeg)
            }

            // ━━━ 4단계: 결과 판정 ━━━
            val hasValidMin  = minBatAngleDeg < Float.MAX_VALUE
            val minInWindow  = hasValidMin && minBatAngleAbsMs >= hitWindowOpenAbsMs && minBatAngleAbsMs <= hitWindowCloseAbsMs
            val minBeforeWin = hasValidMin && minBatAngleAbsMs < hitWindowOpenAbsMs
            val minAfterWin  = hasValidMin && minBatAngleAbsMs > hitWindowCloseAbsMs
            val angleDiff    = if (hasValidMin) minBatAngleDeg - BATTING_ANGLE_DEG else Float.MAX_VALUE
            val winDelta     = windowClosePitchDeg - windowOpenPitchDeg
            val winSign      = if (winDelta >= 0f) "+" else ""

            when {

                // ── 4x: 최소 스윙 미달 ──
                !minSwingDetected && !testForceHit -> {
                    currentPitchRecord["판정"] = "스트라이크(무스윙)"
                    currentPitchRecord["피드백"] = "더 빨리 스윙하세요"
                    withContext(Dispatchers.Main) {
                        perPitchRecords.add(HashMap(currentPitchRecord))
                        ballTrackView?.reset()
                        ttsManager.speak("스트라이크")
                        strikeCount++
                        tvStatus?.text = "스트라이크!"
                        tvStatus?.setTextColor(0xFFF87171.toInt())
                        tvResult?.text = "스윙 없음"
                        updateSimpleStatus("스트라이크!", 0xFF7F1D1D.toInt())
                        if (isAdmin && !isTraining) {
                            showSwingGraph()
                            btnStart?.isEnabled = true
                            btnStart?.text = "다시하기"
                        }
                    }
                    delay(1000L)
                    ttsManager.speakAndWait("더 빨리 스윙하세요", Locale.KOREAN)
                    if (isTraining) {
                        withContext(Dispatchers.Main) { scheduleNextOrFinish(false) }
                    }
                }

                // ── 4a: 정타 — 강제 모드 or 조기 판정 or 정상 판정 ──
                testForceHit || gangSpoken || (minInWindow && swingWasStrong && abs(angleDiff) <= PITCH_TOLERANCE) -> {
                    currentPitchRecord["판정"] = "정타"
                    currentPitchRecord["피드백"] = "정타 — 베이스 선택"
                    withContext(Dispatchers.Main) {
                        perPitchRecords.add(HashMap(currentPitchRecord))
                        hitCount++
                        // gangSpoken=false면 조기 발화가 안 된 경우 — 여기서 fallback 발화
                        if (!gangSpoken) {
                            ttsManager.speak("깡")
                            tvStatus?.text = "소리 들어봐!"
                            tvStatus?.setTextColor(0xFFFBBF24.toInt())
                            updateSimpleStatus("정타!", 0xFF166534.toInt())
                        }
                        tvResult?.text = "최저 각도: %.0f°".format(minBatAngleDeg)
                    }
                    if (!gangSpoken && !(tutorialManager.isRunning && tutorialManager.currentStep == 4)) {
                        launch {
                            delay(100L)
                            withContext(Dispatchers.Main) { if (isAdmin) activateBothBases() }
                            startBaseBeep()
                            isWaitingForInput = true
                        }
                    }

                    val divMs      = 1500L
                    val tDiv       = System.currentTimeMillis()
                    val targetPanX = if (targetBase == 3) -1f else 1f

                    spatialAudio.updateBallPosition(0f, 0f, -0.5f)
                    spatialAudio.updateHeading(currentHeadingDeg)
                    spatialAudio.startBeep()

                    while (isActive) {
                        val progress = ((System.currentTimeMillis() - tDiv).toFloat() / divMs)
                            .coerceIn(0f, 1f)
                        divProgress = progress
                        spatialAudio.updateBallPosition(targetPanX * progress * 5f, 0f, -0.5f)
                        spatialAudio.updateHeading(currentHeadingDeg)
                        if (isAdmin) withContext(Dispatchers.Main) {
                            ballTrackView?.updateBall(
                                targetPanX * progress,
                                1f - progress,
                                BallPhase.DIVERGE
                            )
                        }
                        if (progress >= 1f) break
                        delay(16)
                    }

                    spatialAudio.stopBeep()
                    audioTrack?.pause(); audioTrack?.flush(); audioTrack?.play()

                    // gangSpoken=true 케이스: earlyMinInWindow에서 시작한 베이스 비프음이
                    // divMs 애니메이션의 startBeep()에 의해 취소됨 → 여기서 재시작
                    // (튜토리얼 step4에서는 베이스 비프 불필요)
                    if (gangSpoken && !(tutorialManager.isRunning && tutorialManager.currentStep == 4)) startBaseBeep()

                    withContext(Dispatchers.Main) {
                        ballTrackView?.reset()
                        if (tutorialManager.isRunning && tutorialManager.currentStep == 4) {
                            isWaitingForInput = false
                            stopAudio()
                            tvStatus?.text = "정타!"
                            tvStatus?.setTextColor(0xFF4ADE80.toInt())
                            // speakAndWait 로 TTS 완료 후 다음 투구로 진행
                            scope.launch {
                                ttsManager.speakAndWait("정타입니다.", java.util.Locale.KOREAN)
                                withContext(Dispatchers.Main) { scheduleNextOrFinish(true) }
                            }
                            return@withContext
                        }
                        if (isAdmin) activateBothBases()
                        tvStatus?.text = ""
                    }
                }

                // ── 4b: 파울 — 최저각이 윈도우 안이지만 각도 불일치 or 힘 부족 ──
                minInWindow -> {
                    val absDiff = abs(angleDiff).toInt()
                    val foulAdvice = when {
                        !swingWasStrong  -> "더 강하게 휘두르세요"
                        angleDiff > 0    -> "배트를 ${absDiff}도 더 내려서 치세요"
                        else             -> "배트를 ${absDiff}도 더 올려서 치세요"
                    }
                    currentPitchRecord["판정"] = "파울"
                    currentPitchRecord["피드백"] = foulAdvice
                    withContext(Dispatchers.Main) {
                        perPitchRecords.add(HashMap(currentPitchRecord))
                        ballTrackView?.reset()
                        ttsManager.speak("파울")
                        foulCount++
                        tvStatus?.text = "파울!"
                        tvStatus?.setTextColor(0xFFFBBF24.toInt())
                        val resultLabel = if (!swingWasStrong) "파울 — 힘 부족" else "파울 — 각도 불일치"
                        tvResult?.text = "$resultLabel\n최저 각도: %.0f°".format(minBatAngleDeg)
                        updateSimpleStatus("파울!", 0xFF78350F.toInt())
                        if (isAdmin && !isTraining) {
                            showSwingGraph()
                            btnStart?.isEnabled = true
                            btnStart?.text = "다시하기"
                        }
                    }
                    delay(900L)
                    ttsManager.speakAndWait(foulAdvice, Locale.KOREAN)
                    if (isTraining) {
                        withContext(Dispatchers.Main) { scheduleNextOrFinish(false) }
                    }
                }

                // ── 4c: 스트라이크 — 최저각이 윈도우 오픈 전 (너무 빠름) ──
                minBeforeWin -> {
                    currentPitchRecord["판정"] = "스트라이크(빠름)"
                    currentPitchRecord["피드백"] = "더 늦게 스윙하세요"
                    withContext(Dispatchers.Main) {
                        perPitchRecords.add(HashMap(currentPitchRecord))
                        ballTrackView?.reset()
                        ttsManager.speak("스트라이크")
                        strikeCount++
                        tvStatus?.text = "스트라이크!"
                        tvStatus?.setTextColor(0xFFF87171.toInt())
                        tvResult?.text = "타격 윈도우 전 스윙\n최저 각도: %.0f°".format(minBatAngleDeg)
                        updateSimpleStatus("스트라이크!", 0xFF7F1D1D.toInt())
                        if (isAdmin && !isTraining) {
                            showSwingGraph()
                            btnStart?.isEnabled = true
                            btnStart?.text = "다시하기"
                        }
                    }
                    delay(1000L)
                    ttsManager.speakAndWait("더 늦게 스윙하세요", Locale.KOREAN)
                    if (isTraining) {
                        withContext(Dispatchers.Main) { scheduleNextOrFinish(false) }
                    }
                }

                // ── 4d: 스트라이크 — 윈도우 마감 후 최저각 도달 (늦은 스윙) ──
                minAfterWin -> {
                    currentPitchRecord["판정"] = "스트라이크(늦음)"
                    currentPitchRecord["피드백"] = "더 빨리 스윙하세요"
                    withContext(Dispatchers.Main) {
                        perPitchRecords.add(HashMap(currentPitchRecord))
                        ballTrackView?.reset()
                        ttsManager.speak("스트라이크")
                        strikeCount++
                        tvStatus?.text = "스트라이크!"
                        tvStatus?.setTextColor(0xFFF87171.toInt())
                        tvResult?.text = "스윙이 늦음\n최저 각도: %.0f° (윈도우 마감 후)".format(minBatAngleDeg)
                        updateSimpleStatus("스트라이크!", 0xFF7F1D1D.toInt())
                        if (isAdmin && !isTraining) {
                            showSwingGraph()
                            btnStart?.isEnabled = true
                            btnStart?.text = "다시하기"
                        }
                    }
                    delay(1000L)
                    ttsManager.speakAndWait("더 빨리 스윙하세요", Locale.KOREAN)
                    if (isTraining) {
                        withContext(Dispatchers.Main) { scheduleNextOrFinish(false) }
                    }
                }

                // ── 4e: 스트라이크 — 무스윙 or 스윙 미감지 ──
                else -> {
                    currentPitchRecord["판정"] = "스트라이크(무스윙)"
                    currentPitchRecord["피드백"] = "더 빨리 스윙하세요"
                    withContext(Dispatchers.Main) {
                        perPitchRecords.add(HashMap(currentPitchRecord))
                        ballTrackView?.reset()
                        ttsManager.speak("스트라이크")
                        strikeCount++
                        tvStatus?.text = "스트라이크!"
                        tvStatus?.setTextColor(0xFFF87171.toInt())
                        tvResult?.text = "타격 윈도우 후 스윙 / 무스윙"
                        updateSimpleStatus("스트라이크!", 0xFF7F1D1D.toInt())
                        if (isAdmin && !isTraining) {
                            showSwingGraph()
                            btnStart?.isEnabled = true
                            btnStart?.text = "다시하기"
                        }
                    }
                    delay(1000L)
                    ttsManager.speakAndWait("더 빨리 스윙하세요", Locale.KOREAN)
                    if (isTraining) {
                        withContext(Dispatchers.Main) { scheduleNextOrFinish(false) }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────
    // 베이스 도착음 — Resonance Audio HRTF 3D 음향
    // ─────────────────────────────────────────────────────
    private fun startBaseBeep() {
        audioJob?.cancel()
        spatialAudio.gainCoeff = 0.092f
        val dirX = if (targetBase == 3) -5f else 5f
        spatialAudio.updateBallPosition(dirX, 0f, -1f)
        spatialAudio.updateHeading(currentHeadingDeg)
        spatialAudio.startContinuousBeep()
        audioJob = scope.launch {
            while (isActive) {
                spatialAudio.updateHeading(currentHeadingDeg)
                delay(16)
            }
        }
        beepStartTime = SystemClock.elapsedRealtimeNanos()

        // 튜토리얼 step 4, 5는 타임아웃 미적용
        val applyTimeout = !tutorialManager.isRunning ||
                tutorialManager.currentStep == 6
        if (applyTimeout) {
            baseBeepTimeoutJob?.cancel()
            baseBeepTimeoutJob = scope.launch {
                delay(10_000L)
                withContext(Dispatchers.Main) { onBaseBeepTimeout() }
            }
        }
    }

    // ─────────────────────────────────────────────────────
    // 베이스 선택 처리
    // ─────────────────────────────────────────────────────
    private fun onBasePressed(pressedBase: Int) {
        if (!isWaitingForInput) return
        baseBeepTimeoutJob?.cancel()
        baseBeepTimeoutJob = null
        isWaitingForInput = false
        stopAudio()
        gameJob?.cancel()
        resetBaseVisuals()
        ballTrackView?.reset()

        // 튜토리얼 step4: 타격 체험 후 베이스 선택 → 성공/실패 피드백 후 완료 통보
        if (tutorialManager.isRunning && tutorialManager.currentStep == 4) {
            val success = pressedBase == targetBase
            scope.launch {
                if (success) {
                    val ms = (SystemClock.elapsedRealtimeNanos() - beepStartTime) / 1_000_000L
                    ttsManager.speakAndWait(
                        "정답입니다. 반응속도 %.1f초입니다.".format(ms / 1000.0),
                        Locale.KOREAN
                    )
                } else {
                    ttsManager.speakAndWait("틀렸습니다. 소리 방향의 버튼을 누르세요.", Locale.KOREAN)
                }
                tutorialManager.notifyStep4Done()
            }
            return
        }
        // 튜토리얼 step5: 주루 체험 베이스 선택 연습
        if (tutorialManager.isRunning && tutorialManager.currentStep == 5) {
            val success = pressedBase == targetBase
            scope.launch {
                if (success) {
                    val ms = (SystemClock.elapsedRealtimeNanos() - beepStartTime) / 1_000_000L
                    ttsManager.speakAndWait(
                        "정답입니다. 반응속도 %.1f초입니다.".format(ms / 1000.0),
                        Locale.KOREAN
                    )
                } else {
                    ttsManager.speakAndWait("틀렸습니다.", Locale.KOREAN)
                }
                tutorialManager.notifyStep5Done()
            }
            return
        }

        val success = pressedBase == targetBase
        currentPitchRecord["선택베이스"]     = pressedBase
        currentPitchRecord["베이스정답여부"] = success
        audioTrack?.stop()

        if (success) {
            val ms = (SystemClock.elapsedRealtimeNanos() - beepStartTime) / 1_000_000L
            currentPitchRecord["주루반응속도"] = ms
            currentPitchRecord["피드백"]       = "정타 — ${pressedBase}루 정답 (반응속도 ${ms}ms)"
            reactionTimes.add(ms)
            tvStatus?.text = "성공!"
            tvStatus?.setTextColor(0xFF4ADE80.toInt())
            val sec = ms / 1000.0
            tvResult?.text = "%.2f 초".format(sec)
            ttsManager.speak("성공, 반응속도 %.1f초".format(sec))
            updateSimpleStatus("성공!", 0xFF166534.toInt())
        } else {
            currentPitchRecord["피드백"] = "정타 — ${pressedBase}루 오답 (목표: ${targetBase}루)"
            tvStatus?.text = "알맞지 않은\n베이스 선택입니다"
            tvStatus?.setTextColor(0xFFF87171.toInt())
            tvResult?.text = ""
            if (isAdmin) Toast.makeText(this, "알맞지 않은 베이스 선택입니다", Toast.LENGTH_SHORT).show()
            ttsManager.speak("베이스 선택이 틀렸습니다")
            updateSimpleStatus("오답", 0xFF7F1D1D.toInt())
        }

        // 4a 판정 블록에서 이미 perPitchRecords에 추가됨 → 마지막 레코드 갱신
        if (perPitchRecords.isNotEmpty()) {
            val last = perPitchRecords.last()
            last["피드백"]         = currentPitchRecord["피드백"] ?: ""
            last["선택베이스"]     = currentPitchRecord["선택베이스"] ?: 0
            last["베이스정답여부"] = currentPitchRecord["베이스정답여부"] ?: false
            if (success) last["주루반응속도"] = currentPitchRecord["주루반응속도"] ?: 0L
        } else {
            perPitchRecords.add(HashMap(currentPitchRecord))
        }

        if (isTraining) {
            scope.launch {
                delay(4000)
                withContext(Dispatchers.Main) { scheduleNextOrFinish(success) }
            }
        } else if (isAdmin) {
            showSwingGraph()
            btnStart?.isEnabled = true
            btnStart?.text = "다시하기"
        }
    }

    // ─────────────────────────────────────────────────────
    // 베이스 선택 10초 타임아웃 처리
    // ─────────────────────────────────────────────────────
    private fun onBaseBeepTimeout() {
        if (!isWaitingForInput) return
        isWaitingForInput = false
        stopAudio()
        gameJob?.cancel()
        resetBaseVisuals()
        ballTrackView?.reset()

        // 레코드 기록 — 틀린 베이스 선택과 동일 형식
        currentPitchRecord["선택베이스"]     = 0
        currentPitchRecord["베이스정답여부"] = false
        currentPitchRecord["피드백"]         = "정타 — 주루 선택 시간 초과"
        if (perPitchRecords.isNotEmpty()) {
            val last = perPitchRecords.last()
            last["피드백"]         = currentPitchRecord["피드백"] ?: ""
            last["선택베이스"]     = 0
            last["베이스정답여부"] = false
        } else {
            perPitchRecords.add(HashMap(currentPitchRecord))
        }

        tvStatus?.text = "시간 초과"
        tvStatus?.setTextColor(0xFFF87171.toInt())
        tvResult?.text = ""
        updateSimpleStatus("시간 초과", 0xFF7F1D1D.toInt())

        scope.launch {
            ttsManager.speakAndWait("주루 선택 시간이 초과되었습니다.", Locale.KOREAN)
            delay(1_000L)
            withContext(Dispatchers.Main) {
                earlyFinishTraining(speakTts = true)
            }
        }
    }

    // ─────────────────────────────────────────────────────
    // BLE 배트 센서 콜백
    // ─────────────────────────────────────────────────────
    private val bleCallback = object : BleManager.Callback {
        override fun onConnected() {
            bleConnected = true
            if (isAdmin) {
                btnBleConnect?.isEnabled = true; btnBleConnect?.text = "배트 센서 해제"
                tvBleStatus?.text = "● 연결됨"; tvBleStatus?.setTextColor(0xFF4ADE80.toInt())
            }
            // 튜토리얼 미완료 → 자동 시작
            if (!tutorialManager.isTutorialDone() && !tutorialManager.isRunning) {
                scope.launch {
                    delay(1_000L)
                    ttsManager.speakAndWait("배트가 연결되었습니다.", Locale.KOREAN)
                    delay(500)
                    withContext(Dispatchers.Main) { tutorialManager.startTutorial() }
                }
            } else {
                scope.launch {
                    delay(1_000L)
                    ttsManager.speak("배트가 연결되었습니다")
                }
            }
        }

        override fun onReconnecting() {
            bleConnected = false
            if (isAdmin) {
                btnBleConnect?.isEnabled = false; btnBleConnect?.text = "재연결 중..."
                tvBleStatus?.text = "● 재연결 중..."; tvBleStatus?.setTextColor(0xFFFBBF24.toInt())
            }
        }

        override fun onDisconnected() {
            bleConnected = false
            if (isAdmin) {
                btnBleConnect?.isEnabled = true; btnBleConnect?.text = "배트 센서 연결"
                tvBleStatus?.text = "● 미연결"; tvBleStatus?.setTextColor(0xFFF87171.toInt())
            }
            ttsManager.speak("배트 연결이 끊겼습니다")
            scope.launch {
                delay(1000)
                withContext(Dispatchers.Main) { if (!bleConnected && hasBlePermissions()) startBleScan() }
            }
        }

        override fun onBatteryLevel(level: Int) {
            if (isAdmin) {
                tvBatteryLevel?.text = "배터리 ${level}%"
                tvBatteryLevel?.setTextColor(if (level <= 20) 0xFFF87171.toInt() else 0xFF64748B.toInt())
            }
        }

        override fun onPacket(packet: BleManager.SensorPacket) {
            linearAccelMag  = magnitude(packet.handleAccel)
            gyroMag         = magnitude(packet.handleGyro)
            currentPitchDeg = packet.handleEuler[1]
            checkSwing()

            if (minAngleSearchActive && currentPitchDeg < minBatAngleDeg) {
                minBatAngleDeg   = currentPitchDeg
                minBatAngleAbsMs = System.currentTimeMillis()
                minBatAngleRelMs = minBatAngleAbsMs - pitchRecordStart
            }

            if (isAdmin) {
                val batH = BATTER_HEIGHT + sin(currentPitchDeg * PI.toFloat() / 180f) * BAT_REACH
                liveBallParabolaView?.updateLiveBat(batH)

                // 실제 시각 기반 기록 (동시 도착 패킷은 +1ms 씩 밀어 수직 점프 방지)
                if (isRecording) {
                    val nowMs  = System.currentTimeMillis() - pitchRecordStart
                    val lastMs = pitchHistory.lastOrNull()?.first ?: 0L
                    val adjMs  = if (nowMs <= lastMs) lastMs + 1L else nowMs
                    pitchHistory.add(Pair(adjMs, currentPitchDeg))
                    swingGraphView?.postInvalidate()
                }
                val phaseElapsed = System.currentTimeMillis() - phaseRecordStartTime
                when (recordingPhase) {
                    1 -> setToReadyHistory.add(Pair(phaseElapsed, currentPitchDeg))
                    2 -> readyToPitchHistory.add(Pair(phaseElapsed, currentPitchDeg))
                    3 -> pitchToEndHistory.add(Pair(phaseElapsed, currentPitchDeg))
                }
            }
            handleBatButton(packet.btn1, packet.btn2)
        }
    }

    // ─────────────────────────────────────────────────────
    // 배트 버튼 처리
    //   오른쪽(btn1) 단일탭 → 투구수 +1 / 1루 선택
    //   왼쪽(btn2)   단일탭 → 투구수 -1 / 3루 선택
    //   양쪽 동시    → 훈련 시작 / 조기종료
    // ─────────────────────────────────────────────────────
    private fun handleBatButton(btn1: Boolean, btn2: Boolean) {
        val wasBtn1    = prevBtn1
        val wasBtn2    = prevBtn2
        val risingBtn1 = btn1 && !wasBtn1
        val risingBtn2 = btn2 && !wasBtn2

        if (tutorialManager.isRunning) {
            if (risingBtn1 || risingBtn2) tutorialManager.onTutorialButton(risingBtn1, risingBtn2)
            if (tutorialManager.currentStep != 2 && tutorialManager.currentStep != 5 && tutorialManager.currentStep != 6) {
                prevBtn1 = btn1; prevBtn2 = btn2
                return
            }
            // step 5: 베이스 비프가 울릴 때(isWaitingForInput=true)만 허용, 그 외 전 구간 차단
            if (tutorialManager.currentStep == 5 && !isWaitingForInput) {
                prevBtn1 = btn1; prevBtn2 = btn2
                return
            }
            // step 2, 6: TTS 안내 중에는 버튼 입력 차단
            if ((tutorialManager.currentStep == 2 || tutorialManager.currentStep == 6) && tutorialManager.isSpeaking) {
                prevBtn1 = btn1; prevBtn2 = btn2
                return
            }
        }

        // 양쪽 버튼 동시 상승 에지
        if (btn1 && !wasBtn1 && btn2 && !wasBtn2) {
            batBtn1Job?.cancel(); batBtn2Job?.cancel()
            when {
                isTraining -> earlyFinishTraining(speakTts = true)
                isAdmin    -> if (btnStart?.isEnabled == true) btnStart?.performClick()
                else       -> startSimpleTraining()
            }
            prevBtn1 = btn1; prevBtn2 = btn2
            return
        }

        // 오른쪽 버튼 상승 에지
        if (btn1 && !wasBtn1) {
            when {
                isWaitingForInput -> { batBtn2Job?.cancel(); onBasePressed(1) }
                isTraining -> {
                    if (batBtn2Job?.isActive == true) {
                        batBtn1Job?.cancel(); batBtn2Job?.cancel()
                        earlyFinishTraining(speakTts = true)
                    } else {
                        batBtn1Job?.cancel()
                        batBtn1Job = scope.launch { delay(200L) }
                    }
                }
                !isTraining -> {
                    if (batBtn2Job?.isActive == true) {
                        batBtn1Job?.cancel(); batBtn2Job?.cancel()
                        if (btnStart?.isEnabled == true) btnStart?.performClick()
                    } else {
                        batBtn1Job?.cancel()
                        batBtn1Job = scope.launch {
                            delay(200L)
                            withContext(Dispatchers.Main) {
                                if (batBtn2Job?.isActive != true && targetPitches < 20) {
                                    targetPitches++
                                    tvSwingPitchCount?.text = targetPitches.toString()
                                    ttsManager.speak("투구횟수 ${targetPitches}회")
                                }
                            }
                        }
                    }
                }
            }
        }

        // 왼쪽 버튼 상승 에지
        if (btn2 && !wasBtn2) {
            when {
                isWaitingForInput -> { batBtn1Job?.cancel(); onBasePressed(3) }
                isTraining -> {
                    if (batBtn1Job?.isActive == true) {
                        batBtn1Job?.cancel(); batBtn2Job?.cancel()
                        earlyFinishTraining(speakTts = true)
                    } else {
                        batBtn2Job?.cancel()
                        batBtn2Job = scope.launch { delay(200L) }
                    }
                }
                !isTraining -> {
                    if (batBtn1Job?.isActive == true) {
                        batBtn1Job?.cancel(); batBtn2Job?.cancel()
                        if (btnStart?.isEnabled == true) btnStart?.performClick()
                    } else {
                        batBtn2Job?.cancel()
                        batBtn2Job = scope.launch {
                            delay(200L)
                            withContext(Dispatchers.Main) {
                                if (batBtn1Job?.isActive != true && targetPitches > 1) {
                                    targetPitches--
                                    tvSwingPitchCount?.text = targetPitches.toString()
                                    ttsManager.speak("투구횟수 ${targetPitches}회")
                                }
                            }
                        }
                    }
                }
            }
        }

        prevBtn1 = btn1
        prevBtn2 = btn2
    }

    // ─────────────────────────────────────────────────────
    // 조기종료 처리
    // ─────────────────────────────────────────────────────
    private fun earlyFinishTraining(speakTts: Boolean, showSummary: Boolean = true) {
        if (!isTraining) return
        isTraining = false

        // 튜토리얼 step 6 진행 중 양쪽 버튼 조기종료 → Firebase 저장 없이 결과 전달
        baseBeepTimeoutJob?.cancel()
        baseBeepTimeoutJob = null

        if (tutorialManager.isRunning && tutorialManager.currentStep == 6) {
            gameJob?.cancel()
            ttsManager.stop()
            stopAudio()
            spatialAudio.stopBeep()
            audioTrack?.stop()
            isRecording          = false
            isWaitingForInput    = false
            hitWindowActive      = false
            preWindowActive      = false
            postWindowActive     = false
            minAngleSearchActive = false
            tutorialManager.notifyStep6Done(hitCount, foulCount, strikeCount)
            return
        }

        gameJob?.cancel()
        ttsManager.stop()
        stopAudio()
        spatialAudio.stopBeep()
        audioTrack?.stop()
        isRecording           = false
        isWaitingForInput     = false
        hitWindowActive       = false
        preWindowActive       = false
        postWindowActive      = false
        minAngleSearchActive  = false

        val actualPitches = perPitchRecords.size
        if (actualPitches > 0) {
            val battingAvg        = hitCount.toFloat() / actualPitches
            val avgReaction       = if (reactionTimes.isNotEmpty()) reactionTimes.average().toLong() else -1L
            val baseCorrectPct    = if (hitCount > 0) successCount.toFloat() / hitCount * 100f else 0f
            val battingAvgPct     = (battingAvg * 100).toInt()

            if (showSummary) {
                val ttsText = buildString {
                    if (speakTts) append("조기종료. ")
                    append("정타 ${hitCount}개, 타율 ${battingAvgPct}퍼센트. ")
                    if (avgReaction >= 0L) append("평균 반응속도 %.1f초.".format(avgReaction / 1000.0))
                }
                ttsManager.speakWithDone(ttsText) {
                    runOnUiThread {
                        btnStart?.isEnabled           = true
                        btnSwingPitchMinus?.isEnabled = true
                        btnSwingPitchPlus?.isEnabled  = true
                    }
                }
            }

            val userId    = getSharedPreferences("UserInfo", MODE_PRIVATE).getString("id", "anonymous") ?: "anonymous"
            val db        = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            val sessionId = System.currentTimeMillis().toString()
            val sessionData = hashMapOf(
                "생성일시"   to com.google.firebase.Timestamp.now(),
                "목표투구수" to targetPitches,
                "실제투구수" to actualPitches,
                "조기종료"   to true,
                "허용오차"   to PITCH_TOLERANCE,
                "종합결과"   to hashMapOf(
                    "정타수"       to hitCount,
                    "파울수"       to foulCount,
                    "스트라이크수" to strikeCount,
                    "타율"         to battingAvg,
                    "베이스정답수" to successCount,
                    "베이스정답률" to baseCorrectPct,
                    "반응속도평균" to avgReaction,
                    "반응속도최소" to (reactionTimes.minOrNull() ?: -1L),
                    "반응속도최대" to (reactionTimes.maxOrNull() ?: -1L)
                )
            )
            val sessionRef = db.collection("users").document(userId).collection("훈련기록").document(sessionId)
            sessionRef.set(sessionData)
                .addOnSuccessListener {
                    perPitchRecords.forEachIndexed { index, record ->
                        sessionRef.collection("투구별기록").document("${index + 1}번투구").set(record)
                    }
                }
                .addOnFailureListener { e -> Log.e("Firebase", "조기종료 업로드 실패: ${e.message}") }

            val statsPref = getSharedPreferences("TrainingStats_$userId", MODE_PRIVATE)
            val editor    = statsPref.edit()
            editor.putInt  ("count",               minOf(statsPref.getInt("count", 0) + 1, 10))
            editor.putFloat("sum_batting_avg",      statsPref.getFloat("sum_batting_avg",      0f) + battingAvg)
            editor.putFloat("sum_base_correct_pct", statsPref.getFloat("sum_base_correct_pct", 0f) + baseCorrectPct)
            editor.putFloat("sum_reaction",         statsPref.getFloat("sum_reaction",         0f) + avgReaction.toFloat())
            editor.putFloat("sum_hit",              statsPref.getFloat("sum_hit",              0f) + hitCount.toFloat())
            editor.putFloat("sum_foul",             statsPref.getFloat("sum_foul",             0f) + foulCount.toFloat())
            editor.putFloat("sum_strike",           statsPref.getFloat("sum_strike",           0f) + strikeCount.toFloat())
            editor.putFloat("sum_base_correct",     statsPref.getFloat("sum_base_correct",     0f) + successCount.toFloat())
            val totalCount = statsPref.getInt("total_count", 0) + 1
            editor.putInt  ("total_count",                totalCount)
            editor.putFloat("total_sum_batting_avg",      statsPref.getFloat("total_sum_batting_avg",      0f) + battingAvg)
            editor.putFloat("total_sum_base_correct_pct", statsPref.getFloat("total_sum_base_correct_pct", 0f) + baseCorrectPct)
            editor.putFloat("total_sum_reaction",         statsPref.getFloat("total_sum_reaction",         0f) + avgReaction.toFloat())
            editor.putFloat("total_sum_hit",              statsPref.getFloat("total_sum_hit",              0f) + hitCount.toFloat())
            editor.putFloat("total_sum_foul",             statsPref.getFloat("total_sum_foul",             0f) + foulCount.toFloat())
            editor.putFloat("total_sum_strike",           statsPref.getFloat("total_sum_strike",           0f) + strikeCount.toFloat())
            editor.putFloat("total_sum_base_correct",     statsPref.getFloat("total_sum_base_correct",     0f) + successCount.toFloat())
            editor.apply()
        } else if (speakTts) {
            ttsManager.speakWithDone("조기종료") {
                runOnUiThread {
                    btnStart?.isEnabled           = true
                    btnSwingPitchMinus?.isEnabled = true
                    btnSwingPitchPlus?.isEnabled  = true
                }
            }
        }

        val ttsWillPlay = (actualPitches > 0 && showSummary) || (actualPitches == 0 && speakTts)
        runOnUiThread {
            tvStatus?.text = if (speakTts) "조기 종료" else ""
            if (speakTts) tvStatus?.setTextColor(0xFFF87171.toInt())
            tvResult?.text             = ""
            tvSwingPitchProgress?.text = ""
            btnStart?.isEnabled        = false
            btnStart?.text             = "다시 훈련"
            btnSwingPitchMinus?.isEnabled = false
            btnSwingPitchPlus?.isEnabled  = false
            updateSimpleStatus(if (speakTts) "조기종료" else "")
            resetBaseVisuals()
            ballTrackView?.reset()
            if (isAdmin && actualPitches > 0 && showSummary) {
                showTrainingSummary(
                    displayCount = actualPitches,
                    dialogTitle  = "조기 종료 결과 (${actualPitches}/${targetPitches}회)"
                )
            } else if (!ttsWillPlay) {
                btnStart?.isEnabled           = true
                btnSwingPitchMinus?.isEnabled = true
                btnSwingPitchPlus?.isEnabled  = true
            }
        }
    }

    // ─────────────────────────────────────────────────────
    // 비관리자 훈련 시작 (양쪽 버튼 동시 → 직접 호출)
    // ─────────────────────────────────────────────────────
    private fun startSimpleTraining() {
        if (tutorialManager.isRunning) return
        btAudioLost      = false
        baseAzimuth      = null
        isTraining       = true
        currentPitchNum  = 1
        successCount     = 0
        hitCount         = 0
        foulCount        = 0
        strikeCount      = 0
        reactionTimes.clear()
        perPitchRecords.clear()
        tutorialManager.targetPitchCount = targetPitches
        startGame()
    }

    // ─────────────────────────────────────────────────────
    // 튜토리얼 3단계: 배트 각도 체험
    //   목표 각도(BATTING_ANGLE_DEG) ± PITCH_TOLERANCE 범위에
    //   5초간 유지하면 notifyStep3Done() 으로 다음 단계 진행
    // ─────────────────────────────────────────────────────
    private fun startAngleCalibration() {
        angleCalibJob?.cancel()
        angleCalibJob = scope.launch {
            // startGame() 과 동일한 패턴으로 초기화 — 0→200ms→1 순서
            if (bleConnected) {
                bleManager.sendControl(0)
                delay(200L)
                bleManager.sendControl(1)
            }
            delay(300)

            outer@ while (isActive) {
                val angle = currentPitchDeg
                val diff  = angle - BATTING_ANGLE_DEG
                val nowInRange = abs(diff) <= PITCH_TOLERANCE

                if (!nowInRange) {
                    // speakAndWait 로 TTS 완료 후 다음 각도 안내
                    val direction = if (diff > 0) "배트를 더 내리세요." else "배트를 조금 올리세요."
                    ttsManager.speakAndWait("현재 ${angle.toInt()}도. $direction", Locale.KOREAN)
                    // TTS 동안 펌웨어 스트리밍이 타임아웃될 수 있으므로 재활성화
                    if (bleConnected) bleManager.sendControl(1)
                    delay(300)
                } else {
                    // 목표 범위 진입 — 현재 각도 안내 후 카운트다운 시작
                    ttsManager.speakAndWait(
                        "목표 각도 ${angle.toInt()}도입니다. 이 자세를 유지하세요.",
                        Locale.KOREAN
                    )
                    if (bleConnected) bleManager.sendControl(1)

                    for (i in 5 downTo 1) {
                        // 카운트다운 중 범위 이탈 확인
                        if (abs(currentPitchDeg - BATTING_ANGLE_DEG) > PITCH_TOLERANCE) {
                            val curr = currentPitchDeg
                            val d    = curr - BATTING_ANGLE_DEG
                            val dir  = if (d > 0) "배트를 더 내리세요." else "배트를 조금 올리세요."
                            ttsManager.speakAndWait(
                                "범위를 벗어났습니다. 현재 ${curr.toInt()}도. $dir",
                                Locale.KOREAN
                            )
                            if (bleConnected) bleManager.sendControl(1)
                            delay(300)
                            continue@outer
                        }
                        ttsManager.speakAndWait("$i", Locale.KOREAN)
                    }

                    // 최종 범위 확인 후 완료
                    if (abs(currentPitchDeg - BATTING_ANGLE_DEG) <= PITCH_TOLERANCE) {
                        ttsManager.speakAndWait("잘 하셨습니다. 스윙할 때 이 각도로 스윙하면 됩니다.", Locale.KOREAN)
                        delay(1000)
                        // 스트리밍 중지 (startGame() 에서 sendControl(0→1) 로 재시작)
                        if (bleConnected) bleManager.sendControl(0)
                        tutorialManager.notifyStep3Done()
                        return@launch
                    }
                    // 범위 이탈 → 안내 재시작
                    if (bleConnected) bleManager.sendControl(1)
                    delay(300)
                }
            }
            // 코루틴 취소 시에도 스트리밍 정리
            if (bleConnected) bleManager.sendControl(0)
        }
    }

    // ─────────────────────────────────────────────────────
    // 훈련 흐름 제어
    // ─────────────────────────────────────────────────────
    private fun scheduleNextOrFinish(success: Boolean) {
        if (tutorialManager.isRunning) {
            // step 6에서만 베이스 정답 카운트 누적 (결과 TTS에 활용)
            if (tutorialManager.currentStep == 6 && success) successCount++
            if (currentPitchNum >= targetPitches) {
                when (tutorialManager.currentStep) {
                    4    -> { isTraining = false; tutorialManager.notifyStep4Done() }
                    6    -> tutorialManager.notifyStep6Done(hitCount, foulCount, strikeCount)
                    else -> { isTraining = false; tutorialManager.notifyStep4Done() }
                }
            } else {
                launchNextTrainingPitch()
            }
            return
        }
        if (success) successCount++
        if (currentPitchNum >= targetPitches) finishTraining()
        else launchNextTrainingPitch()
    }

    private fun launchNextTrainingPitch() {
        currentPitchNum++
        tvSwingPitchProgress?.text = "${currentPitchNum}/${targetPitches}"
        startGame()
        if (isAdmin) resetAndShowLiveGraphs()
    }

    private fun finishTraining() {
        if (tutorialManager.isRunning) {
            isTraining = false
            audioTrack?.stop()
            if (tutorialManager.currentStep == 6) {
                tutorialManager.notifyStep6Done(hitCount, foulCount, strikeCount)
            }
            return
        }
        isTraining = false
        tvStatus?.text = "훈련 완료!"
        tvStatus?.setTextColor(0xFF4ADE80.toInt())
        tvResult?.text             = ""
        tvSwingPitchProgress?.text = ""
        btnStart?.isEnabled        = false
        btnStart?.text             = "다시 훈련"
        btnSwingPitchMinus?.isEnabled = false
        btnSwingPitchPlus?.isEnabled  = false
        audioTrack?.stop()
        updateSimpleStatus("훈련 완료!")

        val battingAvgPct     = if (targetPitches > 0) (hitCount.toFloat() / targetPitches * 100).toInt() else 0
        val avgReactionForTts = if (reactionTimes.isNotEmpty()) reactionTimes.average().toLong() else -1L
        val ttsText = buildString {
            append("훈련 완료. ")
            append("정타 ${hitCount}개, 타율 ${battingAvgPct}퍼센트. ")
            if (avgReactionForTts >= 0L) append("평균 반응속도 %.1f초.".format(avgReactionForTts / 1000.0))
        }
        ttsManager.speakWithDone(ttsText) {
            runOnUiThread {
                btnStart?.isEnabled           = true
                btnSwingPitchMinus?.isEnabled = true
                btnSwingPitchPlus?.isEnabled  = true
            }
        }

        val battingAvg     = if (targetPitches > 0) hitCount.toFloat() / targetPitches else 0f
        val avgReaction    = if (reactionTimes.isNotEmpty()) reactionTimes.average().toLong() else -1L
        val baseCorrectPct = if (hitCount > 0) successCount.toFloat() / hitCount * 100f else 0f
        val userId         = getSharedPreferences("UserInfo", MODE_PRIVATE).getString("id", "anonymous") ?: "anonymous"
        val db             = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val sessionId      = System.currentTimeMillis().toString()

        val sessionData = hashMapOf(
            "생성일시"   to com.google.firebase.Timestamp.now(),
            "목표투구수" to targetPitches,
            "허용오차"   to PITCH_TOLERANCE,
            "종합결과"   to hashMapOf(
                "정타수"       to hitCount,
                "파울수"       to foulCount,
                "스트라이크수" to strikeCount,
                "타율"         to battingAvg,
                "베이스정답수" to successCount,
                "베이스정답률" to baseCorrectPct,
                "반응속도평균" to avgReaction,
                "반응속도최소" to (reactionTimes.minOrNull() ?: -1L),
                "반응속도최대" to (reactionTimes.maxOrNull() ?: -1L)
            )
        )
        val sessionRef = db.collection("users").document(userId).collection("훈련기록").document(sessionId)
        sessionRef.set(sessionData)
            .addOnSuccessListener {
                perPitchRecords.forEachIndexed { index, record ->
                    sessionRef.collection("투구별기록").document("${index + 1}번투구").set(record)
                }
            }
            .addOnFailureListener { e -> Log.e("Firebase", "업로드 실패: ${e.message}") }

        val statsPref = getSharedPreferences("TrainingStats_$userId", MODE_PRIVATE)
        val editor    = statsPref.edit()
        editor.putInt  ("count",               minOf(statsPref.getInt("count", 0) + 1, 10))
        editor.putFloat("sum_batting_avg",      statsPref.getFloat("sum_batting_avg",      0f) + battingAvg)
        editor.putFloat("sum_base_correct_pct", statsPref.getFloat("sum_base_correct_pct", 0f) + baseCorrectPct)
        editor.putFloat("sum_reaction",         statsPref.getFloat("sum_reaction",         0f) + avgReaction.toFloat())
        editor.putFloat("sum_hit",              statsPref.getFloat("sum_hit",              0f) + hitCount.toFloat())
        editor.putFloat("sum_foul",             statsPref.getFloat("sum_foul",             0f) + foulCount.toFloat())
        editor.putFloat("sum_strike",           statsPref.getFloat("sum_strike",           0f) + strikeCount.toFloat())
        editor.putFloat("sum_base_correct",     statsPref.getFloat("sum_base_correct",     0f) + successCount.toFloat())
        val totalCount = statsPref.getInt("total_count", 0) + 1
        editor.putInt  ("total_count",                totalCount)
        editor.putFloat("total_sum_batting_avg",      statsPref.getFloat("total_sum_batting_avg",      0f) + battingAvg)
        editor.putFloat("total_sum_base_correct_pct", statsPref.getFloat("total_sum_base_correct_pct", 0f) + baseCorrectPct)
        editor.putFloat("total_sum_reaction",         statsPref.getFloat("total_sum_reaction",         0f) + avgReaction.toFloat())
        editor.putFloat("total_sum_hit",              statsPref.getFloat("total_sum_hit",              0f) + hitCount.toFloat())
        editor.putFloat("total_sum_foul",             statsPref.getFloat("total_sum_foul",             0f) + foulCount.toFloat())
        editor.putFloat("total_sum_strike",           statsPref.getFloat("total_sum_strike",           0f) + strikeCount.toFloat())
        editor.putFloat("total_sum_base_correct",     statsPref.getFloat("total_sum_base_correct",     0f) + successCount.toFloat())
        editor.apply()

        if (isAdmin) showTrainingSummary()
    }

    // ─────────────────────────────────────────────────────
    // 훈련 종합 결과 다이얼로그 (관리자 전용)
    // ─────────────────────────────────────────────────────
    private fun showTrainingSummary(
        displayCount: Int = targetPitches,
        dialogTitle: String = "훈련 종합 결과"
    ) {
        val battingAvg     = if (displayCount > 0) hitCount.toFloat() / displayCount else 0f
        val avgReaction    = if (reactionTimes.isNotEmpty()) reactionTimes.average().toLong() else -1L
        val baseCorrectPct = if (hitCount > 0) successCount.toFloat() / hitCount * 100f else 0f

        val summaryText = buildString {
            appendLine("총 타석         ${displayCount}회")
            appendLine()
            appendLine("정타 (볼 맞힘)   ${hitCount}회")
            appendLine("파울            ${foulCount}회")
            appendLine("스트라이크      ${strikeCount}회")
            appendLine()
            appendLine("베이스 정답     ${successCount}회")
            if (hitCount > 0) appendLine("베이스 정답률   ${"%.0f".format(baseCorrectPct)}%  (${successCount}/${hitCount})")
            appendLine()
            appendLine("타율            ${"%.3f".format(battingAvg)}  (${hitCount}/${displayCount})")
            appendLine()
            if (avgReaction >= 0L) {
                appendLine("주루 반응속도 평균   ${avgReaction} ms")
                if (reactionTimes.size > 1) appendLine("  최소 ${reactionTimes.minOrNull()} ms  /  최대 ${reactionTimes.maxOrNull()} ms")
            } else {
                appendLine("주루 반응속도   기록 없음")
            }
            appendLine()
            appendLine("── SET 초기 각도 ──")
            allSetAngles.forEachIndexed { i, a -> appendLine("  #${i + 1}: ${"%.1f".format(a)}°") }
        }

        val totalData  = perPitchFullHistory.size
        val heightPx   = (340 * resources.displayMetrics.density).toInt()
        var currentIdx = 0

        val tv = TextView(this).apply {
            text = summaryText; textSize = 14f
            setTextColor(0xFFE2E8F0.toInt()); typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(0xFF0A1423.toInt()); setPadding(56, 40, 56, 24); setLineSpacing(0f, 1.3f)
        }
        val btnPrev = Button(this).apply { text = "◀"; textSize = 13f; setTextColor(0xFF64B4FF.toInt()) }
        val tvPitchNum = TextView(this).apply {
            textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnNext = Button(this).apply { text = "▶"; textSize = 13f; setTextColor(0xFF64B4FF.toInt()) }
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(40, 8, 40, 4); addView(btnPrev); addView(tvPitchNum); addView(btnNext)
        }
        val tvFeedback = TextView(this).apply {
            textSize = 14f; gravity = android.view.Gravity.CENTER; setPadding(56, 4, 56, 8)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val tvLabelCombined = TextView(this).apply {
            text = "READY 이후 배트 각도 궤적"; textSize = 13f
            setTextColor(0xFF93C5FD.toInt()); setPadding(56, 4, 56, 4)
        }
        val graphCombined = SwingGraphView(this)

        fun updateGraphs(idx: Int) {
            val fullHist    = if (idx < perPitchFullHistory.size)          perPitchFullHistory[idx]          else ArrayList()
            val hitTime     = if (idx < perPitchHitTimeRelFull.size)       perPitchHitTimeRelFull[idx]       else -1L
            val winOpen     = if (idx < perPitchWinOpenRelFull.size)       perPitchWinOpenRelFull[idx]       else -1L
            val winClose    = if (idx < perPitchWinCloseRelFull.size)      perPitchWinCloseRelFull[idx]      else -1L
            val minAngleRel = if (idx < perPitchMinAngleRelFull.size)      perPitchMinAngleRelFull[idx]      else -1L
            val minDeg      = if (idx < perPitchMinAngleDegList.size)      perPitchMinAngleDegList[idx]      else Float.MAX_VALUE
            val pitchWinSt  = if (idx < perPitchPitchWinStartRelFull.size) perPitchPitchWinStartRelFull[idx] else -1L
            val pitchTtsSt  = if (idx < perPitchPitchTtsStartRelFull.size) perPitchPitchTtsStartRelFull[idx] else -1L
            val phase2Rel   = if (idx < perPitchPhase2RelFull.size)        perPitchPhase2RelFull[idx].coerceAtLeast(0L) else 0L
            val winOpenDeg  = if (idx < perPitchWinOpenDeg.size)           perPitchWinOpenDeg[idx]           else 0f
            val winCloseDeg = if (idx < perPitchWinCloseDeg.size)          perPitchWinCloseDeg[idx]          else 0f

            val record   = perPitchRecords.getOrNull(idx)
            val judgment = record?.get("판정")  as? String ?: ""
            val feedback = record?.get("피드백") as? String ?: ""
            val (jc, fc) = when {
                judgment.startsWith("정타")       -> 0xFF4ADE80.toInt() to 0xFF86EFAC.toInt()
                judgment.startsWith("파울")       -> 0xFFFBBF24.toInt() to 0xFFFDE68A.toInt()
                judgment.startsWith("스트라이크") -> 0xFFF87171.toInt() to 0xFFFCA5A5.toInt()
                else                              -> 0xFFFFFFFF.toInt() to 0xFFAAAAAA.toInt()
            }
            tvPitchNum.text = "${idx + 1} / $totalData   [$judgment]"
            tvPitchNum.setTextColor(jc)
            tvFeedback.text = "💬 $feedback"
            tvFeedback.setTextColor(fc)

            val segCombined = ArrayList(fullHist.filter { it.first >= phase2Rel }
                .map { Pair(it.first - phase2Rel, it.second) })
            fun toCombined(ms: Long) = ms - phase2Rel
            val cHit        = if (hitTime     >= 0L) toCombined(hitTime)              else -1L
            val cWinOpen    = if (winOpen     >= 0L) maxOf(0L, toCombined(winOpen))   else -1L
            val cWinClose   = if (winClose    >= 0L) toCombined(winClose)             else -1L
            val cMinAngle   = if (minAngleRel >= 0L) toCombined(minAngleRel)          else -1L
            val cPitchTtsSt = if (pitchTtsSt  >= 0L) toCombined(pitchTtsSt)          else -1L
            val cPitchWin   = if (pitchWinSt  >= 0L) toCombined(pitchWinSt)          else -1L

            graphCombined.setData(segCombined, cHit, BATTING_ANGLE_DEG)
            graphCombined.setTolerance(PITCH_TOLERANCE)
            graphCombined.setWindowAngles(winOpenDeg, winCloseDeg)
            if (cPitchTtsSt >= 0L) graphCombined.setPitchTtsStart(cPitchTtsSt)
            if (cPitchWin   >= 0L) graphCombined.setPitchWindowStart(cPitchWin)
            if (cWinOpen    >= 0L) graphCombined.setHitWindowOpen(cWinOpen)
            if (cWinClose   >= 0L) graphCombined.setHitWindowClose(cWinClose)
            if (cMinAngle   >= 0L && minDeg < Float.MAX_VALUE) graphCombined.setMinAngle(cMinAngle, minDeg)
            btnPrev.isEnabled = idx > 0
            btnNext.isEnabled = idx < totalData - 1
        }

        if (totalData > 0) updateGraphs(0) else tvPitchNum.text = "0 / 0"
        btnPrev.setOnClickListener { if (currentIdx > 0) { currentIdx--; updateGraphs(currentIdx) } }
        btnNext.setOnClickListener { if (currentIdx < totalData - 1) { currentIdx++; updateGraphs(currentIdx) } }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0A1423.toInt())
            addView(tv); addView(navRow); addView(tvFeedback); addView(tvLabelCombined)
            addView(graphCombined, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx))
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF0A1423.toInt()); addView(container) }
        lastResultDialog = AlertDialog.Builder(this).setTitle(dialogTitle).setView(scroll).setPositiveButton("확인", null).create()
        lastResultDialog?.show()
        btnResultView?.visibility = View.VISIBLE
        if (totalData > 0) {
            graphCombined.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    graphCombined.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    graphCombined.invalidate()
                }
            })
        }
    }

    private fun resetAndShowLiveGraphs() {
        pitchHistory.clear()
        hitTimeRelMs        = -1L
        pitchRecordStart    = System.currentTimeMillis()
        bleGraphStarted     = false
        bleGraphPacketCount = 0L
        isRecording         = true
        swingGraphView?.setLiveSource(pitchHistory, BATTING_ANGLE_DEG)
        swingGraphView?.setTolerance(PITCH_TOLERANCE)
        swingGraphView?.visibility = View.VISIBLE
    }

    // ─────────────────────────────────────────────────────
    // 베이스 시각 활성화 / 초기화
    // ─────────────────────────────────────────────────────
    private fun activateBothBases() {
        base1Glow?.visibility = View.VISIBLE; base3Glow?.visibility = View.VISIBLE
        tvBase1Label?.setTextColor(0xFF4ADE80.toInt()); tvBase3Label?.setTextColor(0xFF4ADE80.toInt())
    }
    private fun resetBaseVisuals() {
        base1Glow?.visibility = View.INVISIBLE; base3Glow?.visibility = View.INVISIBLE
        tvBase1Label?.setTextColor(0xFF94A3B8.toInt()); tvBase3Label?.setTextColor(0xFF94A3B8.toInt())
    }

    // ─────────────────────────────────────────────────────
    // 스윙 결과 그래프 다이얼로그 (비훈련 단일 투구 — 관리자 전용)
    // ─────────────────────────────────────────────────────
    private fun showSwingGraph() {
        val history = ArrayList(pitchHistory)
        if (history.isEmpty()) return
        val contactH = BATTER_HEIGHT + sin(BATTING_ANGLE_DEG * PI.toFloat() / 180f) * BAT_REACH
        val batH = when {
            !swingBatHeight.isNaN() -> swingBatHeight
            swingDetected           -> BATTER_HEIGHT + sin(swingPitchDeg * PI.toFloat() / 180f) * BAT_REACH
            else                    -> contactH
        }
        val heightPx     = (300 * resources.displayMetrics.density).toInt()
        val parabolaView = BallParabolaView(this).apply { setData(PITCHER_DIST, PITCHER_HEIGHT, contactH, batH, BALL_ARC, swingIsHit) }
        val graphView    = SwingGraphView(this).apply {
            setData(history, hitTimeRelMs, BATTING_ANGLE_DEG); setTolerance(PITCH_TOLERANCE)
            setWindowAngles(windowOpenPitchDeg, windowClosePitchDeg)
            if (graphPitchWindowStartMs  >= 0L) setPitchWindowStart(graphPitchWindowStartMs)
            if (graphHitWindowOpenRelMs  >= 0L) setHitWindowOpen(graphHitWindowOpenRelMs)
            if (graphHitWindowCloseRelMs >= 0L) setHitWindowClose(graphHitWindowCloseRelMs)
            if (graphMinSearchStartRelMs >= 0L) setMinAngleSearch(graphMinSearchStartRelMs)
            if (minBatAngleAbsMs > 0L && minBatAngleDeg < Float.MAX_VALUE) setMinAngle(minBatAngleRelMs, minBatAngleDeg)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0A1423.toInt())
            addView(parabolaView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx))
            addView(graphView,    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx))
        }
        AlertDialog.Builder(this).setTitle("스윙 결과").setView(ScrollView(this).apply { addView(container) }).setPositiveButton("확인", null).show()
    }

    // ─────────────────────────────────────────────────────
    // 오디오 중단
    // ─────────────────────────────────────────────────────
    private fun stopAudio() {
        audioJob?.cancel()
        spatialAudio.stopBeep()
        audioTrack?.pause(); audioTrack?.flush(); audioTrack?.play()
    }

    // ─────────────────────────────────────────────────────
    // 유틸리티
    // ─────────────────────────────────────────────────────
    private fun magnitude(v: FloatArray) = sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2])

    private fun initAudioTrack() {
        audioTrack?.stop(); audioTrack?.release()
        val sr     = 44100
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(minBuf * 4).setTransferMode(AudioTrack.MODE_STREAM).build()
        audioTrack?.play()
    }

    // ─────────────────────────────────────────────────────
    // BLE 권한
    // ─────────────────────────────────────────────────────
    private fun requestBlePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)    != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_BLE_PERM)
        else bleManager.startScan()
    }

    private fun hasBlePermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)    == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLE_PERM && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startBleScan()
    }

    private fun startBleScan() {
        if (isAdmin) { btnBleConnect?.isEnabled = false; btnBleConnect?.text = "연결 중..." }
        ttsManager.speak("배트 연결을 시도하고 있습니다.")
        bleManager.startScan()
    }

    // ─────────────────────────────────────────────────────
    // 생명주기
    // ─────────────────────────────────────────────────────
    override fun onResume() {
        super.onResume()
        initAudioTrack()
        linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        rotationSensor  = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        linearAccelSensor?.let { sensorManager.registerListener(swingListener,      it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscopeSensor?.let   { sensorManager.registerListener(swingListener,      it, SensorManager.SENSOR_DELAY_GAME) }
        rotationSensor?.let    { sensorManager.registerListener(orientationListener, it, SensorManager.SENSOR_DELAY_GAME) }
        audioManager.registerAudioDeviceCallback(btAudioCallback, Handler(Looper.getMainLooper()))
    }

    override fun onPause() {
        super.onPause()
        audioManager.unregisterAudioDeviceCallback(btAudioCallback)
        sensorManager.unregisterListener(swingListener)
        sensorManager.unregisterListener(orientationListener)
        bleManager.stopScan()
        gameJob?.cancel()
        audioJob?.cancel()
        spatialAudio.stopBeep()
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
        audioTrack?.stop(); audioTrack?.release()
        spatialAudio.release()
        bleManager.disconnect()
        scope.cancel()
    }
}
