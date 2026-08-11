package com.beepbeep.defense.batting

import android.app.AlertDialog
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.beepbeep.defense.R
import kotlin.math.*

// ─────────────────────────────────────────────────────
// UI 갱신 메서드 (관리자/비관리자 공통)
// ─────────────────────────────────────────────────────

internal fun SwingTestActivity.updateSimpleStatus(text: String, bgColor: Int = 0xFF0A0A0A.toInt()) {
    // 비관리자 상태 텍스트 — 현재 UI에 별도 처리 없음 (확장 가능)
}

internal fun SwingTestActivity.updateSimpleBleStatus(connected: Boolean, battery: Int = -1) {
    if (isAdmin) return
    runOnUiThread {
        val icon   = findViewById<TextView>(R.id.tvSimpleBleIcon)
        val status = findViewById<TextView>(R.id.tvSimpleBleStatus)
        val bat    = findViewById<TextView>(R.id.tvSimpleBattery)
        if (connected) {
            icon?.text  = "●"; icon?.setTextColor(0xFF4ADE80.toInt())
            status?.text = "배트 연결됨"; status?.setTextColor(0xFF4ADE80.toInt())
            bat?.text    = if (battery >= 0) "배터리  $battery%" else ""
            bat?.setTextColor(0xFFFFFFFF.toInt())
        } else {
            icon?.text  = "●"; icon?.setTextColor(0xFFF87171.toInt())
            status?.text = "배트 미연결"; status?.setTextColor(0xFFF87171.toInt())
            bat?.text    = ""
        }
    }
}

internal fun SwingTestActivity.activateBothBases() {
    base1Glow?.visibility = View.VISIBLE; base3Glow?.visibility = View.VISIBLE
    tvBase1Label?.setTextColor(0xFF4ADE80.toInt()); tvBase3Label?.setTextColor(0xFF4ADE80.toInt())
}

internal fun SwingTestActivity.resetBaseVisuals() {
    base1Glow?.visibility = View.INVISIBLE; base3Glow?.visibility = View.INVISIBLE
    tvBase1Label?.setTextColor(0xFF94A3B8.toInt()); tvBase3Label?.setTextColor(0xFF94A3B8.toInt())
}

internal fun SwingTestActivity.resetAndShowLiveGraphs() {
    pitchHistory.clear()
    hitTimeRelMs        = -1L
    pitchRecordStart    = System.currentTimeMillis()
    bleGraphStarted     = false
    bleGraphPacketCount = 0L
    isRecording         = true
    swingGraphView?.setLiveSource(pitchHistory, BATTING_ANGLE_DEG)
    swingGraphView?.setTolerance(PITCH_TOLERANCE)
    swingGraphView?.visibility = View.VISIBLE
}

internal fun SwingTestActivity.showSwingGraph() {
    val history = ArrayList(pitchHistory)
    if (history.isEmpty()) return
    val contactH = BATTER_HEIGHT + sin(BATTING_ANGLE_DEG * PI.toFloat() / 180f) * BAT_REACH
    val batH = when {
        !swingBatHeight.isNaN() -> swingBatHeight
        swingDetected           -> BATTER_HEIGHT + sin(swingPitchDeg * PI.toFloat() / 180f) * BAT_REACH
        else                    -> contactH
    }
    val heightPx     = (300 * resources.displayMetrics.density).toInt()
    val parabolaView = BallParabolaView(this).apply { setData(PITCHER_DIST, PITCHER_HEIGHT, contactH, batH, BALL_ARC, swingIsHit) }
    val graphView    = SwingGraphView(this).apply {
        setData(history, hitTimeRelMs, BATTING_ANGLE_DEG); setTolerance(PITCH_TOLERANCE)
        setWindowAngles(windowOpenPitchDeg, windowClosePitchDeg)
        if (graphPitchWindowStartMs  >= 0L) setPitchWindowStart(graphPitchWindowStartMs)
        if (graphHitWindowOpenRelMs  >= 0L) setHitWindowOpen(graphHitWindowOpenRelMs)
        if (graphHitWindowCloseRelMs >= 0L) setHitWindowClose(graphHitWindowCloseRelMs)
        if (graphMinSearchStartRelMs >= 0L) setMinAngleSearch(graphMinSearchStartRelMs)
        if (minBatAngleAbsMs > 0L && minBatAngleDeg < Float.MAX_VALUE) setMinAngle(minBatAngleRelMs, minBatAngleDeg)
    }
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0A1423.toInt())
        addView(parabolaView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx))
        addView(graphView,    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx))
    }
    AlertDialog.Builder(this).setTitle("스윙 결과").setView(ScrollView(this).apply { addView(container) }).setPositiveButton("확인", null).show()
}

internal fun SwingTestActivity.showTrainingSummary(
    displayCount: Int = targetPitches,
    dialogTitle: String = "훈련 종합 결과"
) {
    val battingAvg     = if (displayCount > 0) hitCount.toFloat() / displayCount else 0f
    val avgReaction    = if (reactionTimes.isNotEmpty()) reactionTimes.average().toLong() else -1L
    val baseCorrectPct = if (hitCount > 0) successCount.toFloat() / hitCount * 100f else 0f

    val summaryText = buildString {
        appendLine("총 타석         ${displayCount}회")
        appendLine()
        appendLine("정타 (볼 맞힘)   ${hitCount}회")
        appendLine("파울            ${foulCount}회")
        appendLine("스트라이크      ${strikeCount}회")
        appendLine()
        appendLine("베이스 정답     ${successCount}회")
        if (hitCount > 0) appendLine("베이스 정답률   ${"%.0f".format(baseCorrectPct)}%  (${successCount}/${hitCount})")
        appendLine()
        appendLine("타율            ${"%.3f".format(battingAvg)}  (${hitCount}/${displayCount})")
        appendLine()
        if (avgReaction >= 0L) {
            appendLine("주루 반응속도 평균   ${avgReaction} ms")
            if (reactionTimes.size > 1) appendLine("  최소 ${reactionTimes.minOrNull()} ms  /  최대 ${reactionTimes.maxOrNull()} ms")
        } else {
            appendLine("주루 반응속도   기록 없음")
        }
        appendLine()
        appendLine("── SET 초기 각도 ──")
        allSetAngles.forEachIndexed { i, a -> appendLine("  #${i + 1}: ${"%.1f".format(a)}°") }
    }

    val totalData  = perPitchRecords.size
    val heightPx   = (340 * resources.displayMetrics.density).toInt()
    var currentIdx = 0

    val tv = TextView(this).apply {
        text = summaryText; textSize = 14f
        setTextColor(0xFFE2E8F0.toInt()); typeface = android.graphics.Typeface.MONOSPACE
        setBackgroundColor(0xFF0A1423.toInt()); setPadding(56, 40, 56, 24); setLineSpacing(0f, 1.3f)
    }
    val btnPrev = Button(this).apply { text = "◀"; textSize = 13f; setTextColor(0xFF64B4FF.toInt()) }
    val tvPitchNum = TextView(this).apply {
        textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); gravity = android.view.Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    val btnNext = Button(this).apply { text = "▶"; textSize = 13f; setTextColor(0xFF64B4FF.toInt()) }
    val navRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(40, 8, 40, 4); addView(btnPrev); addView(tvPitchNum); addView(btnNext)
    }
    val tvFeedback = TextView(this).apply {
        textSize = 14f; gravity = android.view.Gravity.CENTER; setPadding(56, 4, 56, 8)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val tvLabelCombined = TextView(this).apply {
        text = "READY 이후 배트 각도 궤적"; textSize = 13f
        setTextColor(0xFF93C5FD.toInt()); setPadding(56, 4, 56, 4)
    }
    val graphCombined = SwingGraphView(this)

    fun updateGraphs(idx: Int) {
        val fullHist    = if (idx < perPitchFullHistory.size)          perPitchFullHistory[idx]          else ArrayList()
        val hitTime     = if (idx < perPitchHitTimeRelFull.size)       perPitchHitTimeRelFull[idx]       else -1L
        val winOpen     = if (idx < perPitchWinOpenRelFull.size)       perPitchWinOpenRelFull[idx]       else -1L
        val winClose    = if (idx < perPitchWinCloseRelFull.size)      perPitchWinCloseRelFull[idx]      else -1L
        val minAngleRel = if (idx < perPitchMinAngleRelFull.size)      perPitchMinAngleRelFull[idx]      else -1L
        val minDeg      = if (idx < perPitchMinAngleDegList.size)      perPitchMinAngleDegList[idx]      else Float.MAX_VALUE
        val pitchWinSt  = if (idx < perPitchPitchWinStartRelFull.size) perPitchPitchWinStartRelFull[idx] else -1L
        val pitchTtsSt  = if (idx < perPitchPitchTtsStartRelFull.size) perPitchPitchTtsStartRelFull[idx] else -1L
        val phase2Rel   = if (idx < perPitchPhase2RelFull.size)        perPitchPhase2RelFull[idx].coerceAtLeast(0L) else 0L
        val winOpenDeg  = if (idx < perPitchWinOpenDeg.size)           perPitchWinOpenDeg[idx]           else 0f
        val winCloseDeg = if (idx < perPitchWinCloseDeg.size)          perPitchWinCloseDeg[idx]          else 0f

        val record   = perPitchRecords.getOrNull(idx)
        val judgment = record?.get("판정")  as? String ?: ""
        val feedback = record?.get("피드백") as? String ?: ""
        val (jc, fc) = when {
            judgment.startsWith("정타")       -> 0xFF4ADE80.toInt() to 0xFF86EFAC.toInt()
            judgment.startsWith("파울")       -> 0xFFFBBF24.toInt() to 0xFFFDE68A.toInt()
            judgment.startsWith("스트라이크") -> 0xFFF87171.toInt() to 0xFFFCA5A5.toInt()
            else                              -> 0xFFFFFFFF.toInt() to 0xFFAAAAAA.toInt()
        }
        tvPitchNum.text = "${idx + 1} / $totalData   [$judgment]"
        tvPitchNum.setTextColor(jc)
        tvFeedback.text = "💬 $feedback"
        tvFeedback.setTextColor(fc)

        val segCombined = ArrayList(fullHist.filter { it.first >= phase2Rel }
            .map { Pair(it.first - phase2Rel, it.second) })
        fun toCombined(ms: Long) = ms - phase2Rel
        val cHit        = if (hitTime     >= 0L) toCombined(hitTime)              else -1L
        val cWinOpen    = if (winOpen     >= 0L) maxOf(0L, toCombined(winOpen))   else -1L
        val cWinClose   = if (winClose    >= 0L) toCombined(winClose)             else -1L
        val cMinAngle   = if (minAngleRel >= 0L) toCombined(minAngleRel)          else -1L
        val cPitchTtsSt = if (pitchTtsSt  >= 0L) toCombined(pitchTtsSt)          else -1L
        val cPitchWin   = if (pitchWinSt  >= 0L) toCombined(pitchWinSt)          else -1L

        graphCombined.setData(segCombined, cHit, BATTING_ANGLE_DEG)
        graphCombined.setTolerance(PITCH_TOLERANCE)
        graphCombined.setWindowAngles(winOpenDeg, winCloseDeg)
        if (cPitchTtsSt >= 0L) graphCombined.setPitchTtsStart(cPitchTtsSt)
        if (cPitchWin   >= 0L) graphCombined.setPitchWindowStart(cPitchWin)
        if (cWinOpen    >= 0L) graphCombined.setHitWindowOpen(cWinOpen)
        if (cWinClose   >= 0L) graphCombined.setHitWindowClose(cWinClose)
        if (cMinAngle   >= 0L && minDeg < Float.MAX_VALUE) graphCombined.setMinAngle(cMinAngle, minDeg)
        btnPrev.isEnabled = idx > 0
        btnNext.isEnabled = idx < totalData - 1
    }

    if (totalData > 0) updateGraphs(0) else tvPitchNum.text = "0 / 0"
    btnPrev.setOnClickListener { if (currentIdx > 0) { currentIdx--; updateGraphs(currentIdx) } }
    btnNext.setOnClickListener { if (currentIdx < totalData - 1) { currentIdx++; updateGraphs(currentIdx) } }

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0A1423.toInt())
        addView(tv); addView(navRow); addView(tvFeedback); addView(tvLabelCombined)
        addView(graphCombined, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx))
    }
    val scroll = ScrollView(this).apply { setBackgroundColor(0xFF0A1423.toInt()); addView(container) }
    lastResultDialog = AlertDialog.Builder(this).setTitle(dialogTitle).setView(scroll).setPositiveButton("확인", null).create()
    lastResultDialog?.show()
    btnResultView?.visibility = View.VISIBLE
    if (totalData > 0) {
        graphCombined.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                graphCombined.viewTreeObserver.removeOnGlobalLayoutListener(this)
                graphCombined.invalidate()
            }
        })
    }
}

internal fun SwingTestActivity.initAudioTrack() {
    if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
        try { audioTrack?.stop() } catch (_: IllegalStateException) {}
    }
    audioTrack?.release()
    audioTrack = null

    val sr     = 44100
    val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
    val newTrack = try {
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(minBuf * 4).setTransferMode(AudioTrack.MODE_STREAM).build()
    } catch (_: Exception) { null }

    if (newTrack?.state == AudioTrack.STATE_INITIALIZED) {
        audioTrack = newTrack
        audioTrack?.play()
    } else {
        newTrack?.release()
    }
}
