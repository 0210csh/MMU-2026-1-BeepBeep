# 비프야구 타격 훈련 시스템

> 시각장애인 비프야구 선수를 위한 Android 스마트폰 기반 타격 보조 훈련 앱

스마트폰 내장 센서와 실시간 오디오 피드백으로 실제 비프야구 청각 환경을 재현하여, 별도 장비 없이 타격 타이밍과 스윙 각도를 반복 훈련할 수 있습니다.

---

## 개발 환경

| 항목 | 버전 |
|---|---|
| Android Studio | Hedgehog 이상 |
| Kotlin | 1.9 이상 |
| 최소 SDK | 26 (Android 8.0) |
| 타겟 SDK | 36 |

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
3. **Gradle Sync** 완료 대기
4. 스마트폰 연결 후 **Run** 실행

---

## 프로젝트 구조

```
app/src/main/
├── java/com/beepbeep/defense/
│   ├── MainActivity.kt               # 앱 진입점
│   └── batting/
│       ├── SwingTestActivity.kt      # 타격 훈련 메인 액티비티
│       ├── BallParabolaView.kt       # 공 포물선 궤적 그래프
│       ├── BallTrackView.kt          # 공 궤적 및 베이스 뷰
│       ├── SwingGraphView.kt         # 스윙 각도 궤적 그래프
│       └── SwingBallView.kt          # 공 뷰
└── res/
    ├── layout/
    │   ├── activity_main.xml
    │   └── activity_swing_test.xml
    ├── drawable/                     # 베이스 이미지 등
    └── values/                       # colors, strings, themes, dimens
```

---

## 주요 기능

| 기능 | 설명 |
|---|---|
| 공 접근 비프음 | 투수에서 타자까지 2.5초간 볼륨 점진 증가 (880Hz) |
| 타격 감지 | 스마트폰 선형 가속도계 + 회전벡터 센서로 스윙 판정 |
| 정타 / 파울 / 스트라이크 | TTS 음성 결과 출력 |
| 공 발산 비프음 | 타격 후 1루/3루 방향으로 스테레오 패닝 이동 |
| 주루 방향 선택 | 타격 성공 후 1루/3루 버튼 선택, 반응속도(ms) 측정 |
| 스윙 결과 그래프 | 포물선 궤적 + 배트 각도 궤적 다이얼로그 표시 |

---

## 훈련 흐름

```
시작 버튼
    ↓
SET 발화 (1초 대기)
    ↓
공 접근 — 비프음 볼륨 점진 증가 (2.5초)
    ↓
PITCH 발화
    ↓
타격 구간 활성화 (0.6초)
    ↓
정타 / 파울 / 스트라이크 판정
    ↓
[정타 시] 공 발산 → 베이스 부저음 → 1루/3루 선택 → 반응속도 표시
```

---

## 타격 판정 기준

| 판정 | 조건 |
|---|---|
| **정타** | 팔 움직임 ≥ 10 m/s² AND 배트 피치각 목표 범위(±19.5°) 이내 |
| **파울** | 팔 움직임 ≥ 8 m/s² AND 정타 조건 미충족 |
| **스트라이크** | 타격 구간(0.6초) 내 스윙 없음 |

---

## 참고 사항

- 스마트폰을 **가로 방향**으로 고정하여 사용합니다.
- **이어폰 착용** 시 좌우 스테레오 패닝으로 베이스 방향을 청각으로 확인할 수 있습니다.
- 타격 요구 각도(`BATTING_ANGLE_DEG`)는 `SwingTestActivity.kt` 상단에서 조정 가능합니다.
