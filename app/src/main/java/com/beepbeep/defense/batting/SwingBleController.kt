package com.beepbeep.defense.batting

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

// ─────────────────────────────────────────────────────
// BLE 권한 및 배트 버튼 처리
// ─────────────────────────────────────────────────────

internal fun SwingTestActivity.requestBlePermissions() {
    val needed = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)    != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_SCAN)
    } else {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), SwingTestActivity.REQ_BLE_PERM)
    else bleManager.startScan()
}

internal fun SwingTestActivity.hasBlePermissions(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)    == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

internal fun SwingTestActivity.startBleScan() {
    if (isAdmin) { btnBleConnect?.isEnabled = false; btnBleConnect?.text = "연결 중..." }
    ttsManager.speak("배트 연결을 시도하고 있습니다.")
    bleManager.startScan()
}

// ─────────────────────────────────────────────────────
// 배트 버튼 처리
//   오른쪽(btn1) 단일탭 → 투구수 +1 / 1루 선택
//   왼쪽(btn2)   단일탭 → 투구수 -1 / 3루 선택
//   양쪽 동시    → 훈련 시작 / 조기종료
// ─────────────────────────────────────────────────────
internal fun SwingTestActivity.handleBatButton(btn1: Boolean, btn2: Boolean) {
    val wasBtn1    = prevBtn1
    val wasBtn2    = prevBtn2
    val risingBtn1 = btn1 && !wasBtn1
    val risingBtn2 = btn2 && !wasBtn2

    if (tutorialManager.isRunning) {
        if (risingBtn1 || risingBtn2) tutorialManager.onTutorialButton(risingBtn1, risingBtn2)
        if (tutorialManager.currentStep != 2 && tutorialManager.currentStep != 5 && tutorialManager.currentStep != 6) {
            prevBtn1 = btn1; prevBtn2 = btn2
            return
        }
        if (tutorialManager.currentStep == 5 && !isWaitingForInput) {
            prevBtn1 = btn1; prevBtn2 = btn2
            return
        }
        if ((tutorialManager.currentStep == 2 || tutorialManager.currentStep == 6) && tutorialManager.isSpeaking) {
            prevBtn1 = btn1; prevBtn2 = btn2
            return
        }
    }

    if (isResultSpeaking) {
        prevBtn1 = btn1; prevBtn2 = btn2
        return
    }

    if (btn1 && !wasBtn1 && btn2 && !wasBtn2) {
        batBtn1Job?.cancel(); batBtn2Job?.cancel()
        when {
            isTraining -> earlyFinishTraining(speakTts = true)
            isAdmin    -> if (btnStart?.isEnabled == true) btnStart?.performClick()
            else       -> startSimpleTraining()
        }
        prevBtn1 = btn1; prevBtn2 = btn2
        return
    }

    if (btn1 && !wasBtn1) {
        when {
            isWaitingForInput -> { batBtn2Job?.cancel(); onBasePressed(1) }
            isTraining -> {
                if (batBtn2Job?.isActive == true) {
                    batBtn1Job?.cancel(); batBtn2Job?.cancel()
                    earlyFinishTraining(speakTts = true)
                } else {
                    batBtn1Job?.cancel()
                    batBtn1Job = scope.launch { delay(200L) }
                }
            }
            !isTraining -> {
                if (batBtn2Job?.isActive == true) {
                    batBtn1Job?.cancel(); batBtn2Job?.cancel()
                    if (btnStart?.isEnabled == true) btnStart?.performClick()
                } else {
                    batBtn1Job?.cancel()
                    batBtn1Job = scope.launch {
                        delay(200L)
                        withContext(Dispatchers.Main) {
                            if (batBtn2Job?.isActive != true && targetPitches < 20) {
                                targetPitches++
                                tvSwingPitchCount?.text = targetPitches.toString()
                                ttsManager.speak("투구횟수 ${targetPitches}회")
                            }
                        }
                    }
                }
            }
        }
    }

    if (btn2 && !wasBtn2) {
        when {
            isWaitingForInput -> { batBtn1Job?.cancel(); onBasePressed(3) }
            isTraining -> {
                if (batBtn1Job?.isActive == true) {
                    batBtn1Job?.cancel(); batBtn2Job?.cancel()
                    earlyFinishTraining(speakTts = true)
                } else {
                    batBtn2Job?.cancel()
                    batBtn2Job = scope.launch { delay(200L) }
                }
            }
            !isTraining -> {
                if (batBtn1Job?.isActive == true) {
                    batBtn1Job?.cancel(); batBtn2Job?.cancel()
                    if (btnStart?.isEnabled == true) btnStart?.performClick()
                } else {
                    batBtn2Job?.cancel()
                    batBtn2Job = scope.launch {
                        delay(200L)
                        withContext(Dispatchers.Main) {
                            if (batBtn1Job?.isActive != true && targetPitches > 1) {
                                targetPitches--
                                tvSwingPitchCount?.text = targetPitches.toString()
                                ttsManager.speak("투구횟수 ${targetPitches}회")
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
