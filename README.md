# 비프야구 타격 훈련 시스템

> 시각장애인 비프야구 선수를 위한 Android 스마트폰 기반 타격 보조 훈련 앱

스마트폰 내장 센서와 실시간 공간 오디오 피드백으로 실제 비프야구 청각 환경을 재현하여, 별도 장비 없이 타격 타이밍·스윙 각도를 반복 훈련할 수 있습니다.

---

## 개발 환경

| 항목 | 버전 |
|---|---|
| Android Studio | Hedgehog 이상 |
| Kotlin | 1.9 이상 |
| 최소 SDK | 26 (Android 8.0) |
| 타겟 SDK | 36 |
| 네이티브 빌드 | CMake (NDK) |

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

---

## 프로젝트 구조

```
app/src/main/
├── cpp/
│   ├── resonance_bridge.cpp          # Resonance Audio JNI 브릿지
│   ├── fmod/                         # FMOD 헤더
│   └── openal/                       # OpenAL 헤더
├── jniLibs/                          # 네이티브 라이브러리 (.so)
│   └── arm64-v8a / armeabi-v7a / x86 / x86_64
│       ├── libResonanceAudioShared.so
│       ├── libfmod.so
│       └── libopenal.so
├── java/com/beepbeep/defense/
│   ├── MainActivity.kt               # 수비 시뮬레이션 액티비티
│   ├── FieldView.kt                  # 경기장 시각화 커스텀 뷰
│   ├── JoystickView.kt               # 조이스틱 커스텀 뷰
│   ├── audio/
│   │   ├── SpatialAudioEngine.kt     # 공간 오디오 엔진 (OpenAL + Resonance)
│   │   └── ResonanceBridge.kt        # Resonance Audio JNI 래퍼
│   ├── game/
│   │   ├── GameEngine.kt             # 수비 시뮬레이션 게임 로직
│   │   └── BallSimulator.kt          # 공 물리 시뮬레이터
│   └── batting/
│       ├── SwingTestActivity.kt      # 타격 훈련 메인 액티비티
│       ├── BallParabolaView.kt       # 공 포물선 궤적 그래프
│       ├── BallTrackView.kt          # 공 궤적 및 베이스 뷰
│       ├── SwingGraphView.kt         # 스윙 각도 궤적 그래프
│       └── BaseRunReactionActivity.kt# (레거시, 미사용)
└── res/
    ├── layout/
    │   ├── activity_main.xml         # 수비 시뮬레이션 레이아웃
    │   └── activity_swing_test.xml   # 타격 훈련 레이아웃
    ├── drawable/                     # 베이스 이미지 등
    └── values/                       # colors, strings, themes, dimens
```

---

## 화면 구성

### 수비 시뮬레이션 (`MainActivity`)
- 공간 오디오로 타구 방향을 듣고 수비수를 조이스틱으로 이동시켜 포구하는 훈련
- 난이도 선택 (쉬움 / 보통 / 어려움)
- 3루·중앙·1루 방향 수동 투구 / 스윙 테스트 화면 이동

### 타격 훈련 (`SwingTestActivity`)
- 설정한 투구 횟수만큼 자동 반복 훈련
- 투구 횟수 설정 UI (−/+ 버튼, 1~30회, 기본 10회)
- 훈련 완료 시 성공 횟수 TTS 안내

---

## 타격 훈련 흐름

```
[투구 횟수 설정] → [훈련 시작]
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
   정타 / 파울 / 스트라이크 판정
        ↓
   [정타 시] 공 발산 → 베이스 부저음 → 1루/3루 선택 → 반응속도 표시
        ↓
   2초 후 자동으로 다음 투구 (목표 횟수 도달 시 훈련 완료)
```

---

## 타격 판정 기준

| 판정 | 조건 |
|---|---|
| **정타** | 강한 스윙(가속도 또는 각속도 임계값 초과) AND 배트 피치각 목표 범위 이내 |
| **파울** | 스윙 감지 AND 정타 조건 미충족 (힘 부족 또는 위치 불일치) |
| **스트라이크** | 타격 구간(0.6초) 내 스윙 없음 |

---

## 구간별 각도 기록

투구마다 3개 구간의 배트 피치각이 기록되어 훈련 종료 후 그래프로 확인할 수 있습니다.

| 구간 | 저장 방식 |
|---|---|
| SET 시점 | 단일 각도값 스냅샷 (`allSetAngles`) |
| READY → PITCH | 시계열 기록 (`perPitchPhase2Data`) |
| PITCH → 타격 윈도우 종료 | 시계열 기록 (`perPitchPhase3Data`) |

---

## 네이티브 오디오 라이브러리

| 라이브러리 | 용도 |
|---|---|
| **OpenAL** | 3D 공간 오디오 기본 처리 |
| **FMOD** | PCM 오디오 스트리밍 및 비프음 생성 |
| **Resonance Audio** | 고품질 바이노럴 공간 오디오 렌더링 |

---

## 참고 사항

- 스마트폰을 **가로 방향**으로 고정하여 사용합니다.
- **이어폰 착용** 시 좌우 스테레오 패닝으로 베이스 방향을 청각으로 확인할 수 있습니다.
- 타격 요구 각도(`BATTING_ANGLE_DEG`)는 `SwingTestActivity.kt` 상단에서 조정 가능합니다.
- 수비 시뮬레이션 관련 코드(`MainActivity`, `GameEngine`, `BallSimulator`)는 별도 모듈로 분리되어 있으며 타격 훈련 코드와 독립적으로 동작합니다.
