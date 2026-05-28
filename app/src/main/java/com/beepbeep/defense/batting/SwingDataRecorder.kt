package com.beepbeep.defense.batting

import android.content.Context
import android.util.Log
import com.beepbeep.defense.PendingUploadManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import java.util.Locale

// ─────────────────────────────────────────────────────
// 세션 데이터 저장 및 Firebase 업로드
// ─────────────────────────────────────────────────────

internal fun SwingTestActivity.finishTraining() {
    if (tutorialManager.isRunning) {
        isTraining = false
        audioTrack?.stop()
        if (tutorialManager.currentStep == 6) {
            tutorialManager.notifyStep6Done(hitCount, foulCount, strikeCount)
        }
        return
    }
    isTraining = false
    tvStatus?.text = "훈련 완료!"
    tvStatus?.setTextColor(0xFF4ADE80.toInt())
    tvResult?.text             = ""
    tvSwingPitchProgress?.text = ""
    btnStart?.isEnabled        = false
    btnStart?.text             = "다시 훈련"
    btnSwingPitchMinus?.isEnabled = false
    btnSwingPitchPlus?.isEnabled  = false
    audioTrack?.stop()
    updateSimpleStatus("훈련 완료!")

    val battingAvgPct     = if (targetPitches > 0) (hitCount.toFloat() / targetPitches * 100).toInt() else 0
    val avgReactionForTts = if (reactionTimes.isNotEmpty()) reactionTimes.average().toLong() else -1L
    val ttsText = buildString {
        append("훈련 완료. ")
        append("정타 ${hitCount}개, 타율 ${battingAvgPct}퍼센트. ")
        if (avgReactionForTts >= 0L) append("평균 반응속도 %.1f초.".format(avgReactionForTts / 1000.0))
    }
    isResultSpeaking = true
    ttsManager.speakWithDone(ttsText) {
        isResultSpeaking = false
        runOnUiThread {
            btnStart?.isEnabled           = true
            btnSwingPitchMinus?.isEnabled = true
            btnSwingPitchPlus?.isEnabled  = true
        }
    }

    val battingAvg     = if (targetPitches > 0) hitCount.toFloat() / targetPitches else 0f
    val avgReaction    = if (reactionTimes.isNotEmpty()) reactionTimes.average().toLong() else -1L
    val baseCorrectPct = if (hitCount > 0) successCount.toFloat() / hitCount * 100f else 0f
    val userId         = getSharedPreferences("UserInfo", Context.MODE_PRIVATE).getString("id", "anonymous") ?: "anonymous"
    val db             = FirebaseFirestore.getInstance()
    val sessionMillis  = System.currentTimeMillis()
    val sessionId      = sessionMillis.toString()

    val sessionData = hashMapOf(
        "생성일시"   to Timestamp.now(),
        "목표투구수" to targetPitches,
        "허용오차"   to PITCH_TOLERANCE,
        "종합결과"   to hashMapOf(
            "정타수"       to hitCount,
            "파울수"       to foulCount,
            "스트라이크수" to strikeCount,
            "타율"         to battingAvg,
            "베이스정답수" to successCount,
            "베이스정답률" to baseCorrectPct,
            "반응속도평균" to avgReaction,
            "반응속도최소" to (reactionTimes.minOrNull() ?: -1L),
            "반응속도최대" to (reactionTimes.maxOrNull() ?: -1L)
        )
    )
    val recordsSnapshot = perPitchRecords.toList()
    val sessionRef = db.collection("users").document(userId).collection("훈련기록").document(sessionId)
    sessionRef.set(sessionData)
        .addOnSuccessListener {
            recordsSnapshot.forEachIndexed { index, record ->
                sessionRef.collection("투구별기록").document("${index + 1}번투구").set(record)
            }
        }
        .addOnFailureListener { e ->
            Log.e("Firebase", "업로드 실패: ${e.message}")
            PendingUploadManager.saveBatting(
                context         = this,
                userId          = userId,
                sessionId       = sessionId,
                sessionMillis   = sessionMillis,
                sessionData     = sessionData,
                perPitchRecords = recordsSnapshot
            )
        }

    updateTrainingStats(userId, battingAvg, baseCorrectPct, avgReaction)

    if (isAdmin) showTrainingSummary()
}

internal fun SwingTestActivity.earlyFinishTraining(speakTts: Boolean, showSummary: Boolean = true) {
    if (!isTraining) return
    isTraining = false

    baseBeepTimeoutJob?.cancel()
    baseBeepTimeoutJob = null

    if (tutorialManager.isRunning && tutorialManager.currentStep == 6) {
        gameJob?.cancel()
        ttsManager.stop()
        stopAudio()
        spatialAudio.stopBeep()
        audioTrack?.stop()
        isRecording          = false
        isWaitingForInput    = false
        hitWindowActive      = false
        preWindowActive      = false
        postWindowActive     = false
        minAngleSearchActive = false
        tutorialManager.notifyStep6Done(hitCount, foulCount, strikeCount)
        return
    }

    gameJob?.cancel()
    ttsManager.stop()
    stopAudio()
    spatialAudio.stopBeep()
    audioTrack?.stop()
    isRecording           = false
    isWaitingForInput     = false
    hitWindowActive       = false
    preWindowActive       = false
    postWindowActive      = false
    minAngleSearchActive  = false

    val actualPitches = perPitchRecords.size
    if (actualPitches > 0) {
        val battingAvg        = hitCount.toFloat() / actualPitches
        val avgReaction       = if (reactionTimes.isNotEmpty()) reactionTimes.average().toLong() else -1L
        val baseCorrectPct    = if (hitCount > 0) successCount.toFloat() / hitCount * 100f else 0f
        val battingAvgPct     = (battingAvg * 100).toInt()

        if (showSummary) {
            val ttsText = buildString {
                if (speakTts) append("조기종료. ")
                append("정타 ${hitCount}개, 타율 ${battingAvgPct}퍼센트. ")
                if (avgReaction >= 0L) append("평균 반응속도 %.1f초.".format(avgReaction / 1000.0))
            }
            isResultSpeaking = true
            ttsManager.speakWithDone(ttsText) {
                isResultSpeaking = false
                runOnUiThread {
                    btnStart?.isEnabled           = true
                    btnSwingPitchMinus?.isEnabled = true
                    btnSwingPitchPlus?.isEnabled  = true
                }
            }
        }

        val userId       = getSharedPreferences("UserInfo", Context.MODE_PRIVATE).getString("id", "anonymous") ?: "anonymous"
        val db           = FirebaseFirestore.getInstance()
        val sessionMillis = System.currentTimeMillis()
        val sessionId    = sessionMillis.toString()
        val sessionData = hashMapOf(
            "생성일시"   to Timestamp.now(),
            "목표투구수" to targetPitches,
            "실제투구수" to actualPitches,
            "조기종료"   to true,
            "허용오차"   to PITCH_TOLERANCE,
            "종합결과"   to hashMapOf(
                "정타수"       to hitCount,
                "파울수"       to foulCount,
                "스트라이크수" to strikeCount,
                "타율"         to battingAvg,
                "베이스정답수" to successCount,
                "베이스정답률" to baseCorrectPct,
                "반응속도평균" to avgReaction,
                "반응속도최소" to (reactionTimes.minOrNull() ?: -1L),
                "반응속도최대" to (reactionTimes.maxOrNull() ?: -1L)
            )
        )
        val recordsSnapshot = perPitchRecords.toList()
        val sessionRef = db.collection("users").document(userId).collection("훈련기록").document(sessionId)
        sessionRef.set(sessionData)
            .addOnSuccessListener {
                recordsSnapshot.forEachIndexed { index, record ->
                    sessionRef.collection("투구별기록").document("${index + 1}번투구").set(record)
                }
            }
            .addOnFailureListener { e ->
                Log.e("Firebase", "조기종료 업로드 실패: ${e.message}")
                PendingUploadManager.saveBatting(
                    context         = this,
                    userId          = userId,
                    sessionId       = sessionId,
                    sessionMillis   = sessionMillis,
                    sessionData     = sessionData,
                    perPitchRecords = recordsSnapshot
                )
            }

        updateTrainingStats(userId, battingAvg, baseCorrectPct, avgReaction)
    } else if (speakTts) {
        isResultSpeaking = true
        ttsManager.speakWithDone("조기종료") {
            isResultSpeaking = false
            runOnUiThread {
                btnStart?.isEnabled           = true
                btnSwingPitchMinus?.isEnabled = true
                btnSwingPitchPlus?.isEnabled  = true
            }
        }
    }

    val ttsWillPlay = (actualPitches > 0 && showSummary) || (actualPitches == 0 && speakTts)
    runOnUiThread {
        tvStatus?.text = if (speakTts) "조기 종료" else ""
        if (speakTts) tvStatus?.setTextColor(0xFFF87171.toInt())
        tvResult?.text             = ""
        tvSwingPitchProgress?.text = ""
        btnStart?.isEnabled        = false
        btnStart?.text             = "다시 훈련"
        btnSwingPitchMinus?.isEnabled = false
        btnSwingPitchPlus?.isEnabled  = false
        updateSimpleStatus(if (speakTts) "조기종료" else "")
        resetBaseVisuals()
        ballTrackView?.reset()
        if (isAdmin && actualPitches > 0 && showSummary) {
            showTrainingSummary(
                displayCount = actualPitches,
                dialogTitle  = "조기 종료 결과 (${actualPitches}/${targetPitches}회)"
            )
        } else if (!ttsWillPlay) {
            btnStart?.isEnabled           = true
            btnSwingPitchMinus?.isEnabled = true
            btnSwingPitchPlus?.isEnabled  = true
        }
    }
}

private fun SwingTestActivity.updateTrainingStats(
    userId: String,
    battingAvg: Float,
    baseCorrectPct: Float,
    avgReaction: Long
) {
    val statsPref = getSharedPreferences("TrainingStats_$userId", Context.MODE_PRIVATE)
    val editor    = statsPref.edit()
    editor.putInt  ("count",               minOf(statsPref.getInt("count", 0) + 1, 10))
    editor.putFloat("sum_batting_avg",      statsPref.getFloat("sum_batting_avg",      0f) + battingAvg)
    editor.putFloat("sum_base_correct_pct", statsPref.getFloat("sum_base_correct_pct", 0f) + baseCorrectPct)
    editor.putFloat("sum_reaction",         statsPref.getFloat("sum_reaction",         0f) + avgReaction.toFloat())
    editor.putFloat("sum_hit",              statsPref.getFloat("sum_hit",              0f) + hitCount.toFloat())
    editor.putFloat("sum_foul",             statsPref.getFloat("sum_foul",             0f) + foulCount.toFloat())
    editor.putFloat("sum_strike",           statsPref.getFloat("sum_strike",           0f) + strikeCount.toFloat())
    editor.putFloat("sum_base_correct",     statsPref.getFloat("sum_base_correct",     0f) + successCount.toFloat())
    val totalCount = statsPref.getInt("total_count", 0) + 1
    editor.putInt  ("total_count",                totalCount)
    editor.putFloat("total_sum_batting_avg",      statsPref.getFloat("total_sum_batting_avg",      0f) + battingAvg)
    editor.putFloat("total_sum_base_correct_pct", statsPref.getFloat("total_sum_base_correct_pct", 0f) + baseCorrectPct)
    editor.putFloat("total_sum_reaction",         statsPref.getFloat("total_sum_reaction",         0f) + avgReaction.toFloat())
    editor.putFloat("total_sum_hit",              statsPref.getFloat("total_sum_hit",              0f) + hitCount.toFloat())
    editor.putFloat("total_sum_foul",             statsPref.getFloat("total_sum_foul",             0f) + foulCount.toFloat())
    editor.putFloat("total_sum_strike",           statsPref.getFloat("total_sum_strike",           0f) + strikeCount.toFloat())
    editor.putFloat("total_sum_base_correct",     statsPref.getFloat("total_sum_base_correct",     0f) + successCount.toFloat())
    editor.apply()
}
