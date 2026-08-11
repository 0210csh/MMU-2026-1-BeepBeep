package com.beepbeep.defense

import android.content.Context

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
    db.collection("batting_stats").document(userId).get()
        .addOnSuccessListener { doc ->
            if (!doc.exists()) { setBattingEmpty(); return@addOnSuccessListener }

            val totalPitches        = doc.getLong("totalPitches")        ?: 0L
            val hitCount            = doc.getLong("hitCount")            ?: 0L
            val foulCount           = doc.getLong("foulCount")           ?: 0L
            val strikeCount         = doc.getLong("strikeCount")         ?: 0L
            val baseCorrectCount    = doc.getLong("baseCorrectCount")    ?: 0L
            val reactionSumMs       = doc.getLong("reactionSumMs")       ?: 0L
            val reactionSampleCount = doc.getLong("reactionSampleCount") ?: 0L

            if (totalPitches == 0L) { setBattingEmpty(); return@addOnSuccessListener }

            val battingAvg    = hitCount.toDouble() / totalPitches
            val avgReaction   = if (reactionSampleCount > 0) reactionSumMs.toDouble() / reactionSampleCount else 0.0
            val baseCorrectPct = if (hitCount > 0) baseCorrectCount.toDouble() / hitCount * 100 else 0.0

            binding.tvStatCount.text = "전체 ${totalPitches}구 기준"
            binding.tvStatCount.contentDescription = "전체 ${totalPitches}구 기준 평균입니다"
            binding.tvStatBattingAvg.text     = "%.3f".format(battingAvg)
            binding.tvStatReaction.text       = if (avgReaction > 0) msToSec(avgReaction) else "-"
            binding.tvStatHit.text            = "%.1f".format(hitCount.toDouble()         / totalPitches * 20)
            binding.tvStatFoul.text           = "%.1f".format(foulCount.toDouble()        / totalPitches * 20)
            binding.tvStatStrike.text         = "%.1f".format(strikeCount.toDouble()      / totalPitches * 20)
            binding.tvStatBaseCorrect.text    = "%.1f".format(baseCorrectCount.toDouble() / totalPitches * 20)
            binding.tvStatBaseCorrectPct.text = "${"%.0f".format(baseCorrectPct)}%"

            @Suppress("UNCHECKED_CAST")
            val recentPitches = (doc.get("recentPitches") as? List<Map<String, Any>>) ?: emptyList()
            val pitchList = recentPitches.mapNotNull { p ->
                val result = p["result"] as? String ?: return@mapNotNull null
                BattingPitch(
                    result      = result,
                    baseCorrect = p["baseCorrect"] as? Boolean,
                    reactionMs  = (p["reactionMs"] as? Number)?.toLong(),
                    date        = (p["dateMs"] as? Number)?.let { java.util.Date(it.toLong()) }
                )
            }

            val (rawBundles, remainingPitches) = splitIntoBundles20(
                pitchList.reversed(), getCount = { 1 }, getDate = { it.date }
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
                val needed = maxOf(0, RecordActivity.BUNDLE_SIZE * 2 - totalPitches.toInt())
                binding.tvBattingGrowthNotice.apply {
                    visibility = android.view.View.VISIBLE
                    text = "📊 ${needed}구 더 훈련하면 성장 추이를 볼 수 있어요!"
                    contentDescription = "${needed}구 더 훈련하면 성장 추이를 확인할 수 있습니다"
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
    db.collection("defense_stats").document(userId).get()
        .addOnSuccessListener { doc ->
            if (!doc.exists()) { setDefenseEmpty(); return@addOnSuccessListener }

            val totalAttempts       = doc.getLong("totalAttempts")       ?: 0L
            val successCount        = doc.getLong("successCount")        ?: 0L
            val reactionSumMs       = doc.getLong("reactionSumMs")       ?: 0L
            val reactionSampleCount = doc.getLong("reactionSampleCount") ?: 0L

            if (totalAttempts == 0L) { setDefenseEmpty(); return@addOnSuccessListener }

            val failCount   = totalAttempts - successCount
            val successRate = successCount.toDouble() / totalAttempts * 100
            val avgReaction = if (reactionSampleCount > 0) reactionSumMs.toDouble() / reactionSampleCount else 0.0

            binding.tvDefenseStatCount.text = "전체 ${totalAttempts}구 기준"
            binding.tvDefenseStatCount.contentDescription = "전체 ${totalAttempts}구 기준 평균입니다"
            binding.tvDefenseStatSuccessRate.text = "${"%.0f".format(successRate)}%"
            binding.tvDefenseStatReaction.text    = if (avgReaction > 0) msToSec(avgReaction) else "-"
            binding.tvDefenseStatSuccess.text = "%.1f".format(successCount.toDouble() / totalAttempts * 20)
            binding.tvDefenseStatFail.text    = "%.1f".format(failCount.toDouble()    / totalAttempts * 20)

            @Suppress("UNCHECKED_CAST")
            val recentCatches = (doc.get("recentCatches") as? List<Map<String, Any>>) ?: emptyList()
            val catchList = recentCatches.map { c ->
                DefenseRecord(
                    success    = c["success"] as? Boolean ?: false,
                    reactionMs = (c["reactionMs"] as? Number)?.toLong() ?: -1L,
                    date       = (c["dateMs"] as? Number)?.let { java.util.Date(it.toLong()) }
                )
            }

            val (rawBundles, remainingPitches) = splitIntoBundles20(
                catchList.reversed(), getCount = { 1 }, getDate = { it.date }
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
                val needed = maxOf(0, RecordActivity.BUNDLE_SIZE * 2 - totalAttempts.toInt())
                binding.tvDefenseGrowthNotice.apply {
                    visibility = android.view.View.VISIBLE
                    text = "📊 ${needed}구 더 훈련하면 성장 추이를 볼 수 있어요!"
                    contentDescription = "${needed}구 더 훈련하면 성장 추이를 확인할 수 있습니다"
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
