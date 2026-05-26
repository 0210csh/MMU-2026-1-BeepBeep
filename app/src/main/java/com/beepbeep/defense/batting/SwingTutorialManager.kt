package com.beepbeep.defense.batting

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

/**
 * 타격 훈련 튜토리얼 매니저 (최초 1회)
 *
 * BLE 연결 완료 후 SwingTestActivity.onConnected() 에서 startTutorial() 호출
 *
 * 단계:
 * 1. 장치 장착 안내 → 오른쪽 버튼으로 다음
 * 2. 투구 횟수 조절 체험 → 양쪽 버튼 동시로 다음
 * 3. 타격 체험 → 연습 투구 1회 후 자동 진행
 * 4. 주루 체험 → 베이스 선택 후 자동 진행
 * 5. 종료 안내 → 오른쪽 버튼으로 본 훈련 시작
 */
class SwingTutorialManager(
    private val context: Context,
    private val ttsManager: SwingTtsManager,
    private val scope: CoroutineScope,
    private val onStartPracticePitch: () -> Unit,
    private val onTutorialFinished: () -> Unit,
    private val onStartBaseBeep: () -> Unit

    ) {

    var targetPitchCount: Int = 1

    companion object {
        private const val PREF_NAME        = "TutorialPrefs"
        private const val KEY_BATTING_DONE = "batting_tutorial_done"
        private const val TAG              = "Tutorial"
    }

    var isRunning = false
        private set

    var currentStep = 0
        private set

    var isSpeaking = false  // ← 추가
        private set

    private var rightBtnDeferred: CompletableDeferred<Unit>? = null
    private var bothBtnDeferred:  CompletableDeferred<Unit>? = null
    private var step3Deferred:    CompletableDeferred<Unit>? = null
    private var step4Deferred:    CompletableDeferred<Unit>? = null

    // ─────────────────────────────────────────────────────
    // 튜토리얼 완료 여부 확인
    // ─────────────────────────────────────────────────────
    fun isTutorialDone(): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BATTING_DONE, false)
    }

    // ─────────────────────────────────────────────────────
    // 튜토리얼 시작 (BLE 연결 완료 후 외부에서 호출)
    // ─────────────────────────────────────────────────────
    fun startTutorial() {
        if (isRunning) return
        isRunning   = true
        currentStep = 0
        Log.d(TAG, "튜토리얼 시작")
        scope.launch {
            delay(1_000L)
            runStep1()
            runStep2()
            runStep3()
            runStep4()
            finishTutorial()
        }
    }

    // ─────────────────────────────────────────────────────
    // 1단계: 장치 장착 안내
    // ─────────────────────────────────────────────────────
    private suspend fun runStep1() {
        currentStep = 1
        Log.d(TAG, "1단계 시작")
        ttsManager.speakAndWait("타격 훈련 튜토리얼을 시작합니다.", java.util.Locale.KOREAN)
        delay(300)
        ttsManager.speakAndWait(
            "휴대폰을 세로 방향으로 모자에 장착하고, 배트를 손으로 쥐세요.",
            java.util.Locale.KOREAN
        )
        delay(300)
        ttsManager.speakAndWait(
            "준비가 되었으면 오른쪽 버튼을 눌러 다음으로 넘어가세요.",
            java.util.Locale.KOREAN
        )
        Log.d(TAG, "1단계 오른쪽 버튼 대기")
        rightBtnDeferred = CompletableDeferred()
        rightBtnDeferred?.await()
        rightBtnDeferred = null
        Log.d(TAG, "1단계 완료")
    }

    // ─────────────────────────────────────────────────────
    // 2단계: 투구 횟수 조절 체험
    // ─────────────────────────────────────────────────────
    private suspend fun runStep2() {
        currentStep = 2
        Log.d(TAG, "2단계 시작")
        isSpeaking = true  // ← 추가
        ttsManager.speakAndWait("두 번째 단계입니다. 투구 횟수 조절 방법을 알려드립니다.", java.util.Locale.KOREAN)
        delay(300)
        ttsManager.speakAndWait(
            "기본적으로 3회로 설정되어있으며 배트의 오른쪽 버튼을 누르면 횟수가 늘어나고, 왼쪽 버튼을 누르면 줄어듭니다.",
            java.util.Locale.KOREAN
        )
        delay(300)
        ttsManager.speakAndWait(
            "직접 눌러보고 완료되면 양쪽 버튼을 동시에 눌러 다음으로 넘어가세요.",
            java.util.Locale.KOREAN
        )
        isSpeaking = false  // ← 추가
        Log.d(TAG, "2단계 양쪽 버튼 대기")
        bothBtnDeferred = CompletableDeferred()
        bothBtnDeferred?.await()
        bothBtnDeferred = null
        Log.d(TAG, "2단계 완료")
    }

    // ─────────────────────────────────────────────────────
    // 3단계: 타격 체험
    // ─────────────────────────────────────────────────────
    private suspend fun runStep3() {
        currentStep = 3
        Log.d(TAG, "3단계 시작")
        ttsManager.speakAndWait("세 번째 단계입니다. 조작 방법을 알려드립니다.", java.util.Locale.KOREAN)
        delay(300)
        ttsManager.speakAndWait(
            "제자리에 서서 오른쪽이나 왼쪽으로 90도 회전하여 준비자세를 잡으세요. SET, READY, PITCH 순서로 구호가 들립니다. PITCH구호가 끝나는 순간 배트를 휘두르세요 " +
                    "판정은 정타, 파울, 스트라이크가 있습니다. 깡 소리가 나면 정타입니다.",
            java.util.Locale.KOREAN
        )
        delay(300)
        ttsManager.speakAndWait("지금 연습 투구를 시작합니다. 준비하세요.", java.util.Locale.KOREAN)
        Log.d(TAG, "3단계 투구 완료 대기")
        delay(500)
        withContext(Dispatchers.Main) {
            onStartPracticePitch()   // ← 여기 추가
        }
        step3Deferred = CompletableDeferred()
        step3Deferred?.await()
        step3Deferred = null
        Log.d(TAG, "3단계 완료")
    }

    // ─────────────────────────────────────────────────────
    // 4단계: 주루 체험
    // ─────────────────────────────────────────────────────
    private suspend fun runStep4() {
        currentStep = 4
        Log.d(TAG, "4단계 시작")
        delay(500)
        ttsManager.speakAndWait("정면으로 몸을 돌려 제자리에 서주세요.", java.util.Locale.KOREAN)  // ← 추가
        delay(500)
        ttsManager.speakAndWait("네 번째 단계입니다. 주루를 선택하는 방법을 알려드립니다.", java.util.Locale.KOREAN)
        delay(300)
        ttsManager.speakAndWait(
            "삐 소리가 들리게 되면 소리가 나는 방향의 버튼을 누르는 것으로 베이스가 선택됩니다",
            java.util.Locale.KOREAN
        )
        delay(300)
        ttsManager.speakAndWait(
            "오른쪽에서 소리가 나면 오른쪽 버튼, 왼쪽에서 소리가 나면 왼쪽 버튼을 누르세요.",
            java.util.Locale.KOREAN
        )
        delay(500)
        repeat(targetPitchCount) { index ->
            ttsManager.speak("${index + 1}번 주루 시작.")
            delay(800)
            withContext(Dispatchers.Main) { onStartBaseBeep() }
            step4Deferred = CompletableDeferred()
            step4Deferred?.await()
            step4Deferred = null
            delay(1500)
        }
        Log.d(TAG, "4단계 완료")
    }


    // ─────────────────────────────────────────────────────
    // 완료 처리
    // ─────────────────────────────────────────────────────
    private suspend fun finishTutorial() {
        currentStep = 0
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BATTING_DONE, true).apply()

        // TTS 완료 후 화면 종료
        ttsManager.speakAndWait(
            "연습이 끝났습니다. 튜토리얼이 완료되었습니다. 훈련 화면으로 돌아갑니다.",
            java.util.Locale.KOREAN
        )
        delay(500)
        isRunning = false
        withContext(Dispatchers.Main) {
            onTutorialFinished()
        }
    }

    // ─────────────────────────────────────────────────────
    // 설정창에서 튜토리얼 초기화 (다시보기)
    // ─────────────────────────────────────────────────────
    fun resetTutorial() {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BATTING_DONE, false).apply()
    }

    // ─────────────────────────────────────────────────────
    // SwingTestActivity 에서 단계 완료 신호
    // ─────────────────────────────────────────────────────
    fun notifyStep3Done() { step3Deferred?.complete(Unit) }
    fun notifyStep4Done() { step4Deferred?.complete(Unit) }

    // ─────────────────────────────────────────────────────
    // 버튼 입력 전달
    // ─────────────────────────────────────────────────────
    fun onTutorialButton(risingBtn1: Boolean, risingBtn2: Boolean) {
        if (!isRunning) return
        val both = risingBtn1 && risingBtn2
        Log.d(TAG, "버튼 입력 - step=$currentStep btn1=$risingBtn1 btn2=$risingBtn2 both=$both")
        when (currentStep) {
            1 -> if (risingBtn1 && !risingBtn2) rightBtnDeferred?.complete(Unit)
            2 -> if (both) bothBtnDeferred?.complete(Unit)
            5 -> if (risingBtn1 && !risingBtn2) rightBtnDeferred?.complete(Unit)
        }
    }
}