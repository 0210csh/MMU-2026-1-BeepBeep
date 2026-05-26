package com.beepbeep.defense.game

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

class DefenseTutorialManager(
    private val context: Context,
    private val ttsManager: DefenseTtsManager,
    private val scope: CoroutineScope,
    private val isControllerConnected: () -> Boolean,
    private val onStartPractice: () -> Unit,
    private val onTutorialFinished: () -> Unit
) {
    companion object {
        private const val PREF_NAME        = "TutorialPrefs"
        private const val KEY_DEFENSE_DONE = "defense_tutorial_done"
        private const val TAG              = "DefenseTutorial"
    }

    var isRunning = false
        private set

    var currentStep = 0
        private set

    var isSpeaking = false
        private set

    var targetBallCount: Int = 3

    private var controllerDeferred: CompletableDeferred<Unit>? = null
    private var xBtnDeferred:       CompletableDeferred<Unit>? = null
    private var step4Deferred:      CompletableDeferred<Unit>? = null

    fun isTutorialDone(): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEFENSE_DONE, false)

    // ─────────────────────────────────────────────────────
    // 튜토리얼 시작
    // ─────────────────────────────────────────────────────
    fun startTutorial() {
        if (isRunning) return
        isRunning   = true
        currentStep = 0
        scope.launch {
            isSpeaking = true
            if (isControllerConnected()) {
                ttsManager.speakAndWait("수비 훈련 튜토리얼을 시작합니다.")
            } else {
                ttsManager.speakAndWait("수비 훈련 튜토리얼을 시작합니다. 컨트롤러를 연결해주세요.")
            }
            isSpeaking = false

            if (isControllerConnected()) {
                isSpeaking = true
                ttsManager.speakAndWait("컨트롤러가 연결되었습니다.")
                isSpeaking = false
            } else {
                controllerDeferred = CompletableDeferred()
                controllerDeferred?.await()
                controllerDeferred = null
                delay(300)
                isSpeaking = true
                ttsManager.speakAndWait("컨트롤러가 연결되었습니다.")
                isSpeaking = false
            }
            delay(300)
            runStep1()
            runStep2()
            runStep3()
            runStep4()
            finishTutorial()
        }
    }

    // ─────────────────────────────────────────────────────
    // 컨트롤러 연결 완료 신호
    // ─────────────────────────────────────────────────────
    fun notifyControllerConnected() {
        Log.d(TAG, "컨트롤러 연결 완료 신호 수신")
        controllerDeferred?.complete(Unit)
    }

    // ─────────────────────────────────────────────────────
    // 1단계: 장치 장착 안내
    // ─────────────────────────────────────────────────────
    private suspend fun runStep1() {
        currentStep = 1
        Log.d(TAG, "1단계 시작")

        isSpeaking = true
        ttsManager.speakAndWait("첫 번째 단계입니다.")
        delay(300)
        ttsManager.speakAndWait(
            "휴대폰카메라가 아래로 가게하고 세로 방향으로 모자에 장착한 후, 컨트롤러를 손으로 쥐세요."
        )
        delay(300)
        ttsManager.speakAndWait("준비가 되었으면 X 버튼을 눌러 다음으로 넘어가세요.")
        isSpeaking = false

        Log.d(TAG, "1단계 X버튼 대기")
        xBtnDeferred = CompletableDeferred()
        xBtnDeferred?.await()
        xBtnDeferred = null
        Log.d(TAG, "1단계 완료")
    }

    // ─────────────────────────────────────────────────────
    // 2단계: 포구 횟수 설정
    // ─────────────────────────────────────────────────────
    private suspend fun runStep2() {
        currentStep = 2
        Log.d(TAG, "2단계 시작")

        isSpeaking = true
        ttsManager.speakAndWait("두 번째 단계입니다. 포구 횟수 조절 방법을 알려드립니다.")
        delay(300)
        ttsManager.speakAndWait(
            "기본적으로 5회로 설정되어있으며 컨트롤러의 LB 버튼을 누르면 횟수가 줄어들고, RB 버튼을 누르면 늘어납니다."
        )
        delay(300)
        ttsManager.speakAndWait("직접 눌러보고 완료되면 X 버튼을 눌러 다음으로 넘어가세요.")
        isSpeaking = false  // ← TTS 끝난 후 LB/RB 허용

        Log.d(TAG, "2단계 X버튼 대기")
        xBtnDeferred = CompletableDeferred()
        xBtnDeferred?.await()
        xBtnDeferred = null
        Log.d(TAG, "2단계 완료")
    }

    // ─────────────────────────────────────────────────────
    // 3단계: 조작 방법 안내
    // ─────────────────────────────────────────────────────
    private suspend fun runStep3() {
        currentStep = 3
        Log.d(TAG, "3단계 시작")

        isSpeaking = true
        ttsManager.speakAndWait("세 번째 단계입니다. 조작 방법을 알려드립니다.")
        delay(300)
        ttsManager.speakAndWait(
            "공이 날아오면 비프음이 들립니다. 소리 방향으로 좌측 조이스틱을 움직여 캐릭터를 이동시키세요."
        )
        delay(300)
        ttsManager.speakAndWait(
            "비프음이 들리면 소리 방향의 착지 예상 지점으로 미리 이동하세요. 비프음이 멈추거나 공이 착지하면 A 버튼을 눌러 포구하세요."
        )
        delay(300)
        ttsManager.speakAndWait("준비되었으면 X 버튼을 눌러 연습을 시작하세요.")
        isSpeaking = false

        Log.d(TAG, "3단계 X버튼 대기")
        xBtnDeferred = CompletableDeferred()
        xBtnDeferred?.await()
        xBtnDeferred = null
        Log.d(TAG, "3단계 완료")
    }

    // ─────────────────────────────────────────────────────
    // 4단계: 포구 체험
    // ─────────────────────────────────────────────────────
    private suspend fun runStep4() {
        currentStep = 4
        Log.d(TAG, "4단계 시작")

        isSpeaking = true
        ttsManager.speakAndWait("네 번째 단계입니다. 지금부터 연습 포구를 시작합니다.")
        isSpeaking = false

        delay(500)
        withContext(Dispatchers.Main) { onStartPractice() }

        Log.d(TAG, "4단계 포구 완료 대기")
        step4Deferred = CompletableDeferred()
        step4Deferred?.await()
        step4Deferred = null

        delay(300)
        isSpeaking = true
        ttsManager.speakAndWait(
            "포구 훈련이 끝났습니다. 본 훈련에선 X 버튼을 누르면 다음 게임을 시작할 수 있습니다."
        )
        isSpeaking = false
        Log.d(TAG, "4단계 완료")
    }

    // ─────────────────────────────────────────────────────
    // 완료 처리
    // ─────────────────────────────────────────────────────
    private suspend fun finishTutorial() {
        currentStep = 0
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DEFENSE_DONE, true).apply()
        isSpeaking = true
        ttsManager.speakAndWait("튜토리얼이 완료되었습니다. 훈련 화면으로 돌아갑니다.")
        isSpeaking = false
        delay(500)
        isRunning = false
        withContext(Dispatchers.Main) { onTutorialFinished() }
        Log.d(TAG, "수비 튜토리얼 완료")
    }

    fun resetTutorial() {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DEFENSE_DONE, false).apply()
    }

    fun notifyStep3Done() { step4Deferred?.complete(Unit) }

    fun onXButton() {
        if (!isRunning) return
        if (isSpeaking) return  // TTS 중엔 X버튼 무시
        Log.d(TAG, "X버튼 입력 - step=$currentStep")
        when (currentStep) {
            1, 2, 3 -> xBtnDeferred?.complete(Unit)
        }
    }
}