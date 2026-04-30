package com.beepbeep.defense.batting
// 이 파일이 속한 패키지 선언. 같은 패키지(batting)의 BallTrackView, BallPhase 등을 import 없이 사용 가능.

// ── Android 프레임워크 import ──────────────────────────────────────────────
import android.hardware.Sensor                  // 센서 종류 상수 (TYPE_GYROSCOPE 등) 참조용
import android.hardware.SensorEvent             // 센서 콜백에서 받는 이벤트 객체 (values 배열 포함)
import android.hardware.SensorEventListener     // 센서 이벤트를 수신하기 위한 인터페이스
import android.hardware.SensorManager           // 시스템 센서 서비스 접근 및 리스너 등록/해제
import android.media.AudioAttributes            // AudioTrack 생성 시 오디오 용도 설정 (USAGE_MEDIA 등)
import android.media.AudioFormat                // AudioTrack 포맷 설정 (샘플레이트, 채널, 인코딩)
import android.media.AudioTrack                 // PCM 오디오를 직접 스트리밍하는 저수준 오디오 클래스
import android.os.Bundle                        // Activity 상태 저장/복원에 쓰이는 키-값 묶음
import android.os.Handler                       // 메인 스레드에 지연 작업(Runnable)을 예약하는 클래스
import android.os.Looper                        // Handler 생성 시 메인 루퍼(Looper.getMainLooper()) 지정용
import android.os.SystemClock                   // elapsedRealtimeNanos(): 부팅 후 경과 시간(나노초, 절전 포함)
import android.speech.tts.TextToSpeech          // TTS 엔진 (SET·PITCH 발화). 초기화 후 speak() 호출
import android.speech.tts.UtteranceProgressListener // TTS 발화 완료 콜백 (onDone) 을 받기 위한 추상 클래스
import android.view.View                        // base1Glow / base3Glow 등 일반 View 타입 참조
import android.widget.Button                    // btnStart 버튼 위젯 타입
import android.widget.FrameLayout               // base1Container / base3Container 컨테이너 타입
import android.widget.TextView                  // tvStatus / tvResult 텍스트 위젯 타입
import android.app.AlertDialog                  // 스윙 결과 그래프 다이얼로그 표시
import android.widget.LinearLayout              // AlertDialog 에 SwingGraphView 를 담는 컨테이너
import android.widget.ScrollView                // 그래프 다이얼로그 스크롤 컨테이너
import android.widget.Toast                     // 잘못된 베이스 선택 시 짧은 안내 메시지 표시
import androidx.appcompat.app.AppCompatActivity // 액션바·테마 지원이 포함된 기본 Activity 상위 클래스
import com.beepbeep.defense.R                   // res/ 폴더 아래 id, layout, drawable 등 리소스 참조
import kotlinx.coroutines.*                     // CoroutineScope, launch, delay, withContext 등 코루틴 API 전체
import kotlin.math.*                            // sin, cos, sqrt 등 수학 함수
import kotlin.random.Random                     // 1루/3루 랜덤 선택에 사용
import java.util.Locale                         // TTS 언어 설정 (Locale.ENGLISH → SET·PITCH 영어 발음)

// ── 액티비티 클래스 선언 ────────────────────────────────────────────────────
class BaseRunReactionActivity : AppCompatActivity() {
// AppCompatActivity를 상속. AndroidManifest.xml 의 <activity android:name=".batting.BaseRunReactionActivity"> 와 연결됨.

    // ── Views ──────────────────────────────────────────────
    // 아래 모든 뷰는 onCreate() 에서 setContentView(R.layout.activity_base_run_reaction) 로
    // 레이아웃을 불러온 뒤 findViewById() 로 바인딩됨. (→ res/layout/activity_base_run_reaction.xml)

    private lateinit var tvStatus: TextView
    // R.id.tvStatus: 현재 게임 상태("SET", "PITCH", "쳐!", 결과 등)를 표시하는 중앙 텍스트뷰

    private lateinit var tvResult: TextView
    // R.id.tvResult: 반응속도 결과(ms) 또는 "스윙하지 않았습니다" 등 보조 결과를 표시

    private lateinit var btnStart: Button
    // R.id.btnStart: 게임 시작/다시하기 버튼. 게임 중에는 isEnabled=false 로 비활성화

    private lateinit var ballTrackView: BallTrackView
    private lateinit var swingGraphView: SwingGraphView
    // R.id.ballTrackView: 공의 궤적·수비수·베이스를 그리는 커스텀 뷰 (→ BallTrackView.kt)

    private lateinit var base1Container: FrameLayout
    // R.id.base1Container: 1루 베이스 영역 컨테이너. 클릭 시 onBasePressed(1) 호출 (→ XML 우측 FrameLayout)

    private lateinit var base3Container: FrameLayout
    // R.id.base3Container: 3루 베이스 영역 컨테이너. 클릭 시 onBasePressed(3) 호출 (→ XML 좌측 FrameLayout)

    private lateinit var base1Glow: View
    // R.id.base1Glow: 1루 활성화 시 빛나는 효과 레이어. 평소 INVISIBLE → 도착 시 VISIBLE (→ base_diamond_active drawable)

    private lateinit var base3Glow: View
    // R.id.base3Glow: 3루 활성화 시 빛나는 효과 레이어. base1Glow와 동일 구조

    private lateinit var tvBase1Label: TextView
    // R.id.tvBase1Label: base1Container 내부 "1루" 텍스트. 베이스 활성화 시 색·크기 변경됨

    private lateinit var tvBase3Label: TextView
    // R.id.tvBase3Label: base3Container 내부 "3루" 텍스트. tvBase1Label과 동일 구조

    // ── TTS (TextToSpeech) ─────────────────────────────────
    private var tts: TextToSpeech? = null
    // TextToSpeech 엔진 인스턴스. onCreate() 에서 초기화되며 null 가능 (초기화 실패 대비)

    private var ttsReady = false
    // TTS 초기화 완료 여부. onInit 콜백에서 SUCCESS 시 true 로 설정됨.
    // speakAndWait() 에서 false 이면 500ms 대기 후 그냥 진행

    // ── 방향 센서 (헤드트래킹, 베이스 도착음 pan 조절) ──────
    private lateinit var sensorManager: SensorManager
    // 시스템 서비스. onCreate() 에서 getSystemService(SensorManager::class.java) 로 획득.
    // 센서 등록(registerListener)·해제(unregisterListener) 에 사용

    private val rotMatrix = FloatArray(9)
    // 3×3 회전 행렬 (9개 원소). SensorManager.getRotationMatrixFromVector() 가 여기에 결과를 씀

    private val orientation = FloatArray(3)
    // [0]=방위각(azimuth), [1]=피치, [2]=롤. SensorManager.getOrientation() 가 여기에 결과를 씀

    private var baseAzimuth: Float? = null
    // 게임 시작 시점의 방위각 기준값. null이면 첫 번째 센서 이벤트 값으로 초기화.
    // 이 값을 기준으로 상대 회전량(currentHeadingDeg)을 계산

    @Volatile private var currentHeadingDeg: Float = 0f
    // 현재 머리 방향 (기준점 대비 상대 각도, 도). @Volatile: 센서 스레드 ↔ 오디오 스레드 간 안전한 공유

    private val orientationListener = object : SensorEventListener {
    // TYPE_GAME_ROTATION_VECTOR (또는 TYPE_ROTATION_VECTOR) 센서 이벤트 수신용 리스너.
    // onResume() 에서 sensorManager.registerListener() 로 등록됨

        override fun onSensorChanged(event: SensorEvent) {
        // 센서 값이 바뀔 때마다 호출됨 (SENSOR_DELAY_GAME ≈ 20ms 간격)

            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            // event.values(회전벡터) → rotMatrix(3×3 회전행렬) 변환.
            // 결과는 this.rotMatrix 에 in-place 저장

            SensorManager.getOrientation(rotMatrix, orientation)
            // rotMatrix → orientation([방위각, 피치, 롤]) 변환. 라디안 단위.
            // 결과는 this.orientation 에 in-place 저장

            val azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
            // orientation[0](방위각, 라디안) → 도(degree) 변환

            if (baseAzimuth == null) baseAzimuth = azimuthDeg
            // 게임 시작 후 첫 번째 이벤트에서만 기준 방위각 저장

            var rel = azimuthDeg - (baseAzimuth ?: azimuthDeg)
            // 현재 방위각 - 기준 방위각 = 상대 회전량(도)

            while (rel > 180f)  rel -= 360f
            while (rel < -180f) rel += 360f
            // -180~+180 범위로 정규화 (경계값 wrap-around 처리)

            currentHeadingDeg = -rel
            // 부호 반전: 오른쪽으로 돌면 양수(+), 왼쪽으로 돌면 음수(-) 로 직관적으로 맞춤

            // ── 피치각(상하 기울기) 추적 ─────────────────────────
            val pitchDeg = Math.toDegrees(orientation[1].toDouble()).toFloat()
            // orientation[1]: 기기 피치 (라디안). 화면을 위로 향하면 양수, 아래로 향하면 음수

            currentPitchDeg = pitchDeg
            // 절대 피치각 (도). 화면이 하늘을 향해 수평 → 0°, 세워서 들면 → 약 -90°

            if (isRecording) {
                pitchHistory.add(Pair(System.currentTimeMillis() - pitchRecordStart, pitchDeg))
                swingGraphView.postInvalidate()
            }

            runOnUiThread { ballTrackView.updateHeading(currentHeadingDeg) }
            // BallTrackView.updateHeading() 호출 → 수비수 FOV 방향 갱신 + 재그리기(invalidate)
            // UI 조작이므로 반드시 메인 스레드에서 실행
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        // 정확도 변경 콜백 (사용 안 함)
    }

    // ── 스윙 감지 센서 ─────────────────────────────────────
    private var linearAccelSensor: Sensor? = null
    // TYPE_LINEAR_ACCELERATION 센서 (중력 제거된 가속도).
    // 지원 안 하면 onCreate() 에서 TYPE_ACCELEROMETER 로 폴백

    private var gyroscopeSensor: Sensor? = null
    // TYPE_GYROSCOPE 센서 (각속도). null이면 자이로 없는 기기

    private val gravity = FloatArray(3)
    // TYPE_ACCELEROMETER 폴백 시 저역통과 필터로 추정한 중력 벡터 [x, y, z]

    private val LP_ALPHA = 0.8f
    // 저역통과 필터 계수. 0에 가까울수록 중력 추정이 빠르고, 1에 가까울수록 느리고 안정적

    @Volatile private var linearAccelMag: Float = 0f
    // 최신 선형 가속도 크기(m/s²). 센서 스레드에서 갱신 → checkSwing() 에서 읽음

    @Volatile private var gyroMag: Float = 0f
    // 최신 각속도 크기(rad/s). 센서 스레드에서 갱신 → checkSwing() 에서 읽음

    // ── 스윙 판정 임계값 (NBBA 앞라인 40ft = 12.19m 기준 물리 계산) ──────────
    // [물리 근거]
    //  • 발사각 30°, 비거리 12.19m → 최소 볼 출구속도 11.75 m/s
    //  • 충돌 공식(소프트볼 COR=0.44, 볼 180g, 배트 850g, 투구 4.5 m/s)
    //    → 최소 배트 헤드 속도 ≈ 14.2 m/s
    //  • 손(폰) 속도 ≈ 배트 속도 × 0.45 = 6.4 m/s, 팔길이 r=0.6m
    //    원심가속도 = v²/r = 68 m/s², 접선가속도 ≈ 43 m/s²
    //    → 합계 ≈ 80 m/s² (이론), 실용 임계값 25 m/s² (스윙 다양성 반영)

    private val FOUL_ACCEL_THRESHOLD = 8f
    // 스윙 시도 최소 임계값 (m/s²). 이 값 초과 시 스윙 동작으로 인식.
    // 미달 시 "스트라이크(스윙 없음)" 판정

    private val HIT_ACCEL_THRESHOLD  = 5f
    // 유효 타격 임계값 (m/s²). NBBA 앞라인 40ft 통과에 필요한 배트 속도 기준.
    // 이 값 초과 시 "깡(정타)" 판정. 실기기 테스트 후 조정 권장

    private val FOUL_GYRO_THRESHOLD  = 3f
    // 스윙 시도 자이로 임계값 (rad/s). FOUL_ACCEL_THRESHOLD 와 OR 조건

    private val HIT_GYRO_THRESHOLD   = 10f
    // 유효 타격 자이로 임계값 (rad/s). HIT_ACCEL_THRESHOLD 와 OR 조건

    @Volatile private var hitWindowActive = false
    // 타격 윈도우 활성 여부. true 인 구간(600ms)에서만 스윙을 감지.
    // 게임 코루틴(gameJob) ↔ 센서 스레드(swingListener) 간 공유 → @Volatile 필수

    @Volatile private var swingDetected = false
    // 스윙 시도 감지 여부 (≥ FOUL 임계값). 파울 또는 정타 판정을 위한 1차 조건

    @Volatile private var swingIsHit = false
    // 유효 타격 감지 여부 (≥ HIT 임계값). true 이면 "깡" 판정 → gameJob 루프 즉시 탈출

    @Volatile private var swingPitchDeg: Float = 0f
    // 스윙 감지 순간의 피치각 (도). 결과 화면에서 "실제 각도" 표시용

    @Volatile private var swingWasStrong: Boolean = false
    // 스윙이 HIT 임계값 이상이었는지 여부. 파울 원인(힘 부족 vs 위치 불일치) 구분용

    @Volatile private var swingBatHeight: Float = Float.NaN
    // 스윙 감지 순간의 배트 높이 (m). NaN이면 아직 기록 안 됨

    // ── 스윙 궤적 기록 ─────────────────────────────────────
    private val pitchHistory = ArrayList<Pair<Long, Float>>()
    // 타격 윈도우 동안 기록된 (경과ms, 피치각도) 목록. SwingGraphView 에 전달됨

    @Volatile private var pitchRecordStart: Long = 0L
    // 기록 시작 시각(ms). pitchHistory 의 첫 번째 항목 기준점

    @Volatile private var hitTimeRelMs: Long = -1L
    // 스윙 감지 순간의 기록 기준 경과시간(ms). -1이면 스윙 없음

    @Volatile private var isRecording: Boolean = false
    // true 인 동안 orientationListener 가 pitchHistory 에 데이터 추가

    private val swingListener = object : SensorEventListener {
    // 선형 가속도 + 자이로 이벤트 수신용 리스너.
    // onResume() 에서 linearAccelSensor, gyroscopeSensor 각각에 등록됨

        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                // TYPE_LINEAR_ACCELERATION: 이미 중력이 제거된 가속도 센서

                    linearAccelMag = magnitude(event.values)
                    // event.values[0..2](x,y,z) 를 magnitude() 로 크기 계산 → linearAccelMag 갱신

                    checkSwing()
                    // 스윙 판정 함수 호출 (→ 아래 checkSwing() 참조)
                }
                Sensor.TYPE_ACCELEROMETER -> {
                // TYPE_LINEAR_ACCELERATION 미지원 폴백. 저역통과 필터로 중력 성분 제거

                    gravity[0] = LP_ALPHA * gravity[0] + (1 - LP_ALPHA) * event.values[0]
                    gravity[1] = LP_ALPHA * gravity[1] + (1 - LP_ALPHA) * event.values[1]
                    gravity[2] = LP_ALPHA * gravity[2] + (1 - LP_ALPHA) * event.values[2]
                    // IIR 저역통과 필터: gravity ← α*gravity + (1-α)*raw
                    // 느리게 변하는 중력 성분만 남기고 빠른 움직임(스윙)은 걸러냄

                    linearAccelMag = magnitude(floatArrayOf(
                        event.values[0] - gravity[0],
                        event.values[1] - gravity[1],
                        event.values[2] - gravity[2]
                    ))
                    // raw 가속도 - 추정 중력 = 선형 가속도. 이 크기를 linearAccelMag 에 저장

                    checkSwing()
                }
                Sensor.TYPE_GYROSCOPE -> {
                    gyroMag = magnitude(event.values)
                    // 각속도 벡터 크기 계산 → gyroMag 갱신. checkSwing() 은 별도 호출 안 함
                    // (자이로만으로 판정하지 않고, 가속도 이벤트에서 OR 조건으로 함께 판단)
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private fun checkSwing() {
    // 스윙 감지 3단계 판정. 센서 콜백(onSensorChanged) 에서 가속도 이벤트마다 호출됨
    // 판정 결과:
    //   swingIsHit=true  → 강한 스윙 + 위치 매칭 → "깡"
    //   swingDetected only → 약한 스윙 또는 위치 미스 → "파울"
    //   둘 다 false → 스윙 없음 → "스트라이크"

        if (!hitWindowActive) return

        val accel = linearAccelMag
        val gyro  = gyroMag

        val isStrongSwing = accel > HIT_ACCEL_THRESHOLD || gyro > HIT_GYRO_THRESHOLD
        val isAnySwing    = accel > FOUL_ACCEL_THRESHOLD || gyro > FOUL_GYRO_THRESHOLD

        if (!swingDetected && isAnySwing) {
            swingDetected   = true
            swingPitchDeg   = currentPitchDeg   // 스윙 순간 피치각 스냅샷
            swingWasStrong  = isStrongSwing      // HIT 임계값 초과 여부 기록
            if (hitTimeRelMs < 0L) {
                hitTimeRelMs = System.currentTimeMillis() - pitchRecordStart
                swingGraphView.setHitTime(hitTimeRelMs)
                swingGraphView.postInvalidate()
            }

            if (isStrongSwing) {
                // 높이 기반 3D 위치 매칭
                // 공 접촉 높이: 타자 기준 높이 + 요구각도에 따른 배트 도달 높이
                val requiredPitchDeg = (pitchYPosition - 0.5f) * 43f
                val contactH = BATTER_HEIGHT + sin(requiredPitchDeg * PI.toFloat() / 180f) * BAT_REACH
                val batH     = BATTER_HEIGHT + sin(currentPitchDeg  * PI.toFloat() / 180f) * BAT_REACH
                swingBatHeight  = batH
                positionMatched = abs(batH - contactH) < HEIGHT_TOLERANCE
                swingIsHit      = positionMatched
                // 강한 스윙 AND 높이 HEIGHT_TOLERANCE 이내 → 정타
                // 강한 스윙이지만 높이 미스 → swingIsHit=false → 파울
            }
            // 약한 스윙: swingDetected=true, swingIsHit=false → 파울
        }
    }

    private fun magnitude(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    // 3차원 벡터 크기 계산: √(x²+y²+z²). FloatArray[3] 을 받아 Float 반환

    // ── 게임 상태 변수 ─────────────────────────────────────
    private var targetBase: Int = 0
    // 이번 라운드 목표 베이스. startGame() 에서 1 또는 3 으로 랜덤 설정

    private var beepStartTime: Long = 0L
    // 베이스 도착음 시작 시각 (SystemClock.elapsedRealtimeNanos() 기준, 나노초).
    // onBasePressed() 에서 현재 시각과 차이로 반응속도 계산

    private var isWaitingForInput = false
    // 베이스 선택 대기 중 여부. true 인 동안만 onBasePressed() 가 동작.
    // startBlinkEffect() 의 루프 종료 조건으로도 사용됨

    @Volatile private var ballMoving = false
    // 공이 움직이는 중인지 여부 (현재 직접 사용되지 않지만 상태 추적용으로 유지)

    @Volatile private var targetPanX: Float = 0f
    // 공이 날아가는 방향 X 패닝값. 3루=-1.0, 1루=+1.0.
    // startBaseBeep() 에서 헤드트래킹과 합산하여 오디오 pan 계산에 사용

    // ── 피치 위치 판정 변수 ─────────────────────────────────
    private var pitchYPosition: Float = 0.5f
    // 이번 투구의 수직 위치. startGame() 에서 3가지 중 랜덤 선택:
    //   0.15 = 낮은 공, 0.50 = 중간 공, 0.85 = 높은 공

    @Volatile private var currentPitchDeg: Float = 0f
    // 현재 기기 피치각 (기준점 대비 상대값, 도).
    // orientationListener 에서 갱신. checkSwing() 에서 읽음 → @Volatile 필수


    @Volatile private var positionMatched: Boolean = false
    // 스윙 시 피치 위치 매칭 여부. checkSwing() 에서 설정.
    // true: 공 높이와 폰 기울기가 ±PITCH_TOLERANCE 도 이내

    private val PITCH_TOLERANCE = 10f
    // 위치 매칭 허용 오차 (도). 실제 타격 위치와 25도 이내 → 위치 일치 판정.
    // 값을 줄이면 더 정밀한 위치 요구, 늘리면 더 관대한 판정

    private val PITCHER_DIST     = 18.44f   // m — 투수판 ~ 타석 거리 (공식 규격)
    private val PITCHER_HEIGHT   = 1.5f    // m — 투수 릴리즈 높이
    private val BALL_ARC         = 0.3f    // m — 포물선 최고점 추가 높이
    private val BATTER_HEIGHT    = 1.0f    // m — 타자 손(접촉점) 기준 높이
    private val BAT_REACH        = 0.6f    // m — 배트 스윙 수직 도달 거리
    private val HEIGHT_TOLERANCE = 0.2f    // m — 허용 높이 오차

    // ── 코루틴 & 핸들러 ────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    // 메인 스레드 핸들러. startBlinkEffect() 에서 300ms 마다 Runnable 을 반복 실행할 때 사용.
    // onDestroy() 에서 removeCallbacksAndMessages(null) 로 모든 예약 작업 취소

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    // 이 액티비티 전용 코루틴 스코프. Default 디스패처(백그라운드 스레드) 에서 실행.
    // SupervisorJob: 자식 코루틴 하나가 실패해도 다른 자식에 영향 없음.
    // onDestroy() 에서 scope.cancel() 로 일괄 취소

    private var gameJob: Job? = null
    // 게임 전체 흐름(SET→공 접근→PITCH→타격→결과)을 담당하는 단일 코루틴 Job.
    // startGame() 시작 시 이전 gameJob 을 cancel() 하고 새로 launch 함

    private var audioJob: Job? = null
    // 베이스 도착음 반복 재생 코루틴 Job.
    // startBaseBeep() 에서 launch, stopAudio() / onBasePressed() 에서 cancel

    // ── 오디오 ─────────────────────────────────────────────
    private var audioTrack: AudioTrack? = null
    // PCM 스트리밍 오디오 재생 객체. initAudioTrack() 에서 초기화.
    // tone() 함수가 생성한 ShortArray 를 write() 로 직접 스트리밍

    // ─────────────────────────────────────────────────────
    // onCreate
    // ─────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
    // 액티비티 생성 시 한 번 호출. 뷰 바인딩, 센서 초기화, TTS 초기화, 리스너 설정

        super.onCreate(savedInstanceState)
        // 상위 클래스(AppCompatActivity) 초기화 먼저 수행

        setContentView(R.layout.activity_base_run_reaction)
        // res/layout/activity_base_run_reaction.xml 을 이 액티비티의 화면으로 설정

        // ── 뷰 바인딩 (XML id → 코드 변수) ──────────────────
        tvStatus       = findViewById(R.id.tvStatus)        // → XML: android:id="@+id/tvStatus"
        tvResult       = findViewById(R.id.tvResult)        // → XML: android:id="@+id/tvResult"
        btnStart       = findViewById(R.id.btnStart)        // → XML: android:id="@+id/btnStart"
        ballTrackView  = findViewById(R.id.ballTrackView)   // → XML: com.beepbeep.defense.batting.BallTrackView
        swingGraphView = findViewById(R.id.swingGraphView)
        base1Container = findViewById(R.id.base1Container)  // → XML: FrameLayout (1루 클릭 영역)
        base3Container = findViewById(R.id.base3Container)  // → XML: FrameLayout (3루 클릭 영역)
        base1Glow      = findViewById(R.id.base1Glow)       // → XML: base1Container 내부 글로우 View
        base3Glow      = findViewById(R.id.base3Glow)       // → XML: base3Container 내부 글로우 View
        tvBase1Label   = findViewById(R.id.tvBase1Label)    // → XML: base1Container 내부 "1루" 텍스트
        tvBase3Label   = findViewById(R.id.tvBase3Label)    // → XML: base3Container 내부 "3루" 텍스트

        sensorManager     = getSystemService(SensorManager::class.java)
        // 시스템에서 SensorManager 서비스 획득. 이후 센서 등록/해제에 사용

        linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        // TYPE_LINEAR_ACCELERATION(중력 제거 가속도) 를 먼저 시도.
        // 기기가 지원 안 하면 null → ?: 연산자로 TYPE_ACCELEROMETER 폴백 (저역통과 필터로 중력 제거)

        gyroscopeSensor   = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        // 자이로 센서 획득. 없으면 null (checkSwing 에서 gyroMag=0 으로 accel 만 판단)

        // TTS 초기화 ─────────────────────────────────────────
        tts = TextToSpeech(this) { status ->
        // TTS 엔진 초기화 요청. 초기화 완료 시 람다(onInit) 가 호출됨

            if (status == TextToSpeech.SUCCESS) {
            // 초기화 성공 여부 확인 (SUCCESS=0, ERROR=-1)

                tts?.language = Locale.KOREAN
                // TTS 기본 언어를 한국어로 설정.
                // "깡", "파울", "스트라이크" 는 한국어 발음.
                // "SET", "PITCH" 는 speakEnglish() 에서 호출 직전 언어를 영어로 전환

                ttsReady = true
                // speakAndWait() / speakResult() 에서 이 플래그로 발화 가능 여부 확인
            }
        }

        initAudioTrack()
        // AudioTrack 초기화 (→ 아래 initAudioTrack() 참조). 베이스 도착음 재생 준비

        // ── 버튼·컨테이너 클릭 리스너 ───────────────────────
        btnStart.setOnClickListener {
        // "시작" / "다시하기" 버튼 클릭 시

            baseAzimuth = null
            // 방위각 기준값 초기화 → 다음 센서 이벤트에서 현재 방향을 새 기준으로 설정

            startGame()
            // 게임 메인 루프 시작 (→ 아래 startGame() 참조)

            pitchHistory.clear()
            hitTimeRelMs = -1L
            pitchRecordStart = System.currentTimeMillis()
            isRecording = true
            val reqPitch = (pitchYPosition - 0.5f) * 43f
            swingGraphView.setLiveSource(pitchHistory, reqPitch)
            swingGraphView.visibility = android.view.View.VISIBLE
        }
        base1Container.setOnClickListener { onBasePressed(1) }
        // 1루 영역(FrameLayout 130×130dp) 클릭 → onBasePressed(1) 호출
        // (이전: btn1Base 버튼 → 현재: 컨테이너 전체가 클릭 영역)

        base3Container.setOnClickListener { onBasePressed(3) }
        // 3루 영역(FrameLayout 130×130dp) 클릭 → onBasePressed(3) 호출
    }

    // ─────────────────────────────────────────────────────
    // TTS: PITCH 발화 완료까지 suspend (영어, 타이밍 동기화 필요)
    // ─────────────────────────────────────────────────────
    private suspend fun speakAndWait(text: String) {
    // 코루틴 suspend 함수. TTS 발화가 완료될 때까지 코루틴을 일시 중단(suspend)함.
    // 호출처: gameJob 코루틴 내부의 pitchJob (PITCH 발화 대기)

        if (!ttsReady) { delay(500); return }
        // TTS 초기화 미완료 시 500ms 대기 후 함수 종료 (발화 없이 진행)

        val deferred = CompletableDeferred<Unit>()
        // 발화 완료 신호를 전달할 Deferred 객체 생성. onDone 콜백에서 complete() 호출

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        // TTS 발화 진행 상태 콜백 리스너 등록

            override fun onStart(id: String?) {}
            // 발화 시작 시 호출 (사용 안 함)

            override fun onDone(id: String?)  { deferred.complete(Unit) }
            // 발화 완료 시 호출 → deferred 를 완료 상태로 만들어 await() 재개

            override fun onError(id: String?) { deferred.complete(Unit) }
            // 발화 오류 시에도 complete() 호출 → 게임이 멈추지 않도록 처리
        })

        tts?.setLanguage(Locale.ENGLISH)
        // PITCH 는 영어 발음으로 재생
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_$text")
        // text("PITCH") 발화 시작. QUEUE_FLUSH: 기존 큐 비우고 즉시 재생.
        // "tts_$text": utteranceId (onDone 콜백의 id 파라미터로 전달됨)

        deferred.await()
        // onDone / onError 콜백이 올 때까지 코루틴 일시 중단 (스레드 블록 없음)
    }

    // ─────────────────────────────────────────────────────
    // TTS: 결과 발화 (한국어, fire-and-forget)
    //   "깡"        - 정타 (HIT_THRESHOLD 이상)
    //   "파울"      - 스윙 시도했으나 HIT 미달 (FOUL_THRESHOLD 이상)
    //   "스트라이크" - 스윙 없음
    // ─────────────────────────────────────────────────────
    private fun speakResult(text: String) {
    // 결과 발화. 완료 대기 불필요(게임이 결과 상태이므로).
    // 메인 스레드에서 호출되어야 함 (withContext(Dispatchers.Main) 블록 내에서 사용)

        if (!ttsReady) return
        tts?.setLanguage(Locale.KOREAN)
        // 한국어 발음: "깡", "파울", "스트라이크"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_result")
        // QUEUE_FLUSH: 진행 중인 SET/PITCH 발화 즉시 중단 후 결과 발화
    }

    // ─────────────────────────────────────────────────────
    // 센서 등록 / 해제
    // ─────────────────────────────────────────────────────
    override fun onResume() {
    // 화면이 포그라운드로 돌아올 때 호출. 배터리 절약을 위해 여기서 센서 등록

        super.onResume()

        val rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        // 헤드트래킹용 회전 센서. TYPE_GAME_ROTATION_VECTOR 우선 (자기장 간섭 없음).
        // 없으면 TYPE_ROTATION_VECTOR 폴백 (자기장 포함, 절대 방위 기준)

        rotSensor?.let {
            sensorManager.registerListener(orientationListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        // orientationListener 를 rotSensor 에 등록. SENSOR_DELAY_GAME ≈ 20ms 간격

        linearAccelSensor?.let {
            sensorManager.registerListener(swingListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        // swingListener 를 선형가속도(또는 일반 가속도) 센서에 등록

        gyroscopeSensor?.let {
            sensorManager.registerListener(swingListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        // 같은 swingListener 를 자이로 센서에도 등록 (하나의 리스너가 두 센서 수신)
    }

    override fun onPause() {
    // 화면이 백그라운드로 갈 때 호출. 센서 해제로 배터리 절약

        super.onPause()
        sensorManager.unregisterListener(orientationListener)
        // orientationListener 가 등록된 모든 센서에서 해제

        sensorManager.unregisterListener(swingListener)
        // swingListener 가 등록된 모든 센서에서 해제
    }

    // ─────────────────────────────────────────────────────
    // 게임 메인 루프
    // ─────────────────────────────────────────────────────
    private fun startGame() {
    // "시작" 버튼 클릭 시 호출. 모든 상태 초기화 후 gameJob 코루틴 시작

        tvResult.text = ""         // 이전 결과 텍스트 초기화
        resetBaseVisuals()         // 베이스 글로우·라벨 색상 초기 상태로 복원 (→ 아래 함수)
        ballTrackView.reset()      // BallTrackView 공 위치·궤적 초기화 (→ BallTrackView.reset())
        btnStart.isEnabled = false // 게임 중 시작 버튼 비활성화
        isWaitingForInput  = false // 베이스 선택 대기 상태 아님
        swingDetected      = false // 스윙 시도 플래그 초기화
        swingIsHit         = false // 정타 플래그 초기화
        positionMatched    = false // 위치 매칭 플래그 초기화
        swingPitchDeg      = 0f   // 스윙 시점 각도 초기화
        swingWasStrong     = false // 스윙 강도 플래그 초기화
        swingBatHeight     = Float.NaN
        pitchHistory.clear()       // 이전 궤적 데이터 초기화
        hitTimeRelMs       = -1L  // 히트 시점 초기화
        isRecording        = false // 기록 중단 상태로 초기화
        hitWindowActive    = false // 타격 윈도우 닫힌 상태로 초기화
        ballMoving         = true  // 공이 움직이는 상태로 설정
        // 이번 투구 높이 랜덤 선택 (낮음/중간/높음)
        pitchYPosition = listOf(0.15f, 0.50f, 0.85f).random()
        // 0.15=낮은 공(배트 끝 아래, 약 -15°), 0.50=중간(수평, 0°), 0.85=높은 공(배트 끝 위, 약 +15°)
        // checkSwing() 에서 requiredPitchDeg = (pitchYPosition-0.5)*43 로 변환됨

        targetBase = if (Random.nextBoolean()) 1 else 3
        // 이번 라운드 목표 베이스를 50% 확률로 1루 또는 3루 선택

        targetPanX = if (targetBase == 3) -1.0f else 1.0f
        // 3루: 화면 왼쪽(-1.0), 1루: 화면 오른쪽(+1.0).
        // startBaseBeep() 의 pan 계산과 BallTrackView.updateBall() 의 panX 에 사용

        gameJob?.cancel()
        // 이전 게임 코루틴이 남아있으면 취소 (다시하기 클릭 시 이전 흐름 중단)

        gameJob = scope.launch {
        // 게임 전체 흐름을 하나의 코루틴으로 실행. scope(Default 디스패처) 에서 백그라운드 실행.
        // 단일 코루틴으로 관리하여 자기 취소(self-cancellation) 버그 방지

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 1단계: "SET" 발화 + 공 출발 동시 진행
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            withContext(Dispatchers.Main) {
            // UI 조작은 반드시 메인 스레드에서 실행

                tvStatus.text = "SET"
                tvStatus.setTextColor(0xFF93C5FD.toInt())
                // 상태 텍스트를 "SET"(파란색)으로 변경. 0xFF93C5FD = #93C5FD (연파랑)
            }

            withContext(Dispatchers.Main) {
                tts?.setLanguage(Locale.ENGLISH)
                // "SET"은 영어 발음 (기본 언어가 한국어이므로 명시적으로 전환)
                tts?.speak("SET", TextToSpeech.QUEUE_FLUSH, null, "tts_set")
                // "SET" 발화 시작. fire-and-forget (완료 대기 안 함).
                // 공 접근 애니메이션과 동시에 재생되어 준비 신호를 줌
            }

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 2단계: 공 접근 애니메이션 (2500ms)
            //         + 도착 700ms 전에 "PITCH" 발화 시작
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            val approachMs  = 2500L
            // 공이 투수에서 타자(수비수)까지 날아오는 시간 (ms)

            val pitchLeadMs = 700L
            // "PITCH" 발화를 타격 전 몇 ms 전에 시작할지 여유 시간.
            // PITCH 발화 시간 ≈ 400~600ms → 발화 완료 직후 타격 윈도우 열림

            val t0 = System.currentTimeMillis()
            // 공 접근 시작 시각 기록 (progress 계산 기준점)

            // 비프볼 소리 (880Hz, 80ms ON / 420ms OFF) — 공 접근 중 계속 재생
            val beepBallJob = launch {
                val sr         = 44100
                val onSamples  = sr * 80  / 1000   // 80ms 분량 샘플 수
                val offSamples = sr * 420 / 1000   // 420ms 분량 샘플 수
                while (isActive) {
                    audioTrack?.write(tone(onSamples, 880f, 0f, 0.6f), 0, onSamples * 2)
                    if (!isActive) break
                    audioTrack?.write(ShortArray(offSamples * 2), 0, offSamples * 2)
                }
            }

            var pitchStarted = false
            // PITCH 발화를 이미 시작했는지 여부 (중복 실행 방지)

            var pitchJob: Job? = null
            // PITCH speakAndWait() 를 실행하는 자식 코루틴. 완료 시까지 join() 으로 대기

            while (isActive) {
            // isActive: 코루틴이 취소되지 않은 동안 반복

                val elapsed  = System.currentTimeMillis() - t0
                // 공 접근 시작 후 경과 시간 (ms)

                val progress = (elapsed.toFloat() / approachMs).coerceIn(0f, 1f)
                // 경과 비율 0.0~1.0. 1.0이면 공이 도착

                withContext(Dispatchers.Main) {
                    ballTrackView.updateBall(0f, progress, BallPhase.APPROACH, pitchYPosition)
                    // BallTrackView 에 공 위치 업데이트. panX=0(중앙), progress=접근 비율, 단계=APPROACH
                    // pitchYPosition: 이번 라운드 공 높이(0.15/0.50/0.85) → 스트라이크존 마커 위치에도 반영
                    // → BallTrackView.updateBall() 이 invalidate() 호출 → onDraw() 재실행
                }

                if (!pitchStarted && elapsed >= approachMs - pitchLeadMs) {
                // 아직 PITCH 발화 안 했고, 도착까지 pitchLeadMs(700ms) 이하 남은 시점

                    pitchStarted = true
                    pitchJob = launch {
                    // 현재 gameJob 의 자식 코루틴으로 PITCH 발화 시작 (gameJob 취소 시 함께 취소됨)

                        withContext(Dispatchers.Main) {
                            tvStatus.text = "PITCH"
                            tvStatus.setTextColor(0xFFFBBF24.toInt())
                            // 상태 텍스트 "PITCH"(노란색)로 변경. 0xFFFBBF24 = #FBBF24 (앰버)
                        }
                        speakAndWait("PITCH")
                        // "PITCH" TTS 발화 시작 + 완료까지 코루틴 일시 중단
                        // 발화 완료 시점이 타격 윈도우 시작 신호
                    }
                }

                if (progress >= 1f) break
                // 공이 도착(progress=1.0)하면 접근 루프 탈출

                delay(16)
                // 약 60fps 갱신 (16ms 대기). 코루틴 취소 체크 포인트이기도 함
            }

            // 비프볼 소리 정지: 코루틴 취소 후 AudioTrack 버퍼 비우기
            beepBallJob.cancel()
            beepBallJob.join()
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()

            pitchJob?.join()
            // PITCH 발화가 아직 진행 중이면 완료될 때까지 대기.
            // 이 join() 이후가 실제 타격 윈도우 시작 시점

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 3단계: 타격 윈도우 (600ms)
            //   PITCH 발화 완료 직후 스윙 감지 시작
            //   판정:
            //     swingIsHit=true  → "깡" (HIT 임계값 이상 → 즉시 탈출)
            //     swingDetected=true, swingIsHit=false → "파울" (FOUL 임계값만 초과)
            //     둘 다 false → "스트라이크" (스윙 없음)
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            swingDetected   = false
            swingIsHit      = false
            // 직전 단계 오탐 방지: 두 플래그 모두 초기화

            hitWindowActive = true
            // 이 시점부터 swingListener.checkSwing() 이 2단계 스윙 감지를 시작

            withContext(Dispatchers.Main) {
                tvStatus.text = "쳐!"
                tvStatus.setTextColor(0xFFFF6B35.toInt())
                // 상태 텍스트 "쳐!"(주황색). 0xFFFF6B35 = #FF6B35
            }

            val deadline = System.currentTimeMillis() + 600L
            // 타격 윈도우 종료 시각 (현재 + 600ms)

            while (isActive && System.currentTimeMillis() < deadline) {
            // 600ms 내 판정 또는 시간 초과까지 대기

                if (swingIsHit) break
                // 정타(HIT 임계값 초과) 감지 시 즉시 탈출 → "깡" 피드백 지연 없음.
                // swingDetected(파울)만인 경우는 더 강한 스윙 가능성을 위해 600ms 끝까지 대기

                delay(8)
                // 8ms 마다 감지 여부 확인 (≈ 125Hz 폴링)
            }
            hitWindowActive = false
            // 타격 윈도우 닫음 → 이후 센서 이벤트는 무시됨

            delay(150L)
            // 스윙 팔로우스루(150ms) 추가 기록 — 공이 맞은 직후 배트 궤적도 그래프에 포함
            isRecording = false
            // 피치 기록 종료

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 4a: 정타 (깡) → 공이 목표 베이스로 날아감
            //     swingIsHit=true (HIT 임계값 25 m/s² 이상)
            //     NBBA 앞라인 40ft(12.19m) 통과 기준 충족
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            if (swingIsHit) {
                withContext(Dispatchers.Main) {
                    speakResult("깡")
                    // 정타 발화: "깡" (한국어, fire-and-forget)
                    tvStatus.text = if (targetBase == 3) "3루 방향!" else "1루 방향!"
                    tvStatus.setTextColor(0xFFFBBF24.toInt())
                    val reqDeg = (pitchYPosition - 0.5f) * 43f
                    tvResult.text = "실제 각도: %.0f°  /  필요 각도: %.0f°".format(swingPitchDeg, reqDeg)
                }

                val divMs = 1500L
                val t1 = System.currentTimeMillis()

                while (isActive) {
                    val progress = ((System.currentTimeMillis() - t1).toFloat() / divMs)
                        .coerceIn(0f, 1f)
                    withContext(Dispatchers.Main) {
                        ballTrackView.updateBall(
                            targetPanX * progress, // panX: 0 → ±1.0 (좌우 발산)
                            1f - progress,          // 깊이: 1.0(타자) → 0.0(베이스)
                            BallPhase.DIVERGE
                        )
                    }
                    if (progress >= 1f) break
                    delay(16)
                }

                withContext(Dispatchers.Main) { onBallArrived() }
                startBaseBeep(targetPanX)

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 4b: 파울 → 스윙했지만 HIT 임계값 미달
            //     swingDetected=true, swingIsHit=false
            //     FOUL(8 m/s²) 이상 ~ HIT(25 m/s²) 미만
            //     → "파울" 발화 + 스트라이크 판정
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            } else if (swingDetected) {
                withContext(Dispatchers.Main) {
                    ballMoving = false
                    ballTrackView.reset()
                    speakResult("파울")
                    // 파울 발화: "파울" (한국어, fire-and-forget)
                    tvStatus.text = "파울!"
                    tvStatus.setTextColor(0xFFFBBF24.toInt())
                    val reqDeg = (pitchYPosition - 0.5f) * 43f
                    tvResult.text = if (swingWasStrong) {
                        // 강한 스윙이었지만 위치가 맞지 않은 경우
                        "스트라이크 — 위치 불일치\n실제 각도: %.0f°  /  필요 각도: %.0f°".format(swingPitchDeg, reqDeg)
                    } else {
                        // 스윙 자체가 약한 경우
                        "스트라이크 — 힘 부족\n필요 각도: %.0f°".format(reqDeg)
                    }
                    btnStart.isEnabled = true
                    btnStart.text = "다시하기"
                    showSwingGraph()
                }

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 4c: 스트라이크 → 타격 윈도우 내 스윙 없음
            //     swingDetected=false, swingIsHit=false
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            } else {
                withContext(Dispatchers.Main) {
                    ballMoving = false
                    ballTrackView.reset()
                    speakResult("스트라이크")
                    // 스트라이크 발화: "스트라이크" (한국어, fire-and-forget)
                    tvStatus.text = "스트라이크!"
                    tvStatus.setTextColor(0xFFF87171.toInt())
                    val reqDeg = (pitchYPosition - 0.5f) * 43f
                    tvResult.text = "스윙하지 않았습니다\n필요 각도: %.0f°".format(reqDeg)
                    btnStart.isEnabled = true
                    btnStart.text = "다시하기"
                    showSwingGraph()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────
    // 공 베이스 도착 처리
    // ─────────────────────────────────────────────────────
    private fun onBallArrived() {
    // gameJob 에서 withContext(Dispatchers.Main) 으로 호출됨 → 반드시 메인 스레드

        ballMoving = false
        // 공 이동 상태 해제

        if (targetBase == 3) { activateBase3(); tvStatus.text = "3루로\n달려라!" }
        else                 { activateBase1(); tvStatus.text = "1루로\n달려라!" }
        // targetBase 에 따라 해당 베이스 시각 활성화 + 안내 텍스트 표시

        tvStatus.setTextColor(0xFFFBBF24.toInt())
        // 텍스트 색상 노란색

        beepStartTime      = SystemClock.elapsedRealtimeNanos()
        // 반응속도 측정 시작점 기록. onBasePressed() 에서 이 값과 차이로 ms 계산

        isWaitingForInput  = true
        // 이 플래그가 true 인 동안만 베이스 컨테이너 클릭이 처리됨
    }

    // ─────────────────────────────────────────────────────
    // 베이스 도착음 — 버튼 누를 때까지 반복 (헤드트래킹 pan 포함)
    // ─────────────────────────────────────────────────────
    private fun startBaseBeep(finalPan: Float) {
    // finalPan: 3루=-1.0, 1루=+1.0. 베이스 방향의 기본 pan 값

        audioJob?.cancel()
        // 이전 오디오 잡이 있으면 취소

        audioJob = scope.launch {
        // 새 오디오 코루틴 시작. isWaitingForInput 이 false 가 될 때까지 반복

            val sr          = 44100        // 샘플레이트 (Hz)
            val beepSamples = sr * 200 / 1000  // 200ms 분량의 샘플 수 (= 8820 샘플)
            val silSamples  = sr * 150 / 1000  // 150ms 무음 분량 (= 6615 샘플)

            while (isActive) {
            // audioJob 취소(stopAudio()) 전까지 반복

                val rAngle = finalPan * 90f + currentHeadingDeg
                // 베이스 방향 각도(finalPan*90°) + 현재 머리 회전량.
                // 머리를 왼쪽으로 돌리면 베이스 소리가 더 오른쪽 귀에서 들림

                val pan    = sin(Math.toRadians(rAngle.toDouble())).toFloat().coerceIn(-1f, 1f)
                // 각도 → pan(-1~+1) 변환. sin 함수로 -90°~+90° 범위를 자연스럽게 매핑

                audioTrack?.write(tone(beepSamples, 1100f, pan, 1.0f), 0, beepSamples * 2)
                // 1100Hz 비프음 200ms 분량 생성(→ tone()) 후 AudioTrack 에 스트리밍.
                // beepSamples * 2: 스테레오이므로 Left+Right 샘플 수

                if (!isActive) break
                // 오디오 쓰기 중 취소 됐을 경우 즉시 탈출

                audioTrack?.write(ShortArray(silSamples * 2), 0, silSamples * 2)
                // 150ms 무음 (빈 ShortArray) 스트리밍. 비프음 간 간격
            }
        }
    }

    private fun stopAudio() {
    // 오디오 재생 중단. onBasePressed() 에서 베이스 선택 시 호출

        audioJob?.cancel()
        // 반복 재생 코루틴 취소

        audioTrack?.pause(); audioTrack?.flush(); audioTrack?.play()
        // pause: 재생 중단 / flush: 버퍼에 남은 데이터 즉시 비움 / play: 재생 상태로 복귀
        // (다음 startBaseBeep 호출을 위해 play 상태 유지)
    }

    // ─────────────────────────────────────────────────────
    // 베이스 컨테이너 클릭 처리
    // ─────────────────────────────────────────────────────
    private fun onBasePressed(pressedBase: Int) {
    // pressedBase: 사용자가 선택한 베이스 번호 (1 또는 3)
    // base1Container.setOnClickListener 또는 base3Container.setOnClickListener 에서 호출

        if (!isWaitingForInput) return
        // 베이스 도착 전이거나 이미 처리된 경우 무시

        isWaitingForInput = false
        // 중복 입력 방지 (한 번 선택하면 더 이상 처리 안 함)

        stopAudio()
        // 베이스 도착음 중단

        gameJob?.cancel()
        // 게임 코루틴 종료 (startBaseBeep의 while 루프도 함께 종료됨)

        btnStart.isEnabled = true
        btnStart.text = "다시하기"
        // 다시하기 버튼 활성화

        resetBaseVisuals()
        // 베이스 글로우·라벨 초기화

        ballTrackView.reset()
        // 공 위치 초기화

        if (pressedBase == targetBase) {
        // 정답 베이스 선택 시

            val ms = (SystemClock.elapsedRealtimeNanos() - beepStartTime) / 1_000_000L
            // 베이스 도착음 시작 ~ 선택까지 경과 시간 (나노초 → 밀리초 변환: / 1,000,000)

            tvStatus.text = "성공!"
            tvStatus.setTextColor(0xFF4ADE80.toInt())
            // 상태 "성공!"(초록색). 0xFF4ADE80 = #4ADE80

            tvResult.text = "${ms} ms"
            // 반응속도 표시

            showSwingGraph()
            // 스윙 궤적 그래프 다이얼로그 표시

        } else {
        // 오답 베이스 선택 시

            tvStatus.text = "알맞지 않은\n베이스 선택입니다"
            tvStatus.setTextColor(0xFFF87171.toInt())
            // 상태 텍스트 빨간색

            tvResult.text = ""
            Toast.makeText(this, "알맞지 않은 베이스 선택입니다", Toast.LENGTH_SHORT).show()
            // Toast: 화면 하단에 짧게 나타나는 메시지 (약 2초)

            showSwingGraph()
            // 오답 시에도 스윙 궤적 확인 가능
        }
    }

    // ─────────────────────────────────────────────────────
    // 스윙 궤적 그래프 다이얼로그
    // ─────────────────────────────────────────────────────
    private fun showSwingGraph() {
        val history = ArrayList(pitchHistory)   // 스냅샷 (UI 스레드에서 안전하게 복사)
        if (history.isEmpty()) return

        val reqPitch = (pitchYPosition - 0.5f) * 43f
        val contactH = BATTER_HEIGHT + sin(reqPitch * PI.toFloat() / 180f) * BAT_REACH
        val batH = when {
            !swingBatHeight.isNaN() -> swingBatHeight
            swingDetected           -> BATTER_HEIGHT + sin(swingPitchDeg * PI.toFloat() / 180f) * BAT_REACH
            else                    -> contactH
        }

        val heightPx = (300 * resources.displayMetrics.density).toInt()

        val parabolaView = BallParabolaView(this)
        parabolaView.setData(PITCHER_DIST, PITCHER_HEIGHT, contactH, batH, BALL_ARC, swingIsHit)

        val graphView = SwingGraphView(this)
        graphView.setData(history, hitTimeRelMs, reqPitch)

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setBackgroundColor(0xFF0A1423.toInt())
        container.addView(parabolaView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx))
        container.addView(graphView,    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx))

        val scrollView = ScrollView(this)
        scrollView.addView(container)

        AlertDialog.Builder(this)
            .setTitle("스윙 결과")
            .setView(scrollView)
            .setPositiveButton("확인", null)
            .show()
    }

    // ─────────────────────────────────────────────────────
    // AudioTrack 초기화
    // ─────────────────────────────────────────────────────
    private fun initAudioTrack() {
    // onCreate() 에서 한 번 호출. PCM 스트리밍 오디오 객체 생성

        audioTrack?.stop(); audioTrack?.release()
        // 이전 인스턴스가 있으면 정리 (재초기화 시 메모리 누수 방지)

        val sr = 44100
        // 샘플레이트 44100Hz (CD 품질). tone() 함수와 동일한 값 사용해야 음정 정확

        val bufSize = AudioTrack.getMinBufferSize(
            sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        ) * 4
        // getMinBufferSize(): 주어진 포맷의 최소 버퍼 크기(바이트) 반환.
        // * 4: 언더런(버퍼 부족으로 소리 끊김) 방지를 위해 4배로 설정

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                // 미디어 재생 용도로 분류 (볼륨 채널 = 미디어 볼륨)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sr)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                // 16비트 PCM: 샘플 하나 = Short(2바이트)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                // 스테레오: Left + Right 채널 (샘플 수 * 2 바이트)
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM).build()
            // MODE_STREAM: 데이터를 write() 로 실시간 공급 (MODE_STATIC 은 미리 로드)

        audioTrack?.play()
        // 즉시 재생 상태로 설정. write() 호출 시 바로 출력됨
    }

    private fun tone(numSamples: Int, freq: Float, pan: Float, vol: Float): ShortArray {
    // PCM 스테레오 사인파 청크 생성 함수
    // numSamples: 모노 기준 샘플 수 (결과 배열 크기 = numSamples * 2)
    // freq: 주파수 (Hz). 1100Hz = 베이스 도착음
    // pan: -1.0(완전 좌)~+1.0(완전 우). 코사인 패닝 공식 적용
    // vol: 음량 0.0~1.0

        val out   = ShortArray(numSamples * 2)
        // 스테레오 출력 배열. [L0, R0, L1, R1, ...] 인터리브 형식

        val lGain = cos((pan.coerceIn(-1f, 1f) + 1f) * Math.PI.toFloat() / 4f)
        // 좌측 채널 게인. pan=-1 → lGain=cos(0)=1.0, pan=0 → cos(π/4)≈0.707, pan=+1 → cos(π/2)=0

        val rGain = cos((1f - pan.coerceIn(-1f, 1f)) * Math.PI.toFloat() / 4f)
        // 우측 채널 게인. pan=+1 → rGain=1.0, pan=0 → 0.707, pan=-1 → 0
        // 코사인 패닝: 좌우 게인 제곱합 = 1 (파워 보존)

        for (i in 0 until numSamples) {
            val s = (sin(2 * Math.PI * freq * i / 44100) * 32767 * vol).toInt()
            // i번째 샘플의 사인파 값. 44100: 샘플레이트와 일치해야 올바른 주파수 생성
            // * 32767: Short 최대값으로 정규화

            out[i * 2]     = (s * lGain).toInt().toShort()
            // 짝수 인덱스: 왼쪽 채널

            out[i * 2 + 1] = (s * rGain).toInt().toShort()
            // 홀수 인덱스: 오른쪽 채널
        }
        return out
        // 완성된 스테레오 PCM 배열 반환 → audioTrack?.write() 에 전달됨
    }

    // ─────────────────────────────────────────────────────
    // 베이스 시각 효과
    // ─────────────────────────────────────────────────────
    private fun activateBase1() {
    // 1루 베이스 활성화. onBallArrived() 에서 targetBase==1 일 때 호출

        base1Glow.visibility = View.VISIBLE
        // R.id.base1Glow 뷰를 보이게 함 (base_diamond_active drawable → 빛나는 테두리)

        tvBase1Label.setTextColor(0xFF0F172A.toInt())
        // "1루" 텍스트를 어두운 색으로 변경 (활성 배경과 대비)

        tvBase1Label.textSize = 30f
        // 텍스트 크기 확대 (평소 22sp → 30f sp)

        startBlinkEffect(base1Glow)
        // 글로우 뷰를 300ms 간격으로 깜박이게 함 (→ 아래 startBlinkEffect())
    }

    private fun activateBase3() {
    // 3루 베이스 활성화. onBallArrived() 에서 targetBase==3 일 때 호출

        base3Glow.visibility = View.VISIBLE
        tvBase3Label.setTextColor(0xFF0F172A.toInt())
        tvBase3Label.textSize = 30f
        startBlinkEffect(base3Glow)
        // activateBase1() 과 동일한 구조
    }

    private fun startBlinkEffect(view: View) {
    // 지정 뷰를 isWaitingForInput 이 true 인 동안 300ms 간격으로 VISIBLE↔INVISIBLE 반복

        val runnable = object : Runnable {
            var on = true
            // 현재 표시 상태 (true=켜짐)

            override fun run() {
                if (!isWaitingForInput) return
                // 베이스 선택이 완료되면 깜박임 중단

                view.visibility = if (on) View.INVISIBLE else View.VISIBLE
                // 켜짐↔꺼짐 토글

                on = !on
                handler.postDelayed(this, 300)
                // 300ms 후 자기 자신(Runnable)을 다시 실행 → 반복 효과
            }
        }
        handler.postDelayed(runnable, 300)
        // 300ms 뒤 첫 실행 예약
    }

    private fun resetBaseVisuals() {
    // 두 베이스를 모두 비활성 상태(기본값)로 복원. startGame()·onBasePressed() 에서 호출

        base1Glow.visibility = View.INVISIBLE  // 글로우 숨김
        base3Glow.visibility = View.INVISIBLE
        tvBase1Label.setTextColor(0xFF94A3B8.toInt())  // 기본 회색. 0xFF94A3B8 = #94A3B8
        tvBase3Label.setTextColor(0xFF94A3B8.toInt())
        tvBase1Label.textSize = 28f  // 기본 크기로 복원 (XML 22sp → 여기선 28f)
        tvBase3Label.textSize = 28f
    }

    // ─────────────────────────────────────────────────────
    // onDestroy
    // ─────────────────────────────────────────────────────
    override fun onDestroy() {
    // 액티비티가 완전히 종료될 때 한 번 호출. 모든 리소스 해제

        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        // 핸들러에 예약된 모든 Runnable(깜박임 효과 등) 취소

        gameJob?.cancel()   // 게임 코루틴 취소
        audioJob?.cancel()  // 오디오 코루틴 취소
        scope.cancel()      // 스코프 내 모든 코루틴 취소

        audioTrack?.stop()
        // 재생 중인 오디오 즉시 정지

        audioTrack?.release()
        // AudioTrack 리소스(하드웨어 오디오 세션) 해제

        audioTrack = null
        // 참조 제거 → GC 가능

        tts?.stop()
        // 현재 발화 중인 TTS 중단

        tts?.shutdown()
        // TTS 엔진 리소스 해제
    }
}

enum class BallPhase { APPROACH, DIVERGE }
// BallTrackView.updateBall() 의 세 번째 파라미터 타입.
// APPROACH: 공이 투수→타자 방향으로 접근 중
// DIVERGE : 공이 타격 후 베이스 방향으로 발산 중
// 같은 패키지이므로 import 없이 BaseRunReactionActivity, BallTrackView 모두에서 사용 가능
