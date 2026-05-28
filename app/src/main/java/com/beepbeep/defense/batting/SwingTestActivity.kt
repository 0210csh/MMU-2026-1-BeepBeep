package com.beepbeep.defense.batting

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.beepbeep.defense.PendingUploadManager
import com.beepbeep.defense.R
import com.beepbeep.defense.audio.SpatialAudioEngine
import com.beepbeep.defense.hardware.BleManager
import kotlinx.coroutines.*
import kotlin.math.*
import kotlin.random.Random
import java.util.Locale

class SwingTestActivity : AppCompatActivity() {

    companion object {
        internal const val REQ_BLE_PERM = 100
    }

    internal val BATTING_ANGLE_DEG = SwingGraphView.REF_PITCH_ANGLE_DEG
    internal val pitchYPosition: Float =
        ((BATTING_ANGLE_DEG / 43f) + 0.5f).coerceIn(0.1f, 0.9f)

    // ── 관리자 여부 ──────────────────────────────────────
    internal var isAdmin = false

    // ── 관리자 전용 뷰 (nullable — 비관리자 레이아웃에는 없음) ──
    internal var tvStatus:             TextView?         = null
    internal var tvResult:             TextView?         = null
    internal var btnStart:             Button?           = null
    internal var ballTrackView:        BallTrackView?    = null
    internal var swingGraphView:       SwingGraphView?   = null
    internal var liveBallParabolaView: BallParabolaView? = null
    internal var base1Container:       FrameLayout?      = null
    internal var base3Container:       FrameLayout?      = null
    internal var base1Glow:            View?             = null
    internal var base3Glow:            View?             = null
    internal var tvBase1Label:         TextView?         = null
    internal var tvBase3Label:         TextView?         = null
    internal var btnSwingPitchMinus:   Button?           = null
    internal var btnSwingPitchPlus:    Button?           = null
    internal var tvSwingPitchCount:    TextView?         = null
    internal var tvSwingPitchProgress: TextView?         = null
    internal var btnBleConnect:        Button?           = null
    internal var tvBleStatus:          TextView?         = null
    internal var tvBatteryLevel:       TextView?         = null
    internal var btnResultView:        Button?           = null
    internal var lastResultDialog:     AlertDialog?      = null

    // ── 훈련 상태 ────────────────────────────────────────
    internal var targetPitches   = 3
    internal var currentPitchNum = 0
    internal var successCount    = 0
    @Volatile internal var isTraining = false
    internal var hitCount        = 0
    internal var foulCount       = 0
    internal var strikeCount     = 0
    internal val reactionTimes   = mutableListOf<Long>()

    internal val perPitchRecords    = mutableListOf<HashMap<String, Any?>>()
    internal val currentPitchRecord = HashMap<String, Any?>()

    internal var phase2StartAbsMs: Long = 0L
    internal var phase3StartAbsMs: Long = 0L
    internal val perPitchFullHistory          = mutableListOf<ArrayList<Pair<Long, Float>>>()
    internal val perPitchHitTimeRelFull       = mutableListOf<Long>()
    internal val perPitchWinOpenRelFull       = mutableListOf<Long>()
    internal val perPitchWinCloseRelFull      = mutableListOf<Long>()
    internal val perPitchMinSearchRelFull     = mutableListOf<Long>()
    internal val perPitchMinAngleRelFull      = mutableListOf<Long>()
    internal val perPitchMinAngleDegList      = mutableListOf<Float>()
    internal val perPitchPitchWinStartRelFull = mutableListOf<Long>()
    internal val perPitchPitchTtsStartRelFull = mutableListOf<Long>()
    internal val perPitchPhase2RelFull        = mutableListOf<Long>()
    internal val perPitchPhase3RelFull        = mutableListOf<Long>()
    internal val perPitchWinOpenDeg           = mutableListOf<Float>()
    internal val perPitchWinCloseDeg          = mutableListOf<Float>()

    // ── Sensors ─────────────────────────────────────────
    internal lateinit var sensorManager: SensorManager
    internal var rotationSensor:         Sensor? = null

    @Volatile internal var linearAccelMag:  Float = 0f
    @Volatile internal var gyroMag:         Float = 0f
    @Volatile internal var currentPitchDeg: Float = 0f

    internal var setAngleThisPitch:  Float = 0f
    internal val setToReadyHistory   = ArrayList<Pair<Long, Float>>()
    internal val readyToPitchHistory = ArrayList<Pair<Long, Float>>()
    internal val pitchToEndHistory   = ArrayList<Pair<Long, Float>>()
    internal val allSetAngles         = mutableListOf<Float>()
    internal val perPitchPhase1Data  = mutableListOf<ArrayList<Pair<Long, Float>>>()
    internal val perPitchPhase2Data  = mutableListOf<ArrayList<Pair<Long, Float>>>()
    internal val perPitchPhase3Data  = mutableListOf<ArrayList<Pair<Long, Float>>>()
    @Volatile internal var recordingPhase:       Int  = 0
    @Volatile internal var phaseRecordStartTime: Long = 0L
    @Volatile internal var hitTimePhase3Ms:      Long = -1L
    internal val perPitchHitTimesPhase3 = mutableListOf<Long>()

    // ── 임계값 ──────────────────────────────────────────
    internal val HIT_ACCEL_THRESHOLD = 65f
    internal val HIT_GYRO_THRESHOLD  = 35f
    internal val MIN_ACCEL_THRESHOLD = 48f
    internal val MIN_GYRO_THRESHOLD  = 35f
    internal val PITCH_TOLERANCE     = 15f

    // ── 물리 상수 ────────────────────────────────────────
    internal val PITCHER_DIST    = 6.53f
    internal val PITCHER_HEIGHT  = 1.0f
    internal val BALL_ARC        = 0.3f
    internal val BATTER_HEIGHT   = 1.0f
    internal val BAT_REACH       = 0.6f
    internal val HEIGHT_TOLERANCE = 0.2f

    // ── 게임 상태 ────────────────────────────────────────
    internal var targetBase = 1

    @Volatile internal var hitWindowActive:         Boolean = false
    @Volatile internal var preWindowActive:         Boolean = false
    @Volatile internal var postWindowActive:        Boolean = false
    @Volatile internal var preWindowSwingDetected:  Boolean = false
    @Volatile internal var postWindowSwingDetected: Boolean = false
    @Volatile internal var minSwingDetected:        Boolean = false
    @Volatile internal var windowOpenPitchDeg:      Float   = 0f
    @Volatile internal var windowClosePitchDeg:     Float   = 0f
    @Volatile internal var minAngleSearchActive:    Boolean = false
    @Volatile internal var minBatAngleDeg:          Float   = Float.MAX_VALUE
    @Volatile internal var minBatAngleAbsMs:        Long    = -1L
    @Volatile internal var minBatAngleRelMs:        Long    = -1L

    internal var hitWindowOpenAbsMs:          Long = -1L
    internal var hitWindowCloseAbsMs:         Long = -1L
    internal var graphHitWindowOpenRelMs:     Long = -1L
    internal var graphHitWindowCloseRelMs:    Long = -1L
    internal var graphMinSearchStartRelMs:    Long = -1L
    internal var graphPitchWindowStartMs:     Long = -1L
    internal var graphPitchTtsStartMs:        Long = -1L

    @Volatile internal var testForceHit:   Boolean = false
    @Volatile internal var swingDetected:  Boolean = false
    @Volatile internal var swingIsHit:     Boolean = false
    @Volatile internal var gangSpoken:     Boolean = false
    @Volatile internal var swingPitchDeg:  Float   = 0f
    @Volatile internal var swingWasStrong: Boolean = false
    @Volatile internal var swingBatHeight: Float   = Float.NaN

    @Volatile internal var isWaitingForInput = false
    @Volatile internal var isResultSpeaking  = false
    internal var beepStartTime = 0L

    internal val pitchHistory = ArrayList<Pair<Long, Float>>()
    @Volatile internal var pitchRecordStart: Long    = 0L
    @Volatile internal var hitTimeRelMs:     Long    = -1L
    @Volatile internal var isRecording:      Boolean = false

    @Volatile internal var ballApproachProgress: Float = 0f
    @Volatile internal var divProgress:          Float = 0f

    // ── 헤드트래킹 ──────────────────────────────────────
    internal lateinit var headTracker: SwingHeadTracker

    // ── Coroutines ──────────────────────────────────────
    internal val scope    = CoroutineScope(Dispatchers.Default + SupervisorJob())
    internal var gameJob:  Job? = null
    internal var audioJob: Job? = null

    internal val ttsManager = SwingTtsManager(this)

    internal var audioTrack: AudioTrack? = null
    internal lateinit var spatialAudio: SpatialAudioEngine

    internal lateinit var bleManager: BleManager
    @Volatile internal var bleConnected    = false
    internal var bleGraphBaseMs:      Long    = 0L
    internal var bleGraphPacketCount: Long    = 0L
    internal var bleGraphStarted:     Boolean = false

    @Volatile internal var prevBtn1 = false
    @Volatile internal var prevBtn2 = false
    internal var batBtn1Job:        Job? = null
    internal var batBtn2Job:        Job? = null
    internal var baseBeepTimeoutJob: Job? = null

    // ── 튜토리얼 ────────────────────────────────────────
    internal lateinit var tutorialManager: SwingTutorialManager
    internal var angleCalibJob: Job? = null

    // ── BT 오디오 기기 감지 ──────────────────────────────
    internal lateinit var audioManager: AudioManager
    @Volatile internal var btAudioLost = false
    private val btAudioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val hasBtAudio = removedDevices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            if (!hasBtAudio) return
            if (btAudioLost) return
            if (!isTraining) return
            btAudioLost = true
            runOnUiThread {
                this@SwingTestActivity.earlyFinishTraining(speakTts = false, showSummary = false)
                scope.launch {
                    delay(200L)
                    ttsManager.speak("블루투스 이어폰 연결이 해제되어 훈련이 종료되었습니다.")
                }
            }
        }
    }

    // ── 네트워크 콜백 (오프라인 → 복구 자동 동기화) ──────────
    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (isTraining) return
            scope.launch(Dispatchers.IO) {
                if (PendingUploadManager.hasPendingBatting(this@SwingTestActivity)) {
                    PendingUploadManager.syncBatting(this@SwingTestActivity)
                }
            }
        }
    }

    // ── BLE 배트 센서 콜백 ───────────────────────────────
    private val bleCallback = object : BleManager.Callback {
        override fun onConnected() {
            bleConnected = true
            this@SwingTestActivity.updateSimpleBleStatus(connected = true)
            if (isAdmin) {
                btnBleConnect?.isEnabled = true; btnBleConnect?.text = "배트 센서 해제"
                tvBleStatus?.text = "● 연결됨"; tvBleStatus?.setTextColor(0xFF4ADE80.toInt())
            }
            scope.launch {
                delay(1_000L)
                val done = tutorialManager.syncTutorialDoneFromFirebase()
                if (!done && !tutorialManager.isRunning) {
                    ttsManager.speakAndWait("배트가 연결되었습니다.", Locale.KOREAN)
                    delay(500)
                    withContext(Dispatchers.Main) { tutorialManager.startTutorial() }
                } else {
                    ttsManager.speak("배트가 연결되었습니다")
                }
            }
        }

        override fun onReconnecting() {
            bleConnected = false
            this@SwingTestActivity.updateSimpleBleStatus(connected = false)
            if (isAdmin) {
                btnBleConnect?.isEnabled = false; btnBleConnect?.text = "재연결 중..."
                tvBleStatus?.text = "● 재연결 중..."; tvBleStatus?.setTextColor(0xFFFBBF24.toInt())
            }
        }

        override fun onDisconnected() {
            bleConnected = false
            this@SwingTestActivity.updateSimpleBleStatus(connected = false)
            if (isAdmin) {
                btnBleConnect?.isEnabled = true; btnBleConnect?.text = "배트 센서 연결"
                tvBleStatus?.text = "● 미연결"; tvBleStatus?.setTextColor(0xFFF87171.toInt())
            }
            ttsManager.speak("배트 연결이 끊겼습니다")
            scope.launch {
                delay(1000)
                withContext(Dispatchers.Main) {
                    if (!bleConnected && this@SwingTestActivity.hasBlePermissions())
                        this@SwingTestActivity.startBleScan()
                }
            }
        }

        override fun onBatteryLevel(level: Int) {
            if (bleConnected) this@SwingTestActivity.updateSimpleBleStatus(connected = true, battery = level)
            if (isAdmin) {
                tvBatteryLevel?.text = "배터리 ${level}%"
                tvBatteryLevel?.setTextColor(if (level <= 20) 0xFFF87171.toInt() else 0xFF64748B.toInt())
            }
        }

        override fun onPacket(packet: BleManager.SensorPacket) {
            linearAccelMag  = magnitude(packet.handleAccel)
            gyroMag         = magnitude(packet.handleGyro)
            currentPitchDeg = packet.handleEuler[1]
            this@SwingTestActivity.checkSwing()

            if (minAngleSearchActive && currentPitchDeg < minBatAngleDeg) {
                minBatAngleDeg   = currentPitchDeg
                minBatAngleAbsMs = System.currentTimeMillis()
                minBatAngleRelMs = minBatAngleAbsMs - pitchRecordStart
            }

            if (isAdmin) {
                val batH = BATTER_HEIGHT + sin(currentPitchDeg * PI.toFloat() / 180f) * BAT_REACH
                liveBallParabolaView?.updateLiveBat(batH)

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
            this@SwingTestActivity.handleBatButton(packet.btn1, packet.btn2)
        }
    }

    // ─────────────────────────────────────────────────────
    // onCreate
    // ─────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isAdmin = getSharedPreferences("AdminCache", MODE_PRIVATE).getBoolean("isAdmin", false)
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
                    isTraining = false
                    headTracker.reset()
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
                headTracker.reset()
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
        headTracker = SwingHeadTracker(spatialAudio)
        bleManager = BleManager(this)
        bleManager.setCallback(bleCallback)

        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

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
                headTracker.reset()
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLE_PERM &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startBleScan()
    }

    // ─────────────────────────────────────────────────────
    // 생명주기
    // ─────────────────────────────────────────────────────
    override fun onResume() {
        super.onResume()
        initAudioTrack()
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        rotationSensor?.let { sensorManager.registerListener(headTracker, it, SensorManager.SENSOR_DELAY_GAME) }
        audioManager.registerAudioDeviceCallback(btAudioCallback, Handler(Looper.getMainLooper()))
        if (!isTraining &&
            PendingUploadManager.hasPendingBatting(this) &&
            PendingUploadManager.isNetworkAvailable(this)) {
            scope.launch(Dispatchers.IO) {
                PendingUploadManager.syncBatting(this@SwingTestActivity)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        audioManager.unregisterAudioDeviceCallback(btAudioCallback)
        sensorManager.unregisterListener(headTracker)
        bleManager.stopScan()
        gameJob?.cancel()
        audioJob?.cancel()
        spatialAudio.stopBeep()
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
        ttsManager.shutdown()
        audioTrack?.stop(); audioTrack?.release()
        spatialAudio.release()
        bleManager.disconnect()
        scope.cancel()
    }
}
