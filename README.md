# 비프야구 타격 훈련 시스템

> 시각장애인 비프야구 선수를 위한 Android 스마트폰 기반 타격 보조 훈련 앱

스마트폰 내장 센서와 실시간 공간 오디오 피드백으로 실제 비프야구 청각 환경을 재현하여, 별도 장비 없이 타격 타이밍·스윙 각도·주루 반응속도를 반복 훈련할 수 있습니다.

---

# BeepBeep Defense — 시각장애인용 수비 훈련 시뮬레이터

> 3D 공간 음향과 헤드 트래킹을 이용해 날아오는 공의 방향을 귀로 파악하고 포구하는 안드로이드 훈련 앱

---

## 업데이트 내역

### 2026-05-12

**TTS 기능 강화**
- 공 개수 변경 시 순우리말로 현재 횟수 안내 ("현재 훈련 횟수 다섯개")
- 난이도 변경 시 현재 난이도 안내 ("현재 난이도는 보통")
- 컨트롤러 연결 시 "컨트롤러가 연결되었습니다" 안내 추가
- 훈련 결과 다이얼로그 닫을 때 TTS 즉시 중지

**게임패드 조작 확장**
- D패드 좌/우로 난이도 변경 가능 (KeyEvent + HAT 축 방식 모두 지원)
- L1/R1로 공 개수 변경 시 훈련 중에는 변경 불가 처리
- 컨트롤러 연결/해제 상태를 화면 슬라이드 버튼에 자동 반영

**오디오 품질 개선**
- 비프음 시작 시 양 옆 노이즈 제거 (50ms 무음 스킵 + prevBallX/Z 초기화)
- AudioTrack 시작 팝(웅) 소리 제거 (play() 전 버퍼 무음 프리필)
- ON↔OFF 경계 클릭 노이즈 제거 (OFF 구간 위상 연속성 유지)
- 인위적 앞/뒤 주파수 구분 제거 → Resonance Audio HRTF에 완전 위임
- AudioTrack 용도 USAGE_MEDIA → USAGE_GAME 변경 (BLE 연결 시 간섭 감소)

**헤드 트래킹**
- 감도 2.0 → 1.0 조정 (실제 회전과 1:1에 근접)

---

## 개발 환경

| 항목 | 버전 |
|---|---|
| Android Studio | Hedgehog 이상 |
| Kotlin | 2.0.21 |
| 최소 SDK | 26 (Android 8.0) |
| 타겟 SDK | 36 |
| 네이티브 빌드 | CMake 3.22.1 (NDK, arm64-v8a) |

---

## 실행 방법

### 1. 저장소 클론

```bash
git clone https://github.com/0210csh/MMU-2026-1-BeepBeep.git
cd MMU-2026-1-BeepBeep
git checkout hyegwan
```

### 2. Android Studio에서 열기

1. Android Studio 실행
2. **Open** 선택 → 클론한 폴더 선택
3. **Gradle Sync** 완료 대기 (NDK 포함)
4. 스마트폰 연결 후 **Run** 실행

> **권한**: 마이크(`RECORD_AUDIO`) 권한 허용 필요  
> 스마트폰을 **가로 방향**으로 고정하여 사용합니다.  
> **이어폰 착용** 시 좌우 스테레오 패닝으로 베이스 방향을 청각으로 확인할 수 있습니다.

---

## 프로젝트 구조 (타격)

```
app/src/main/
├── cpp/
│   ├── resonance_bridge.cpp          # Resonance Audio JNI 브릿지
│   ├── fmod/                         # FMOD 헤더
│   └── openal/                       # OpenAL 헤더
├── jniLibs/                          # 네이티브 라이브러리 (.so)
│   └── arm64-v8a/
│       ├── libResonanceAudioShared.so
│       ├── libfmod.so
│       └── libopenal.so
├── java/com/beepbeep/defense/
│   ├── audio/
│   │   ├── SpatialAudioEngine.kt     # 공간 오디오 엔진 (OpenAL + Resonance)
│   │   └── ResonanceBridge.kt        # Resonance Audio JNI 래퍼
│   └── batting/
│       ├── SwingTestActivity.kt      # 타격 훈련 메인 액티비티
│       ├── BallParabolaView.kt       # 공 포물선 궤적 그래프
│       ├── BallTrackView.kt          # 공 궤적 및 베이스 뷰
│       └── SwingGraphView.kt         # 스윙 각도 궤적 그래프
└── res/layout/
    └── activity_swing_test.xml       # 타격 훈련 레이아웃
```

---

## 목차

1. [프로젝트 개요](#프로젝트-개요)
2. [주요 기능](#주요-기능)
3. [아키텍처](#아키텍처)
4. [3D 음향 시스템](#3d-음향-시스템)
5. [헤드 트래킹](#헤드-트래킹)
6. [게임패드 조작법](#게임패드-조작법)
7. [세션 관리](#세션-관리)
8. [빌드 환경](#빌드-환경)
9. [패키지 구조](#패키지-구조)

---

## 프로젝트 개요

BeepBeep Defense는 시각장애인 야구 선수의 수비 훈련을 보조하기 위한 Android 앱입니다.  
투수 방향에서 날아오는 공을 **비프음의 방향과 거리감**만으로 파악하고, 블루투스 게임패드로 수비수를 이동시켜 포구합니다.

- **플랫폼**: Android (minSdk 26 / targetSdk 36)
- **언어**: Kotlin + C++17 (NDK)
- **3D 음향 라이브러리**: Google Resonance Audio (HRTF 바이노럴 렌더링)
- **대상**: 시각장애인 야구 훈련, 공간 청각 능력 개발

---

## 주요 기능

| 기능 | 설명 |
|---|---|
| 투구 횟수 설정 | 훈련 시작 전 −/+ 버튼으로 투구 횟수 설정 (1~30회, 기본 10회) |
| 자동 반복 훈련 | 매 투구 결과 후 2초 대기 → 자동으로 다음 투구 진행 |
| 공 접근 비프음 | 투수에서 타자까지 2.5초간 볼륨 점진 증가 (880Hz, 공간 오디오) |
| 타격 감지 | 선형 가속도계 + 자이로스코프로 스윙 강도 및 배트 피치각 판정 |
| 정타 / 파울 / 스트라이크 | TTS 음성 결과 출력 |
| 공 발산 비프음 | 정타 후 목표 베이스 방향으로 스테레오 패닝 이동 |
| 주루 방향 선택 | 정타 후 1루·3루 버튼 **모두 활성화**, 부저음이 들리는 방향의 베이스를 선택 |
| 베이스 정답률 측정 | 올바른 베이스 선택 여부 및 반응속도(ms) 측정 |
| 구간별 각도 그래프 | READY→PITCH / PITCH→종료 구간 배트 피치각 궤적 표시 |
| 훈련 종합 결과 | 완료 시 정타·파울·스트라이크·타율·베이스 정답률·반응속도 통계 표시 |
| **3D 공간 음향** | Resonance Audio HRTF로 공의 좌/우/거리를 입체적으로 표현 |
| **도플러 효과** | 공이 접근할 때 비프음 주파수가 올라가는 물리적 효과 |
| **헤드 트래킹** | 스마트폰 자이로 센서로 고개 방향을 실시간 추적, 음장 회전 |
| **저지연 음향** | FRAMES=128 (~2.9ms/청크), LOW_LATENCY 모드로 지연 최소화 |
| **조이스틱 이동** | 게임패드 아날로그 스틱 또는 화면 조이스틱으로 수비수 이동 |
| **세션 훈련** | 목표 횟수 설정 → 자동 진행 → 결과 팝업 + TTS 음성 안내 |
| **한국어 TTS** | 포구 성공/실패, 포구 시간, 훈련 결과를 한국어로 음성 출력 |
| **컨트롤러 감지** | 블루투스 게임패드 연결/해제를 실시간으로 감지해 음성 안내 |

---

## 아키텍처

```
MainActivity
├── SensorManager          ← 자이로 센서 (헤드 트래킹)
├── InputManager           ← 게임패드 연결/해제 감지
├── GameEngine             ← 게임 상태 관리 (StateFlow)
│   ├── BallSimulator      ← 공 궤적 물리 시뮬레이션
│   └── SpatialAudioEngine ← 3D 음향 렌더링
│       └── ResonanceBridge (JNI) ← Resonance Audio C++ 라이브러리
├── FieldView              ← 경기장 2D 시각화 (Canvas)
└── JoystickView           ← 화면 터치 조이스틱
```

### 데이터 흐름

```
센서 → smoothedHeading → GameEngine.updateHeadingDirectly()
                                    ↓
                         SpatialAudioEngine.updateHeading()
                         ResonanceBridge.nativeSetHeadRotation()

게임패드 스틱 → joystickDx/Dz → tick() → defX/defZ 이동
                                         ↓
                              updateBallPosition(relX, relY, relZ)
                              ResonanceBridge.nativeSetSourcePosition()
```

---

## 훈련 흐름 (타격)

```
[투구 횟수 설정 (−/+)] → [훈련 시작]
         ↓
    SET 발화 (1초 대기)
         ↓
    공 접근 — 비프음 볼륨 점진 증가 (2.5초)
         ↓
    READY 발화 (타자까지 10피트 지점)
         ↓
    PITCH 발화
         ↓
    타격 구간 활성화 (0.6초)
         ↓
    ┌──────────────────────────────────────┐
    │  정타  │  파울  │  스트라이크        │
    └──────────────────────────────────────┘
         ↓ (정타 시)
    "소리 들어봐!" 안내
         ↓
    공 발산 → 목표 베이스 방향 부저음
         ↓
    "소리 따라 달려라!" 안내
    1루·3루 버튼 모두 활성화 → 부저음 방향 베이스 선택
         ↓
    정답: 반응속도(ms) 표시 / 오답: 알맞지 않은 베이스 선택 안내
         ↓
    2초 후 자동으로 다음 투구
         ↓
    목표 횟수 도달 시 → 훈련 완료 (종합 결과 표시)
```

---

## 타격 판정 기준

| 판정 | 조건 |
|---|---|
| **정타** | 강한 스윙(가속도 또는 각속도 임계값 초과) AND 배트 피치각이 목표 각도 ±25° 이내 |
| **파울** | 스윙 감지 AND 정타 조건 미충족 (힘 부족 또는 위치 불일치) |
| **스트라이크** | 타격 구간(0.6초) 내 스윙 없음 |

---

## 주루 베이스 선택 방식

정타 판정 이후 **1루·3루 버튼이 동시에 활성화**됩니다.  
시각적 힌트 없이 부저음이 들리는 방향만으로 올바른 베이스를 선택해야 합니다.

| 결과 | 조건 |
|---|---|
| **정답** | 부저음이 나는 목표 베이스 선택 → 반응속도(ms) 기록 |
| **오답** | 반대 베이스 선택 → 베이스 정답여부 false 기록 |

---

## 구간별 각도 기록

투구마다 배트 피치각이 구간별로 기록되어 훈련 종료 후 그래프로 확인할 수 있습니다.

| 구간 | 저장 방식 | 그래프 |
|---|---|---|
| SET 시점 | 단일 각도값 스냅샷 | 종합 결과 텍스트로 표시 |
| READY → PITCH | 시계열 기록 (~50Hz) | 파란 선 궤적, 하단 요약 미표시 |
| PITCH → 타격 윈도우 종료 | 시계열 기록 (~50Hz) | 파란 선 궤적 + 히트 시점 마커 |

훈련 종합 결과 화면에서 ◀/▶ 버튼으로 투구별 그래프를 전환할 수 있습니다.

---

## 훈련 데이터 구조 (Firebase 연동 부분)

훈련이 완료되면 아래 구조의 데이터가 수집됩니다. Firebase 업로드는 `finishTraining()` 내부에서 연동합니다.

```
users/
└── {userId}/
    └── 훈련기록/
        └── {세션ID}/
            ├── 생성일시
            ├── 목표투구수
            ├── 허용오차 (±25°)
            │
            ├── 종합결과/
            │   ├── 정타수
            │   ├── 파울수
            │   ├── 스트라이크수
            │   ├── 타율          ← 정타수 / 목표투구수
            │   ├── 베이스정답수
            │   ├── 베이스정답률  ← 베이스정답수 / 정타수 × 100
            │   ├── 반응속도평균 (ms)
            │   ├── 반응속도최소 (ms)
            │   └── 반응속도최대 (ms)
            │
            └── 투구별기록/
                └── {n}번투구/
                    ├── 투구번호
                    ├── 판정          ("정타" / "파울" / "스트라이크")
                    ├── 목표베이스    (1 또는 3)
                    ├── 배트각도      (추후 구간별 각도 피드백으로 대체 예정)
                    ├── 선택베이스    (정타 시만 기록)
                    ├── 베이스정답여부 (정타 시만 기록)
                    └── 주루반응속도  (정답 시만 기록, ms)
```

---

## Firebase 코드 위치 상세 매핑

### 세션 루트 필드

| Firebase 필드 | 라인 | 코드 | 비고 |
|---|---|---|---|
| 생성일시 | — | `Timestamp.now()` | Firebase 업로드 시점에 생성 |
| 목표투구수 | 103 | `var targetPitches = 10` | 버튼으로 조정된 최종값 |
| 허용오차 | 173 | `private val PITCH_TOLERANCE = 25f` | 고정 상수 |

### 종합결과

| Firebase 필드 | 라인 | 코드 | 비고 |
|---|---|---|---|
| 정타수 | 107 / 689 | `var hitCount = 0` / `hitCount++` | 선언: 107, 증가: 689 |
| 파울수 | 108 / 705 | `var foulCount = 0` / `foulCount++` | 선언: 108, 증가: 705 |
| 스트라이크수 | 109 / 734 | `var strikeCount = 0` / `strikeCount++` | 선언: 109, 증가: 734 |
| 타율 | 867 | `val battingAvg = hitCount.toFloat() / targetPitches` | 정타수 / 목표투구수 |
| 베이스정답수 | 105 / 837 | `var successCount = 0` / `successCount++` | 선언: 105, 증가: 837 |
| 베이스정답률 | 869 | `val baseCorrectPct = successCount.toFloat() / hitCount * 100f` | showTrainingSummary() 내부 |
| 반응속도평균 | 868 | `val avgReaction = reactionTimes.average().toLong()` | showTrainingSummary() 내부 |
| 반응속도최소 | 888 | `reactionTimes.minOrNull()` | showTrainingSummary() 내부 |
| 반응속도최대 | 888 | `reactionTimes.maxOrNull()` | showTrainingSummary() 내부 |

### 투구별기록

| Firebase 필드 | 라인 | 코드 | 저장 시점 |
|---|---|---|---|
| 투구번호 | 519 | `currentPitchRecord["투구번호"] = currentPitchNum` | startGame() 시작 |
| 판정 (정타) | 638 | `currentPitchRecord["판정"] = "정타"` | 타격 윈도우 종료 후 |
| 판정 (파울) | 700 | `currentPitchRecord["판정"] = "파울"` | 타격 윈도우 종료 후 |
| 판정 (스트라이크) | 729 | `currentPitchRecord["판정"] = "스트라이크"` | 타격 윈도우 종료 후 |
| 목표베이스 | 515 / 520 | `targetBase = if (Random.nextBoolean()) 1 else 3` / `currentPitchRecord["목표베이스"] = targetBase` | 515: 결정, 520: 맵에 저장 |
| 배트각도 | 521 | `currentPitchRecord["배트각도"] = null` | 현재 null, 추후 대체 예정 |
| 선택베이스 | 807 | `currentPitchRecord["선택베이스"] = pressedBase` | onBasePressed() |
| 베이스정답여부 | 804 / 808 | `val success = pressedBase == targetBase` / `currentPitchRecord["베이스정답여부"] = success` | 804: 계산, 808: 맵에 저장 |
| 주루반응속도 | 809~811 | `val ms = (SystemClock.elapsedRealtimeNanos() - beepStartTime) / 1_000_000L` / `currentPitchRecord["주루반응속도"] = ms` | onBasePressed(), 정답일 때만 |

### perPitchRecords 최종 저장 시점

| 판정 | 라인 | 코드 |
|---|---|---|
| 파울 | 702 | `perPitchRecords.add(HashMap(currentPitchRecord))` |
| 스트라이크 | 731 | `perPitchRecords.add(HashMap(currentPitchRecord))` |
| 정타 (베이스 선택 완료 후) | 822 | `perPitchRecords.add(HashMap(currentPitchRecord))` |

### Firebase 업로드 호출 위치

| 라인 | 코드 | 설명 |
|---|---|---|
| 852~864 | `fun finishTraining()` | 이 함수 내 `showTrainingSummary()` 호출 직전에 업로드 함수 삽입 |
| 863 | `showTrainingSummary()` | 이 줄 바로 위에 `uploadTrainingSession(...)` 호출 추가 |

> `finishTraining()` 도달 시점에 `perPitchRecords`(112번 라인)와 종합 통계 변수 전부 확정 완료 상태입니다.

---

## 네이티브 오디오 라이브러리

| 라이브러리 | 용도 |
|---|---|
| **OpenAL** | 3D 공간 오디오 기본 처리 |
| **FMOD** | PCM 오디오 스트리밍 및 비프음 생성 |
| **Resonance Audio** | 고품질 바이노럴 공간 오디오 렌더링 |

---

## 개발자 설정

`SwingTestActivity.kt` 상단에서 타격 요구 각도를 조정할 수 있습니다.

```kotlin
private val BATTING_ANGLE_DEG = 0f
//  0f  → 배트 수평 (중간 공)
// +15f → 배트 끝이 약간 위 (높은 공)
// -15f → 배트 끝이 약간 아래 (낮은 공)
```

---

## 3D 음향 시스템

### 비프음 패턴

- **ON / OFF 반복**: 100ms 비프음 → 200ms 무음 → 반복
- **페이드 인/아웃**: 클릭 노이즈 방지를 위한 청크 단위 게인 보간
- **거리 감쇠**: 공이 멀수록 볼륨 감소 (`gain = (4f / dist)²`)

### 방향 계산 (Pan)

```
azimuth = atan2(relX, -relZ)   // 공의 상대 방위각
pan = azimuth / (π/2)          // ±1.0 범위로 정규화
```

`sin()` 기반 대신 `atan2()` 기반을 사용해 40°~90° 구간의 좌우 구분력을 향상시켰습니다.

### 도플러 효과

```
vel = (prevDist - curDist) / dt          // 접근 속도 (m/s)
doppFreq = 880Hz × (343 / (343 - vel))  // 도플러 주파수 보정
```

### safeZ 처리

`ballZ ≈ 0`일 때 `atan2()` 불안정 문제를 방지하기 위해 `|ballZ| < 0.5f`이면 `±0.5f`로 클램프합니다.

---

## 헤드 트래킹

스마트폰의 `TYPE_GAME_ROTATION_VECTOR` 센서 (없으면 `TYPE_ROTATION_VECTOR` 폴백)를 사용합니다.

### 적응형 스무딩

각속도(회전 속도)에 따라 스무딩 강도를 동적으로 조정합니다.

| 각속도 (°/update) | 스무딩 계수 | 동작 |
|-------------------|------------|------|
| > 1.2°            | 1.00       | 즉시 추종 (빠른 스냅) |
| > 0.5°            | 0.80       | 빠른 추종 |
| > 0.1°            | 0.30       | 부드러운 추종 |
| ≤ 0.1°            | 0.08       | 노이즈 강하게 억제 |

### 예측 보상 (Predictive Compensation)

회전 중 발생하는 지연을 각속도 기반 예측으로 보상합니다.  
멈추면 `predictionScale → 0`이 되어 오버슈트 없이 자동 수렴합니다.

```kotlin
predictionScale = (headingVelocity / 1.2f).coerceIn(0f, 1f)
predicted = smoothedHeading + signedVelocity × 12f × predictionScale
```

### 감도 및 원점 재설정

- 기본 감도 배율: **1.0×** (실제 회전과 1:1)
- **시작 버튼을 누를 때마다** 현재 정면 방향을 트래킹 원점(0°)으로 재설정

---

## 게임패드 조작법

| 버튼 | 기능 |
|------|------|
| **A** | CATCH (포구 시도) |
| **B** | 전체 리셋 (훈련 중단 및 초기화) |
| **X** | 시작 / 다음 공 발사 |
| **L1** | 공 개수 -1 (훈련 중 비활성) |
| **R1** | 공 개수 +1 (훈련 중 비활성) |
| **D패드 ◀** | 난이도 감소 |
| **D패드 ▶** | 난이도 증가 |
| **왼쪽 스틱** | 수비수 이동 (X/Z 방향) |
| **오른쪽 스틱** | 수비수 이동 (왼쪽 스틱 미입력 시 폴백) |

> 수비수 이동은 **헤드 트래킹 방향 기준**으로 좌표 변환됩니다.  
> 고개를 돌린 방향이 스틱의 "앞"이 됩니다.

---

## 세션 관리

### 훈련 흐름 (수비)

```
[설정] 공 개수(1~20) + 난이도(쉬움/보통/어려움)
    ↓
[시작] startSession() → launchRandom() 자동 연속 실행
    ↓
[포구 시도] onCatchPressed() → 성공/실패 TTS 안내
    ↓
[반복] 설정한 공 개수 소진 시까지
    ↓
[결과] 팝업 다이얼로그 + TTS 훈련 요약
```

### 결과 팝업 항목

- 총 시도 횟수 / 성공 / 실패
- 성공률 (%)
- 평균 포구 시간 / 최단 시간 / 최장 시간

### 한국어 시간 TTS

화면 표시(`"%.1f"초`)와 동일한 반올림 기준을 사용합니다.

| 포구 시간 | TTS 출력 |
|-----------|----------|
| 6.0s      | "육초" |
| 6.1s      | "육점일초" |
| 12.3s     | "십이점삼초" |

---

## 빌드 환경

| 항목 | 버전 |
|------|------|
| Android Gradle Plugin | 최신 stable |
| compileSdk | 36 |
| minSdk | 26 (Android 8.0) |
| Kotlin | 최신 stable |
| NDK | arm64-v8a, C++17 |
| CMake | 3.22.1 |
| Resonance Audio | ResonanceAudioShared (.so) |

### 필수 빌드 조건

1. `app/src/main/jniLibs/arm64-v8a/` 에 `libResonanceAudioShared.so` 배치
2. `app/CMakeLists.txt` 에 `resonance_bridge` C++ 소스 등록
3. `RECORD_AUDIO` 권한 (런타임 요청 포함)

---

## 패키지 구조

```
com.beepbeep.defense
├── MainActivity.kt              # UI, 센서, 게임패드 입력 처리
├── FieldView.kt                 # 경기장 Canvas 시각화
├── JoystickView.kt              # 터치 조이스틱
├── audio/
│   ├── SpatialAudioEngine.kt    # 3D 음향 렌더링 (비프음, 도플러, pan)
│   └── ResonanceBridge.kt       # Resonance Audio JNI 브릿지
└── game/
    ├── GameEngine.kt            # 게임 상태 머신, 세션 관리, TTS
    ├── BallSimulator.kt         # 공 궤적 물리 시뮬레이션
    └── (GamePhase, CatchResult, SessionResult ← GameEngine.kt 내 정의)
```

---

## 게임 상태 머신

```
IDLE ──[시작/X버튼]──► LAUNCHED ──[착지]──► LANDED
  ▲                        │                    │
  │              [CATCH → 실패]        [CATCH → 성공]
  │                        ▼                    ▼
  └────────────────── RESULT           CAUGHT
                           │                    │
                        2초 후 자동           2초 후 자동
                        IDLE 복귀            IDLE 복귀
```

---

## 난이도 설정

| 난이도 | 파라미터 | 비행 시간 | 착지 범위 |
|--------|----------|-----------|-----------|
| 쉬움   | 0.3      | ~4.25s    | 좁음 (Z: 20~26m) |
| 보통   | 0.5      | ~3.75s    | 보통 (Z: 20~30m) |
| 어려움 | 0.8      | ~3.00s    | 넓음 (Z: 20~36m) |

포구 성공 반경: **3.0m** (수비수 중심 기준)

---

## Git 브랜치 전략 (협업 가이드)

### 브랜치 구조

```
main
├── hyegwan                ← 최종 통합본 (항상 동작하는 버전)
├── hyegwan-no-hw          ← 하드웨어 연결 전 순수 소프트웨어 버전 (보존용, 수정 금지)
├── hyegwan-no-hw-ui       ← UI 담당자 작업 브랜치 (hyegwan-no-hw 기반)
└── hyegwan-hw             ← 하드웨어 담당자 작업 브랜치
```

### 브랜치별 역할

| 브랜치 | 담당 | 설명 |
|---|---|---|
| `hyegwan` | 공동 | 최종 통합본. 직접 수정하지 않고 merge로만 업데이트 |
| `hyegwan-no-hw` | — | 하드웨어 연결 전 버전 보존. **절대 수정하지 않음** |
| `hyegwan-no-hw-ui` | UI 담당자 | `hyegwan-no-hw` 기반으로 UI 작업 |
| `hyegwan-hw` | 하드웨어 담당자 | 하드웨어 연결 코드 작업 |

---

### UI 담당자 — 처음 시작할 때

```bash
# 1. 저장소 클론
git clone https://github.com/0210csh/MMU-2026-1-BeepBeep.git
cd MMU-2026-1-BeepBeep

# 2. no-hw 브랜치 가져오기
git checkout hyegwan-no-hw

# 3. UI 작업용 브랜치 생성
git checkout -b hyegwan-no-hw-ui
git push origin hyegwan-no-hw-ui
```

### UI 담당자 — 작업 후 업로드

```bash
# 수정된 파일 확인 (먼저 확인하는 습관 권장)
git status

# 파일 하나만 올릴 때
git add app/src/main/java/com/beepbeep/defense/batting/SwingTestActivity.kt

# 여러 파일 올릴 때
git add app/src/main/java/com/beepbeep/defense/batting/SwingTestActivity.kt
git add app/src/main/res/layout/activity_swing_test.xml

# 수정한 파일 전부 한번에 올릴 때
git add .

git commit -m "feat: UI 수정 내용 설명"
git push origin hyegwan-no-hw-ui
```

---

### 하드웨어 담당자 — 처음 시작할 때

```bash
# 1. 저장소 클론
git clone https://github.com/0210csh/MMU-2026-1-BeepBeep.git
cd MMU-2026-1-BeepBeep

# 2. hw 브랜치로 전환
git checkout hyegwan-hw
```

### 하드웨어 담당자 — 작업 후 업로드

```bash
# 수정된 파일 확인 (먼저 확인하는 습관 권장)
git status

# 파일 하나만 올릴 때
git add app/src/main/java/com/beepbeep/defense/batting/SwingTestActivity.kt

# 여러 파일 올릴 때
git add app/src/main/java/com/beepbeep/defense/batting/SwingTestActivity.kt
git add app/src/main/java/com/beepbeep/defense/audio/SpatialAudioEngine.kt

# 수정한 파일 전부 한번에 올릴 때
git add .

git commit -m "feat: 하드웨어 연결 내용 설명"
git push origin hyegwan-hw
```

---

### 업로드 전 확인 명령어

```bash
# 어떤 파일이 수정됐는지 목록 확인
git status

# 수정 내용 상세 확인
git diff
```

> `git status`로 수정된 파일을 먼저 확인한 뒤 `git add` 하는 습관을 들이면 실수를 줄일 수 있습니다.

---

### 브랜치 전환 방법

```bash
# 하드웨어 버전으로 전환
git checkout hyegwan-hw

# 하드웨어 없는 순수 버전으로 전환
git checkout hyegwan-no-hw

# 최종 통합본으로 전환
git checkout hyegwan
```

> Android Studio 우측 하단의 브랜치 이름을 클릭해도 전환할 수 있습니다.

---

### HW 작업 중 SW를 수정해야 할 때

`hyegwan-no-hw`는 보존용이므로 수정하지 않습니다.  
SW 수정은 `hyegwan`에서 하고 `hyegwan-hw`로 가져오는 방식을 사용합니다.

**1단계 — 현재 HW 작업 임시 저장**
```bash
git stash
```

**2단계 — hyegwan으로 이동해서 SW 수정**
```bash
git checkout hyegwan
# SW 파일 수정 후
git add .
git commit -m "fix: SW 수정 내용"
git push origin hyegwan
```

**3단계 — hyegwan-hw로 돌아와서 SW 수정 내용 가져오기**
```bash
git checkout hyegwan-hw
git merge hyegwan
```

**4단계 — 임시 저장했던 HW 작업 복구**
```bash
git stash pop
```

전체 흐름 요약:
```
hyegwan-hw 작업 중
       ↓
git stash              (HW 작업 임시 저장)
       ↓
git checkout hyegwan
       ↓
SW 수정 → commit → push
       ↓
git checkout hyegwan-hw
       ↓
git merge hyegwan      (SW 수정 내용 가져오기)
       ↓
git stash pop          (HW 작업 복구)
       ↓
HW 작업 계속
```

| 명령어 | 역할 |
|---|---|
| `git stash` | 커밋 안 한 작업 임시 보관 |
| `git stash pop` | 임시 보관한 작업 복구 |
| `git merge hyegwan` | hyegwan의 변경사항을 현재 브랜치로 가져오기 |

---

### 작업 완료 후 최종 통합

각 브랜치 작업이 완료되면 GitHub에서 **Pull Request**를 생성합니다.  
확인 후 `hyegwan` 브랜치로 merge합니다.

```bash
# 로컬에서 직접 통합할 경우
git checkout hyegwan
git merge hyegwan-hw        # 하드웨어 브랜치 통합
git merge hyegwan-no-hw-ui  # UI 브랜치 통합
git push origin hyegwan
```
