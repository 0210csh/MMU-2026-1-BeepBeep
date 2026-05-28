package com.beepbeep.defense

import android.content.Context
import com.google.firebase.firestore.Query

// ─────────────────────────────────────────────────────
// 데이터 모델
// ─────────────────────────────────────────────────────

data class BattingPitch(
    val result: String,
    val baseCorrect: Boolean?,
    val reactionMs: Long?,
    val date: java.util.Date?
)

data class DefenseRecord(
    val success: Boolean,
    val reactionMs: Long,
    val date: java.util.Date?
)

data class BattingBundle(
    val index: Int,
    val label: String,
    val ttsLabel: String,
    val pitchCount: Int,
    val isPartial: Boolean,
    val battingAvg: Double,
    val reactionAvg: Double?,
    val hitAvg: Double,
    val foulAvg: Double,
    val strikeAvg: Double,
    val baseCorrectAvg: Double?
)

data class DefenseBundle(
    val index: Int,
    val label: String,
    val ttsLabel: String,
    val pitchCount: Int,
    val isPartial: Boolean,
    val successRate: Double,
    val reactionAvg: Double?,
    val successAvg: Double,
    val failAvg: Double
)

data class PitchUnit<T>(val session: T, val date: java.util.Date?)

// ─────────────────────────────────────────────────────
// Firebase 로드 & 데이터 처리
// ─────────────────────────────────────────────────────

internal fun RecordActivity.loadBattingStatsFromFirebase(userId: String) {
    db.collection("users").document(userId).collection("훈련기록")
        .orderBy("생성일시", Query.Direction.ASCENDING)
        .get()
        .addOnSuccessListener { documents ->
            if (documents.isEmpty) { setBattingEmpty(); return@addOnSuccessListener }
            val sessionDocs = documents.filter { it.getTimestamp("생성일시") != null }
            if (sessionDocs.isEmpty()) { setBattingEmpty(); return@addOnSuccessListener }

            data class SessionPitches(val date: java.util.Date?, val pitches: List<BattingPitch>)
            val sessionPitchesList = mutableListOf<SessionPitches>()
            var completedCount = 0
            val targetCount = sessionDocs.size

            sessionDocs.forEach { doc ->
                val sessionDate = doc.getTimestamp("생성일시")?.toDate()
                doc.reference.collection("투구별기록")
                    .orderBy("투구번호", Query.Direction.ASCENDING)
                    .get()
                    .addOnSuccessListener { pitchDocs ->
                        if (!pitchDocs.isEmpty) {
                            val records = pitchDocs.mapNotNull { p ->
                                val result = p.getString("판정") ?: return@mapNotNull null
                                BattingPitch(
                                    result      = result,
                                    baseCorrect = p.getBoolean("베이스정답여부"),
                                    reactionMs  = (p.get("주루반응속도") as? Number)?.toLong(),
                                    date        = sessionDate
                                )
                            }
                            synchronized(sessionPitchesList) {
                                sessionPitchesList.add(SessionPitches(sessionDate, records))
                            }
                        }
                        synchronized(sessionPitchesList) { completedCount++ }
                        if (completedCount == targetCount) {
                            val allPitches = sessionPitchesList.sortedBy { it.date }.flatMap { it.pitches }
                            runOnUiThread { processBattingPitches(allPitches) }
                        }
                    }
                    .addOnFailureListener {
                        synchronized(sessionPitchesList) { completedCount++ }
                        if (completedCount == targetCount) {
                            val allPitches = sessionPitchesList.sortedBy { it.date }.flatMap { it.pitches }
                            runOnUiThread { processBattingPitches(allPitches) }
                        }
                    }
            }
        }
        .addOnFailureListener { setBattingEmpty() }
}

internal fun RecordActivity.processBattingPitches(pitches: List<BattingPitch>) {
    if (pitches.isEmpty()) { setBattingEmpty(); return }

    val totalPitches = pitches.size
    val hits         = pitches.filter { it.result == "정타" }
    val fouls        = pitches.filter { it.result == "파울" }
    val strikes      = pitches.filter { it.result.startsWith("스트라이크") }
    val baseCorrects = hits.filter { it.baseCorrect == true }
    val reactions    = hits.mapNotNull { it.reactionMs }

    val battingAvg     = hits.size.toDouble() / totalPitches
    val avgReaction    = if (reactions.isNotEmpty()) reactions.average() else 0.0
    val baseCorrectPct = if (hits.isNotEmpty()) baseCorrects.size.toDouble() / hits.size * 100 else 0.0

    binding.tvStatCount.text = "전체 ${totalPitches}구 기준"
    binding.tvStatCount.contentDescription = "전체 ${totalPitches}구 기준 평균입니다"
    binding.tvStatBattingAvg.text     = "%.3f".format(battingAvg)
    binding.tvStatReaction.text       = if (avgReaction > 0) msToSec(avgReaction) else "-"
    binding.tvStatHit.text            = "%.1f".format(hits.size.toDouble()         / totalPitches * 20)
    binding.tvStatFoul.text           = "%.1f".format(fouls.size.toDouble()        / totalPitches * 20)
    binding.tvStatStrike.text         = "%.1f".format(strikes.size.toDouble()      / totalPitches * 20)
    binding.tvStatBaseCorrect.text    = "%.1f".format(baseCorrects.size.toDouble() / totalPitches * 20)
    binding.tvStatBaseCorrectPct.text = "${"%.0f".format(baseCorrectPct)}%"

    val (rawBundles, remainingPitches) = splitIntoBundles20(
        pitches.reversed(), getCount = { 1 }, getDate = { it.date }
    )
    val completeBundles = rawBundles.mapIndexed { i, bundle ->
        makeBattingBundle(bundle, i + 1, false)
    }.toMutableList()
    if (remainingPitches.isNotEmpty()) {
        completeBundles.add(makeBattingBundle(remainingPitches, completeBundles.size + 1, true))
    }
    if (completeBundles.size >= 2) {
        battingChartIndex = 0
        showBattingGrowthChart(completeBundles)
    } else {
        binding.tvBattingGrowthSection.visibility = android.view.View.GONE
        binding.llBattingGrowthRows.visibility    = android.view.View.GONE
        val needed = maxOf(0, RecordActivity.BUNDLE_SIZE * 2 - totalPitches)
        binding.tvBattingGrowthNotice.apply {
            visibility = android.view.View.VISIBLE
            text = "📊 ${needed}구 더 훈련하면 성장 추이를 볼 수 있어요!"
            contentDescription = "${needed}구 더 훈련하면 성장 추이를 확인할 수 있습니다"
        }
    }
}

internal fun RecordActivity.makeBattingBundle(
    pitches: List<PitchUnit<BattingPitch>>,
    index: Int,
    isPartial: Boolean
): BattingBundle {
    val dates     = pitches.mapNotNull { it.date }
    val firstDate = dates.minOrNull()
    val lastDate  = dates.maxOrNull()
    val (label, ttsLabel) = makeDateLabels(firstDate, lastDate, index)
    val pc = pitches.size

    val hits         = pitches.filter { it.session.result == "정타" }
    val fouls        = pitches.filter { it.session.result == "파울" }
    val strikes      = pitches.filter { it.session.result.startsWith("스트라이크") }
    val baseCorrects = hits.filter { it.session.baseCorrect == true }
    val reactions    = hits.mapNotNull { it.session.reactionMs }

    val bBatAvg   = hits.size.toDouble() / pc
    val bReaction = if (reactions.isEmpty()) null else reactions.average()

    return BattingBundle(
        index          = index,
        label          = label,
        ttsLabel       = ttsLabel,
        pitchCount     = pc,
        isPartial      = isPartial,
        battingAvg     = roundDiff2(bBatAvg).toDouble(),
        reactionAvg    = bReaction?.let { roundDiff2(it / 1000.0).toDouble() * 1000.0 },
        hitAvg         = hits.size.toDouble(),
        foulAvg        = fouls.size.toDouble(),
        strikeAvg      = strikes.size.toDouble(),
        baseCorrectAvg = if (hits.isEmpty()) null else baseCorrects.size.toDouble()
    )
}

internal fun RecordActivity.loadDefenseStatsFromFirebase(userId: String) {
    db.collection("users").document(userId).collection("수비훈련기록")
        .orderBy("생성일시", Query.Direction.ASCENDING)
        .get()
        .addOnSuccessListener { documents ->
            if (documents.isEmpty) { setDefenseEmpty(); return@addOnSuccessListener }
            val sessionDocs = documents.filter { doc -> doc.getTimestamp("생성일시") != null }
            if (sessionDocs.isEmpty()) { setDefenseEmpty(); return@addOnSuccessListener }

            data class SessionCatches(val date: java.util.Date?, val catches: List<DefenseRecord>)
            val sessionCatchesList = mutableListOf<SessionCatches>()
            var completedCount = 0
            val targetCount = sessionDocs.size

            sessionDocs.forEach { doc ->
                val sessionDate = doc.getTimestamp("생성일시")?.toDate()
                doc.reference.collection("포구별기록")
                    .orderBy("회차", Query.Direction.ASCENDING)
                    .get()
                    .addOnSuccessListener { catches ->
                        if (!catches.isEmpty) {
                            val records = catches.mapNotNull { c ->
                                val success = (c.getString("결과") == "성공")
                                val reaction = (c.get("반응속도") as? Number)?.toLong() ?: -1L
                                DefenseRecord(success = success, reactionMs = reaction, date = sessionDate)
                            }
                            synchronized(sessionCatchesList) {
                                sessionCatchesList.add(SessionCatches(sessionDate, records))
                            }
                        }
                        synchronized(sessionCatchesList) { completedCount++ }
                        if (completedCount == targetCount) {
                            val allCatches = sessionCatchesList.sortedBy { it.date }.flatMap { it.catches }
                            runOnUiThread { processDefenseCatches(allCatches) }
                        }
                    }
                    .addOnFailureListener {
                        synchronized(sessionCatchesList) { completedCount++ }
                        if (completedCount == targetCount) {
                            val allCatches = sessionCatchesList.sortedBy { it.date }.flatMap { it.catches }
                            runOnUiThread { processDefenseCatches(allCatches) }
                        }
                    }
            }
        }
        .addOnFailureListener { setDefenseEmpty() }
}

internal fun RecordActivity.processDefenseCatches(catches: List<DefenseRecord>) {
    if (catches.isEmpty()) { setDefenseEmpty(); return }

    val totalCount   = catches.size
    val successList  = catches.filter { it.success }
    val totalSuccess = successList.size
    val totalFail    = totalCount - totalSuccess
    val successRate  = totalSuccess.toDouble() / totalCount * 100
    val avgReaction  = successList.filter { it.reactionMs > 0 }.let { f ->
        if (f.isEmpty()) 0.0 else f.sumOf { it.reactionMs.toDouble() } / f.size
    }

    binding.tvDefenseStatCount.text = "전체 ${totalCount}구 기준"
    binding.tvDefenseStatCount.contentDescription = "전체 ${totalCount}구 기준 평균입니다"
    binding.tvDefenseStatSuccessRate.text = "${"%.0f".format(successRate)}%"
    binding.tvDefenseStatReaction.text    = if (avgReaction > 0) msToSec(avgReaction) else "-"
    binding.tvDefenseStatSuccess.text = "%.1f".format(totalSuccess.toDouble() / totalCount * 20)
    binding.tvDefenseStatFail.text    = "%.1f".format(totalFail.toDouble() / totalCount * 20)

    val (rawBundles, remainingPitches) = splitIntoBundles20(
        catches.reversed(), getCount = { 1 }, getDate = { it.date }
    )
    val completeBundles = rawBundles.mapIndexed { i, pitches ->
        makeDefenseBundle(pitches, i + 1, false)
    }.toMutableList()
    if (remainingPitches.isNotEmpty()) {
        completeBundles.add(makeDefenseBundle(remainingPitches, completeBundles.size + 1, true))
    }
    if (completeBundles.size >= 2) {
        defenseChartIndex = 0
        showDefenseGrowthChart(completeBundles)
    } else {
        binding.tvDefenseGrowthSection.visibility = android.view.View.GONE
        binding.llDefenseGrowthRows.visibility    = android.view.View.GONE
        val needed = maxOf(0, RecordActivity.BUNDLE_SIZE * 2 - totalCount)
        binding.tvDefenseGrowthNotice.apply {
            visibility = android.view.View.VISIBLE
            text = "📊 ${needed}구 더 훈련하면 성장 추이를 볼 수 있어요!"
            contentDescription = "${needed}구 더 훈련하면 성장 추이를 확인할 수 있습니다"
        }
    }
}

internal fun RecordActivity.makeDefenseBundle(
    pitches: List<PitchUnit<DefenseRecord>>,
    index: Int,
    isPartial: Boolean
): DefenseBundle {
    val catches   = pitches.map { it.session }
    val dates     = pitches.mapNotNull { it.date }
    val firstDate = dates.minOrNull()
    val lastDate  = dates.maxOrNull()
    val (label, ttsLabel) = makeDateLabels(firstDate, lastDate, index)
    val pc = catches.size

    val successCount = catches.count { it.success }.toDouble()
    val failCount    = (pc - successCount)
    val successRate  = if (pc > 0) successCount / pc * 100 else 0.0
    val reactionAvg  = catches.filter { it.success && it.reactionMs > 0 }.let { f ->
        if (f.isEmpty()) null
        else f.sumOf { it.reactionMs.toDouble() } / f.size
    }

    return DefenseBundle(
        index       = index,
        label       = label,
        ttsLabel    = ttsLabel,
        pitchCount  = pc,
        isPartial   = isPartial,
        successRate = roundDiff2(successRate).toDouble(),
        reactionAvg = reactionAvg?.let { roundDiff2(it / 1000.0).toDouble() * 1000.0 },
        successAvg  = roundDiff2(successCount / pc * 20).toDouble(),
        failAvg     = roundDiff2(failCount / pc * 20).toDouble()
    )
}

internal fun <T> RecordActivity.splitIntoBundles20(
    sessions: List<T>,
    getCount: (T) -> Int,
    getDate: (T) -> java.util.Date?
): Pair<List<List<PitchUnit<T>>>, List<PitchUnit<T>>> {
    val allPitches = mutableListOf<PitchUnit<T>>()
    for (session in sessions.reversed()) {
        val count = getCount(session)
        repeat(count) { allPitches.add(PitchUnit(session, getDate(session))) }
    }
    val totalPitches = allPitches.size
    val remaining    = totalPitches % RecordActivity.BUNDLE_SIZE
    val fullCount    = totalPitches - remaining
    val bundles = mutableListOf<List<PitchUnit<T>>>()
    var idx = 0
    while (idx + RecordActivity.BUNDLE_SIZE <= fullCount) {
        bundles.add(allPitches.subList(idx, idx + RecordActivity.BUNDLE_SIZE).toList())
        idx += RecordActivity.BUNDLE_SIZE
    }
    val remainingPitches = if (remaining > 0) allPitches.subList(fullCount, totalPitches).toList()
    else emptyList()
    return Pair(bundles.takeLast(RecordActivity.MAX_BUNDLES), remainingPitches)
}

internal fun RecordActivity.setBattingEmpty() {
    binding.tvStatCount.text          = "기록 없음"
    binding.tvStatBattingAvg.text     = "-"
    binding.tvStatReaction.text       = "-"
    binding.tvStatHit.text            = "-"
    binding.tvStatFoul.text           = "-"
    binding.tvStatStrike.text         = "-"
    binding.tvStatBaseCorrectPct.text = "-"
    binding.tvStatBaseCorrect.text    = "-"
    binding.tvBattingGrowthSection.visibility = android.view.View.GONE
    binding.llBattingGrowthRows.visibility    = android.view.View.GONE
    binding.tvBattingGrowthNotice.visibility  = android.view.View.GONE
}

internal fun RecordActivity.setDefenseEmpty() {
    binding.tvDefenseStatCount.text       = "기록 없음"
    binding.tvDefenseStatSuccessRate.text = "-"
    binding.tvDefenseStatReaction.text    = "-"
    binding.tvDefenseStatSuccess.text     = "-"
    binding.tvDefenseStatFail.text        = "-"
    binding.tvDefenseGrowthSection.visibility = android.view.View.GONE
    binding.llDefenseGrowthRows.visibility    = android.view.View.GONE
    binding.tvDefenseGrowthNotice.visibility  = android.view.View.GONE
}
