package com.beepbeep.defense

import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

// ─────────────────────────────────────────────────────
// 차트 페이지 데이터 모델
// ─────────────────────────────────────────────────────

data class ChartPage(
    val title: String,
    val lines: List<List<GrowthChartView.ChartPoint>>,
    val colors: List<Int>,
    val labels: List<String>,
    val onPointClick: (Int, Int) -> Unit
)

// ─────────────────────────────────────────────────────
// 유틸리티 함수 (패키지 레벨)
// ─────────────────────────────────────────────────────

internal fun roundDiff2(value: Double): Float = "%.2f".format(value).toFloat()

internal fun msToSec(ms: Double): String = "${"%.2f".format(ms / 1000.0)}s"

internal fun makeDateLabels(
    firstDate: java.util.Date?,
    lastDate: java.util.Date?,
    index: Int
): Pair<String, String> {
    val shortFmt = java.text.SimpleDateFormat("M/d", Locale.KOREAN)
    val ttsFmt   = java.text.SimpleDateFormat("M월 d일", Locale.KOREAN)
    return when {
        firstDate == null  -> Pair("$index", "${index}번째 묶음")
        firstDate == lastDate -> Pair(shortFmt.format(firstDate), ttsFmt.format(firstDate))
        else -> Pair(
            "${shortFmt.format(firstDate)}\n~${shortFmt.format(lastDate!!)}",
            "${ttsFmt.format(firstDate)}부터 ${ttsFmt.format(lastDate!!)}"
        )
    }
}

internal fun toKoreanNumber(n: Int): String = when (n) {
    1 -> "한"; 2 -> "두"; 3 -> "세"; 4 -> "네"; 5 -> "다섯"
    6 -> "여섯"; 7 -> "일곱"; 8 -> "여덟"; 9 -> "아홉"; 10 -> "열"
    11 -> "열한"; 12 -> "열두"; 13 -> "열세"; 14 -> "열네"; 15 -> "열다섯"
    16 -> "열여섯"; 17 -> "열일곱"; 18 -> "열여덟"; 19 -> "열아홉"
    else -> "$n"
}

internal fun formatNumberForTts(value: Float): String {
    val intPart = value.toInt()
    val decPart = Math.round((value - intPart) * 100)
    return if (decPart == 0) {
        "$intPart"
    } else {
        val tens = decPart / 10
        val ones = decPart % 10
        val tensText = when (tens) {
            0 -> "영"; 1 -> "일"; 2 -> "이"; 3 -> "삼"; 4 -> "사"; 5 -> "오"
            6 -> "육"; 7 -> "칠"; 8 -> "팔"; 9 -> "구"; else -> "$tens"
        }
        val onesText = when (ones) {
            0 -> "영"; 1 -> "일"; 2 -> "이"; 3 -> "삼"; 4 -> "사"; 5 -> "오"
            6 -> "육"; 7 -> "칠"; 8 -> "팔"; 9 -> "구"; else -> "$ones"
        }
        "${intPart}점${tensText}${onesText}"
    }
}

internal fun formatSecForTts(ms: Double): String {
    val formatted = "%.2f".format(ms / 1000.0)
    val parts     = formatted.split(".")
    val intPart   = parts[0]
    val decPart   = parts[1]
    val decText   = decPart.map { c ->
        when (c) {
            '0' -> "영"; '1' -> "일"; '2' -> "이"; '3' -> "삼"; '4' -> "사"
            '5' -> "오"; '6' -> "육"; '7' -> "칠"; '8' -> "팔"; '9' -> "구"
            else -> c.toString()
        }
    }.joinToString("")
    return "${intPart}점${decText}초"
}

// ─────────────────────────────────────────────────────
// RecordActivity 확장 함수 (차트 빌드 / 렌더링)
// ─────────────────────────────────────────────────────

internal fun RecordActivity.speakProgress(pitchCount: Int) {
    val current = toKoreanNumber(pitchCount)
    val needed  = toKoreanNumber(RecordActivity.BUNDLE_SIZE - pitchCount)
    speak("현재 ${current}판 진행 중. ${needed}판 더 하면 묶음 완성.")
}

internal fun RecordActivity.buildBattingChartPages(bundles: List<BattingBundle>): List<ChartPage> = listOf(
    ChartPage("타율",
        listOf(bundles.map { GrowthChartView.ChartPoint(it.label, it.battingAvg.toFloat(), it.isPartial) }),
        listOf(0xFF00FF7F.toInt()), listOf("타율")
    ) { _, pi ->
        val b = bundles[pi]
        if (pi == bundles.size - 1 && b.isPartial) { speakProgress(b.pitchCount) } else {
            val prev = if (pi > 0) bundles[pi - 1] else null
            val diff = if (prev != null) {
                val d = roundDiff2(b.battingAvg - prev.battingAvg)
                when {
                    d > 0.001f  -> "이전보다 타율 ${formatNumberForTts(d)} 향상."
                    d < -0.001f -> "이전보다 타율 ${formatNumberForTts(-d)} 저하."
                    else        -> ""
                }
            } else ""
            speak("${b.ttsLabel}. 타율 ${formatNumberForTts(b.battingAvg.toFloat())}. $diff")
        }
    },
    ChartPage("반응속도",
        listOf(bundles.map { b ->
            GrowthChartView.ChartPoint(b.label, b.reactionAvg?.let { (it / 1000.0).toFloat() } ?: Float.NaN, b.isPartial)
        }),
        listOf(0xFFFFB74D.toInt()), listOf("반응속도")
    ) { _, pi ->
        val b = bundles[pi]
        when {
            pi == bundles.size - 1 && b.isPartial -> speakProgress(b.pitchCount)
            b.reactionAvg == null -> speak("${b.ttsLabel}. 반응속도 기록이 없습니다.")
            else -> {
                val prev = if (pi > 0) bundles[pi - 1] else null
                val diff = if (prev?.reactionAvg != null) {
                    val d = roundDiff2((b.reactionAvg - prev.reactionAvg) / 1000.0).toDouble() * 1000.0
                    when {
                        d < -0.5 -> "이전보다 ${formatSecForTts(-d)} 향상."
                        d > 0.5  -> "이전보다 ${formatSecForTts(d)} 저하."
                        else     -> ""
                    }
                } else ""
                speak("${b.ttsLabel}. 반응속도 ${formatSecForTts(b.reactionAvg)}. $diff")
            }
        }
    },
    ChartPage("정타 / 파울 / 스트라이크",
        listOf(
            bundles.map { GrowthChartView.ChartPoint(it.label, it.hitAvg.toFloat(),    it.isPartial) },
            bundles.map { GrowthChartView.ChartPoint(it.label, it.foulAvg.toFloat(),   it.isPartial) },
            bundles.map { GrowthChartView.ChartPoint(it.label, it.strikeAvg.toFloat(), it.isPartial) }
        ),
        listOf(0xFF5CF387.toInt(), 0xFFFBBF24.toInt(), 0xFFF87171.toInt()),
        listOf("정타", "파울", "스트라이크")
    ) { lineIndex, pi ->
        val b = bundles[pi]
        if (pi == bundles.size - 1 && b.isPartial) { speakProgress(b.pitchCount) } else {
            when (lineIndex) {
                0 -> speak("${b.ttsLabel}. 정타 ${formatNumberForTts(b.hitAvg.toFloat())}개.")
                1 -> speak("${b.ttsLabel}. 파울 ${formatNumberForTts(b.foulAvg.toFloat())}개.")
                2 -> speak("${b.ttsLabel}. 스트라이크 ${formatNumberForTts(b.strikeAvg.toFloat())}개.")
            }
        }
    },
    ChartPage("베이스 정답수",
        listOf(bundles.map { b ->
            GrowthChartView.ChartPoint(b.label, b.baseCorrectAvg?.toFloat() ?: Float.NaN, b.isPartial)
        }),
        listOf(0xFFA78BFA.toInt()), listOf("베이스 정답수")
    ) { _, pi ->
        val b = bundles[pi]
        when {
            pi == bundles.size - 1 && b.isPartial -> speakProgress(b.pitchCount)
            b.baseCorrectAvg == null -> speak("${b.ttsLabel}. 베이스 정답수 기록이 없습니다.")
            else -> {
                val prev = if (pi > 0) bundles[pi - 1] else null
                val diff = if (prev?.baseCorrectAvg != null) {
                    val d = roundDiff2(b.baseCorrectAvg - prev.baseCorrectAvg)
                    when {
                        d > 0.1f  -> "이전보다 ${formatNumberForTts(d)}개 향상."
                        d < -0.1f -> "이전보다 ${formatNumberForTts(-d)}개 저하."
                        else      -> ""
                    }
                } else ""
                speak("${b.ttsLabel}. 베이스 정답수 ${formatNumberForTts(b.baseCorrectAvg.toFloat())}개. $diff")
            }
        }
    }
)

internal fun RecordActivity.buildDefenseChartPages(bundles: List<DefenseBundle>): List<ChartPage> = listOf(
    ChartPage("성공률",
        listOf(bundles.map { GrowthChartView.ChartPoint(it.label, it.successRate.toFloat(), it.isPartial) }),
        listOf(0xFF38BDF8.toInt()), listOf("성공률")
    ) { _, pi ->
        val b = bundles[pi]
        if (pi == bundles.size - 1 && b.isPartial) { speakProgress(b.pitchCount) } else {
            val prev = if (pi > 0) bundles[pi - 1] else null
            val diff = if (prev != null) {
                val d = roundDiff2(b.successRate - prev.successRate)
                when {
                    d > 0.5f  -> "이전보다 ${"%.0f".format(d)}퍼센트 향상."
                    d < -0.5f -> "이전보다 ${"%.0f".format(-d)}퍼센트 저하."
                    else      -> ""
                }
            } else ""
            speak("${b.ttsLabel}. 성공률 ${b.successRate.toInt()}퍼센트. $diff")
        }
    },
    ChartPage("반응속도",
        listOf(bundles.map { b ->
            GrowthChartView.ChartPoint(b.label, b.reactionAvg?.let { (it / 1000.0).toFloat() } ?: Float.NaN, b.isPartial)
        }),
        listOf(0xFFFFB74D.toInt()), listOf("반응속도")
    ) { _, pi ->
        val b = bundles[pi]
        when {
            pi == bundles.size - 1 && b.isPartial -> speakProgress(b.pitchCount)
            b.reactionAvg == null -> speak("${b.ttsLabel}. 반응속도 기록이 없습니다.")
            else -> {
                val prev = if (pi > 0) bundles[pi - 1] else null
                val diff = if (prev?.reactionAvg != null) {
                    val d = roundDiff2((b.reactionAvg - prev.reactionAvg) / 1000.0).toDouble() * 1000.0
                    when {
                        d < -0.5 -> "이전보다 ${formatSecForTts(-d)} 향상."
                        d > 0.5  -> "이전보다 ${formatSecForTts(d)} 저하."
                        else     -> ""
                    }
                } else ""
                speak("${b.ttsLabel}. 반응속도 ${formatSecForTts(b.reactionAvg)}. $diff")
            }
        }
    },
    ChartPage("성공횟수 / 실패횟수",
        listOf(
            bundles.map { GrowthChartView.ChartPoint(it.label, it.successAvg.toFloat(), it.isPartial) },
            bundles.map { GrowthChartView.ChartPoint(it.label, it.failAvg.toFloat(),    it.isPartial) }
        ),
        listOf(0xFF5CF387.toInt(), 0xFFF87171.toInt()),
        listOf("성공", "실패")
    ) { lineIndex, pi ->
        val b = bundles[pi]
        if (pi == bundles.size - 1 && b.isPartial) { speakProgress(b.pitchCount) } else {
            when (lineIndex) {
                0 -> speak("${b.ttsLabel}. 성공횟수 ${formatNumberForTts(b.successAvg.toFloat())}개.")
                1 -> speak("${b.ttsLabel}. 실패횟수 ${formatNumberForTts(b.failAvg.toFloat())}개.")
            }
        }
    }
)

internal fun RecordActivity.showBattingGrowthChart(bundles: List<BattingBundle>) {
    binding.tvBattingGrowthSection.visibility = android.view.View.VISIBLE
    binding.tvBattingGrowthSection.text = "📈 타격 성장 추이"
    binding.tvBattingGrowthSection.contentDescription = "최근 ${RecordActivity.BUNDLE_SIZE}판을 비교하여 정렬하였습니다."
    binding.tvBattingGrowthNotice.visibility = android.view.View.GONE
    binding.llBattingGrowthRows.visibility   = android.view.View.VISIBLE

    binding.tvGrowthBattingAvg.visibility = android.view.View.GONE
    binding.tvGrowthReaction.visibility   = android.view.View.GONE
    binding.tvGrowthHit.visibility        = android.view.View.GONE
    binding.tvGrowthFoul.visibility       = android.view.View.GONE
    binding.tvGrowthStrike.visibility     = android.view.View.GONE

    val pages = buildBattingChartPages(bundles)
    renderChartPager(
        container         = binding.llBattingGrowthRows,
        pages             = pages,
        currentIndex      = battingChartIndex,
        onIndexChange     = { battingChartIndex = it },
        bundles           = bundles,
        partialPitchCount = bundles.lastOrNull { it.isPartial }?.pitchCount
    )
}

internal fun RecordActivity.showDefenseGrowthChart(bundles: List<DefenseBundle>) {
    binding.tvDefenseGrowthSection.visibility = android.view.View.VISIBLE
    binding.tvDefenseGrowthSection.text = "📈 수비 성장 추이"
    binding.tvDefenseGrowthSection.contentDescription = "최근 ${RecordActivity.BUNDLE_SIZE}판을 비교하여 정렬하였습니다."
    binding.tvDefenseGrowthNotice.visibility = android.view.View.GONE
    binding.llDefenseGrowthRows.visibility   = android.view.View.VISIBLE

    binding.tvDefenseGrowthRate.visibility     = android.view.View.GONE
    binding.tvDefenseGrowthReaction.visibility = android.view.View.GONE
    binding.tvDefenseGrowthSuccess.visibility  = android.view.View.GONE

    val pages = buildDefenseChartPages(bundles)
    renderChartPager(
        container         = binding.llDefenseGrowthRows,
        pages             = pages,
        currentIndex      = defenseChartIndex,
        onIndexChange     = { defenseChartIndex = it },
        bundles           = bundles,
        partialPitchCount = bundles.lastOrNull { it.isPartial }?.pitchCount
    )
}

internal fun <T> RecordActivity.renderChartPager(
    container: LinearLayout,
    pages: List<ChartPage>,
    currentIndex: Int,
    onIndexChange: (Int) -> Unit,
    bundles: List<T>,
    partialPitchCount: Int?
) {
    val activity = this
    container.removeAllViews()
    val page = pages[currentIndex]

    val navRow = LinearLayout(this).apply {
        orientation  = LinearLayout.HORIZONTAL
        gravity      = Gravity.CENTER_VERTICAL
        setPadding(8, 16, 8, 4)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    val btnPrev = Button(this).apply {
        text = "◀"; textSize = 28f
        setTextColor(if (currentIndex > 0) 0xFF5CF387.toInt() else 0xFF444444.toInt())
        setBackgroundColor(Color.TRANSPARENT); isEnabled = currentIndex > 0
        layoutParams       = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        contentDescription = "이전 그래프"
        setOnClickListener {
            val newIndex = currentIndex - 1
            onIndexChange(newIndex)
            activity.speak("${pages[newIndex].title} 그래프입니다.")
            container.post { activity.renderChartPager(container, pages, newIndex, onIndexChange, bundles, partialPitchCount) }
        }
    }

    val tvTitle = TextView(this).apply {
        text = "${page.title}  ${currentIndex + 1}/${pages.size}"; textSize = 20f
        setTextColor(0xFFCCCCCC.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity      = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    val btnNext = Button(this).apply {
        text = "▶"; textSize = 28f
        setTextColor(if (currentIndex < pages.size - 1) 0xFF5CF387.toInt() else 0xFF444444.toInt())
        setBackgroundColor(Color.TRANSPARENT); isEnabled = currentIndex < pages.size - 1
        layoutParams       = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        contentDescription = "다음 그래프"
        setOnClickListener {
            val newIndex = currentIndex + 1
            onIndexChange(newIndex)
            activity.speak("${pages[newIndex].title} 그래프입니다.")
            container.post { activity.renderChartPager(container, pages, newIndex, onIndexChange, bundles, partialPitchCount) }
        }
    }

    navRow.addView(btnPrev); navRow.addView(tvTitle); navRow.addView(btnNext)
    container.addView(navRow)

    val chart = GrowthChartView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (340 * resources.displayMetrics.density).toInt()
        )
        setData(page.lines, page.colors, page.labels)
        onPointClick          = page.onPointClick
        contentDescription    = "${page.title} 그래프. 점을 눌러 해당 기간 상세 기록을 들을 수 있습니다."
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    container.addView(chart)

    if (currentIndex == 0 || currentIndex == 1) {
        addCompareSummary(container, bundles)
    }

    if (partialPitchCount != null) {
        val remainTv = TextView(this).apply {
            text = "📌 현재 ${partialPitchCount}판 진행 중 (${RecordActivity.BUNDLE_SIZE - partialPitchCount}판 더 하면 다음 묶음 완성)"
            textSize = 15f; setTextColor(0xFFAAAAAA.toInt()); setPadding(12, 12, 12, 12)
            setOnClickListener { activity.speakProgress(partialPitchCount) }
        }
        container.addView(remainTv)
    }
}

internal fun <T> RecordActivity.addCompareSummary(container: LinearLayout, bundles: List<T>) {
    val completeBundles = when {
        bundles.firstOrNull() is BattingBundle -> (bundles as List<BattingBundle>).filter { !it.isPartial }
        bundles.firstOrNull() is DefenseBundle -> (bundles as List<DefenseBundle>).filter { !it.isPartial }
        else -> return
    }
    if (completeBundles.size < 2) return

    when (val prev = completeBundles[completeBundles.size - 2]) {
        is BattingBundle -> {
            val curr = (completeBundles as List<BattingBundle>).last()
            addCompareText(container, "타율",
                "%.2f".format(prev.battingAvg), "%.2f".format(curr.battingAvg),
                (curr.battingAvg - prev.battingAvg).toFloat(), true, "")
            val prevR = prev.reactionAvg; val currR = curr.reactionAvg
            if (prevR != null && currR != null) {
                addCompareText(container, "반응속도",
                    msToSec(prevR), msToSec(currR),
                    ((currR - prevR) / 1000.0).toFloat(), false, "초")
            } else {
                addCompareTextNoData(container, "반응속도",
                    prevR?.let { msToSec(it) } ?: "-", currR?.let { msToSec(it) } ?: "-")
            }
        }
        is DefenseBundle -> {
            val curr = (completeBundles as List<DefenseBundle>).last()
            addCompareText(container, "성공률",
                "${"%.0f".format(prev.successRate)}%", "${"%.0f".format(curr.successRate)}%",
                (curr.successRate - prev.successRate).toFloat(), true, "%")
            val prevR = prev.reactionAvg; val currR = curr.reactionAvg
            if (prevR != null && currR != null) {
                addCompareText(container, "반응속도",
                    msToSec(prevR), msToSec(currR),
                    ((currR - prevR) / 1000.0).toFloat(), false, "초")
            } else {
                addCompareTextNoData(container, "반응속도",
                    prevR?.let { msToSec(it) } ?: "-", currR?.let { msToSec(it) } ?: "-")
            }
        }
    }
}

internal fun RecordActivity.addCompareText(
    container: LinearLayout,
    label: String, before: String, after: String,
    diff: Float, higherGood: Boolean, unit: String
) {
    val improved = if (higherGood) diff > 0f else diff < 0f
    val same     = kotlin.math.abs(diff) < 0.001f
    val arrow    = when { same -> "→"; improved -> "▲"; else -> "▼" }
    val diffText = if (same) "변화 없음"
    else "${"%.2f".format(kotlin.math.abs(diff))}$unit ${if (improved) "향상" else "저하"}"
    val color = when { same -> "#AAAAAA"; improved -> "#5CF387"; else -> "#F87171" }
    val tv = TextView(this).apply {
        text = "$label   $before → $after   $arrow $diffText"
        textSize = 15f; setTextColor(Color.parseColor(color)); setPadding(12, 8, 12, 8)
    }
    container.addView(tv)
}

internal fun RecordActivity.addCompareTextNoData(
    container: LinearLayout,
    label: String, before: String, after: String
) {
    val tv = TextView(this).apply {
        text = "$label   $before → $after   기록 없음"
        textSize = 15f; setTextColor(Color.parseColor("#AAAAAA")); setPadding(12, 8, 12, 8)
    }
    container.addView(tv)
}
