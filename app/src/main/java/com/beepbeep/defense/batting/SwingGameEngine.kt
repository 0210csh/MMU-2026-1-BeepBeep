package com.beepbeep.defense.batting

import android.os.SystemClock
import android.view.View
import android.widget.Toast
import kotlinx.coroutines.*
import kotlin.math.*
import kotlin.random.Random
import java.util.Locale

// ─────────────────────────────────────────────────────
// 유틸리티 (top-level — bleCallback 등에서도 직접 호출 가능)
// ─────────────────────────────────────────────────────
internal fun magnitude(v: FloatArray) = sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2])

// ─────────────────────────────────────────────────────
// 스윙 판정
// ─────────────────────────────────────────────────────
internal fun SwingTestActivity.checkSwing() {
    val accel = linearAccelMag
    val gyro  = gyroMag
    val isStrongSwing = accel > HIT_ACCEL_THRESHOLD && gyro > HIT_GYRO_THRESHOLD
    val isAnySwing    = accel > HIT_ACCEL_THRESHOLD && gyro > HIT_GYRO_THRESHOLD
    val isMinSwing    = accel > MIN_ACCEL_THRESHOLD  && gyro > MIN_GYRO_THRESHOLD

    if ((preWindowActive || hitWindowActive || postWindowActive) && !minSwingDetected && isMinSwing)
        minSwingDetected = true
    if (preWindowActive && !preWindowSwingDetected && isAnySwing)
        preWindowSwingDetected = true

    if (hitWindowActive) {
        if (!swingDetected && isAnySwing) {
            swingDetected = true
            swingPitchDeg = currentPitchDeg
            if (hitTimeRelMs < 0L) {
                hitTimeRelMs = System.currentTimeMillis() - pitchRecordStart
                if (recordingPhase == 3) hitTimePhase3Ms = System.currentTimeMillis() - phaseRecordStartTime
                if (isAdmin) {
                    swingGraphView?.setHitTime(hitTimeRelMs)
                    swingGraphView?.postInvalidate()
                }
            }
        }
        if (!swingWasStrong && isStrongSwing) {
            swingWasStrong = true
            swingBatHeight = BATTER_HEIGHT + sin(currentPitchDeg * PI.toFloat() / 180f) * BAT_REACH
            if (abs(currentPitchDeg - BATTING_ANGLE_DEG) <= PITCH_TOLERANCE) swingIsHit = true
        }
    }
    if (postWindowActive && !postWindowSwingDetected && isAnySwing)
        postWindowSwingDetected = true
}

internal fun SwingTestActivity.startPhaseRecording() { phaseRecordStartTime = System.currentTimeMillis() }
internal fun SwingTestActivity.stopPhaseRecording()  { recordingPhase = 0 }

// ─────────────────────────────────────────────────────
// 오디오 중단
// ─────────────────────────────────────────────────────
internal fun SwingTestActivity.stopAudio() {
    audioJob?.cancel()
    spatialAudio.stopBeep()
    audioTrack?.pause(); audioTrack?.flush(); audioTrack?.play()
}

// ─────────────────────────────────────────────────────
// 게임 메인 루프
// ─────────────────────────────────────────────────────
internal fun SwingTestActivity.startGame() {
    btAudioLost         = false
    gameJob?.cancel()
    tvResult?.text      = ""
    ballTrackView?.reset()
    btnStart?.isEnabled = false
    isWaitingForInput   = false
    updateSimpleStatus("준비 중...")

    swingDetected             = false
    swingIsHit                = false
    gangSpoken                = false
    preWindowSwingDetected    = false
    postWindowSwingDetected   = false
    minSwingDetected          = false
    preWindowActive           = false
    postWindowActive          = false
    windowOpenPitchDeg        = 0f
    windowClosePitchDeg       = 0f
    minAngleSearchActive      = false
    minBatAngleDeg            = Float.MAX_VALUE
    minBatAngleAbsMs          = -1L
    minBatAngleRelMs          = -1L
    hitWindowOpenAbsMs        = -1L
    hitWindowCloseAbsMs       = -1L
    graphHitWindowOpenRelMs   = -1L
    graphHitWindowCloseRelMs  = -1L
    graphMinSearchStartRelMs  = -1L
    graphPitchWindowStartMs   = -1L
    graphPitchTtsStartMs      = -1L
    recordingPhase     = 0
    phase2StartAbsMs   = 0L
    phase3StartAbsMs   = 0L
    setAngleThisPitch  = 0f
    setToReadyHistory.clear()
    readyToPitchHistory.clear()
    pitchToEndHistory.clear()
    swingPitchDeg      = 0f
    swingWasStrong     = false
    swingBatHeight     = Float.NaN
    hitTimePhase3Ms    = -1L
    ballApproachProgress = 0f
    divProgress          = 0f
    pitchHistory.clear()
    hitTimeRelMs    = -1L
    isRecording     = false
    hitWindowActive = false
    initAudioTrack()
    targetBase = if (Random.nextBoolean()) 1 else 3
    resetBaseVisuals()
    currentPitchRecord.clear()
    currentPitchRecord["투구번호"]       = currentPitchNum
    currentPitchRecord["목표베이스"]     = targetBase
    currentPitchRecord["배트각도"]       = null
    currentPitchRecord["선택베이스"]     = null
    currentPitchRecord["베이스정답여부"] = null
    currentPitchRecord["주루반응속도"]   = null

    gameJob = scope.launch {
        // ━━━ 1단계: SET ━━━
        setAngleThisPitch = currentPitchDeg
        allSetAngles.add(setAngleThisPitch)
        if (bleConnected) {
            bleManager.sendControl(0)
            delay(200L)
            bleManager.sendControl(1)
        }
        recordingPhase = 1
        startPhaseRecording()
        withContext(Dispatchers.Main) {
            tvStatus?.text = "SET"
            tvStatus?.setTextColor(0xFF93C5FD.toInt())
            updateSimpleStatus("SET")
            ttsManager.speakEnglish("SET", speechRate = 1.2f)
        }
        delay(1000)

        // ━━━ 2단계: 공 접근 + READY/PITCH TTS ━━━
        val approachMs    = 1300L
        val readyDistM    = 10f * 0.3048f
        val readyProgress = ((PITCHER_DIST - readyDistM) / PITCHER_DIST).coerceIn(0f, 1f)
        val contactH      = BATTER_HEIGHT + sin(BATTING_ANGLE_DEG * PI.toFloat() / 180f) * BAT_REACH
        var pitchTtsJob: Job? = null
        var pitchCompletedMs  = -1L

        stopPhaseRecording()
        recordingPhase = 2
        startPhaseRecording()
        phase2StartAbsMs = phaseRecordStartTime
        preWindowActive = true
        pitchTtsJob = launch {
            withContext(Dispatchers.Main) {
                tvStatus?.text = "READY"
                tvStatus?.setTextColor(0xFF93C5FD.toInt())
                updateSimpleStatus("READY")
            }
            ttsManager.speakTwoSequentially(
                first  = "READY",
                second = "PITCH",
                locale = Locale.ENGLISH,
                speechRate = 1.2f,
                onFirstDone = {
                    val ttsStartMs = System.currentTimeMillis() - pitchRecordStart
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        graphPitchTtsStartMs = ttsStartMs
                        if (isAdmin) {
                            swingGraphView?.setPitchTtsStart(ttsStartMs)
                            tvStatus?.text = "PITCH"
                            tvStatus?.setTextColor(0xFFFBBF24.toInt())
                        }
                        updateSimpleStatus("PITCH", 0xFF78350F.toInt())
                    }
                }
            )
            pitchCompletedMs = System.currentTimeMillis()
            preWindowActive  = false
            withContext(Dispatchers.Main) { tvStatus?.text = "" }
        }

        delay(500L)
        val tApproach = System.currentTimeMillis()

        spatialAudio.gainCoeff = 0.30f
        spatialAudio.updateBallPosition(0f, 0f, -PITCHER_DIST)
        spatialAudio.updateHeading(headTracker.currentHeadingDeg)
        spatialAudio.startBeep()

        val animJob = launch {
            while (isActive) {
                val elapsed  = System.currentTimeMillis() - tApproach
                val progress = (elapsed.toFloat() / approachMs).coerceIn(0f, 1f)
                ballApproachProgress = progress
                val zPos = -(PITCHER_DIST * (1f - progress)).coerceAtLeast(0.5f)
                spatialAudio.updateBallPosition(0f, 0f, zPos)
                spatialAudio.updateHeading(headTracker.currentHeadingDeg)
                if (isAdmin) {
                    withContext(Dispatchers.Main) {
                        ballTrackView?.updateBall(0f, progress, BallPhase.APPROACH, pitchYPosition)
                        liveBallParabolaView?.setBallProgress(progress)
                    }
                }
                if (progress >= 1f) break
                delay(16)
            }
        }

        while (isActive) {
            val elapsed = System.currentTimeMillis() - tApproach

            if (!minAngleSearchActive && elapsed >= approachMs - 900L) {
                minBatAngleDeg           = Float.MAX_VALUE
                minBatAngleAbsMs         = -1L
                minAngleSearchActive     = true
                graphMinSearchStartRelMs = System.currentTimeMillis() - pitchRecordStart
                if (isAdmin) withContext(Dispatchers.Main) { swingGraphView?.setMinAngleSearch(graphMinSearchStartRelMs) }
            }

            if (!hitWindowActive && elapsed >= approachMs - 160L) {
                swingDetected           = false
                swingIsHit              = false
                gangSpoken              = false
                windowOpenPitchDeg      = currentPitchDeg
                hitWindowOpenAbsMs      = System.currentTimeMillis()
                graphHitWindowOpenRelMs = hitWindowOpenAbsMs - pitchRecordStart
                hitWindowActive         = true
                if (isAdmin) withContext(Dispatchers.Main) { swingGraphView?.setHitWindowOpen(graphHitWindowOpenRelMs) }
            }

            if (elapsed >= approachMs) break
            delay(1)
        }
        animJob.join()

        audioTrack?.pause(); audioTrack?.flush(); audioTrack?.play()

        stopPhaseRecording()
        recordingPhase = 3
        startPhaseRecording()
        phase3StartAbsMs = phaseRecordStartTime

        pitchTtsJob?.join()
        val TTS_BUFFER_OFFSET_MS = 190L
        graphPitchWindowStartMs = if (pitchCompletedMs >= 0L)
            (pitchCompletedMs - pitchRecordStart - TTS_BUFFER_OFFSET_MS)
                .coerceAtLeast(graphPitchTtsStartMs + 50L)
        else 0L
        if (isAdmin) withContext(Dispatchers.Main) { swingGraphView?.setPitchWindowStart(graphPitchWindowStartMs) }

        // ━━━ 3단계: 타격 윈도우 마감 (+50ms) ━━━
        val deadline = System.currentTimeMillis() + 50L
        while (isActive && System.currentTimeMillis() < deadline) {
            if (swingDetected) break
            delay(8)
        }
        hitWindowActive          = false
        spatialAudio.stopBeep()
        hitWindowCloseAbsMs      = System.currentTimeMillis()
        graphHitWindowCloseRelMs = hitWindowCloseAbsMs - pitchRecordStart
        windowClosePitchDeg      = currentPitchDeg

        val snapMinAngleDeg   = minBatAngleDeg
        val snapMinAngleAbsMs = minBatAngleAbsMs

        withContext(Dispatchers.Main) {
            tvStatus?.text = ""
            if (isAdmin) {
                swingGraphView?.setHitWindowClose(graphHitWindowCloseRelMs)
                swingGraphView?.setWindowAngles(windowOpenPitchDeg, windowClosePitchDeg)
            }
        }
        if (bleConnected) bleManager.sendControl(0)

        val earlyMinInWindow = snapMinAngleDeg < Float.MAX_VALUE &&
                snapMinAngleAbsMs >= hitWindowOpenAbsMs &&
                snapMinAngleAbsMs <= hitWindowCloseAbsMs
        val earlyAngleDiff   = if (snapMinAngleDeg < Float.MAX_VALUE)
            snapMinAngleDeg - BATTING_ANGLE_DEG else Float.MAX_VALUE

        if (earlyMinInWindow && swingWasStrong && abs(earlyAngleDiff) <= PITCH_TOLERANCE) {
            gangSpoken = true
            val gangSnapHistory     = ArrayList(pitchHistory)
            val gangSnapHitTime     = hitTimeRelMs
            val gangSnapWinOpen     = graphHitWindowOpenRelMs
            val gangSnapWinClose    = graphHitWindowCloseRelMs
            val gangSnapMinSearch   = graphMinSearchStartRelMs
            val gangSnapMinRel      = if (minBatAngleAbsMs > 0L && minBatAngleDeg < Float.MAX_VALUE) minBatAngleRelMs else -1L
            val gangSnapMinDeg      = minBatAngleDeg
            val gangSnapPitchWin    = graphPitchWindowStartMs
            val gangSnapPitchTts    = graphPitchTtsStartMs
            val gangSnapP2Rel       = phase2StartAbsMs - pitchRecordStart
            val gangSnapP3Rel       = phase3StartAbsMs - pitchRecordStart
            val gangSnapWinOpenDeg  = windowOpenPitchDeg
            val gangSnapWinCloseDeg = windowClosePitchDeg
            withContext(Dispatchers.Main) {
                ttsManager.speak("깡")
                tvStatus?.text = "소리 들어봐!"
                tvStatus?.setTextColor(0xFFFBBF24.toInt())
                updateSimpleStatus("정타!", 0xFF166534.toInt())
                if (isAdmin) {
                    perPitchFullHistory.add(gangSnapHistory)
                    perPitchHitTimeRelFull.add(gangSnapHitTime)
                    perPitchWinOpenRelFull.add(gangSnapWinOpen)
                    perPitchWinCloseRelFull.add(gangSnapWinClose)
                    perPitchMinSearchRelFull.add(gangSnapMinSearch)
                    perPitchMinAngleRelFull.add(gangSnapMinRel)
                    perPitchMinAngleDegList.add(gangSnapMinDeg)
                    perPitchPitchWinStartRelFull.add(gangSnapPitchWin)
                    perPitchPitchTtsStartRelFull.add(gangSnapPitchTts)
                    perPitchPhase2RelFull.add(gangSnapP2Rel)
                    perPitchPhase3RelFull.add(gangSnapP3Rel)
                    perPitchWinOpenDeg.add(gangSnapWinOpenDeg)
                    perPitchWinCloseDeg.add(gangSnapWinCloseDeg)
                }
            }
            launch {
                delay(100L)
                if (tutorialManager.isRunning && tutorialManager.currentStep == 4) return@launch
                withContext(Dispatchers.Main) {
                    if (isAdmin) activateBothBases()
                    currentPitchRecord["판정"] = "정타"
                    currentPitchRecord["피드백"] = "정타 — 베이스 선택"
                    perPitchRecords.add(HashMap(currentPitchRecord))
                    hitCount++
                    startBaseBeep()
                    isWaitingForInput = true
                }
            }
        }

        postWindowActive = true
        delay(1000L)
        postWindowActive = false
        minAngleSearchActive = false
        if (isAdmin) withContext(Dispatchers.Main) {
            if (minBatAngleAbsMs > 0L && minBatAngleDeg < Float.MAX_VALUE)
                swingGraphView?.setMinAngle(minBatAngleRelMs, minBatAngleDeg)
        }

        delay(150L)
        isRecording = false
        stopPhaseRecording()
        if (setToReadyHistory.isNotEmpty())   perPitchPhase1Data.add(ArrayList(setToReadyHistory))
        if (readyToPitchHistory.isNotEmpty()) perPitchPhase2Data.add(ArrayList(readyToPitchHistory))
        if (pitchToEndHistory.isNotEmpty())   perPitchPhase3Data.add(ArrayList(pitchToEndHistory))
        perPitchHitTimesPhase3.add(hitTimePhase3Ms)

        val snapHitTime       = hitTimeRelMs
        val snapWinOpen       = graphHitWindowOpenRelMs
        val snapWinClose      = graphHitWindowCloseRelMs
        val snapMinSearch     = graphMinSearchStartRelMs
        val snapMinRel        = if (minBatAngleAbsMs > 0L && minBatAngleDeg < Float.MAX_VALUE) minBatAngleRelMs else -1L
        val snapMinDeg        = minBatAngleDeg
        val snapPitchWin      = graphPitchWindowStartMs
        val snapPitchTtsStart = graphPitchTtsStartMs
        val snapP2Rel         = phase2StartAbsMs - pitchRecordStart
        val snapP3Rel         = phase3StartAbsMs - pitchRecordStart
        val snapWinOpenDeg    = windowOpenPitchDeg
        val snapWinCloseDeg   = windowClosePitchDeg
        if (isAdmin && !gangSpoken) withContext(Dispatchers.Main) {
            perPitchFullHistory.add(ArrayList(pitchHistory))
            perPitchHitTimeRelFull.add(snapHitTime)
            perPitchWinOpenRelFull.add(snapWinOpen)
            perPitchWinCloseRelFull.add(snapWinClose)
            perPitchMinSearchRelFull.add(snapMinSearch)
            perPitchMinAngleRelFull.add(snapMinRel)
            perPitchMinAngleDegList.add(snapMinDeg)
            perPitchPitchWinStartRelFull.add(snapPitchWin)
            perPitchPitchTtsStartRelFull.add(snapPitchTtsStart)
            perPitchPhase2RelFull.add(snapP2Rel)
            perPitchPhase3RelFull.add(snapP3Rel)
            perPitchWinOpenDeg.add(snapWinOpenDeg)
            perPitchWinCloseDeg.add(snapWinCloseDeg)
        }

        // ━━━ 4단계: 결과 판정 ━━━
        val hasValidMin  = minBatAngleDeg < Float.MAX_VALUE
        val minInWindow  = hasValidMin && minBatAngleAbsMs >= hitWindowOpenAbsMs && minBatAngleAbsMs <= hitWindowCloseAbsMs
        val minBeforeWin = hasValidMin && minBatAngleAbsMs < hitWindowOpenAbsMs
        val minAfterWin  = hasValidMin && minBatAngleAbsMs > hitWindowCloseAbsMs
        val angleDiff    = if (hasValidMin) minBatAngleDeg - BATTING_ANGLE_DEG else Float.MAX_VALUE

        when {
            !minSwingDetected && !testForceHit -> {
                currentPitchRecord["판정"] = "스트라이크(무스윙)"
                currentPitchRecord["피드백"] = "더 빨리 스윙하세요"
                withContext(Dispatchers.Main) {
                    perPitchRecords.add(HashMap(currentPitchRecord))
                    ballTrackView?.reset()
                    ttsManager.speak("스트라이크")
                    strikeCount++
                    tvStatus?.text = "스트라이크!"
                    tvStatus?.setTextColor(0xFFF87171.toInt())
                    tvResult?.text = "스윙 없음"
                    updateSimpleStatus("스트라이크!", 0xFF7F1D1D.toInt())
                    if (isAdmin && !isTraining) {
                        showSwingGraph()
                        btnStart?.isEnabled = true
                        btnStart?.text = "다시하기"
                    }
                }
                delay(1000L)
                ttsManager.speakAndWait("더 빨리 스윙하세요", Locale.KOREAN)
                if (isTraining) {
                    withContext(Dispatchers.Main) { scheduleNextOrFinish(false) }
                }
            }

            testForceHit || gangSpoken || (minInWindow && swingWasStrong && abs(angleDiff) <= PITCH_TOLERANCE) -> {
                currentPitchRecord["판정"] = "정타"
                currentPitchRecord["피드백"] = "정타 — 베이스 선택"
                withContext(Dispatchers.Main) {
                    if (!gangSpoken) {
                        perPitchRecords.add(HashMap(currentPitchRecord))
                        hitCount++
                    }
                    if (!gangSpoken) {
                        ttsManager.speak("깡")
                        tvStatus?.text = "소리 들어봐!"
                        tvStatus?.setTextColor(0xFFFBBF24.toInt())
                        updateSimpleStatus("정타!", 0xFF166534.toInt())
                    }
                    tvResult?.text = "최저 각도: %.0f°".format(minBatAngleDeg)
                }
                if (!gangSpoken && !(tutorialManager.isRunning && tutorialManager.currentStep == 4)) {
                    launch {
                        delay(100L)
                        withContext(Dispatchers.Main) { if (isAdmin) activateBothBases() }
                        startBaseBeep()
                        isWaitingForInput = true
                    }
                }

                val divMs      = 1500L
                val tDiv       = System.currentTimeMillis()
                val targetPanX = if (targetBase == 3) -1f else 1f

                if (!gangSpoken) {
                    spatialAudio.updateBallPosition(0f, 0f, -0.5f)
                    spatialAudio.updateHeading(headTracker.currentHeadingDeg)
                    spatialAudio.startBeep()
                }

                while (isActive) {
                    val progress = ((System.currentTimeMillis() - tDiv).toFloat() / divMs)
                        .coerceIn(0f, 1f)
                    divProgress = progress
                    if (!gangSpoken) {
                        spatialAudio.updateBallPosition(targetPanX * progress * 5f, 0f, -0.5f)
                        spatialAudio.updateHeading(headTracker.currentHeadingDeg)
                    }
                    if (isAdmin) withContext(Dispatchers.Main) {
                        ballTrackView?.updateBall(
                            targetPanX * progress,
                            1f - progress,
                            BallPhase.DIVERGE
                        )
                    }
                    if (progress >= 1f) break
                    delay(16)
                }

                if (!gangSpoken) {
                    spatialAudio.stopBeep()
                    audioTrack?.pause(); audioTrack?.flush(); audioTrack?.play()
                }

                withContext(Dispatchers.Main) {
                    ballTrackView?.reset()
                    if (tutorialManager.isRunning && tutorialManager.currentStep == 4) {
                        isWaitingForInput = false
                        stopAudio()
                        tvStatus?.text = "정타!"
                        tvStatus?.setTextColor(0xFF4ADE80.toInt())
                        scope.launch {
                            ttsManager.speakAndWait("정타입니다.", Locale.KOREAN)
                            withContext(Dispatchers.Main) { scheduleNextOrFinish(true) }
                        }
                        return@withContext
                    }
                    if (isAdmin) activateBothBases()
                    tvStatus?.text = ""
                }
            }

            minInWindow -> {
                val absDiff = abs(angleDiff).toInt()
                val foulAdvice = when {
                    !swingWasStrong  -> "더 강하게 휘두르세요"
                    angleDiff > 0    -> "배트를 ${absDiff}도 더 내려서 치세요"
                    else             -> "배트를 ${absDiff}도 더 올려서 치세요"
                }
                currentPitchRecord["판정"] = "파울"
                currentPitchRecord["피드백"] = foulAdvice
                withContext(Dispatchers.Main) {
                    perPitchRecords.add(HashMap(currentPitchRecord))
                    ballTrackView?.reset()
                    ttsManager.speak("파울")
                    foulCount++
                    tvStatus?.text = "파울!"
                    tvStatus?.setTextColor(0xFFFBBF24.toInt())
                    val resultLabel = if (!swingWasStrong) "파울 — 힘 부족" else "파울 — 각도 불일치"
                    tvResult?.text = "$resultLabel\n최저 각도: %.0f°".format(minBatAngleDeg)
                    updateSimpleStatus("파울!", 0xFF78350F.toInt())
                    if (isAdmin && !isTraining) {
                        showSwingGraph()
                        btnStart?.isEnabled = true
                        btnStart?.text = "다시하기"
                    }
                }
                delay(900L)
                ttsManager.speakAndWait(foulAdvice, Locale.KOREAN)
                if (isTraining) {
                    withContext(Dispatchers.Main) { scheduleNextOrFinish(false) }
                }
            }

            minBeforeWin -> {
                currentPitchRecord["판정"] = "스트라이크(빠름)"
                currentPitchRecord["피드백"] = "더 늦게 스윙하세요"
                withContext(Dispatchers.Main) {
                    perPitchRecords.add(HashMap(currentPitchRecord))
                    ballTrackView?.reset()
                    ttsManager.speak("스트라이크")
                    strikeCount++
                    tvStatus?.text = "스트라이크!"
                    tvStatus?.setTextColor(0xFFF87171.toInt())
                    tvResult?.text = "타격 윈도우 전 스윙\n최저 각도: %.0f°".format(minBatAngleDeg)
                    updateSimpleStatus("스트라이크!", 0xFF7F1D1D.toInt())
                    if (isAdmin && !isTraining) {
                        showSwingGraph()
                        btnStart?.isEnabled = true
                        btnStart?.text = "다시하기"
                    }
                }
                delay(1000L)
                ttsManager.speakAndWait("더 늦게 스윙하세요", Locale.KOREAN)
                if (isTraining) {
                    withContext(Dispatchers.Main) { scheduleNextOrFinish(false) }
                }
            }

            minAfterWin -> {
                currentPitchRecord["판정"] = "스트라이크(늦음)"
                currentPitchRecord["피드백"] = "더 빨리 스윙하세요"
                withContext(Dispatchers.Main) {
                    perPitchRecords.add(HashMap(currentPitchRecord))
                    ballTrackView?.reset()
                    ttsManager.speak("스트라이크")
                    strikeCount++
                    tvStatus?.text = "스트라이크!"
                    tvStatus?.setTextColor(0xFFF87171.toInt())
                    tvResult?.text = "스윙이 늦음\n최저 각도: %.0f° (윈도우 마감 후)".format(minBatAngleDeg)
                    updateSimpleStatus("스트라이크!", 0xFF7F1D1D.toInt())
                    if (isAdmin && !isTraining) {
                        showSwingGraph()
                        btnStart?.isEnabled = true
                        btnStart?.text = "다시하기"
                    }
                }
                delay(1000L)
                ttsManager.speakAndWait("더 빨리 스윙하세요", Locale.KOREAN)
                if (isTraining) {
                    withContext(Dispatchers.Main) { scheduleNextOrFinish(false) }
                }
            }

            else -> {
                currentPitchRecord["판정"] = "스트라이크(무스윙)"
                currentPitchRecord["피드백"] = "더 빨리 스윙하세요"
                withContext(Dispatchers.Main) {
                    perPitchRecords.add(HashMap(currentPitchRecord))
                    ballTrackView?.reset()
                    ttsManager.speak("스트라이크")
                    strikeCount++
                    tvStatus?.text = "스트라이크!"
                    tvStatus?.setTextColor(0xFFF87171.toInt())
                    tvResult?.text = "타격 윈도우 후 스윙 / 무스윙"
                    updateSimpleStatus("스트라이크!", 0xFF7F1D1D.toInt())
                    if (isAdmin && !isTraining) {
                        showSwingGraph()
                        btnStart?.isEnabled = true
                        btnStart?.text = "다시하기"
                    }
                }
                delay(1000L)
                ttsManager.speakAndWait("더 빨리 스윙하세요", Locale.KOREAN)
                if (isTraining) {
                    withContext(Dispatchers.Main) { scheduleNextOrFinish(false) }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────
// 베이스 도착음 — Resonance Audio HRTF 3D 음향
// ─────────────────────────────────────────────────────
internal fun SwingTestActivity.startBaseBeep() {
    audioJob?.cancel()
    spatialAudio.gainCoeff = 0.092f
    val dirX = if (targetBase == 3) -5f else 5f
    spatialAudio.updateBallPosition(dirX, 0f, -1f)
    spatialAudio.updateHeading(headTracker.currentHeadingDeg)
    spatialAudio.startContinuousBeep()
    audioJob = scope.launch {
        while (isActive) {
            spatialAudio.updateHeading(headTracker.currentHeadingDeg)
            delay(16)
        }
    }
    beepStartTime = SystemClock.elapsedRealtimeNanos()

    val applyTimeout = !tutorialManager.isRunning ||
            tutorialManager.currentStep == 6
    if (applyTimeout) {
        baseBeepTimeoutJob?.cancel()
        baseBeepTimeoutJob = scope.launch {
            delay(10_000L)
            withContext(Dispatchers.Main) { onBaseBeepTimeout() }
        }
    }
}

// ─────────────────────────────────────────────────────
// 베이스 선택 처리
// ─────────────────────────────────────────────────────
internal fun SwingTestActivity.onBasePressed(pressedBase: Int) {
    if (!isWaitingForInput) return
    baseBeepTimeoutJob?.cancel()
    baseBeepTimeoutJob = null
    isWaitingForInput = false
    stopAudio()
    gameJob?.cancel()
    resetBaseVisuals()
    ballTrackView?.reset()

    if (tutorialManager.isRunning && tutorialManager.currentStep == 4) {
        val success = pressedBase == targetBase
        scope.launch {
            if (success) {
                val ms = (SystemClock.elapsedRealtimeNanos() - beepStartTime) / 1_000_000L
                ttsManager.speakAndWait(
                    "정답입니다. 반응속도 %.1f초입니다.".format(ms / 1000.0),
                    Locale.KOREAN
                )
            } else {
                ttsManager.speakAndWait("틀렸습니다. 소리 방향의 버튼을 누르세요.", Locale.KOREAN)
            }
            tutorialManager.notifyStep4Done()
        }
        return
    }
    if (tutorialManager.isRunning && tutorialManager.currentStep == 5) {
        val success = pressedBase == targetBase
        scope.launch {
            if (success) {
                val ms = (SystemClock.elapsedRealtimeNanos() - beepStartTime) / 1_000_000L
                ttsManager.speakAndWait(
                    "정답입니다. 반응속도 %.1f초입니다.".format(ms / 1000.0),
                    Locale.KOREAN
                )
            } else {
                ttsManager.speakAndWait("틀렸습니다.", Locale.KOREAN)
            }
            tutorialManager.notifyStep5Done()
        }
        return
    }

    val success = pressedBase == targetBase
    currentPitchRecord["선택베이스"]     = pressedBase
    currentPitchRecord["베이스정답여부"] = success
    audioTrack?.stop()

    if (success) {
        val ms = (SystemClock.elapsedRealtimeNanos() - beepStartTime) / 1_000_000L
        currentPitchRecord["주루반응속도"] = ms
        currentPitchRecord["피드백"]       = "정타 — ${pressedBase}루 정답 (반응속도 ${ms}ms)"
        reactionTimes.add(ms)
        tvStatus?.text = "성공!"
        tvStatus?.setTextColor(0xFF4ADE80.toInt())
        val sec = ms / 1000.0
        tvResult?.text = "%.2f 초".format(sec)
        ttsManager.speak("성공, 반응속도 %.1f초".format(sec))
        updateSimpleStatus("성공!", 0xFF166534.toInt())
    } else {
        currentPitchRecord["피드백"] = "정타 — ${pressedBase}루 오답 (목표: ${targetBase}루)"
        tvStatus?.text = "알맞지 않은\n베이스 선택입니다"
        tvStatus?.setTextColor(0xFFF87171.toInt())
        tvResult?.text = ""
        if (isAdmin) Toast.makeText(this, "알맞지 않은 베이스 선택입니다", Toast.LENGTH_SHORT).show()
        ttsManager.speak("베이스 선택이 틀렸습니다")
        updateSimpleStatus("오답", 0xFF7F1D1D.toInt())
    }

    if (perPitchRecords.isNotEmpty()) {
        val last = perPitchRecords.last()
        last["피드백"]         = currentPitchRecord["피드백"] ?: ""
        last["선택베이스"]     = currentPitchRecord["선택베이스"] ?: 0
        last["베이스정답여부"] = currentPitchRecord["베이스정답여부"] ?: false
        if (success) last["주루반응속도"] = currentPitchRecord["주루반응속도"] ?: 0L
    } else {
        perPitchRecords.add(HashMap(currentPitchRecord))
    }

    if (isTraining) {
        scope.launch {
            delay(4000)
            withContext(Dispatchers.Main) { scheduleNextOrFinish(success) }
        }
    } else if (isAdmin) {
        showSwingGraph()
        btnStart?.isEnabled = true
        btnStart?.text = "다시하기"
    }
}

// ─────────────────────────────────────────────────────
// 베이스 선택 10초 타임아웃 처리
// ─────────────────────────────────────────────────────
internal fun SwingTestActivity.onBaseBeepTimeout() {
    if (!isWaitingForInput) return
    isWaitingForInput = false
    stopAudio()
    gameJob?.cancel()
    resetBaseVisuals()
    ballTrackView?.reset()

    currentPitchRecord["선택베이스"]     = 0
    currentPitchRecord["베이스정답여부"] = false
    currentPitchRecord["피드백"]         = "정타 — 주루 선택 시간 초과"
    if (perPitchRecords.isNotEmpty()) {
        val last = perPitchRecords.last()
        last["피드백"]         = currentPitchRecord["피드백"] ?: ""
        last["선택베이스"]     = 0
        last["베이스정답여부"] = false
    } else {
        perPitchRecords.add(HashMap(currentPitchRecord))
    }

    tvStatus?.text = "시간 초과"
    tvStatus?.setTextColor(0xFFF87171.toInt())
    tvResult?.text = ""
    updateSimpleStatus("시간 초과", 0xFF7F1D1D.toInt())

    scope.launch {
        ttsManager.speakAndWait("주루 선택 시간이 초과되었습니다.", Locale.KOREAN)
        delay(1_000L)
        withContext(Dispatchers.Main) {
            earlyFinishTraining(speakTts = true)
        }
    }
}

// ─────────────────────────────────────────────────────
// 훈련 흐름 제어
// ─────────────────────────────────────────────────────
internal fun SwingTestActivity.scheduleNextOrFinish(success: Boolean) {
    if (!isTraining) return
    if (tutorialManager.isRunning) {
        if (tutorialManager.currentStep == 6 && success) successCount++
        if (currentPitchNum >= targetPitches) {
            when (tutorialManager.currentStep) {
                4    -> { isTraining = false; tutorialManager.notifyStep4Done() }
                6    -> tutorialManager.notifyStep6Done(hitCount, foulCount, strikeCount)
                else -> { isTraining = false; tutorialManager.notifyStep4Done() }
            }
        } else {
            launchNextTrainingPitch()
        }
        return
    }
    if (success) successCount++
    if (currentPitchNum >= targetPitches) finishTraining()
    else launchNextTrainingPitch()
}

internal fun SwingTestActivity.launchNextTrainingPitch() {
    currentPitchNum++
    tvSwingPitchProgress?.text = "${currentPitchNum}/${targetPitches}"
    startGame()
    if (isAdmin) resetAndShowLiveGraphs()
}

// ─────────────────────────────────────────────────────
// 비관리자 훈련 시작
// ─────────────────────────────────────────────────────
internal fun SwingTestActivity.startSimpleTraining() {
    if (tutorialManager.isRunning) return
    btAudioLost      = false
    headTracker.reset()
    isTraining       = true
    currentPitchNum  = 1
    successCount     = 0
    hitCount         = 0
    foulCount        = 0
    strikeCount      = 0
    reactionTimes.clear()
    perPitchRecords.clear()
    tutorialManager.targetPitchCount = targetPitches
    startGame()
}

// ─────────────────────────────────────────────────────
// 튜토리얼 3단계: 배트 각도 체험
// ─────────────────────────────────────────────────────
internal fun SwingTestActivity.startAngleCalibration() {
    angleCalibJob?.cancel()
    angleCalibJob = scope.launch {
        if (bleConnected) {
            bleManager.sendControl(0)
            delay(200L)
            bleManager.sendControl(1)
        }
        delay(300)

        outer@ while (isActive) {
            val angle = currentPitchDeg
            val diff  = angle - BATTING_ANGLE_DEG
            val nowInRange = abs(diff) <= PITCH_TOLERANCE

            if (!nowInRange) {
                val direction = if (diff > 0) "배트를 더 내리세요." else "배트를 조금 올리세요."
                ttsManager.speakAndWait("현재 ${angle.toInt()}도. $direction", Locale.KOREAN, speechRate = 2.0f)
                if (bleConnected) bleManager.sendControl(1)
                delay(300)
            } else {
                ttsManager.speakAndWait(
                    "목표 각도 ${angle.toInt()}도입니다. 이 자세를 유지하세요.",
                    Locale.KOREAN, speechRate = 2.0f
                )
                if (bleConnected) bleManager.sendControl(1)

                for (i in 5 downTo 1) {
                    if (abs(currentPitchDeg - BATTING_ANGLE_DEG) > PITCH_TOLERANCE) {
                        val curr = currentPitchDeg
                        val d    = curr - BATTING_ANGLE_DEG
                        val dir  = if (d > 0) "배트를 더 내리세요." else "배트를 조금 올리세요."
                        ttsManager.speakAndWait(
                            "범위를 벗어났습니다. 현재 ${curr.toInt()}도. $dir",
                            Locale.KOREAN, speechRate = 2.0f
                        )
                        if (bleConnected) bleManager.sendControl(1)
                        delay(300)
                        continue@outer
                    }
                    ttsManager.speakAndWait("$i", Locale.KOREAN, speechRate = 2.0f)
                }

                if (abs(currentPitchDeg - BATTING_ANGLE_DEG) <= PITCH_TOLERANCE) {
                    ttsManager.speakAndWait("잘 하셨습니다. 스윙할 때 이 각도로 스윙하면 됩니다.", Locale.KOREAN, speechRate = 2.0f)
                    delay(1000)
                    if (bleConnected) bleManager.sendControl(0)
                    tutorialManager.notifyStep3Done()
                    return@launch
                }
                if (bleConnected) bleManager.sendControl(1)
                delay(300)
            }
        }
        if (bleConnected) bleManager.sendControl(0)
    }
}
