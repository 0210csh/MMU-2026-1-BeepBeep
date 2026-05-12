package com.beepbeep.defense.batting

// ── Android 프레임워크 import ──────────────────────────────────────────────
import android.Manifest
import android.app.AlertDialog                  // 스윙 결과 그래프 다이얼로그 표시
import android.content.pm.PackageManager
import android.hardware.Sensor                  // 센서 종류 상수 (TYPE_GYROSCOPE 등)
import android.hardware.SensorEvent             // 센서 콜백에서 받는 이벤트 객체 (values 배열 포함)
import android.hardware.SensorEventListener     // 센서 이벤트를 수신하기 위한 인터페이스
import android.hardware.SensorManager           // 시스템 센서 서비스 접근 및 리스너 등록/해제
import android.media.AudioAttributes            // AudioTrack 생성 시 오디오 용도 설정 (USAGE_MEDIA 등)
import android.media.AudioFormat                // AudioTrack 포맷 설정 (샘플레이트, 채널, 인코딩)
import android.media.AudioTrack                 // PCM 오디오를 직접 스트리밍하는 저수준 오디오 클래스
import android.os.Build
import android.os.Bundle                        // Activity 상태 저장/복원에 쓰이는 키-값 묶음
import android.os.SystemClock                   // elapsedRealtimeNanos(): 반응속도 측정용 고정밀 시계
import android.speech.tts.TextToSpeech          // TTS 엔진 (SET·PITCH 발화)
import android.speech.tts.UtteranceProgressListener // TTS 발화 완료 콜백을 받기 위한 추상 클래스
import android.view.View                        // visibility(GONE/VISIBLE/INVISIBLE) 제어
import android.widget.Button                    // 시작/다시하기 버튼 위젯
import android.widget.FrameLayout               // 베이스 컨테이너 (클릭 영역)
import android.widget.LinearLayout              // 결과 다이얼로그 내부 뷰 컨테이너
import android.widget.ScrollView                // 결과 다이얼로그 스크롤 래퍼
import android.widget.TextView                  // 상태·결과 텍스트뷰
import android.widget.Toast                     // 오답 베이스 선택 시 짧은 안내 메시지
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

/**
 * 타격 시뮬레이션 v2
 *
 * v1(BaseRunReactionActivity)과 흐름 동일.
 * 차이점: pitchYPosition 이 랜덤이 아니라 개발자가 정해놓은
 * BATTING_ANGLE_DEG 상수에서 결정된다.
 * 사용자는 해당 각도에 맞게 폰을 기울여 스윙해야 한다.
 *
 * ── 개발자 설정값 ──
 *   BATTING_ANGLE_DEG : 타격 요구 각도(도). 0° = 수평, +양수 = 위로, -음수 = 아래로.
 *                       이 값만 바꾸면 공 높이와 요구 각도가 함께 변경된다.
 */
class SwingTestActivity : AppCompatActivity() {

    companion object {
        private const val REQ_BLE_PERM = 100
    }

    // ═══════════════════════════════════════════════════
    //  개발자 설정값 — 여기만 수정하면 됩니다
    // ═══════════════════════════════════════════════════
    private val BATTING_ANGLE_DEG = 0f
    // 타격 요구 각도 (절대각도, 도).
    //   0f  → 배트 수평 (중간 공)
    //  +15f → 배트 끝이 약간 위 (높은 공)
    //  -15f → 배트 끝이 약간 아래 (낮은 공)

    // ── pitchYPosition: BATTING_ANGLE_DEG 에서 자동 계산 (수정 불필요) ──
    private val pitchYPosition: Float =
        ((BATTING_ANGLE_DEG / 43f) + 0.5f).coerceIn(0.1f, 0.9f)
    // BallTrackView 에 전달하는 공 수직 위치값 (0.0~1.0).
    // 0.0=아래, 0.5=중간, 1.0=위. BATTING_ANGLE_DEG 에서 선형 변환.

    // ═══════════════════════════════════════════════════

    // ── Views ───────────────────────────────────────────
    private lateinit var tvStatus:             TextView
    // R.id.tvV2Status: 현재 게임 상태("SET", "PITCH", "쳐!", 결과 등) 표시

    private lateinit var tvResult:             TextView
    // R.id.tvV2Result: 반응속도·각도 등 보조 결과 표시

    private lateinit var btnStart:             Button
    // R.id.btnV2Start: 시작/다시하기 버튼. 게임 중 비활성화

    private lateinit var ballTrackView:        BallTrackView
    // R.id.v2BallTrackView: 공 궤적·수비수·베이스 커스텀 뷰.
    // setShowStrikeZone(false) 호출로 스트라이크존 UI 숨김

    private lateinit var swingGraphView:       SwingGraphView
    // R.id.v2SwingGraphView: 배트 각도 궤적 라이브 그래프 (시작 시 VISIBLE)

    private lateinit var liveBallParabolaView: BallParabolaView
    // R.id.v2LiveParabolaView: 공 포물선 궤적 라이브 그래프
    // swingGraphView 바로 위에 표시. 게임 중 이동 공 애니메이션 포함

    private lateinit var base1Container:       FrameLayout
    // R.id.v2Base1Container: 1루 베이스 클릭 영역 (130×130dp)

    private lateinit var base3Container:       FrameLayout
    // R.id.v2Base3Container: 3루 베이스 클릭 영역 (130×130dp)

    private lateinit var base1Glow:            View
    // R.id.v2Base1Glow: 1루 활성화 시 빛나는 효과 레이어. 평소 INVISIBLE

    private lateinit var base3Glow:            View
    // R.id.v2Base3Glow: 3루 활성화 시 빛나는 효과 레이어. base1Glow 와 동일 구조

    private lateinit var tvBase1Label:         TextView
    private lateinit var tvBase3Label:         TextView

    private lateinit var btnSwingPitchMinus:   Button
    private lateinit var btnSwingPitchPlus:    Button
    private lateinit var tvSwingPitchCount:    TextView
    private lateinit var tvSwingPitchProgress: TextView
    private lateinit var btnBleConnect:        Button
    private lateinit var tvBleStatus:          TextView

    private var targetPitches  = 10
    private var currentPitchNum = 0
    private var successCount   = 0
    private var isTraining     = false
    private var hitCount       = 0
    private var foulCount      = 0
    private var strikeCount    = 0
    private val reactionTimes  = mutableListOf<Long>()

    private val perPitchRecords    = mutableListOf<HashMap<String, Any?>>()
    private val currentPitchRecord = HashMap<String, Any?>()

    // ── Sensors ─────────────────────────────────────────
    private lateinit var sensorManager:        SensorManager
    // 시스템 센서 서비스. onResume/onPause 에서 리스너 등록·해제

    private var linearAccelSensor:             Sensor? = null
    // TYPE_LINEAR_ACCELERATION (중력 제거 가속도). 미지원 시 TYPE_ACCELEROMETER 폴백

    private var gyroscopeSensor:               Sensor? = null
    // TYPE_GYROSCOPE (각속도). null 이면 자이로 없는 기기 → gyroMag=0 으로 처리

    private var rotationSensor:                Sensor? = null
    // TYPE_GAME_ROTATION_VECTOR: 피치각·방위각 추출용. 자기장 간섭 없음

    private val rotMatrix   = FloatArray(9)
    // 3×3 회전 행렬. SensorManager.getRotationMatrixFromVector() 결과를 저장

    private val orientation = FloatArray(3)
    // [0]=방위각, [1]=피치, [2]=롤 (라디안). SensorManager.getOrientation() 결과

    private val gravity     = FloatArray(3)
    // TYPE_ACCELEROMETER 폴백 시 저역통과 필터로 추정한 중력 벡터 [x, y, z]

    private val LP_ALPHA    = 0.8f
    // 저역통과 필터 계수. 0 에 가까울수록 빠르고, 1 에 가까울수록 안정적

    @Volatile private var linearAccelMag:      Float = 0f
    // 최신 선형 가속도 크기 (m/s²). 센서 스레드에서 갱신 → checkSwing() 에서 읽음

    @Volatile private var gyroMag:             Float = 0f
    // 최신 각속도 크기 (rad/s). 센서 스레드에서 갱신 → checkSwing() 에서 읽음

    @Volatile private var currentPitchDeg:     Float = 0f
    // 현재 기기 절대 피치각 (도). orientationListener 에서 갱신. 수평=0°, 아래=음수

    private var setAngleThisPitch:   Float = 0f
    private val setToReadyHistory    = ArrayList<Pair<Long, Float>>()  // phase1: SET→READY
    private val readyToPitchHistory  = ArrayList<Pair<Long, Float>>()  // phase2: READY→PITCH
    private val pitchToEndHistory    = ArrayList<Pair<Long, Float>>()  // phase3: PITCH→end
    private val allSetAngles          = mutableListOf<Float>()
    private val perPitchPhase1Data   = mutableListOf<ArrayList<Pair<Long, Float>>>()
    private val perPitchPhase2Data   = mutableListOf<ArrayList<Pair<Long, Float>>>()
    private val perPitchPhase3Data   = mutableListOf<ArrayList<Pair<Long, Float>>>()
    @Volatile private var recordingPhase: Int = 0
    @Volatile private var phaseRecordStartTime: Long = 0L
    @Volatile private var hitTimePhase3Ms: Long = -1L
    private val perPitchHitTimesPhase3 = mutableListOf<Long>()

    // ── 임계값 ──────────────────────────────────────────
    private val FOUL_ACCEL_THRESHOLD = 1f
    // 스윙 시도 최소 가속도 임계값 (m/s²). 초과 시 스윙 동작으로 인식

    private val HIT_ACCEL_THRESHOLD  = 2f
    // 유효 타격 가속도 임계값 (m/s²). 초과 시 강한 스윙으로 판정

    private val FOUL_GYRO_THRESHOLD  = 3f
    // 스윙 시도 자이로 임계값 (rad/s). FOUL_ACCEL_THRESHOLD 와 OR 조건

    private val HIT_GYRO_THRESHOLD   = 10f
    // 유효 타격 자이로 임계값 (rad/s). HIT_ACCEL_THRESHOLD 와 OR 조건

    private val PITCH_TOLERANCE      = 25f
    // 각도 허용 오차 (도). 현재 HEIGHT_TOLERANCE 기반 판정을 사용하나 참고용으로 보존

    // ── 물리 상수 (공·배트 3D 위치 계산) ──────────────────
    private val PITCHER_DIST     = 18.44f
    // 투수판 ~ 타석 거리 (m). 야구 공식 규격 (60피트 6인치)

    private val PITCHER_HEIGHT   = 1.0f
    // 투수 릴리즈 높이 (m). 공이 이 높이에서 출발함

    private val BALL_ARC         = 0.3f
    // 포물선 최고점 추가 높이 (m). 직선 궤적 대비 이 만큼 볼록하게 솟음

    private val BATTER_HEIGHT    = 1.0f
    // 타자 손(접촉점) 기준 높이 (m)

    private val BAT_REACH        = 0.6f
    // 배트 스윙 수직 도달 거리 (m). 피치각에 따라 접촉 높이가 결정됨

    private val HEIGHT_TOLERANCE = 0.2f
    // 높이 허용 오차 (m). |배트높이 - 공높이| < HEIGHT_TOLERANCE → HIT 판정

    // ── 게임 상태 ────────────────────────────────────────
    private var targetBase = 1
    // 이번 라운드 목표 베이스 (1 또는 3). startGame() 에서 랜덤 선택
    // [DB 저장 후보] 라운드별 목표 베이스 번호

    @Volatile private var hitWindowActive: Boolean = false
    // true 인 600ms 구간에서만 스윙 감지. 센서 스레드 ↔ 코루틴 공유 → @Volatile 필수

    @Volatile private var swingDetected:   Boolean = false
    // 스윙 시도 감지 (≥ FOUL 임계값). 파울 판정의 1차 조건
    // [DB 저장 후보] 스윙 여부 (true=스윙, false=스트라이크)

    @Volatile private var swingIsHit:      Boolean = false
    // 유효 타격 (≥ HIT 임계값 + 높이 일치). true → "깡" 판정
    // [DB 저장 후보] 정타 여부 (true=정타, false=파울/스트라이크)

    @Volatile private var swingPitchDeg:   Float   = 0f
    // 스윙 감지 순간의 피치각 (도). 결과 텍스트 "실제 각도" 표시용
    // [DB 저장 후보] 스윙 순간 실제 배트 각도(°) → BATTING_ANGLE_DEG 과 비교해 각도 오차 계산 가능

    @Volatile private var swingWasStrong:  Boolean = false
    // 스윙이 HIT 임계값 이상이었는지. 파울 원인(힘 부족 vs 위치 미스) 구분용

    @Volatile private var swingBatHeight:  Float   = Float.NaN
    // 스윙 감지 순간의 배트 높이 (m). NaN=스윙 없음. 결과 그래프에서 배트 위치 표시용
    // [DB 저장 후보] 스윙 순간 배트 높이(m) → contactH 와 비교해 높이 오차 계산 가능

    private var isWaitingForInput = false
    // 베이스 선택 대기 중 여부. true 인 동안만 onBasePressed() 처리

    private var beepStartTime     = 0L
    // 베이스 도착음 시작 시각 (elapsedRealtimeNanos, ns). 반응속도 측정 기준점

    // ── 스윙 궤적 기록 ───────────────────────────────────
    private val pitchHistory = ArrayList<Pair<Long, Float>>()
    // (경과ms, 피치각도) 목록. orientationListener 에서 isRecording=true 인 동안 추가

    @Volatile private var pitchRecordStart: Long    = 0L
    // 기록 시작 시각 (System.currentTimeMillis() 기준). pitchHistory 경과ms 계산 기준

    @Volatile private var hitTimeRelMs:     Long    = -1L
    // 스윙 감지 순간의 기록 기준 경과시간 (ms). -1이면 스윙 없음
    // [DB 저장 후보] 투구 시작 ~ 스윙 감지까지 경과시간(ms) → 타이밍 오차 계산 가능

    @Volatile private var isRecording:      Boolean = false
    // true 인 동안 orientationListener 가 pitchHistory 에 데이터 추가

    // ── 공 접근 진행률 (오디오 거리 감쇠 공유) ──────────
    @Volatile private var ballApproachProgress: Float = 0f
    // 0.0(투수) ~ 1.0(타자). 게임 루프에서 갱신 → beepBallJob 에서 읽어 볼륨·주파수 조정

    @Volatile private var divProgress: Float = 0f
    // 타격 후 DIVERGE(공 발산) 진행률. 0.0(타격 직후) ~ 1.0(베이스 도착).
    // divBeepJob 에서 볼륨·패닝 계산에 사용

    // ── 헤드트래킹 (방위각 기반 스테레오 패닝) ──────────
    private var baseAzimuth: Float? = null
    // 게임 시작 시점 방위각 기준값. null 이면 다음 센서 이벤트에서 초기화

    @Volatile private var currentHeadingDeg: Float = 0f
    // 현재 머리 방향 (기준 방위각 대비 상대 각도, 도). 베이스 도착음 스테레오 패닝에 사용

    // ── Coroutines ───────────────────────────────────────
    private val scope    = CoroutineScope(Dispatchers.Default + SupervisorJob())
    // 이 액티비티 전용 코루틴 스코프. Default 디스패처(백그라운드 스레드).
    // onDestroy() 에서 scope.cancel() 로 모든 하위 코루틴 일괄 취소

    private var gameJob:  Job? = null
    // 게임 전체 흐름 코루틴. startGame() 에서 이전 gameJob 취소 후 새로 launch

    private var audioJob: Job? = null
    // 베이스 도착음 반복 재생 코루틴. startBaseBeep() 에서 launch, stopAudio() 에서 cancel

    // ── TTS ─────────────────────────────────────────────
    private var tts:      TextToSpeech? = null
    // TTS 엔진. "깡"·"파울"·"스트라이크" 한국어, "SET"·"PITCH" 영어 발화

    private var ttsReady: Boolean       = false
    // TTS 초기화 완료 여부. false 이면 speakAndWait() 500ms 대기 후 스킵

    // ── Audio ────────────────────────────────────────────
    private var audioTrack: AudioTrack? = null
    // PCM 스트리밍 오디오. tone() 으로 생성한 ShortArray 를 write() 로 직접 공급

    private lateinit var spatialAudio: SpatialAudioEngine

    // ── Hardware BLE ─────────────────────────────────────
    private lateinit var bleManager: BleManager
    // Seeed XIAO nRF52840 + BNO055 배트 센서 BLE 관리자

    @Volatile private var bleConnected = false
    // BLE 연결 여부. true 이면 폰 센서 대신 BLE 센서 데이터 사용

    @Volatile private var prevBtn1 = false
    @Volatile private var prevBtn2 = false
    private var bothBtnHandled = false
    private var batBtn1Job: Job? = null
    private var batBtn2Job: Job? = null

    // ─────────────────────────────────────────────────────
    // 스윙 감지 리스너 (선형가속도 + 자이로)
    // ─────────────────────────────────────────────────────
    private val swingListener = object : SensorEventListener {
    // onResume() 에서 linearAccelSensor, gyroscopeSensor 각각에 등록

        override fun onSensorChanged(event: SensorEvent) {
            if (bleConnected) return
            // BLE 센서 연결 시 폰 센서 스윙 감지 비활성화 (BLE 데이터 우선)
            when (event.sensor.type) {
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                // 중력이 이미 제거된 순수 가속도. 스윙 동작 감지에 사용
                    linearAccelMag = magnitude(event.values)
                    checkSwing()
                }
                Sensor.TYPE_ACCELEROMETER -> {
                // TYPE_LINEAR_ACCELERATION 미지원 기기 폴백.
                // 저역통과 필터로 중력 성분을 추정·제거한 뒤 linearAccelMag 갱신
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
                Sensor.TYPE_GYROSCOPE -> {
                    gyroMag = magnitude(event.values)
                    // 각속도 벡터 크기만 갱신. checkSwing 은 가속도 이벤트에서 OR 조건으로 호출
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    // ─────────────────────────────────────────────────────
    // 방향 감지 리스너 (피치각 + 방위각)
    // ─────────────────────────────────────────────────────
    private val orientationListener = object : SensorEventListener {
    // TYPE_GAME_ROTATION_VECTOR 이벤트 수신. 피치각(스윙 위치 판정) +
    // 방위각(헤드트래킹 패닝) + 배트 높이 라이브 업데이트 모두 처리

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return

            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            SensorManager.getOrientation(rotMatrix, orientation)
            // orientation[0]=방위각, orientation[1]=피치, orientation[2]=롤 (라디안)

            // ── 방위각 → 헤드트래킹 패닝 계산 (BLE 연결 여부와 무관하게 항상 계산) ──
            val azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
            if (baseAzimuth == null) baseAzimuth = azimuthDeg
            // 버튼 클릭 후 첫 이벤트에서 기준 방위각 저장
            var rel = azimuthDeg - (baseAzimuth ?: azimuthDeg)
            while (rel > 180f)  rel -= 360f
            while (rel < -180f) rel += 360f
            currentHeadingDeg = -rel
            // -180~+180° 정규화 후 부호 반전 → 오른쪽=양수, 왼쪽=음수

            if (bleConnected) return
            // BLE 연결 시 피치각·배트 높이·기록은 BLE 콜백에서 담당

            currentPitchDeg = Math.toDegrees(orientation[1].toDouble()).toFloat()
            // 절대 피치각 (도). 화면 수평=0°, 아래로 기울이면 음수

            // ── 배트 높이 라이브 업데이트 ──────────────────────
            val batH = BATTER_HEIGHT + sin(currentPitchDeg * PI.toFloat() / 180f) * BAT_REACH
            liveBallParabolaView.updateLiveBat(batH)
            // updateLiveBat() 는 postInvalidate() 를 사용 → 센서 스레드에서 직접 호출 안전

            // ── 스윙 궤적 기록 ──────────────────────────────────
            if (isRecording) {
                pitchHistory.add(Pair(System.currentTimeMillis() - pitchRecordStart, currentPitchDeg))
                swingGraphView.postInvalidate()
            }

            // ── 구간별 각도 기록 (sensor 콜백에서 직접 기록 → 코루틴 지연 없음) ──
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
        if (!hitWindowActive) return

        val accel = linearAccelMag
        val gyro  = gyroMag

        val isStrongSwing = accel > HIT_ACCEL_THRESHOLD || gyro > HIT_GYRO_THRESHOLD
        val isAnySwing    = accel > FOUL_ACCEL_THRESHOLD || gyro > FOUL_GYRO_THRESHOLD

        if (!swingDetected && isAnySwing) {
            swingDetected = true
            swingPitchDeg = currentPitchDeg
            if (hitTimeRelMs < 0L) {
                hitTimeRelMs = System.currentTimeMillis() - pitchRecordStart
                if (recordingPhase == 3) {
                    hitTimePhase3Ms = System.currentTimeMillis() - phaseRecordStartTime
                }
                swingGraphView.setHitTime(hitTimeRelMs)
                swingGraphView.postInvalidate()
            }
        }

        if (!swingWasStrong && isStrongSwing) {
            swingWasStrong = true
            swingBatHeight = BATTER_HEIGHT + sin(currentPitchDeg * PI.toFloat() / 180f) * BAT_REACH
            if (abs(currentPitchDeg - BATTING_ANGLE_DEG) <= PITCH_TOLERANCE) swingIsHit = true
        }
    }

    private fun startPhaseRecording() {
        phaseRecordStartTime = System.currentTimeMillis()
    }

    private fun stopPhaseRecording() {
        recordingPhase = 0
    }

    // ─────────────────────────────────────────────────────
    // onCreate
    // ─────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_swing_test)
        // res/layout/activity_swing_test.xml 로 화면 설정 (가로 모드 고정)

        // ── 뷰 바인딩 (XML id → 코드 변수) ──────────────────
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

        btnSwingPitchMinus   = findViewById(R.id.btnSwingPitchMinus)
        btnSwingPitchPlus    = findViewById(R.id.btnSwingPitchPlus)
        tvSwingPitchCount    = findViewById(R.id.tvSwingPitchCount)
        tvSwingPitchProgress = findViewById(R.id.tvSwingPitchProgress)
        btnBleConnect        = findViewById(R.id.btnBleConnect)
        tvBleStatus          = findViewById(R.id.tvBleStatus)

        ballTrackView.setShowStrikeZone(false)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        // ── TTS 초기화 ────────────────────────────────────
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                // 기본 언어 한국어. "SET"·"PITCH" 발화 직전 영어로 전환
                ttsReady = true
            }
        }

        initAudioTrack()
        // AudioTrack 인스턴스 생성 및 재생 상태 설정
        spatialAudio = SpatialAudioEngine(this)
        spatialAudio.init()

        bleManager = BleManager(this)
        bleManager.setCallback(bleCallback)

        btnBleConnect.setOnClickListener {
            if (bleConnected) {
                bleManager.disconnect()
            } else {
                startBleScan()
            }
        }

        // 앱 시작 시 자동 스캔
        if (hasBlePermissions()) startBleScan() else requestBlePermissions()

        btnSwingPitchMinus.setOnClickListener {
            if (targetPitches > 1) {
                targetPitches--
                tvSwingPitchCount.text = targetPitches.toString()
            }
        }
        btnSwingPitchPlus.setOnClickListener {
            if (targetPitches < 30) {
                targetPitches++
                tvSwingPitchCount.text = targetPitches.toString()
            }
        }

        btnStart.setOnClickListener {
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
            btnSwingPitchMinus.isEnabled = false
            btnSwingPitchPlus.isEnabled = false
            tvSwingPitchProgress.text = "1/${targetPitches}"
            startGame()
            resetAndShowLiveGraphs()
        }

        base1Container.setOnClickListener { onBasePressed(1) }
        base3Container.setOnClickListener { onBasePressed(3) }
    }

    // ─────────────────────────────────────────────────────
    // 게임 메인 루프
    // ─────────────────────────────────────────────────────
    private fun startGame() {
        gameJob?.cancel()
        // 이전 게임 코루틴이 남아있으면 취소 (다시하기 클릭 시 이전 흐름 중단)

        // ── 모든 상태 초기화 ──────────────────────────────
        tvResult.text      = ""
        ballTrackView.reset()      // 공 위치·궤적·트레일 초기화
        btnStart.isEnabled = false
        isWaitingForInput  = false
        swingDetected      = false
        swingIsHit         = false
        recordingPhase     = 0
        setAngleThisPitch  = 0f
        setToReadyHistory.clear()
        readyToPitchHistory.clear()
        pitchToEndHistory.clear()
        swingPitchDeg      = 0f
        swingWasStrong     = false
        swingBatHeight     = Float.NaN
        hitTimePhase3Ms    = -1L
        ballApproachProgress = 0f  // 비프볼 볼륨 기준 초기화
        divProgress          = 0f  // DIVERGE 비프음 볼륨·패닝 기준 초기화
        pitchHistory.clear()
        hitTimeRelMs   = -1L
        isRecording    = false
        hitWindowActive = false
        initAudioTrack()
        targetBase     = if (Random.nextBoolean()) 1 else 3
        // 50% 확률로 1루 또는 3루 선택
        resetBaseVisuals()
        currentPitchRecord.clear()
        currentPitchRecord["투구번호"]     = currentPitchNum
        currentPitchRecord["목표베이스"]   = targetBase
        currentPitchRecord["배트각도"]     = null
        currentPitchRecord["선택베이스"]   = null
        currentPitchRecord["베이스정답여부"] = null
        currentPitchRecord["주루반응속도"] = null

        gameJob = scope.launch {
        // 게임 전체 흐름을 단일 코루틴으로 관리 (자기 취소 버그 방지)

            // ━━━ 1단계: SET 발화 + 초기 각도 기록 ━━━
            setAngleThisPitch = currentPitchDeg
            allSetAngles.add(setAngleThisPitch)
            // BLE: SET 단계부터 측정 시작 + phase1(SET→READY) 기록 시작
            if (bleConnected) bleManager.sendControl(1)
            recordingPhase = 1
            startPhaseRecording()
            withContext(Dispatchers.Main) {
                tvStatus.text = "SET"
                tvStatus.setTextColor(0xFF93C5FD.toInt())
                if (ttsReady) {
                    tts?.setLanguage(Locale.ENGLISH)
                    tts?.speak("SET", TextToSpeech.QUEUE_FLUSH, null, "tts_set")
                }
            }
            delay(1000)

            // ━━━ 2단계: 공 접근 (2500ms) + READY (10피트) + PITCH 발화 ━━━
            val approachMs    = 2500L
            val readyDistM    = 10f * 0.3048f
            val readyProgress = ((PITCHER_DIST - readyDistM) / PITCHER_DIST).coerceIn(0f, 1f)
            val tApproach     = System.currentTimeMillis()
            var readyStarted  = false

            val contactH = BATTER_HEIGHT + sin(BATTING_ANGLE_DEG * PI.toFloat() / 180f) * BAT_REACH

            spatialAudio.updateBallPosition(0f, 0f, -PITCHER_DIST)
            spatialAudio.updateHeading(currentHeadingDeg)
            spatialAudio.startBeep()

            while (isActive) {
                val elapsed  = System.currentTimeMillis() - tApproach
                val progress = (elapsed.toFloat() / approachMs).coerceIn(0f, 1f)

                ballApproachProgress = progress
                val zPos = -(PITCHER_DIST * (1f - progress)).coerceAtLeast(0.5f)
                spatialAudio.updateBallPosition(0f, 0f, zPos)
                spatialAudio.updateHeading(currentHeadingDeg)

                withContext(Dispatchers.Main) {
                    ballTrackView.updateBall(0f, progress, BallPhase.APPROACH, pitchYPosition)
                    liveBallParabolaView.setBallProgress(progress)
                }

                if (!readyStarted && progress >= readyProgress) {
                    readyStarted = true
                    // phase1(SET→READY) 종료 → phase2(READY→PITCH) 시작
                    stopPhaseRecording()
                    recordingPhase = 2
                    startPhaseRecording()
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "READY"
                        tvStatus.setTextColor(0xFF93C5FD.toInt())
                        if (ttsReady) {
                            tts?.setLanguage(Locale.ENGLISH)
                            tts?.speak("READY", TextToSpeech.QUEUE_FLUSH, null, "tts_ready")
                        }
                    }
                }

                if (progress >= 1f) break
                delay(16)
            }

            spatialAudio.stopBeep()
            audioTrack?.pause(); audioTrack?.flush(); audioTrack?.play()

            stopPhaseRecording()
            recordingPhase = 3
            startPhaseRecording()

            withContext(Dispatchers.Main) {
                tvStatus.text = "PITCH"
                tvStatus.setTextColor(0xFFFBBF24.toInt())
            }
            speakAndWait("PITCH")

            // ━━━ 3단계: 타격 윈도우 (600ms) ━━━
            swingDetected   = false
            swingIsHit      = false
            // 2단계 중 발생한 오탐 방지 — 두 플래그 초기화

            hitWindowActive = true
            // 이 시점부터 swingListener.checkSwing() 이 스윙 감지 시작

            withContext(Dispatchers.Main) {
                tvStatus.text = "쳐!"
                tvStatus.setTextColor(0xFFFF6B35.toInt())  // 주황 #FF6B35
            }

            val deadline = System.currentTimeMillis() + 600L
            // 타격 윈도우 종료 시각 (현재 + 600ms)
            while (isActive && System.currentTimeMillis() < deadline) {
                if (swingIsHit) break
                // 정타 감지 즉시 탈출 → "깡" 피드백 지연 최소화
                // swingDetected(파울)만인 경우는 더 강한 스윙 가능성 위해 600ms 끝까지 대기
                delay(8)
                // 8ms 마다 판정 확인 (≈125Hz 폴링)
            }
            hitWindowActive = false
            // 타격 윈도우 닫음 → 이후 센서 이벤트는 무시
            if (bleConnected) bleManager.sendControl(0)
            // BLE 배트 센서에 측정 정지 명령 전송

            delay(150L)
            isRecording = false
            stopPhaseRecording()
            if (setToReadyHistory.isNotEmpty())   perPitchPhase1Data.add(ArrayList(setToReadyHistory))
            if (readyToPitchHistory.isNotEmpty()) perPitchPhase2Data.add(ArrayList(readyToPitchHistory))
            if (pitchToEndHistory.isNotEmpty())   perPitchPhase3Data.add(ArrayList(pitchToEndHistory))
            perPitchHitTimesPhase3.add(hitTimePhase3Ms)

            // ━━━ 4단계: 결과 판정 ━━━
            when {

                // ── 4a: 정타 (깡) ─────────────────────────────
                // 강한 스윙 + 높이 일치 → 공이 목표 베이스로 날아감
                swingIsHit -> {
                    currentPitchRecord["판정"] = "정타"
                    // ── 깡 임팩트 사운드 (AudioTrack, 최대 볼륨) ─────────────────────────────
                    // [볼륨 조절] vol=1.0f 값을 변경하면 깡 소리 크기가 바뀜
                    // [음색 조절] 950f 값을 변경하면 깡 소리 주파수가 바뀜
                    // [길이 조절] 150 (ms) 값을 변경하면 깡 소리 길이가 바뀜
                    val impactSamples = 44100 * 150 / 1000
                    audioTrack?.write(tone(impactSamples, 880f, 0f, 1.0f), 0, impactSamples * 2)

                    withContext(Dispatchers.Main) {
                        speakResult("깡")
                        tvStatus.text = "소리 들어봐!"
                        tvStatus.setTextColor(0xFFFBBF24.toInt())
                        tvResult.text = "실제 각도: %.0f°  /  필요 각도: %.0f°"
                            .format(swingPitchDeg, BATTING_ANGLE_DEG)
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
                        withContext(Dispatchers.Main) {
                            ballTrackView.updateBall(
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

                    withContext(Dispatchers.Main) {
                        ballTrackView.reset()
                        activateBothBases()
                        tvStatus.text = "소리 따라\n달려라!"
                        tvStatus.setTextColor(0xFFFBBF24.toInt())
                        hitCount++
                    }

                    startBaseBeep()
                    // 1100Hz 스테레오 도착음 시작 (헤드트래킹 방향 패닝)
                    isWaitingForInput = true
                    // 베이스 선택 대기 시작 — onBasePressed() 처리 활성화
                }

                // ── 4b: 파울 ──────────────────────────────────
                swingDetected -> {
                    currentPitchRecord["판정"] = "파울"
                    withContext(Dispatchers.Main) {
                        perPitchRecords.add(HashMap(currentPitchRecord))
                        ballTrackView.reset()
                        speakResult("파울")
                        foulCount++
                        tvStatus.text = "파울!"
                        tvStatus.setTextColor(0xFFFBBF24.toInt())
                        tvResult.text = if (swingWasStrong) {
                            "스트라이크 — 위치 불일치\n실제 각도: %.0f°  /  필요 각도: %.0f°"
                                .format(swingPitchDeg, BATTING_ANGLE_DEG)
                        } else {
                            "스트라이크 — 힘 부족\n필요 각도: %.0f°".format(BATTING_ANGLE_DEG)
                        }
                        if (!isTraining) {
                            showSwingGraph()
                            btnStart.isEnabled = true
                            btnStart.text = "다시하기"
                        }
                    }
                    if (isTraining) {
                        delay(2000)
                        withContext(Dispatchers.Main) { scheduleNextOrFinish(false) }
                    }
                }

                // ── 4c: 스트라이크 ────────────────────────────
                else -> {
                    currentPitchRecord["판정"] = "스트라이크"
                    withContext(Dispatchers.Main) {
                        perPitchRecords.add(HashMap(currentPitchRecord))
                        ballTrackView.reset()
                        speakResult("스트라이크")
                        strikeCount++
                        tvStatus.text = "스트라이크!"
                        tvStatus.setTextColor(0xFFF87171.toInt())
                        tvResult.text = "스윙하지 않았습니다\n필요 각도: %.0f°".format(BATTING_ANGLE_DEG)
                        if (!isTraining) {
                            showSwingGraph()
                            btnStart.isEnabled = true
                            btnStart.text = "다시하기"
                        }
                    }
                    if (isTraining) {
                        delay(2000)
                        withContext(Dispatchers.Main) { scheduleNextOrFinish(false) }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────
    // 베이스 도착음 — 베이스 선택 전까지 반복 재생 (헤드트래킹 스테레오 패닝)
    // ─────────────────────────────────────────────────────
    private fun startBaseBeep() {
        val finalPan = if (targetBase == 3) -1.0f else 1.0f
        // 3루=왼쪽(-1.0), 1루=오른쪽(+1.0). 베이스 방향의 기본 패닝값

        audioJob?.cancel()
        // 이전 오디오 잡이 있으면 취소

        audioJob = scope.launch {
        // 독립 코루틴으로 실행 → onBasePressed() 의 stopAudio() 에서 명시적 취소

            val sr          = 44100
            val beepSamples = sr * 200 / 1000   // 200ms 비프음 샘플 수
            val silSamples  = sr * 0 / 1000   // 150ms 무음 샘플 수

            while (isActive) {
                val rAngle = finalPan * 90f + currentHeadingDeg
                // 베이스 방향(±90°) + 현재 머리 방향 = 실제 음원 각도
                val pan    = sin(Math.toRadians(rAngle.toDouble())).toFloat().coerceIn(-1f, 1f)
                // 각도 → 스테레오 pan(-1~+1) 변환. sin 함수로 -90°~+90° 자연스럽게 매핑
                audioTrack?.write(tone(beepSamples, 880f, pan, 1.0f), 0, beepSamples * 2)
                // 1100Hz 200ms 비프음 스트리밍
                if (!isActive) break
                audioTrack?.write(ShortArray(silSamples * 2), 0, silSamples * 2)
                // 150ms 무음 (비프음 간 간격)
            }
        }
        beepStartTime = SystemClock.elapsedRealtimeNanos()
        // 반응속도 측정 기준점 기록. onBasePressed() 에서 차이를 ms 로 계산
    }

    // ─────────────────────────────────────────────────────
    // 베이스 선택 처리
    // ─────────────────────────────────────────────────────
    private fun onBasePressed(pressedBase: Int) {
    // base1Container / base3Container 클릭 시 호출

        if (!isWaitingForInput) return
        // 베이스 도착 전이거나 이미 처리된 경우 무시

        isWaitingForInput = false
        // 중복 입력 방지 (한 번 선택하면 더 이상 처리 안 함)

        stopAudio()
        gameJob?.cancel()
        resetBaseVisuals()
        ballTrackView.reset()

        val success = pressedBase == targetBase
        // [DB 저장 후보] 베이스 선택 정답 여부 (true=정답, false=오답)
        currentPitchRecord["선택베이스"]   = pressedBase
        currentPitchRecord["베이스정답여부"] = success
        audioTrack?.stop()
        if (success) {
            val ms = (SystemClock.elapsedRealtimeNanos() - beepStartTime) / 1_000_000L
            // [DB 저장 후보] 베이스 부저음 시작 ~ 버튼 선택까지 반응속도(ms)
            currentPitchRecord["주루반응속도"] = ms
            reactionTimes.add(ms)
            tvStatus.text = "성공!"
            tvStatus.setTextColor(0xFF4ADE80.toInt())
            tvResult.text = "${ms} ms"
            speakResult("성공, 반응속도 ${ms}밀리초")
        } else {
            tvStatus.text = "알맞지 않은\n베이스 선택입니다"
            tvStatus.setTextColor(0xFFF87171.toInt())
            tvResult.text = ""
            Toast.makeText(this, "알맞지 않은 베이스 선택입니다", Toast.LENGTH_SHORT).show()
            speakResult("베이스 선택이 틀렸습니다")
        }
        perPitchRecords.add(HashMap(currentPitchRecord))

        if (isTraining) {
            scope.launch {
                delay(4000)
                withContext(Dispatchers.Main) { scheduleNextOrFinish(success) }
            }
        } else {
            showSwingGraph()
            btnStart.isEnabled = true
            btnStart.text = "다시하기"
        }
    }

    // ─────────────────────────────────────────────────────
    // BLE 배트 센서 콜백
    // ─────────────────────────────────────────────────────
    private val bleCallback = object : BleManager.Callback {
        override fun onConnected() {
            bleConnected = true
            btnBleConnect.isEnabled = true
            btnBleConnect.text = "배트 센서 해제"
            tvBleStatus.text = "● 연결됨"
            tvBleStatus.setTextColor(0xFF4ADE80.toInt())
            speakResult("배트가 연결되었습니다")
        }

        override fun onDisconnected() {
            bleConnected = false
            btnBleConnect.isEnabled = true
            btnBleConnect.text = "배트 센서 연결"
            tvBleStatus.text = "● 미연결"
            tvBleStatus.setTextColor(0xFFF87171.toInt())
            speakResult("배트 연결이 끊겼습니다")
            // 2초 후 자동 재스캔
            scope.launch {
                delay(2000)
                withContext(Dispatchers.Main) {
                    if (!bleConnected && hasBlePermissions()) startBleScan()
                }
            }
        }

        override fun onPacket(packet: BleManager.SensorPacket) {
            // BLE 패킷에서 스윙 감지용 값 갱신
            // handleEuler: [X=heading, Y=pitch, Z=roll] (도)
            // handleGyro:  [x,y,z] (rad/s), handleAccel: [x,y,z] (m/s²)
            linearAccelMag = magnitude(packet.handleAccel)
            gyroMag        = magnitude(packet.handleGyro)
            currentPitchDeg = packet.handleEuler[1]
            // BNO055 VECTOR_EULER: Y축 = 피치각 (배트 기울기)
            // 센서 장착 방향에 따라 [0] 또는 [2] 로 변경 필요할 수 있음
            checkSwing()

            // 배트 높이 라이브 업데이트
            val batH = BATTER_HEIGHT + sin(currentPitchDeg * PI.toFloat() / 180f) * BAT_REACH
            liveBallParabolaView.updateLiveBat(batH)

            // 스윙 궤적 기록 (메인 스레드에서 실행 — BleManager가 mainHandler.post 사용)
            if (isRecording) {
                pitchHistory.add(Pair(System.currentTimeMillis() - pitchRecordStart, currentPitchDeg))
                swingGraphView.postInvalidate()
            }
            val phaseElapsed = System.currentTimeMillis() - phaseRecordStartTime
            when (recordingPhase) {
                1 -> setToReadyHistory.add(Pair(phaseElapsed, currentPitchDeg))
                2 -> readyToPitchHistory.add(Pair(phaseElapsed, currentPitchDeg))
                3 -> pitchToEndHistory.add(Pair(phaseElapsed, currentPitchDeg))
            }

            handleBatButton(packet.btn1, packet.btn2)
        }
    }

    // ─────────────────────────────────────────────────────
    // 배트 버튼 처리
    // ─────────────────────────────────────────────────────
    private fun handleBatButton(btn1: Boolean, btn2: Boolean) {
        val wasBtn1 = prevBtn1
        val wasBtn2 = prevBtn2

        if (btn1 && !wasBtn1) {
            if (batBtn2Job?.isActive == true) {
                batBtn1Job?.cancel(); batBtn2Job?.cancel()
                if (!bothBtnHandled) { bothBtnHandled = true; onBothBatButtons() }
            } else {
                batBtn1Job?.cancel()
                batBtn1Job = scope.launch {
                    delay(200L)
                    withContext(Dispatchers.Main) {
                        if (batBtn2Job?.isActive != true) onBatButton1()
                    }
                }
            }
        }

        if (btn2 && !wasBtn2) {
            if (batBtn1Job?.isActive == true) {
                batBtn1Job?.cancel(); batBtn2Job?.cancel()
                if (!bothBtnHandled) { bothBtnHandled = true; onBothBatButtons() }
            } else {
                batBtn2Job?.cancel()
                batBtn2Job = scope.launch {
                    delay(200L)
                    withContext(Dispatchers.Main) {
                        if (batBtn1Job?.isActive != true) onBatButton2()
                    }
                }
            }
        }

        if (!btn1 && !btn2) bothBtnHandled = false

        prevBtn1 = btn1
        prevBtn2 = btn2
    }

    private fun onBatButton1() {
        when {
            isWaitingForInput -> onBasePressed(1)
            !isTraining -> {
                if (targetPitches < 30) {
                    targetPitches++
                    tvSwingPitchCount.text = targetPitches.toString()
                    speakResult("${targetPitches}회")
                }
            }
        }
    }

    private fun onBatButton2() {
        when {
            isWaitingForInput -> onBasePressed(3)
            !isTraining -> {
                if (targetPitches > 1) {
                    targetPitches--
                    tvSwingPitchCount.text = targetPitches.toString()
                    speakResult("${targetPitches}회")
                }
            }
        }
    }

    private fun onBothBatButtons() {
        if (!isTraining && btnStart.isEnabled) {
            btnStart.performClick()
        }
    }

    // ─────────────────────────────────────────────────────
    // BLE 권한 요청
    // ─────────────────────────────────────────────────────
    private fun requestBlePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_BLE_PERM)
        } else {
            bleManager.startScan()
        }
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
        if (requestCode == REQ_BLE_PERM && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startBleScan()
        }
    }

    private fun startBleScan() {
        btnBleConnect.isEnabled = false
        btnBleConnect.text = "연결 중..."
        bleManager.startScan()
    }

    private fun scheduleNextOrFinish(success: Boolean) {
        if (success) successCount++
        if (currentPitchNum >= targetPitches) {
            finishTraining()
        } else {
            launchNextTrainingPitch()
        }
    }

    private fun launchNextTrainingPitch() {
        currentPitchNum++
        tvSwingPitchProgress.text = "${currentPitchNum}/${targetPitches}"
        startGame()
        resetAndShowLiveGraphs()
    }

    private fun finishTraining() {
        isTraining = false
        tvStatus.text = "훈련 완료!"
        tvStatus.setTextColor(0xFF4ADE80.toInt())
        tvResult.text = ""
        tvSwingPitchProgress.text = ""
        btnStart.isEnabled = true
        btnStart.text = "다시 훈련"
        btnSwingPitchMinus.isEnabled = true
        btnSwingPitchPlus.isEnabled = true
        audioTrack?.stop()
        val battingAvgPct = if (targetPitches > 0) (hitCount.toFloat() / targetPitches * 100).toInt() else 0
        val avgReaction = if (reactionTimes.isNotEmpty()) reactionTimes.average().toLong() else -1L
        val ttsText = buildString {
            append("훈련 완료. ")
            append("정타 ${hitCount}개, 타율 ${battingAvgPct}퍼센트. ")
            if (avgReaction >= 0L) append("평균 반응속도 ${avgReaction}밀리초.")
        }
        speakResult(ttsText)
        scope.launch {
            delay(500)
            withContext(Dispatchers.Main) { showTrainingSummary() }
        }
    }

    private fun showTrainingSummary() {
        val battingAvg     = if (targetPitches > 0) hitCount.toFloat() / targetPitches else 0f
        val avgReaction    = if (reactionTimes.isNotEmpty()) reactionTimes.average().toLong() else -1L
        val baseCorrectPct = if (hitCount > 0) successCount.toFloat() / hitCount * 100f else 0f

        val summaryText = buildString {
            appendLine("총 타석         ${targetPitches}회")
            appendLine()
            appendLine("정타 (볼 맞힘)   ${hitCount}회")
            appendLine("파울            ${foulCount}회")
            appendLine("스트라이크      ${strikeCount}회")
            appendLine()
            appendLine("베이스 정답     ${successCount}회")
            if (hitCount > 0) {
                appendLine("베이스 정답률   ${"%.0f".format(baseCorrectPct)}%  (${successCount}/${hitCount})")
            }
            appendLine()
            appendLine("타율            ${"%.3f".format(battingAvg)}  (${hitCount}/${targetPitches})")
            appendLine()
            if (avgReaction >= 0L) {
                appendLine("주루 반응속도 평균   ${avgReaction} ms")
                if (reactionTimes.size > 1) {
                    appendLine("  최소 ${reactionTimes.minOrNull()} ms  /  최대 ${reactionTimes.maxOrNull()} ms")
                }
            } else {
                appendLine("주루 반응속도   기록 없음")
            }
            appendLine()
            appendLine("── SET 초기 각도 ──")
            allSetAngles.forEachIndexed { i, a ->
                appendLine("  #${i + 1}: ${"%.1f".format(a)}°")
            }
        }

        val heightPx  = (220 * resources.displayMetrics.density).toInt()
        val totalData = maxOf(perPitchPhase1Data.size, perPitchPhase2Data.size, perPitchPhase3Data.size)
        var currentIdx = 0

        val tv = TextView(this)
        tv.text = summaryText
        tv.textSize = 14f
        tv.setTextColor(0xFFE2E8F0.toInt())
        tv.typeface = android.graphics.Typeface.MONOSPACE
        tv.setBackgroundColor(0xFF0A1423.toInt())
        tv.setPadding(56, 40, 56, 24)
        tv.setLineSpacing(0f, 1.3f)

        val btnPrev = Button(this)
        btnPrev.text = "◀"
        btnPrev.textSize = 13f
        btnPrev.setTextColor(0xFF64B4FF.toInt())

        val tvPitchNum = TextView(this)
        tvPitchNum.textSize = 14f
        tvPitchNum.setTextColor(0xFFFFFFFF.toInt())
        tvPitchNum.gravity = android.view.Gravity.CENTER
        tvPitchNum.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val btnNext = Button(this)
        btnNext.text = "▶"
        btnNext.textSize = 13f
        btnNext.setTextColor(0xFF64B4FF.toInt())

        val navRow = LinearLayout(this)
        navRow.orientation = LinearLayout.HORIZONTAL
        navRow.gravity = android.view.Gravity.CENTER_VERTICAL
        navRow.setPadding(40, 8, 40, 4)
        navRow.addView(btnPrev)
        navRow.addView(tvPitchNum)
        navRow.addView(btnNext)

        val tvLabel1 = TextView(this)
        tvLabel1.text = "SET → READY 구간 배트 각도"
        tvLabel1.textSize = 13f
        tvLabel1.setTextColor(0xFF86EFAC.toInt())
        tvLabel1.setPadding(56, 4, 56, 4)

        val graph1 = SwingGraphView(this)
        graph1.setShowSummary(false)

        val tvLabel2 = TextView(this)
        tvLabel2.text = "READY → PITCH 구간 배트 각도"
        tvLabel2.textSize = 13f
        tvLabel2.setTextColor(0xFF93C5FD.toInt())
        tvLabel2.setPadding(56, 8, 56, 4)

        val graph2 = SwingGraphView(this)
        graph2.setShowSummary(false)

        val tvLabel3 = TextView(this)
        tvLabel3.text = "PITCH 이후 구간 배트 각도"
        tvLabel3.textSize = 13f
        tvLabel3.setTextColor(0xFFFBBF24.toInt())
        tvLabel3.setPadding(56, 8, 56, 4)

        val graph3 = SwingGraphView(this)

        fun updateGraphs(idx: Int) {
            tvPitchNum.text = "${idx + 1} / $totalData"
            val p1 = if (idx < perPitchPhase1Data.size) perPitchPhase1Data[idx] else ArrayList()
            val p2 = if (idx < perPitchPhase2Data.size) perPitchPhase2Data[idx] else ArrayList()
            val p3 = if (idx < perPitchPhase3Data.size) perPitchPhase3Data[idx] else ArrayList()
            val h3 = if (idx < perPitchHitTimesPhase3.size) perPitchHitTimesPhase3[idx] else -1L
            graph1.setData(p1, -1L, BATTING_ANGLE_DEG)
            graph2.setData(p2, -1L, BATTING_ANGLE_DEG)
            graph3.setData(p3, h3, BATTING_ANGLE_DEG)
            btnPrev.isEnabled = idx > 0
            btnNext.isEnabled = idx < totalData - 1
        }

        if (totalData > 0) updateGraphs(0) else tvPitchNum.text = "0 / 0"

        btnPrev.setOnClickListener {
            if (currentIdx > 0) { currentIdx--; updateGraphs(currentIdx) }
        }
        btnNext.setOnClickListener {
            if (currentIdx < totalData - 1) { currentIdx++; updateGraphs(currentIdx) }
        }

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setBackgroundColor(0xFF0A1423.toInt())
        container.addView(tv)
        container.addView(navRow)
        container.addView(tvLabel1)
        container.addView(graph1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx))
        container.addView(tvLabel2)
        container.addView(graph2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx))
        container.addView(tvLabel3)
        container.addView(graph3, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx))

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(0xFF0A1423.toInt())
        scroll.addView(container)

        AlertDialog.Builder(this)
            .setTitle("훈련 종합 결과")
            .setView(scroll)
            .setPositiveButton("확인", null)
            .show()
    }

    private fun resetAndShowLiveGraphs() {
        pitchHistory.clear()
        hitTimeRelMs     = -1L
        pitchRecordStart = System.currentTimeMillis()
        isRecording      = true
        swingGraphView.setLiveSource(pitchHistory, BATTING_ANGLE_DEG)
        swingGraphView.visibility = View.VISIBLE
    }

    // ─────────────────────────────────────────────────────
    // 베이스 시각 활성화 / 초기화
    // ─────────────────────────────────────────────────────
    private fun activateBase1() {
        base1Glow.visibility = View.VISIBLE
        tvBase1Label.setTextColor(0xFF4ADE80.toInt())   // 초록 = 활성 베이스
    }
    private fun activateBase3() {
        base3Glow.visibility = View.VISIBLE
        tvBase3Label.setTextColor(0xFF4ADE80.toInt())
    }
    private fun activateBothBases() {
        base1Glow.visibility = View.VISIBLE
        base3Glow.visibility = View.VISIBLE
        tvBase1Label.setTextColor(0xFF4ADE80.toInt())
        tvBase3Label.setTextColor(0xFF4ADE80.toInt())
    }
    private fun resetBaseVisuals() {
        base1Glow.visibility = View.INVISIBLE
        base3Glow.visibility = View.INVISIBLE
        tvBase1Label.setTextColor(0xFF94A3B8.toInt())   // 슬레이트 = 비활성
        tvBase3Label.setTextColor(0xFF94A3B8.toInt())
    }

    // ─────────────────────────────────────────────────────
    // 스윙 결과 그래프 다이얼로그 (공 포물선 + 배트 각도 두 그래프 표시)
    // ─────────────────────────────────────────────────────
    private fun showSwingGraph() {
        val history = ArrayList(pitchHistory)   // 스냅샷 (UI 스레드 안전 복사)
        if (history.isEmpty()) return

        val contactH = BATTER_HEIGHT + sin(BATTING_ANGLE_DEG * PI.toFloat() / 180f) * BAT_REACH
        // 이 라운드 공의 목표 접촉 높이

        val batH = when {
            !swingBatHeight.isNaN() -> swingBatHeight
            // 강한 스윙 → checkSwing() 에서 기록된 배트 높이 사용
            swingDetected           -> BATTER_HEIGHT + sin(swingPitchDeg * PI.toFloat() / 180f) * BAT_REACH
            // 약한 스윙 → swingPitchDeg 로 배트 높이 역산
            else                    -> contactH
            // 스윙 없음 → 배트를 목표 높이와 동일하게 표시
        }

        val heightPx = (300 * resources.displayMetrics.density).toInt()
        // 그래프 뷰 높이 300dp → 픽셀 변환

        val parabolaView = BallParabolaView(this)
        parabolaView.setData(PITCHER_DIST, PITCHER_HEIGHT, contactH, batH, BALL_ARC, swingIsHit)
        // 결과 다이얼로그용: liveMode=false → HIT/MISS 레이블·요약 표시

        val graphView = SwingGraphView(this)
        graphView.setData(history, hitTimeRelMs, BATTING_ANGLE_DEG)

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setBackgroundColor(0xFF0A1423.toInt())   // 앱 배경색 #0A1423
        container.addView(parabolaView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx))
        container.addView(graphView,    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx))
        // 포물선 그래프(위) + 배트 각도 그래프(아래) 세로 배치

        val scrollView = ScrollView(this)
        scrollView.addView(container)
        // 화면이 작은 기기에서 두 그래프 모두 스크롤하여 확인 가능

        AlertDialog.Builder(this)
            .setTitle("스윙 결과")
            .setView(scrollView)
            .setPositiveButton("확인", null)
            .show()
    }

    // ─────────────────────────────────────────────────────
    // TTS 발화
    // ─────────────────────────────────────────────────────
    private fun speakResult(text: String) {
    // 결과 발화 (한국어, fire-and-forget). 완료 대기 불필요
        if (!ttsReady) return
        tts?.setLanguage(Locale.KOREAN)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_result")
        // QUEUE_FLUSH: 진행 중인 발화 즉시 중단 후 결과 발화
    }

    private suspend fun speakAndWait(text: String) {
    // TTS 발화 + 완료까지 코루틴 suspend. "PITCH" 발화 타이밍 동기화용
        if (!ttsReady) return
        val deferred = CompletableDeferred<Unit>()
        // 발화 완료 신호 전달용 Deferred. onDone 콜백에서 complete() 호출
        withContext(Dispatchers.Main) {
            tts?.setLanguage(Locale.ENGLISH)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?)  { deferred.complete(Unit) }
                // 발화 완료 → deferred 완료 → await() 에서 코루틴 재개
                override fun onError(utteranceId: String?) { deferred.complete(Unit) }
                // 오류 시에도 complete() → 게임이 멈추지 않도록 처리
            })
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_wait")
        }
        deferred.await()
        // onDone / onError 콜백이 올 때까지 코루틴 일시 중단 (스레드 블록 없음)
    }

    // ─────────────────────────────────────────────────────
    // 오디오 중단
    // ─────────────────────────────────────────────────────
    private fun stopAudio() {
        audioJob?.cancel()
        // 베이스 도착음 반복 재생 코루틴 취소
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.play()
        // pause→flush→play: 버퍼에 남은 데이터 비우고 재생 상태로 복귀
        // (다음 write() 호출을 위해 play() 상태 유지)
    }

    // ─────────────────────────────────────────────────────
    // 유틸리티
    // ─────────────────────────────────────────────────────
    private fun magnitude(v: FloatArray) = sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2])
    // 3차원 벡터 크기: √(x²+y²+z²). FloatArray[3] → Float

    private fun tone(numSamples: Int, freq: Float, pan: Float, vol: Float): ShortArray {
    // 스테레오 PCM 사인파 샘플 배열 생성
    // numSamples: 모노 기준 샘플 수 (출력 배열 크기 = numSamples * 2, 스테레오 인터리브)
    // freq: 주파수 (Hz) | pan: 위치 -1(좌)~+1(우) | vol: 음량 0.0~1.0
        val out   = ShortArray(numSamples * 2)
        val lGain = cos((pan.coerceIn(-1f, 1f) + 1f) * PI.toFloat() / 4f)
        // 좌측 채널 게인: pan=-1 → 1.0, pan=0 → 0.707, pan=+1 → 0
        val rGain = cos((1f - pan.coerceIn(-1f, 1f)) * PI.toFloat() / 4f)
        // 우측 채널 게인: pan=+1 → 1.0, pan=0 → 0.707, pan=-1 → 0
        // 코사인 패닝: lGain² + rGain² = 1 (파워 보존)
        for (i in 0 until numSamples) {
            val s = (sin(2 * PI * freq * i / 44100) * 32767 * vol).toInt()
            // i번째 모노 샘플 값. 44100과 일치해야 정확한 주파수 생성
            out[i * 2]     = (s * lGain).toInt().toShort()   // 짝수 인덱스: 좌측 채널
            out[i * 2 + 1] = (s * rGain).toInt().toShort()   // 홀수 인덱스: 우측 채널
        }
        return out
    }

    private fun initAudioTrack() {
    // AudioTrack 초기화. onCreate() 에서 한 번 호출. PCM 스트리밍 재생 준비
        audioTrack?.stop(); audioTrack?.release()
        // 이전 인스턴스 정리 (재초기화 시 메모리 누수 방지)
        val sr     = 44100   // 샘플레이트 (Hz). tone() 함수와 동일한 값 사용 필수
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                // 미디어 재생 용도 분류 → 볼륨 채널 = 미디어 볼륨
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sr)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                // 16비트 PCM: 샘플 하나 = Short(2바이트)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                // 스테레오: Left + Right 채널
            .setBufferSizeInBytes(minBuf * 4)
            // 최소 버퍼의 4배 → 언더런(소리 끊김) 방지
            .setTransferMode(AudioTrack.MODE_STREAM).build()
            // MODE_STREAM: write() 로 실시간 데이터 공급
        audioTrack?.play()
        // 즉시 재생 상태로 설정. write() 호출 시 바로 출력
    }

    // ─────────────────────────────────────────────────────
    // 센서 등록 / 해제
    // ─────────────────────────────────────────────────────
    override fun onResume() {
    // 화면이 포그라운드로 돌아올 때. 배터리 절약을 위해 여기서 센서 등록
        super.onResume()
        linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        // TYPE_LINEAR_ACCELERATION 우선. 미지원 기기에서 TYPE_ACCELEROMETER 폴백
        gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        rotationSensor  = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        linearAccelSensor?.let { sensorManager.registerListener(swingListener, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscopeSensor?.let  { sensorManager.registerListener(swingListener, it, SensorManager.SENSOR_DELAY_GAME) }
        rotationSensor?.let   { sensorManager.registerListener(orientationListener, it, SensorManager.SENSOR_DELAY_GAME) }
        // SENSOR_DELAY_GAME ≈ 20ms 간격으로 센서 이벤트 수신
    }

    override fun onPause() {
    // 화면이 백그라운드로 갈 때. 센서 해제로 배터리 절약
        super.onPause()
        sensorManager.unregisterListener(swingListener)
        sensorManager.unregisterListener(orientationListener)
        // 등록된 모든 센서에서 각 리스너 해제
        bleManager.stopScan()
        gameJob?.cancel()
        audioJob?.cancel()
        spatialAudio.stopBeep()
        // 백그라운드 진입 시 게임·오디오 코루틴 중단
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop(); tts?.shutdown()
        audioTrack?.stop(); audioTrack?.release()
        spatialAudio.release()
        bleManager.disconnect()
        scope.cancel()
        // 스코프 취소 → gameJob, audioJob, beepBallJob 등 모든 하위 코루틴 일괄 종료
    }
}
