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

import android.util.Log
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
    private val BATTING_ANGLE_DEG = SwingGraphView.REF_PITCH_ANGLE_DEG
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
    private lateinit var tvBatteryLevel:       TextView
    private lateinit var btnResultView:        Button
    private var lastResultDialog:              android.app.AlertDialog? = null

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
    private val perPitchPitchTtsStartRelFull = mutableListOf<Long>()   // PITCH 발화 시작 시각
    private val perPitchPhase2RelFull        = mutableListOf<Long>()
    private val perPitchPhase3RelFull        = mutableListOf<Long>()
    private val perPitchWinOpenDeg           = mutableListOf<Float>()
    private val perPitchWinCloseDeg          = mutableListOf<Float>()

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
    private val HIT_ACCEL_THRESHOLD  = 48f
    private val HIT_GYRO_THRESHOLD   = 30f
    private val MIN_ACCEL_THRESHOLD  = 48f
    private val MIN_GYRO_THRESHOLD   = 30f

    private val PITCH_TOLERANCE      = 10f
    // 각도 허용 오차 (도). 현재 HEIGHT_TOLERANCE 기반 판정을 사용하나 참고용으로 보존

    // ── 물리 상수 (공·배트 3D 위치 계산) ──────────────────
    private val PITCHER_DIST     = 6.53f
    // 투수판 ~ 타석 거리 (m)

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

    @Volatile private var hitWindowActive:        Boolean = false
    @Volatile private var preWindowActive:        Boolean = false
    @Volatile private var postWindowActive:       Boolean = false
    @Volatile private var preWindowSwingDetected: Boolean = false
    @Volatile private var postWindowSwingDetected:Boolean = false
    @Volatile private var minSwingDetected:       Boolean = false
    @Volatile private var windowOpenPitchDeg:     Float   = 0f
    @Volatile private var windowClosePitchDeg:    Float   = 0f

    @Volatile private var minAngleSearchActive:   Boolean = false
    @Volatile private var minBatAngleDeg:         Float   = Float.MAX_VALUE
    @Volatile private var minBatAngleAbsMs:       Long    = -1L
    @Volatile private var minBatAngleRelMs:       Long    = -1L
    private var hitWindowOpenAbsMs:               Long    = -1L
    private var hitWindowCloseAbsMs:              Long    = -1L
    private var graphHitWindowOpenRelMs:          Long    = -1L
    private var graphHitWindowCloseRelMs:         Long    = -1L
    private var graphMinSearchStartRelMs:         Long    = -1L
    private var graphPitchWindowStartMs:          Long    = -1L
    private var graphPitchTtsStartMs:             Long    = -1L   // PITCH 발화 시작 시각

    @Volatile private var swingDetected:   Boolean = false
    // 스윙 시도 감지 (≥ FOUL 임계값). 파울 판정의 1차 조건
    // [DB 저장 후보] 스윙 여부 (true=스윙, false=스트라이크)

    @Volatile private var swingIsHit:      Boolean = false
    // 유효 타격 (≥ HIT 임계값 + 높이 일치). true → "깡" 판정
    // [DB 저장 후보] 정타 여부 (true=정타, false=파울/스트라이크)

    @Volatile private var gangSpoken:      Boolean = false
    // 윈도우 마감 직후 "깡" 조기 발화 여부 — 4a 판정에서 중복 방지용

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
    private val ttsManager = SwingTtsManager(this)

    // ── Audio ────────────────────────────────────────────
    private var audioTrack: AudioTrack? = null
    // PCM 스트리밍 오디오. tone() 으로 생성한 ShortArray 를 write() 로 직접 공급

    private lateinit var spatialAudio: SpatialAudioEngine

    // ── Hardware BLE ─────────────────────────────────────
    private lateinit var bleManager: BleManager
    // Seeed XIAO nRF52840 + BNO055 배트 센서 BLE 관리자

    @Volatile private var bleConnected = false
    // BLE 연결 여부. true 이면 폰 센서 대신 BLE 센서 데이터 사용

    // BLE 그래프 타임스탬프 균등 분배용
    // MCU 는 정확히 10ms 마다 전송하지만 BLE 연결 간격에 따라 여러 패킷이
    // 동시에 도착해 같은 타임스탬프를 갖는 계단 현상이 발생함.
    // 기록 시작 시점을 기준으로 패킷 수 × 10ms 로 표시 시각을 계산하면
    // 폰 기종과 무관하게 균등하게 분배되고 마커 위치도 틀어지지 않음.
    private var bleGraphBaseMs:       Long = 0L   // 기록 시작 시 실제 경과ms 기준점
    private var bleGraphPacketCount:  Long = 0L   // 기록 시작 후 수신 패킷 수
    private var bleGraphStarted:    Boolean = false // 현재 기록 구간 첫 패킷 여부

    @Volatile private var prevBtn1 = false
    @Volatile private var prevBtn2 = false
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

            if (minAngleSearchActive && currentPitchDeg < minBatAngleDeg) {
                minBatAngleDeg   = currentPitchDeg
                minBatAngleAbsMs = System.currentTimeMillis()
                minBatAngleRelMs = minBatAngleAbsMs - pitchRecordStart
            }

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
        val accel = linearAccelMag
        val gyro  = gyroMag

        val isStrongSwing = accel > HIT_ACCEL_THRESHOLD && gyro > HIT_GYRO_THRESHOLD
        val isAnySwing    = accel > HIT_ACCEL_THRESHOLD && gyro > HIT_GYRO_THRESHOLD
        val isMinSwing    = accel > MIN_ACCEL_THRESHOLD  && gyro > MIN_GYRO_THRESHOLD

        if ((preWindowActive || hitWindowActive || postWindowActive) && !minSwingDetected && isMinSwing) {
            minSwingDetected = true
        }

        if (preWindowActive && !preWindowSwingDetected && isAnySwing) {
            preWindowSwingDetected = true
        }

        if (hitWindowActive) {
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

        if (postWindowActive && !postWindowSwingDetected && isAnySwing) {
            postWindowSwingDetected = true
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
        Log.d("R_CHECK", "btnV2Start: ${R.id.btnV2Start}")
        Log.d("R_CHECK", "tvV2Status: ${R.id.tvV2Status}")
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
        tvBatteryLevel       = findViewById(R.id.tvBatteryLevel)
        btnResultView        = findViewById(R.id.btnResultView)

        btnResultView.setOnClickListener { lastResultDialog?.show() }

        ballTrackView.setShowStrikeZone(false)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        // ── TTS 초기화 ────────────────────────────────────
        ttsManager.init()

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
            lastResultDialog?.dismiss()
            lastResultDialog = null
            btnResultView.visibility = View.GONE
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
                ttsManager.speakEnglish("SET", speechRate = 1.2f)
            }
            delay(1000)

            // ━━━ 2단계: 공 접근 (2500ms) + READY (10피트) + PITCH 발화 ━━━
            val approachMs    = 1300L
            val readyDistM    = 10f * 0.3048f
            val readyProgress = ((PITCHER_DIST - readyDistM) / PITCHER_DIST).coerceIn(0f, 1f)
            var pitchTtsJob: Job? = null
            var pitchCompletedMs  = -1L

            val contactH = BATTER_HEIGHT + sin(BATTING_ANGLE_DEG * PI.toFloat() / 180f) * BAT_REACH

            // READY+PITCH TTS를 공 애니메이션보다 100ms 먼저 시작
            // → PITCH가 100ms 일찍 끝나 히트 윈도우 전에 발화 완료됨
            stopPhaseRecording()
            recordingPhase = 2
            startPhaseRecording()
            phase2StartAbsMs = phaseRecordStartTime
            preWindowActive = true
            pitchTtsJob = launch {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "READY"
                    tvStatus.setTextColor(0xFF93C5FD.toInt())
                }
                // READY·PITCH를 하나의 TTS 세션에 연속 큐잉
                // → 버퍼 오버헤드가 2회→1회로 줄어 총 발화 시간 ~0.7초 단축
                ttsManager.speakTwoSequentially(
                    first  = "READY",
                    second = "PITCH",
                    locale = Locale.ENGLISH,
                    speechRate = 1.2f,
                    onFirstDone = {
                        // READY 완료 → PITCH 발화 시작 시각 기록 + UI 전환
                        val ttsStartMs = System.currentTimeMillis() - pitchRecordStart
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            graphPitchTtsStartMs = ttsStartMs
                            swingGraphView.setPitchTtsStart(ttsStartMs)
                            tvStatus.text = "PITCH"
                            tvStatus.setTextColor(0xFFFBBF24.toInt())
                        }
                    }
                )
                pitchCompletedMs = System.currentTimeMillis()
                preWindowActive  = false
                withContext(Dispatchers.Main) {
                    tvStatus.text = ""
                }
            }

            // TTS에 300ms 선행 시간을 준 뒤 공 애니메이션 시작
            delay(500L)
            val tApproach = System.currentTimeMillis()

            spatialAudio.updateBallPosition(0f, 0f, -PITCHER_DIST)
            spatialAudio.updateHeading(currentHeadingDeg)
            spatialAudio.startBeep()

            // 루프 A: 공 애니메이션 (16ms 주기)
            val animJob = launch {
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

                    if (progress >= 1f) break
                    delay(16)
                }
            }

            // 루프 B: 타이밍 조건 체크 (1ms 주기)
            while (isActive) {
                val elapsed = System.currentTimeMillis() - tApproach

                // -900ms: 최저 각도 측정 시작
                if (!minAngleSearchActive && elapsed >= approachMs - 900L) {
                    minBatAngleDeg           = Float.MAX_VALUE
                    minBatAngleAbsMs         = -1L
                    minAngleSearchActive     = true
                    graphMinSearchStartRelMs = System.currentTimeMillis() - pitchRecordStart
                    withContext(Dispatchers.Main) {
                        swingGraphView.setMinAngleSearch(graphMinSearchStartRelMs)
                    }
                }

                // 타격 윈도우 오픈 (공 도착 160ms 전 — PITCH 발화 완료 후 여유 확보)
                if (!hitWindowActive && elapsed >= approachMs - 160L) {
                    swingDetected           = false
                    swingIsHit              = false
                    gangSpoken              = false
                    windowOpenPitchDeg      = currentPitchDeg
                    hitWindowOpenAbsMs      = System.currentTimeMillis()
                    graphHitWindowOpenRelMs = hitWindowOpenAbsMs - pitchRecordStart
                    hitWindowActive         = true
                    withContext(Dispatchers.Main) {
                        swingGraphView.setHitWindowOpen(graphHitWindowOpenRelMs)
                    }
                }

                if (elapsed >= approachMs) break
                delay(1)
            }
            animJob.join()

            // 애니메이션 종료(0ms): 공이 타자 위치에 도달
            spatialAudio.stopBeep()
            audioTrack?.pause(); audioTrack?.flush(); audioTrack?.play()

            stopPhaseRecording()
            recordingPhase = 3
            startPhaseRecording()
            phase3StartAbsMs = phaseRecordStartTime

            pitchTtsJob?.join()
            // onDone은 오디오 버퍼 flush 후 호출 → 실제 발음 종료보다 ~190ms 늦음
            // 보정값을 빼서 귀에 들리는 실제 PITCH 종료 시점에 맞춤
            val TTS_BUFFER_OFFSET_MS = 190L
            graphPitchWindowStartMs = if (pitchCompletedMs >= 0L)
                (pitchCompletedMs - pitchRecordStart - TTS_BUFFER_OFFSET_MS)
                    .coerceAtLeast(graphPitchTtsStartMs + 50L)  // PITCH↑보다는 항상 뒤에 위치
            else 0L
            withContext(Dispatchers.Main) {
                swingGraphView.setPitchWindowStart(graphPitchWindowStartMs)
            }

            // ━━━ 3단계: 타격 윈도우 마감 (+50ms) ━━━
            val deadline = System.currentTimeMillis() + 50L
            while (isActive && System.currentTimeMillis() < deadline) {
                if (swingDetected) break
                delay(8)
            }
            hitWindowActive          = false
            hitWindowCloseAbsMs      = System.currentTimeMillis()
            graphHitWindowCloseRelMs = hitWindowCloseAbsMs - pitchRecordStart
            windowClosePitchDeg      = currentPitchDeg

            // 윈도우 닫히는 순간의 minBatAngleDeg 스냅샷 — postWindow 동안 배트가
            // 계속 내려가도 최종 판정이 흔들리지 않도록 이 시점 값을 고정해서 사용
            val snapMinAngleDeg    = minBatAngleDeg
            val snapMinAngleAbsMs  = minBatAngleAbsMs

            withContext(Dispatchers.Main) {
                tvStatus.text = ""
                swingGraphView.setHitWindowClose(graphHitWindowCloseRelMs)
                swingGraphView.setWindowAngles(windowOpenPitchDeg, windowClosePitchDeg)
            }
            if (bleConnected) bleManager.sendControl(0)

            // 윈도우 마감 직후 정타 조건 즉시 평가 — postWindow 1000ms 기다리지 않음
            // 스냅샷 값 사용 → postWindow 중 배트가 더 내려가도 조기 판정과 최종 판정이 일치
            val earlyMinInWindow = snapMinAngleDeg < Float.MAX_VALUE &&
                                   snapMinAngleAbsMs >= hitWindowOpenAbsMs &&
                                   snapMinAngleAbsMs <= hitWindowCloseAbsMs
            val earlyAngleDiff   = if (snapMinAngleDeg < Float.MAX_VALUE)
                                       snapMinAngleDeg - BATTING_ANGLE_DEG
                                   else Float.MAX_VALUE

            if (earlyMinInWindow && swingWasStrong && abs(earlyAngleDiff) <= PITCH_TOLERANCE) {
                gangSpoken = true
                val impactSamples = 44100 * 150 / 1000
                launch(Dispatchers.IO) {
                    audioTrack?.write(tone(impactSamples, 880f, 0f, 1.0f), 0, impactSamples * 2)
                }
                withContext(Dispatchers.Main) {
                    ttsManager.speak("깡")
                    tvStatus.text = "소리 들어봐!"
                    tvStatus.setTextColor(0xFFFBBF24.toInt())
                }
            }

            postWindowActive = true
            delay(1000L)
            postWindowActive = false
            minAngleSearchActive = false
            withContext(Dispatchers.Main) {
                if (minBatAngleAbsMs > 0L && minBatAngleDeg < Float.MAX_VALUE) {
                    swingGraphView.setMinAngle(minBatAngleRelMs, minBatAngleDeg)
                }
            }

            delay(150L)
            isRecording = false
            stopPhaseRecording()
            if (setToReadyHistory.isNotEmpty())   perPitchPhase1Data.add(ArrayList(setToReadyHistory))
            if (readyToPitchHistory.isNotEmpty()) perPitchPhase2Data.add(ArrayList(readyToPitchHistory))
            if (pitchToEndHistory.isNotEmpty())   perPitchPhase3Data.add(ArrayList(pitchToEndHistory))
            perPitchHitTimesPhase3.add(hitTimePhase3Ms)

            // 메인 스레드에서 스냅샷 — BLE 콜백 큐를 모두 소진한 뒤 pitchHistory 복사
            val snapHitTime   = hitTimeRelMs
            val snapWinOpen   = graphHitWindowOpenRelMs
            val snapWinClose  = graphHitWindowCloseRelMs
            val snapMinSearch = graphMinSearchStartRelMs
            val snapMinRel    = if (minBatAngleAbsMs > 0L && minBatAngleDeg < Float.MAX_VALUE) minBatAngleRelMs else -1L
            val snapMinDeg    = minBatAngleDeg
            val snapPitchWin      = graphPitchWindowStartMs
            val snapPitchTtsStart = graphPitchTtsStartMs
            val snapP2Rel         = phase2StartAbsMs - pitchRecordStart
            val snapP3Rel     = phase3StartAbsMs - pitchRecordStart
            val snapWinOpenDeg  = windowOpenPitchDeg
            val snapWinCloseDeg = windowClosePitchDeg
            withContext(Dispatchers.Main) {
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

            // ━━━ 4단계: 결과 판정 (최저각도 기반) ━━━
            // 스냅샷 값 사용 → 조기 판정(earlyMinInWindow)과 동일 기준으로 평가
            val hasValidMin   = snapMinAngleDeg < Float.MAX_VALUE
            val minInWindow   = hasValidMin &&
                                snapMinAngleAbsMs >= hitWindowOpenAbsMs &&
                                snapMinAngleAbsMs <= hitWindowCloseAbsMs
            val minBeforeWin  = hasValidMin && snapMinAngleAbsMs < hitWindowOpenAbsMs
            val angleDiff     = if (hasValidMin) snapMinAngleDeg - BATTING_ANGLE_DEG else Float.MAX_VALUE
            val winDelta      = windowClosePitchDeg - windowOpenPitchDeg
            val winSign       = if (winDelta >= 0f) "+" else ""

            when {

                // ── 4x: 최소 스윙 미달 — 스트라이크(늦음)와 동일 처리 ──
                !minSwingDetected -> {
                    currentPitchRecord["판정"] = "스트라이크(무스윙)"
                    currentPitchRecord["피드백"] = "더 빨리 스윙하세요"
                    withContext(Dispatchers.Main) {
                        perPitchRecords.add(HashMap(currentPitchRecord))
                        ballTrackView.reset()
                        ttsManager.speak("스트라이크")
                        strikeCount++
                        tvStatus.text = "스트라이크!"
                        tvStatus.setTextColor(0xFFF87171.toInt())
                        tvResult.text = "스윙 없음\n필요 각도: %.0f°\n윈도우 각도 변화: $winSign%.1f°"
                            .format(BATTING_ANGLE_DEG, winDelta)
                        if (!isTraining) {
                            showSwingGraph()
                            btnStart.isEnabled = true
                            btnStart.text = "다시하기"
                        }
                    }
                    delay(1000L)
                    ttsManager.speakAndWait("더 빨리 스윙하세요", Locale.KOREAN)
                    if (isTraining) {
                        withContext(Dispatchers.Main) { scheduleNextOrFinish(false) }
                    }
                }

                // ── 4a: 정타 — 최저각이 윈도우 안 + 각도 일치 + 강한 스윙 ──
                minInWindow && swingWasStrong && abs(angleDiff) <= PITCH_TOLERANCE -> {
                    currentPitchRecord["판정"] = "정타"
                    currentPitchRecord["피드백"] = "정타 — 베이스 선택"
                    withContext(Dispatchers.Main) {
                        // gangSpoken=false면 조기 발화가 안 된 경우 — 여기서 fallback 발화
                        if (!gangSpoken) {
                            val impactSamples = 44100 * 150 / 1000
                            launch(Dispatchers.IO) {
                                audioTrack?.write(tone(impactSamples, 880f, 0f, 1.0f), 0, impactSamples * 2)
                            }
                            ttsManager.speak("깡")
                            tvStatus.text = "소리 들어봐!"
                            tvStatus.setTextColor(0xFFFBBF24.toInt())
                        }
                        tvResult.text = "최저 각도: %.0f°  /  필요 각도: %.0f°\n윈도우 각도 변화: $winSign%.1f°"
                            .format(minBatAngleDeg, BATTING_ANGLE_DEG, winDelta)
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
                        tvStatus.text = ""
                        hitCount++
                    }

                    startBaseBeep()
                    isWaitingForInput = true
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
                        ballTrackView.reset()
                        ttsManager.speak("파울")
                        foulCount++
                        tvStatus.text = "파울!"
                        tvStatus.setTextColor(0xFFFBBF24.toInt())
                        val resultLabel = if (!swingWasStrong) "파울 — 힘 부족" else "파울 — 각도 불일치"
                        tvResult.text = "$resultLabel\n최저 각도: %.0f°  /  필요 각도: %.0f°\n윈도우 각도 변화: $winSign%.1f°"
                            .format(minBatAngleDeg, BATTING_ANGLE_DEG, winDelta)
                        if (!isTraining) {
                            showSwingGraph()
                            btnStart.isEnabled = true
                            btnStart.text = "다시하기"
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
                        ballTrackView.reset()
                        ttsManager.speak("스트라이크")
                        strikeCount++
                        tvStatus.text = "스트라이크!"
                        tvStatus.setTextColor(0xFFF87171.toInt())
                        tvResult.text = "타격 윈도우 전 스윙\n최저 각도: %.0f°  /  필요 각도: %.0f°\n윈도우 각도 변화: $winSign%.1f°"
                            .format(minBatAngleDeg, BATTING_ANGLE_DEG, winDelta)
                        if (!isTraining) {
                            showSwingGraph()
                            btnStart.isEnabled = true
                            btnStart.text = "다시하기"
                        }
                    }
                    delay(1000L)
                    ttsManager.speakAndWait("더 늦게 스윙하세요", Locale.KOREAN)
                    if (isTraining) {
                        withContext(Dispatchers.Main) { scheduleNextOrFinish(false) }
                    }
                }

                // ── 4d: 스트라이크 — 무스윙 or 윈도우 마감 후 ──
                else -> {
                    currentPitchRecord["판정"] = "스트라이크(늦음)"
                    currentPitchRecord["피드백"] = "더 빨리 스윙하세요"
                    withContext(Dispatchers.Main) {
                        perPitchRecords.add(HashMap(currentPitchRecord))
                        ballTrackView.reset()
                        ttsManager.speak("스트라이크")
                        strikeCount++
                        tvStatus.text = "스트라이크!"
                        tvStatus.setTextColor(0xFFF87171.toInt())
                        tvResult.text = "타격 윈도우 후 스윙 / 무스윙\n필요 각도: %.0f°\n윈도우 각도 변화: $winSign%.1f°"
                            .format(BATTING_ANGLE_DEG, winDelta)
                        if (!isTraining) {
                            showSwingGraph()
                            btnStart.isEnabled = true
                            btnStart.text = "다시하기"
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
    // 베이스 도착음 — 베이스 선택 전까지 반복 재생 (헤드트래킹 스테레오 패닝)
    // ─────────────────────────────────────────────────────
    private fun startBaseBeep() {
        val finalPan = if (targetBase == 3) -1.0f else 1.0f
        // 3루=왼쪽(-1.0), 1루=오른쪽(+1.0). 베이스 방향의 기본 패닝값

        audioJob?.cancel()

        audioJob = scope.launch {
            val sr          = 44100
            val beepSamples = sr * 200 / 1000   // 200ms 비프음 샘플 수
            val silSamples  = sr * 0 / 1000     // 무음 샘플 수

            while (isActive) {
                val rAngle = finalPan * 90f + currentHeadingDeg
                // 베이스 방향(±90°) + 현재 머리 방향 = 실제 음원 각도
                val pan    = sin(Math.toRadians(rAngle.toDouble())).toFloat().coerceIn(-1f, 1f)
                audioTrack?.write(tone(beepSamples, 880f, pan, 1.0f), 0, beepSamples * 2)
                if (!isActive) break
                audioTrack?.write(ShortArray(silSamples * 2), 0, silSamples * 2)
            }
        }
        beepStartTime = SystemClock.elapsedRealtimeNanos()
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
            currentPitchRecord["피드백"] = "정타 — ${pressedBase}루 정답 (반응속도 ${ms}ms)"
            reactionTimes.add(ms)
            tvStatus.text = "성공!"
            tvStatus.setTextColor(0xFF4ADE80.toInt())
            val sec = ms / 1000.0
            tvResult.text = "%.2f 초".format(sec)
            ttsManager.speak("성공, 반응속도 %.1f초".format(sec))
        } else {
            currentPitchRecord["피드백"] = "정타 — ${pressedBase}루 오답 (목표: ${targetBase}루)"
            tvStatus.text = "알맞지 않은\n베이스 선택입니다"
            tvStatus.setTextColor(0xFFF87171.toInt())
            tvResult.text = ""
            Toast.makeText(this, "알맞지 않은 베이스 선택입니다", Toast.LENGTH_SHORT).show()
            ttsManager.speak("베이스 선택이 틀렸습니다")
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
            ttsManager.speak("배트가 연결되었습니다")
            // sendControl(1) 은 여기서 호출하지 않음.
            // onConnected 에서 sendControl(1) 을 보내면 MCU 가 isMeasuring=true 로 진입해
            // 단일 버튼(왼쪽/오른쪽) 전송이 5 초간 차단됨 → 버튼 딜레이 버그.
            // 또한 게임 SET 단계에서 sendControl(1) 을 다시 보낼 때 MCU 가
            // !isMeasuring 조건 불충족으로 무시 → 측정 데이터 안 들어오는 버그.
            // sendControl(1) 은 게임 SET 단계(startPitchSequence)에서만 전송.
        }

        override fun onDisconnected() {
            bleConnected = false
            btnBleConnect.isEnabled = true
            btnBleConnect.text = "배트 센서 연결"
            tvBleStatus.text = "● 미연결"
            tvBleStatus.setTextColor(0xFFF87171.toInt())
            ttsManager.speak("배트 연결이 끊겼습니다")
            // 2초 후 자동 재스캔
            scope.launch {
                delay(2000)
                withContext(Dispatchers.Main) {
                    if (!bleConnected && hasBlePermissions()) startBleScan()
                }
            }
        }

        override fun onBatteryLevel(level: Int) {
            tvBatteryLevel.text = "배터리 ${level}%"
            tvBatteryLevel.setTextColor(if (level <= 20) 0xFFF87171.toInt() else 0xFF64748B.toInt())
        }

        override fun onPacket(packet: BleManager.SensorPacket) {
            // BLE 패킷에서 스윙 감지용 값 갱신
            // handleEuler: [X=heading, Y=pitch, Z=roll] (도)
            // handleGyro:  [x,y,z] (rad/s), handleAccel: [x,y,z] (m/s²)
            linearAccelMag  = magnitude(packet.handleAccel)
            gyroMag         = magnitude(packet.handleGyro)
            currentPitchDeg = packet.handleEuler[1]
            checkSwing()

            if (minAngleSearchActive && currentPitchDeg < minBatAngleDeg) {
                minBatAngleDeg   = currentPitchDeg
                minBatAngleAbsMs = System.currentTimeMillis()
                minBatAngleRelMs = minBatAngleAbsMs - pitchRecordStart
            }


            // 배트 높이 라이브 업데이트
            val batH = BATTER_HEIGHT + sin(currentPitchDeg * PI.toFloat() / 180f) * BAT_REACH
            liveBallParabolaView.updateLiveBat(batH)

            // 스윙 궤적 기록 (메인 스레드에서 실행 — BleManager가 mainHandler.post 사용)
            // BLE 버스트 수신 시 여러 패킷이 동시에 도착해 같은 타임스탬프를 가짐 → 계단 현상.
            // 패킷 카운터 × MCU 전송 주기(10ms) 로 표시 시각을 균등 분배.
            // 기준점(bleGraphBaseMs)을 첫 패킷의 실제 경과시각으로 맞춰
            // 히트 윈도우 등 마커와 시간축이 어긋나지 않도록 함.
            if (isRecording) {
                val realMs = System.currentTimeMillis() - pitchRecordStart
                if (!bleGraphStarted) {
                    bleGraphBaseMs  = realMs
                    bleGraphStarted = true
                }
                val displayMs = bleGraphBaseMs + bleGraphPacketCount * 10L
                bleGraphPacketCount++
                pitchHistory.add(Pair(displayMs, currentPitchDeg))
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
    // ─────────────────────────────────────────────────────
    // 배트 버튼 처리
    //   오른쪽(btn1) 단일탭 → 투구수 +1 / 1루 선택
    //   왼쪽(btn2)   단일탭 → 투구수 -1 / 3루 선택
    //   양쪽 동시    → 훈련 시작
    // ─────────────────────────────────────────────────────
    private fun handleBatButton(btn1: Boolean, btn2: Boolean) {
        val wasBtn1 = prevBtn1
        val wasBtn2 = prevBtn2

        // 오른쪽 버튼 상승 에지
        if (btn1 && !wasBtn1) {
            when {
                isWaitingForInput -> { batBtn2Job?.cancel(); onBasePressed(1) }
                !isTraining -> {
                    if (batBtn2Job?.isActive == true) {
                        // 왼쪽 타이머 대기 중 → 동시 누름 → 훈련 시작
                        batBtn1Job?.cancel(); batBtn2Job?.cancel()
                        if (btnStart.isEnabled) btnStart.performClick()
                    } else {
                        batBtn1Job?.cancel()
                        batBtn1Job = scope.launch {
                            delay(200L)
                            withContext(Dispatchers.Main) {
                                if (batBtn2Job?.isActive != true && targetPitches < 30) {
                                    targetPitches++
                                    tvSwingPitchCount.text = targetPitches.toString()
                                    ttsManager.speak("${targetPitches}회")
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
                !isTraining -> {
                    if (batBtn1Job?.isActive == true) {
                        // 오른쪽 타이머 대기 중 → 동시 누름 → 훈련 시작
                        batBtn1Job?.cancel(); batBtn2Job?.cancel()
                        if (btnStart.isEnabled) btnStart.performClick()
                    } else {
                        batBtn2Job?.cancel()
                        batBtn2Job = scope.launch {
                            delay(200L)
                            withContext(Dispatchers.Main) {
                                if (batBtn1Job?.isActive != true && targetPitches > 1) {
                                    targetPitches--
                                    tvSwingPitchCount.text = targetPitches.toString()
                                    ttsManager.speak("${targetPitches}회")
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

        audioTrack?.stop()  // ✅ 팀원 코드에서 추가타율,주루반응속도
        audioTrack?.stop()  // 기존 오디오 정지
        val battingAvgPct = if (targetPitches > 0) (hitCount.toFloat() / targetPitches * 100).toInt() else 0
        val avgReactionForTts = if (reactionTimes.isNotEmpty()) reactionTimes.average().toLong() else -1L
        val ttsText = buildString {
            append("훈련 완료. ")
            append("정타 ${hitCount}개, 타율 ${battingAvgPct}퍼센트. ")
            if (avgReactionForTts >= 0L) append("평균 반응속도 %.1f초.".format(avgReactionForTts / 1000.0))
        }
        ttsManager.speak(ttsText)

        val battingAvg     = if (targetPitches > 0) hitCount.toFloat() / targetPitches else 0f
        val avgReaction    = if (reactionTimes.isNotEmpty()) reactionTimes.average().toLong() else -1L
        val baseCorrectPct = if (hitCount > 0) successCount.toFloat() / hitCount * 100f else 0f

        val userId = getSharedPreferences("UserInfo", MODE_PRIVATE).getString("id", "anonymous") ?: "anonymous"

        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val sessionId = System.currentTimeMillis().toString()

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

        val sessionRef = db.collection("users")
            .document(userId)
            .collection("훈련기록")
            .document(sessionId)

        sessionRef.set(sessionData)
            .addOnSuccessListener {
                perPitchRecords.forEachIndexed { index, record ->
                    sessionRef.collection("투구별기록")
                        .document("${index + 1}번투구")
                        .set(record)
                }
            }
            .addOnFailureListener { e ->
                android.util.Log.e("Firebase", "업로드 실패: ${e.message}")
            }

        val statsPref = getSharedPreferences("TrainingStats_$userId", MODE_PRIVATE)
        val editor = statsPref.edit()

        val count = statsPref.getInt("count", 0)
        val newCount = minOf(count + 1, 10)
        editor.putInt("count", newCount)
        editor.putFloat("sum_batting_avg", statsPref.getFloat("sum_batting_avg", 0f) + battingAvg)
        editor.putFloat("sum_base_correct_pct", statsPref.getFloat("sum_base_correct_pct", 0f) + baseCorrectPct)
        editor.putFloat("sum_reaction", statsPref.getFloat("sum_reaction", 0f) + avgReaction.toFloat())
        editor.putFloat("sum_hit", statsPref.getFloat("sum_hit", 0f) + hitCount.toFloat())
        editor.putFloat("sum_foul", statsPref.getFloat("sum_foul", 0f) + foulCount.toFloat())
        editor.putFloat("sum_strike", statsPref.getFloat("sum_strike", 0f) + strikeCount.toFloat())
        editor.putFloat("sum_base_correct", statsPref.getFloat("sum_base_correct", 0f) + successCount.toFloat())

        val totalCount = statsPref.getInt("total_count", 0) + 1
        editor.putInt("total_count", totalCount)
        editor.putFloat("total_sum_batting_avg", statsPref.getFloat("total_sum_batting_avg", 0f) + battingAvg)
        editor.putFloat("total_sum_base_correct_pct", statsPref.getFloat("total_sum_base_correct_pct", 0f) + baseCorrectPct)
        editor.putFloat("total_sum_reaction", statsPref.getFloat("total_sum_reaction", 0f) + avgReaction.toFloat())
        editor.putFloat("total_sum_hit", statsPref.getFloat("total_sum_hit", 0f) + hitCount.toFloat())
        editor.putFloat("total_sum_foul", statsPref.getFloat("total_sum_foul", 0f) + foulCount.toFloat())
        editor.putFloat("total_sum_strike", statsPref.getFloat("total_sum_strike", 0f) + strikeCount.toFloat())
        editor.putFloat("total_sum_base_correct", statsPref.getFloat("total_sum_base_correct", 0f) + successCount.toFloat())

        editor.apply()

        showTrainingSummary()
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

        val totalData   = perPitchFullHistory.size
        val heightPx    = (220 * resources.displayMetrics.density).toInt()
        var currentIdx  = 0

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

        val tvFeedback = TextView(this)
        tvFeedback.textSize = 14f
        tvFeedback.gravity = android.view.Gravity.CENTER
        tvFeedback.setPadding(56, 4, 56, 8)
        tvFeedback.typeface = android.graphics.Typeface.DEFAULT_BOLD

        val tvLabel1 = TextView(this)
        tvLabel1.text = "SET → READY 구간 배트 각도"
        tvLabel1.textSize = 13f
        tvLabel1.setTextColor(0xFF86EFAC.toInt())
        tvLabel1.setPadding(56, 4, 56, 4)

        val graph1 = SwingGraphView(this)
        graph1.setShowSummary(false)

        val tvLabel2 = TextView(this)
        tvLabel2.text = "READY → PITCH 구간 배트 각도  (보라: 최저각 측정 시작)"
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

            val fullHist    = if (idx < perPitchFullHistory.size)          perPitchFullHistory[idx]          else ArrayList()
            val hitTime     = if (idx < perPitchHitTimeRelFull.size)       perPitchHitTimeRelFull[idx]       else -1L
            val winOpen     = if (idx < perPitchWinOpenRelFull.size)       perPitchWinOpenRelFull[idx]       else -1L
            val winClose    = if (idx < perPitchWinCloseRelFull.size)      perPitchWinCloseRelFull[idx]      else -1L
            val minSearch   = if (idx < perPitchMinSearchRelFull.size)     perPitchMinSearchRelFull[idx]     else -1L
            val minAngleRel = if (idx < perPitchMinAngleRelFull.size)      perPitchMinAngleRelFull[idx]      else -1L
            val minDeg      = if (idx < perPitchMinAngleDegList.size)      perPitchMinAngleDegList[idx]      else Float.MAX_VALUE
            val pitchWinSt     = if (idx < perPitchPitchWinStartRelFull.size)  perPitchPitchWinStartRelFull[idx]  else -1L
            val pitchTtsSt     = if (idx < perPitchPitchTtsStartRelFull.size) perPitchPitchTtsStartRelFull[idx] else -1L
            val phase2Rel   = if (idx < perPitchPhase2RelFull.size)        perPitchPhase2RelFull[idx].coerceAtLeast(0L) else 0L
            val phase3Rel   = if (idx < perPitchPhase3RelFull.size)        perPitchPhase3RelFull[idx].coerceAtLeast(0L) else 0L
            val winOpenDeg  = if (idx < perPitchWinOpenDeg.size)           perPitchWinOpenDeg[idx]           else 0f
            val winCloseDeg = if (idx < perPitchWinCloseDeg.size)          perPitchWinCloseDeg[idx]          else 0f

            // 판정 + 피드백 표시
            val record = perPitchRecords.getOrNull(idx)
            val judgment = record?.get("판정") as? String ?: ""
            val feedback = record?.get("피드백") as? String ?: ""
            val (judgmentColor, feedbackColor) = when {
                judgment.startsWith("정타")         -> 0xFF4ADE80.toInt() to 0xFF86EFAC.toInt()
                judgment.startsWith("파울")         -> 0xFFFBBF24.toInt() to 0xFFFDE68A.toInt()
                judgment.startsWith("스트라이크")   -> 0xFFF87171.toInt() to 0xFFFCA5A5.toInt()
                else                                -> 0xFFFFFFFF.toInt() to 0xFFAAAAAA.toInt()
            }
            tvPitchNum.text = "${idx + 1} / $totalData   [$judgment]"
            tvPitchNum.setTextColor(judgmentColor)
            tvFeedback.text = "💬 $feedback"
            tvFeedback.setTextColor(feedbackColor)

            // fullHist 를 phase 경계로 분할 (각 그래프 내부 시간 기준으로 정규화)
            val seg1 = ArrayList(fullHist.filter { it.first < phase2Rel }
                .map { Pair(it.first, it.second) })
            val seg2 = ArrayList(fullHist.filter { it.first in phase2Rel until phase3Rel }
                .map { Pair(it.first - phase2Rel, it.second) })
            val seg3 = ArrayList(fullHist.filter { it.first >= phase3Rel }
                .map { Pair(it.first - phase3Rel, it.second) })

            // graph2 기준 마커 (pitchRecordStart relative → phase2 relative)
            fun toG2(ms: Long) = ms - phase2Rel
            // minSearch 는 phase2 구간 중(-900ms)에 시작 → graph2에 표시
            val g2MinSearch   = if (minSearch    >= 0L) toG2(minSearch)   else -1L
            // hitWindowOpen 도 PITCH TTS 완료 시점(~phase2 말미)에 발생 → graph2 우측에 표시
            val g2WinOpen     = if (winOpen      >= 0L) toG2(winOpen)     else -1L
            // PITCH 발화 시작/종료 → graph2 후반부에 표시
            val g2PitchTtsSt  = if (pitchTtsSt   >= 0L) toG2(pitchTtsSt)  else -1L
            val g2PitchWin    = if (pitchWinSt   >= 0L) toG2(pitchWinSt)  else -1L

            // graph3 기준 마커 (pitchRecordStart relative → phase3 relative)
            fun toG3(ms: Long) = ms - phase3Rel
            val g3Hit       = if (hitTime     >= 0L) toG3(hitTime)     else -1L
            val g3WinOpen   = if (winOpen     >= 0L) maxOf(0L, toG3(winOpen)) else -1L
            val g3WinClose  = if (winClose    >= 0L) toG3(winClose)    else -1L
            val g3MinAngle  = if (minAngleRel >= 0L) toG3(minAngleRel) else -1L
            val g3PitchWin  = if (pitchWinSt  >= 0L) toG3(pitchWinSt)  else -1L

            graph1.setData(seg1, -1L, BATTING_ANGLE_DEG)

            graph2.setData(seg2, -1L, BATTING_ANGLE_DEG)
            if (g2MinSearch  >= 0L) graph2.setMinAngleSearch(g2MinSearch)
            if (g2WinOpen    >= 0L) graph2.setHitWindowOpen(g2WinOpen)
            if (g2PitchTtsSt >= 0L) graph2.setPitchTtsStart(g2PitchTtsSt)
            if (g2PitchWin   >= 0L) graph2.setPitchWindowStart(g2PitchWin)

            graph3.setData(seg3, g3Hit, BATTING_ANGLE_DEG)
            graph3.setTolerance(PITCH_TOLERANCE)
            graph3.setWindowAngles(winOpenDeg, winCloseDeg)
            if (g3PitchWin >= 0L)                               graph3.setPitchWindowStart(g3PitchWin)
            if (g3WinOpen  >= 0L)                               graph3.setHitWindowOpen(g3WinOpen)
            if (g3WinClose >= 0L)                               graph3.setHitWindowClose(g3WinClose)
            if (g3MinAngle >= 0L && minDeg < Float.MAX_VALUE)   graph3.setMinAngle(g3MinAngle, minDeg)

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
        container.addView(tvFeedback)
        container.addView(tvLabel1)
        container.addView(graph1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx))
        container.addView(tvLabel2)
        container.addView(graph2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx))
        container.addView(tvLabel3)
        container.addView(graph3, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx))

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(0xFF0A1423.toInt())
        scroll.addView(container)

        lastResultDialog = AlertDialog.Builder(this)
            .setTitle("훈련 종합 결과")
            .setView(scroll)
            .setPositiveButton("확인", null)
            .create()
        lastResultDialog?.show()
        btnResultView.visibility = View.VISIBLE
        if (totalData > 0) {
            graph1.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    graph1.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    graph1.invalidate()
                }
            })
            graph2.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    graph2.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    graph2.invalidate()
                }
            })
            graph3.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    graph3.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    graph3.invalidate()
                }
            })
        }
    }

    private fun resetAndShowLiveGraphs() {
        pitchHistory.clear()
        hitTimeRelMs       = -1L
        pitchRecordStart   = System.currentTimeMillis()
        bleGraphStarted    = false   // BLE 패킷 카운터 리셋
        bleGraphPacketCount = 0L
        isRecording        = true
        swingGraphView.setLiveSource(pitchHistory, BATTING_ANGLE_DEG)
        swingGraphView.setTolerance(PITCH_TOLERANCE)
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
        graphView.setTolerance(PITCH_TOLERANCE)
        graphView.setWindowAngles(windowOpenPitchDeg, windowClosePitchDeg)
        if (graphPitchWindowStartMs >= 0L)
            graphView.setPitchWindowStart(graphPitchWindowStartMs)
        if (graphHitWindowOpenRelMs >= 0L)
            graphView.setHitWindowOpen(graphHitWindowOpenRelMs)
        if (graphHitWindowCloseRelMs >= 0L)
            graphView.setHitWindowClose(graphHitWindowCloseRelMs)
        if (graphMinSearchStartRelMs >= 0L)
            graphView.setMinAngleSearch(graphMinSearchStartRelMs)
        if (minBatAngleAbsMs > 0L && minBatAngleDeg < Float.MAX_VALUE)
            graphView.setMinAngle(minBatAngleRelMs, minBatAngleDeg)

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

    // ─────────────────────────────────────────────────────
    // 오디오 중단
    // ─────────────────────────────────────────────────────
    private fun stopAudio() {
        audioJob?.cancel()
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.play()
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
        super.onResume()
        initAudioTrack()
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
        ttsManager.shutdown()
        audioTrack?.stop(); audioTrack?.release()
        spatialAudio.release()
        bleManager.disconnect()
        scope.cancel()
        // 스코프 취소 → gameJob, audioJob, btn1TapJob 등 모든 하위 코루틴 일괄 종료
    }
}
