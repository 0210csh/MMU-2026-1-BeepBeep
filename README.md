# BeepBeep — 시각장애인 비프야구 훈련 앱

> 시각장애인 비프야구 선수를 위한 Android 스마트폰 기반 타격·수비 보조 훈련 앱

스마트폰 내장 센서와 실시간 공간 오디오 피드백으로 실제 비프야구 청각 환경을 재현하여, 별도 장비 없이 타격 타이밍·스윙 각도·주루 반응속도 및 수비 포구 훈련을 반복할 수 있습니다.

---

## 개발 환경

| 항목 | 버전 |
|---|---|
| Android Studio | Hedgehog 이상 |
| Kotlin | 2.0.21 |
| 최소 SDK | 26 (Android 8.0) |
| 타겟 SDK | 36 |
| 네이티브 빌드 | CMake 3.22.1 (NDK, arm64-v8a) |
| Firebase | BOM 32.7.0 (Firestore, Auth) |

---

## 실행 방법

### 1. 저장소 클론

```bash
git clone https://github.com/0210csh/MMU-2026-1-BeepBeep.git
cd MMU-2026-1-BeepBeep
```

### 2. Android Studio에서 열기

1. Android Studio 실행
2. **Open** 선택 → 클론한 폴더 선택
3. **Gradle Sync** 완료 대기 (NDK 포함)
4. 스마트폰 연결 후 **Run** 실행

> **권한**: `RECORD_AUDIO`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` 권한 허용 필요

### 3. 필수 빌드 조건

1. `app/src/main/jniLibs/arm64-v8a/` 에 `libResonanceAudioShared.so` 배치
2. `app/CMakeLists.txt` 에 `resonance_bridge` C++ 소스 등록
3. Firebase 프로젝트의 `google-services.json` 을 `app/` 폴더에 배치

---

## 앱 구조 (화면 흐름)

```
LoginActivity       ← 앱 진입점 (Firebase Auth 로그인/회원가입)
    ↓
HomeActivity        ← 홈 화면 (훈련 선택 / 내 기록)
    ├── TrainingActivity    ← 훈련 선택 화면 (타격 / 수비 진입)
    │       ├── SwingTestActivity   ← 타격 훈련
    │       └── MainActivity        ← 수비 훈련
    ├── RecordActivity      ← 훈련 기록 전체 조회
    └── SettingActivity     ← 설정
```

---

## 패키지 구조

```
com.beepbeep.defense
├── LoginActivity.kt             # 로그인 / 회원가입 진입점
├── SignupActivity.kt            # 회원가입
├── HomeActivity.kt              # 홈 화면
├── TrainingActivity.kt          # 훈련 선택 + 최근 기록 미리보기
├── RecordActivity.kt            # 훈련 기록 전체 조회 (타격·수비)
├── RecordRepository.kt          # Firebase 기록 조회 공통 로직
├── RecordChartBuilder.kt        # 기록 차트 생성
├── GrowthChartView.kt           # 성장 그래프 뷰
├── SettingActivity.kt           # 설정
├── PendingUploadManager.kt      # 오프라인 시 업로드 대기 관리
├── FieldView.kt                 # 수비 경기장 Canvas 시각화
├── JoystickView.kt              # 터치 조이스틱
├── MainActivity.kt              # 수비 훈련 메인 (센서, 게임패드 입력)
│
├── audio/
│   ├── SpatialAudioEngine.kt    # 3D 공간 오디오 엔진 (OpenAL + Resonance)
│   └── ResonanceBridge.kt       # Resonance Audio JNI 래퍼
│
├── batting/
│   ├── SwingTestActivity.kt     # 타격 훈련 메인 액티비티
│   ├── SwingGameEngine.kt       # 타격 게임 상태 관리
│   ├── SwingUiController.kt     # 타격 UI 제어
│   ├── SwingHeadTracker.kt      # 타격용 헤드 트래킹
│   ├── SwingTtsManager.kt       # 타격 TTS 관리
│   ├── SwingTutorialManager.kt  # 타격 튜토리얼
│   ├── SwingBleController.kt    # BLE 하드웨어 연동
│   ├── SwingDataRecorder.kt     # 훈련 데이터 수집 및 Firebase 업로드
│   ├── BaseRunReactionActivity.kt # 주루 반응속도 훈련
│   ├── BallParabolaView.kt      # 공 포물선 궤적 그래프
│   ├── BallTrackView.kt         # 공 궤적 및 베이스 뷰
│   └── SwingGraphView.kt        # 스윙 각도 궤적 그래프
│
├── game/
│   ├── GameEngine.kt            # 수비 게임 상태 머신 + 세션 관리
│   ├── BallSimulator.kt         # 공 궤적 물리 시뮬레이션
│   ├── DefenseTtsManager.kt     # 수비 TTS 관리
│   └── DefenseTutorialManager.kt # 수비 튜토리얼
│
└── hardware/
    ├── BleManager.kt            # BLE 장치 연결 관리
    └── BleTestActivity.kt       # BLE 테스트 화면
```

---

## 타격 훈련 (SwingTestActivity)

### 훈련 흐름

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
    ┌─────────────────────────────┐
    │  정타  │  파울  │ 스트라이크 │
    └─────────────────────────────┘
         ↓ (정타 시)
    공 발산 → 목표 베이스 방향 부저음
    1루·3루 버튼 활성화 → 부저음 방향 베이스 선택
         ↓
    2초 후 다음 투구 자동 진행
         ↓
    목표 횟수 도달 → 훈련 완료 (종합 결과 표시)
```

### 타격 판정 기준

| 판정 | 조건 |
|---|---|
| **정타** | 스윙 감지 AND 배트 피치각이 목표 각도 ±25° 이내 |
| **파울** | 스윙 감지 AND 정타 조건 미충족 |
| **스트라이크** | 타격 구간(0.6초) 내 스윙 없음 |

### 주요 기능

| 기능 | 설명 |
|---|---|
| 투구 횟수 설정 | −/+ 버튼으로 투구 횟수 설정 (1~30회, 기본 3회) |
| 공 접근 비프음 | 880Hz, 볼륨 점진 증가, 공간 오디오 |
| 타격 감지 | 가속도계 + 자이로스코프로 스윙 강도 및 배트 피치각 판정 |
| 주루 베이스 선택 | 정타 후 부저음 방향 베이스 선택, 반응속도(ms) 측정 |
| 구간별 각도 그래프 | READY→PITCH / PITCH→종료 구간 배트 피치각 궤적 표시 |
| 훈련 종합 결과 | 정타·파울·스트라이크·타율·베이스 정답률·반응속도 통계 |
| Firebase 저장 | 투구별 기록 및 종합 결과를 Firestore에 업로드 |

---

## 수비 훈련 (MainActivity + GameEngine)

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

### 게임 상태 머신

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

### 난이도 설정

| 난이도 | 비행 시간 | 착지 범위 |
|--------|-----------|-----------|
| 쉬움   | ~4.25s    | 좁음 (Z: 20~26m) |
| 보통   | ~3.75s    | 보통 (Z: 20~30m) |
| 어려움 | ~3.00s    | 넓음 (Z: 20~36m) |

포구 성공 반경: **3.0m** (수비수 중심 기준)

### 게임패드 조작법

| 버튼 | 기능 |
|------|------|
| **A** | CATCH (포구 시도) / 결과 다이얼로그 닫기 |
| **B** | 훈련 중단 및 초기화 |
| **X** | 시작 / 다음 공 발사 |
| **L1** | 공 개수 -1 (훈련 중 비활성) |
| **R1** | 공 개수 +1 (훈련 중 비활성) |
| **왼쪽 스틱** | 수비수 이동 (X/Z 방향) |
| **오른쪽 스틱** | 수비수 이동 (왼쪽 스틱 미입력 시 폴백) |

### 주요 기능

| 기능 | 설명 |
|---|---|
| 3D 공간 음향 | Resonance Audio HRTF로 공의 좌/우/거리를 입체적으로 표현 |
| 헤드 트래킹 | 자이로 센서로 고개 방향 실시간 추적, 음장 회전 |
| 도플러 효과 | 공 접근 시 비프음 주파수 변화 |
| 조이스틱 이동 | 게임패드 아날로그 스틱 또는 화면 터치 조이스틱 |
| 한국어 TTS | 포구 성공/실패, 포구 시간, 훈련 결과 음성 안내 |
| 컨트롤러 감지 | BT 게임패드 연결/해제 실시간 감지 및 음성 안내 |
| 튜토리얼 | 첫 사용자를 위한 단계별 튜토리얼 (Firebase 완료 여부 동기화) |
| 관리자 모드 | Firebase admins 컬렉션 기반 관리자 전용 디버그 UI |
| 오프라인 대기 | 네트워크 미연결 시 훈련 기록 로컬 보관 후 복구 시 자동 업로드 |

---

## 3D 음향 시스템

### 비프음 패턴

- **ON / OFF 반복**: 100ms 비프음 → 200ms 무음 → 반복
- **페이드 인/아웃**: 클릭 노이즈 방지를 위한 청크 단위 게인 보간
- **거리 감쇠**: 공이 멀수록 볼륨 감소 (`gain = (4f / dist)²`)

### 방향 계산

```
azimuth = atan2(relX, -relZ)   // 공의 상대 방위각
pan = azimuth / (π/2)          // ±1.0 범위로 정규화
```

### 헤드 트래킹 적응형 스무딩

각속도에 따라 스무딩 강도를 동적으로 조정합니다.

| 각속도 (°/update) | 스무딩 계수 | 동작 |
|-------------------|------------|------|
| > 1.2°            | 1.00       | 즉시 추종 |
| > 0.5°            | 0.80       | 빠른 추종 |
| > 0.1°            | 0.30       | 부드러운 추종 |
| ≤ 0.1°            | 0.08       | 노이즈 억제 |

### 네이티브 오디오 라이브러리

| 라이브러리 | 용도 |
|---|---|
| **OpenAL** | 3D 공간 오디오 기본 처리 |
| **FMOD** | PCM 오디오 스트리밍 및 비프음 생성 |
| **Resonance Audio** | 고품질 바이노럴 공간 오디오 렌더링 (HRTF) |

---

## Firebase 데이터 구조

### 타격 훈련 기록

```
users/{userId}/훈련기록/{세션ID}/
    ├── 생성일시
    ├── 목표투구수
    ├── 허용오차 (±25°)
    ├── 종합결과/
    │   ├── 정타수 / 파울수 / 스트라이크수
    │   ├── 타율
    │   ├── 베이스정답수 / 베이스정답률
    │   └── 반응속도평균 / 최소 / 최대 (ms)
    └── 투구별기록/{n}번투구/
        ├── 투구번호 / 판정 / 목표베이스
        ├── 선택베이스 / 베이스정답여부  (정타 시)
        └── 주루반응속도 (ms)           (정답 시)
```

### 수비 훈련 기록

```
users/{userId}/수비훈련기록/{세션ID}/
    ├── 생성일시 / 목표횟수
    ├── 종합결과/
    │   ├── 성공횟수 / 실패횟수 / 성공률
    │   └── 반응속도평균 / 최소 / 최대 (ms)
    └── 포구별기록/{회차}/
        ├── 회차 / 결과 ("성공" / "실패")
        └── 반응속도 (ms)
```

---

## 권한

| 권한 | 용도 |
|---|---|
| `RECORD_AUDIO` | 마이크 (오디오 처리) |
| `HIGH_SAMPLING_RATE_SENSORS` | 고속 자이로 센서 |
| `MODIFY_AUDIO_SETTINGS` | 오디오 설정 변경 |
| `BLUETOOTH_CONNECT` | BT 게임패드 연결 |
| `BLUETOOTH_SCAN` | BT 장치 검색 |
