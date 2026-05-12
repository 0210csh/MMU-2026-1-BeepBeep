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
|------|------|
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

### 훈련 흐름

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
