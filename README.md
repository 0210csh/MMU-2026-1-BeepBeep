# 비프야구 타격 훈련 시스템

> 시각장애인 비프야구 선수를 위한 Android 스마트폰 기반 타격 보조 훈련 앱

스마트폰 내장 센서와 실시간 공간 오디오 피드백으로 실제 비프야구 청각 환경을 재현하여, 별도 장비 없이 타격 타이밍·스윙 각도·주루 반응속도를 반복 훈련할 수 있습니다.

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

## 프로젝트 구조

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

---

## 훈련 흐름

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
