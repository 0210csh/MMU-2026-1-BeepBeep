package com.beepbeep.defense

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.beepbeep.defense.databinding.ActivityRecordBinding
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.util.Locale

class RecordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordBinding
    private val db = FirebaseFirestore.getInstance()

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var battingChartIndex = 0
    private var defenseChartIndex = 0

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

    companion object {
        const val BUNDLE_SIZE = 20
        const val MAX_BUNDLES = 4
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutBottomNav) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, view.paddingTop, 0, systemBars.bottom)
            insets
        }

        binding.root.postDelayed({
            binding.llRecordHeader.performAccessibilityAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }, 1500)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                ttsReady = true
            }
        }

        val userPref = getSharedPreferences("UserInfo", MODE_PRIVATE)
        binding.tvUserName.text = userPref.getString("name", "로그인이 필요합니다")
        val userId = userPref.getString("id", "anonymous") ?: "anonymous"

        loadBattingStatsFromFirebase(userId)
        loadDefenseStatsFromFirebase(userId)
        setupNavigation()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        val userPref = getSharedPreferences("UserInfo", MODE_PRIVATE)
        val userId = userPref.getString("id", "anonymous") ?: "anonymous"
        loadBattingStatsFromFirebase(userId)
        loadDefenseStatsFromFirebase(userId)
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop(); tts?.shutdown()
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun formatNumberForTts(value: Float): String {
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

    private fun roundDiff2(value: Double): Float {
        return "%.2f".format(value).toFloat()
    }

    private fun makeDateLabels(
        firstDate: java.util.Date?,
        lastDate: java.util.Date?,
        index: Int
    ): Pair<String, String> {
        val shortFmt = java.text.SimpleDateFormat("M/d", Locale.KOREAN)
        val ttsFmt   = java.text.SimpleDateFormat("M월 d일", Locale.KOREAN)
        return when {
            firstDate == null -> Pair("$index", "${index}번째 묶음")
            firstDate == lastDate -> Pair(shortFmt.format(firstDate), ttsFmt.format(firstDate))
            else -> Pair(
                "${shortFmt.format(firstDate)}~${shortFmt.format(lastDate!!)}",
                "${ttsFmt.format(firstDate)}부터 ${ttsFmt.format(lastDate!!)}"
            )
        }
    }

    data class PitchUnit<T>(val session: T, val date: java.util.Date?)

    private fun speakProgress(pitchCount: Int) {
        val current = toKoreanNumber(pitchCount)
        val needed  = toKoreanNumber(BUNDLE_SIZE - pitchCount)
        speak("현재 ${current}판 진행 중. ${needed}판 더 하면 묶음 완성.")
    }

    private fun toKoreanNumber(n: Int): String = when (n) {
        1 -> "한"; 2 -> "두"; 3 -> "세"; 4 -> "네"; 5 -> "다섯"
        6 -> "여섯"; 7 -> "일곱"; 8 -> "여덟"; 9 -> "아홉"; 10 -> "열"
        11 -> "열한"; 12 -> "열두"; 13 -> "열세"; 14 -> "열네"; 15 -> "열다섯"
        16 -> "열여섯"; 17 -> "열일곱"; 18 -> "열여덟"; 19 -> "열아홉"
        else -> "$n"
    }

    private fun formatSecForTts(ms: Double): String {
        val formatted = "%.2f".format(ms / 1000.0)
        val parts = formatted.split(".")
        val intPart = parts[0]
        val decPart = parts[1]
        val decText = decPart.map { c ->
            when (c) {
                '0' -> "영"; '1' -> "일"; '2' -> "이"; '3' -> "삼"; '4' -> "사"
                '5' -> "오"; '6' -> "육"; '7' -> "칠"; '8' -> "팔"; '9' -> "구"
                else -> c.toString()
            }
        }.joinToString("")
        return "${intPart}점${decText}초"
    }

    private fun msToSec(ms: Double): String = "${"%.2f".format(ms / 1000.0)}s"

    // ────────────────────────────────────────────────────────
    // 20판씩 묶기
    // ────────────────────────────────────────────────────────
    private fun <T> splitIntoBundles20(
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
        val remaining    = totalPitches % BUNDLE_SIZE
        val fullCount    = totalPitches - remaining
        val bundles = mutableListOf<List<PitchUnit<T>>>()
        var idx = 0
        while (idx + BUNDLE_SIZE <= fullCount) {
            bundles.add(allPitches.subList(idx, idx + BUNDLE_SIZE).toList())
            idx += BUNDLE_SIZE
        }
        val remainingPitches = if (remaining > 0) allPitches.subList(fullCount, totalPitches).toList()
        else emptyList()
        return Pair(bundles.takeLast(MAX_BUNDLES), remainingPitches)
    }

    // ────────────────────────────────────────────────────────
    // 타격 통계
    // ────────────────────────────────────────────────────────
    private fun loadBattingStatsFromFirebase(userId: String) {
        db.collection("users").document(userId).collection("훈련기록")
            .orderBy("생성일시", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { documents ->
                val records = documents.mapNotNull { doc ->
                    val result = doc.get("종합결과") as? Map<*, *> ?: return@mapNotNull null
                    val pitchCount = (doc.get("목표투구수") as? Number)?.toInt() ?: 1
                    BattingRecord(
                        pitchCount     = pitchCount,
                        battingAvg     = (result["타율"] as? Number)?.toFloat() ?: 0f,
                        reactionAvg    = (result["반응속도평균"] as? Number)?.toFloat() ?: 0f,
                        hitCount       = (result["정타수"] as? Number)?.toFloat() ?: 0f,
                        foulCount      = (result["파울수"] as? Number)?.toFloat() ?: 0f,
                        strikeCount    = (result["스트라이크수"] as? Number)?.toFloat() ?: 0f,
                        baseCorrectPct = (result["베이스정답률"] as? Number)?.toFloat() ?: 0f,
                        baseCorrect    = (result["베이스정답수"] as? Number)?.toFloat() ?: 0f,
                        date           = doc.getTimestamp("생성일시")
                    )
                }

                if (records.isEmpty()) { setBattingEmpty(); return@addOnSuccessListener }

                val totalPitches = records.sumOf { it.pitchCount }
                binding.tvStatCount.text = "전체 ${totalPitches}구 기준"
                binding.tvStatCount.contentDescription = "전체 ${totalPitches}구 기준 평균입니다"

                val totalHit    = records.sumOf { it.hitCount.toDouble() }
                val totalFoul   = records.sumOf { it.foulCount.toDouble() }
                val totalStrike = records.sumOf { it.strikeCount.toDouble() }
                val totalBase   = records.sumOf { it.baseCorrect.toDouble() }
                val weightedBattingAvg = totalHit / totalPitches
                val weightedReaction = records.filter { it.reactionAvg > 0 }.let { f ->
                    if (f.isEmpty()) 0.0
                    else f.sumOf { it.reactionAvg.toDouble() * it.hitCount } / f.sumOf { it.hitCount.toDouble() }.coerceAtLeast(1.0)
                }
                val weightedBaseCorrectPct = if (totalHit > 0) totalBase / totalHit * 100 else 0.0

                binding.tvStatBattingAvg.text     = "%.3f".format(weightedBattingAvg)
                binding.tvStatReaction.text       = msToSec(weightedReaction)
                // 20판 기준 환산 (정타+파울+스트라이크 ≈ 20)
                binding.tvStatHit.text            = "%.1f".format(totalHit / totalPitches * 20)
                binding.tvStatFoul.text           = "%.1f".format(totalFoul / totalPitches * 20)
                binding.tvStatStrike.text         = "%.1f".format(totalStrike / totalPitches * 20)
                binding.tvStatBaseCorrect.text    = "%.1f".format(totalBase / totalPitches * 20)
                binding.tvStatBaseCorrectPct.text = "${"%.0f".format(weightedBaseCorrectPct)}%"

                val (rawBundles, remainingPitches) = splitIntoBundles20(
                    records, getCount = { it.pitchCount }, getDate = { it.date?.toDate() }
                )
                val completeBundles = rawBundles.mapIndexed { i, pitches ->
                    makeBattingBundle(pitches, i + 1, false)
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
                    val needed = maxOf(0, BUNDLE_SIZE * 2 - totalPitches)
                    binding.tvBattingGrowthNotice.apply {
                        visibility = android.view.View.VISIBLE
                        text = "📊 ${needed}구 더 훈련하면 성장 추이를 볼 수 있어요!"
                        contentDescription = "${needed}구 더 훈련하면 성장 추이를 확인할 수 있습니다"
                    }
                }
            }
            .addOnFailureListener { setBattingEmpty() }
    }

    private fun makeBattingBundle(
        pitches: List<PitchUnit<BattingRecord>>,
        index: Int,
        isPartial: Boolean
    ): BattingBundle {
        val sessions  = pitches.map { it.session }
        val dates     = pitches.mapNotNull { it.date }
        val firstDate = dates.minOrNull()
        val lastDate  = dates.maxOrNull()
        val (label, ttsLabel) = makeDateLabels(firstDate, lastDate, index)
        val pc = pitches.size

        val bHit         = sessions.sumOf { it.hitCount.toDouble() / it.pitchCount } / sessions.size * pc
        val bFoul        = sessions.sumOf { it.foulCount.toDouble() / it.pitchCount } / sessions.size * pc
        val bStrike      = sessions.sumOf { it.strikeCount.toDouble() / it.pitchCount } / sessions.size * pc
        val bBatAvg      = if (pc > 0) bHit / pc else 0.0
        val bBaseCorrectRaw = sessions.sumOf { it.baseCorrect.toDouble() / it.pitchCount } / sessions.size * pc
        val bBaseCorrect = if (sessions.all { it.hitCount == 0f }) null else bBaseCorrectRaw
        val bReactionRaw = sessions.filter { it.reactionAvg > 0 }.let { f ->
            if (f.isEmpty()) null
            else f.sumOf { it.reactionAvg.toDouble() * it.hitCount } / f.sumOf { it.hitCount.toDouble() }.coerceAtLeast(1.0)
        }
        return BattingBundle(
            index          = index,
            label          = label,
            ttsLabel       = ttsLabel,
            pitchCount     = pc,
            isPartial      = isPartial,
            battingAvg     = roundDiff2(bBatAvg).toDouble(),
            reactionAvg    = bReactionRaw?.let { roundDiff2(it / 1000.0).toDouble() * 1000.0 },
            hitAvg         = roundDiff2(bHit).toDouble(),
            foulAvg        = roundDiff2(bFoul).toDouble(),
            strikeAvg      = roundDiff2(bStrike).toDouble(),
            baseCorrectAvg = bBaseCorrect?.let { roundDiff2(it).toDouble() }
        )
    }

    data class ChartPage(
        val title: String,
        val lines: List<List<GrowthChartView.ChartPoint>>,
        val colors: List<Int>,
        val labels: List<String>,
        val onPointClick: (Int, Int) -> Unit
    )

    private fun buildBattingChartPages(bundles: List<BattingBundle>): List<ChartPage> = listOf(
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

    private fun buildDefenseChartPages(bundles: List<DefenseBundle>): List<ChartPage> = listOf(
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

    private fun showBattingGrowthChart(bundles: List<BattingBundle>) {
        binding.tvBattingGrowthSection.visibility = android.view.View.VISIBLE
        binding.tvBattingGrowthSection.text = "📈 타격 성장 추이"
        binding.tvBattingGrowthSection.contentDescription = "최근 ${BUNDLE_SIZE}판을 비교하여 정렬하였습니다."
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

    private fun showDefenseGrowthChart(bundles: List<DefenseBundle>) {
        binding.tvDefenseGrowthSection.visibility = android.view.View.VISIBLE
        binding.tvDefenseGrowthSection.text = "📈 수비 성장 추이"
        binding.tvDefenseGrowthSection.contentDescription = "최근 ${BUNDLE_SIZE}판을 비교하여 정렬하였습니다."
        binding.tvDefenseGrowthNotice.visibility = android.view.View.GONE
        binding.llDefenseGrowthRows.visibility   = android.view.View.VISIBLE

        binding.tvDefenseGrowthRate.visibility    = android.view.View.GONE
        binding.tvDefenseGrowthReaction.visibility= android.view.View.GONE
        binding.tvDefenseGrowthSuccess.visibility = android.view.View.GONE

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

    private fun <T> renderChartPager(
        container: LinearLayout,
        pages: List<ChartPage>,
        currentIndex: Int,
        onIndexChange: (Int) -> Unit,
        bundles: List<T>,
        partialPitchCount: Int?
    ) {
        container.removeAllViews()
        val page = pages[currentIndex]

        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(8, 16, 8, 4)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val btnPrev = Button(this).apply {
            text = "◀"; textSize = 28f
            setTextColor(if (currentIndex > 0) 0xFF5CF387.toInt() else 0xFF444444.toInt())
            setBackgroundColor(Color.TRANSPARENT); isEnabled = currentIndex > 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            contentDescription = "이전 그래프"
            setOnClickListener {
                val newIndex = currentIndex - 1
                onIndexChange(newIndex)
                speak("${pages[newIndex].title} 그래프입니다.")
                container.post { renderChartPager(container, pages, newIndex, onIndexChange, bundles, partialPitchCount) }
            }
        }

        val tvTitle = TextView(this).apply {
            text = "${page.title}  ${currentIndex + 1}/${pages.size}"; textSize = 20f
            setTextColor(0xFFCCCCCC.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnNext = Button(this).apply {
            text = "▶"; textSize = 28f
            setTextColor(if (currentIndex < pages.size - 1) 0xFF5CF387.toInt() else 0xFF444444.toInt())
            setBackgroundColor(Color.TRANSPARENT); isEnabled = currentIndex < pages.size - 1
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            contentDescription = "다음 그래프"
            setOnClickListener {
                val newIndex = currentIndex + 1
                onIndexChange(newIndex)
                speak("${pages[newIndex].title} 그래프입니다.")
                container.post { renderChartPager(container, pages, newIndex, onIndexChange, bundles, partialPitchCount) }
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
            onPointClick = page.onPointClick
            contentDescription = "${page.title} 그래프. 점을 눌러 해당 기간 상세 기록을 들을 수 있습니다."
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        container.addView(chart)

        if (currentIndex == 0 || currentIndex == 1) {
            addCompareSummary(container, bundles)
        }

        if (partialPitchCount != null) {
            val remainTv = TextView(this).apply {
                text = "📌 현재 ${partialPitchCount}판 진행 중 (${BUNDLE_SIZE - partialPitchCount}판 더 하면 다음 묶음 완성)"
                textSize = 15f; setTextColor(0xFFAAAAAA.toInt()); setPadding(12, 12, 12, 12)
                setOnClickListener { speakProgress(partialPitchCount) }
            }
            container.addView(remainTv)
        }
    }

    private fun <T> addCompareSummary(container: LinearLayout, bundles: List<T>) {
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

    // ────────────────────────────────────────────────────────
    // 수비 통계
    // ────────────────────────────────────────────────────────
    private fun loadDefenseStatsFromFirebase(userId: String) {
        db.collection("users").document(userId).collection("수비훈련기록")
            .orderBy("생성일시", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { documents ->
                val records = documents.mapNotNull { doc ->
                    val pitchCount = (doc.get("목표횟수") as? Number)?.toInt() ?: 1
                    DefenseRecord(
                        pitchCount   = pitchCount,
                        successRate  = (doc.get("성공률") as? Number)?.toFloat() ?: 0f,
                        reactionAvg  = (doc.get("평균반응속도") as? Number)?.toFloat() ?: 0f,
                        successCount = (doc.get("성공횟수") as? Number)?.toFloat() ?: 0f,
                        failCount    = (doc.get("실패횟수") as? Number)?.toFloat() ?: 0f,
                        date         = doc.getTimestamp("생성일시")
                    )
                }

                if (records.isEmpty()) { setDefenseEmpty(); return@addOnSuccessListener }

                val totalPitches = records.sumOf { it.pitchCount }
                binding.tvDefenseStatCount.text = "전체 ${totalPitches}구 기준"
                binding.tvDefenseStatCount.contentDescription = "전체 ${totalPitches}구 기준 평균입니다"

                val totalSuccess = records.sumOf { it.successCount.toDouble() }
                val totalFail    = records.sumOf { it.failCount.toDouble() }
                val weightedSuccessRate = totalSuccess / totalPitches * 100
                val weightedReaction = records.filter { it.reactionAvg > 0 }.let { f ->
                    if (f.isEmpty()) 0.0
                    else f.sumOf { it.reactionAvg.toDouble() * it.successCount } / f.sumOf { it.successCount.toDouble() }.coerceAtLeast(1.0)
                }

                binding.tvDefenseStatSuccessRate.text = "${"%.0f".format(weightedSuccessRate)}%"
                binding.tvDefenseStatReaction.text    = msToSec(weightedReaction)
                // 20판 기준 환산 (성공+실패 = 20)
                binding.tvDefenseStatSuccess.text     = "%.1f".format(totalSuccess / totalPitches * 20)
                binding.tvDefenseStatFail.text        = "%.1f".format(totalFail / totalPitches * 20)

                val (rawBundles, remainingPitches) = splitIntoBundles20(
                    records, getCount = { it.pitchCount }, getDate = { it.date?.toDate() }
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
                    val needed = maxOf(0, BUNDLE_SIZE * 2 - totalPitches)
                    binding.tvDefenseGrowthNotice.apply {
                        visibility = android.view.View.VISIBLE
                        text = "📊 ${needed}구 더 훈련하면 성장 추이를 볼 수 있어요!"
                        contentDescription = "${needed}구 더 훈련하면 성장 추이를 확인할 수 있습니다"
                    }
                }
            }
            .addOnFailureListener { setDefenseEmpty() }
    }

    private fun makeDefenseBundle(
        pitches: List<PitchUnit<DefenseRecord>>,
        index: Int,
        isPartial: Boolean
    ): DefenseBundle {
        val sessions  = pitches.map { it.session }
        val dates     = pitches.mapNotNull { it.date }
        val firstDate = dates.minOrNull()
        val lastDate  = dates.maxOrNull()
        val (label, ttsLabel) = makeDateLabels(firstDate, lastDate, index)
        val pc = pitches.size

        val bSuccess  = sessions.sumOf { it.successCount.toDouble() / it.pitchCount } / sessions.size * pc
        val bFail     = sessions.sumOf { it.failCount.toDouble() / it.pitchCount } / sessions.size * pc
        val bRate     = if (pc > 0) bSuccess / pc * 100 else 0.0
        val bReactionRaw = sessions.filter { it.reactionAvg > 0 }.let { f ->
            if (f.isEmpty()) null
            else f.sumOf { it.reactionAvg.toDouble() } / f.size
        }
        return DefenseBundle(
            index       = index,
            label       = label,
            ttsLabel    = ttsLabel,
            pitchCount  = pc,
            isPartial   = isPartial,
            successRate = roundDiff2(bRate).toDouble(),
            reactionAvg = bReactionRaw?.let { roundDiff2(it / 1000.0).toDouble() * 1000.0 },
            successAvg  = roundDiff2(bSuccess).toDouble(),
            failAvg     = roundDiff2(bFail).toDouble()
        )
    }

    private fun addCompareText(
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

    private fun addCompareTextNoData(
        container: LinearLayout,
        label: String, before: String, after: String
    ) {
        val tv = TextView(this).apply {
            text = "$label   $before → $after   기록 없음"
            textSize = 15f; setTextColor(Color.parseColor("#AAAAAA")); setPadding(12, 8, 12, 8)
        }
        container.addView(tv)
    }

    private fun setBattingEmpty() {
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

    private fun setDefenseEmpty() {
        binding.tvDefenseStatCount.text       = "기록 없음"
        binding.tvDefenseStatSuccessRate.text = "-"
        binding.tvDefenseStatReaction.text    = "-"
        binding.tvDefenseStatSuccess.text     = "-"
        binding.tvDefenseStatFail.text        = "-"
        binding.tvDefenseGrowthSection.visibility = android.view.View.GONE
        binding.llDefenseGrowthRows.visibility    = android.view.View.GONE
        binding.tvDefenseGrowthNotice.visibility  = android.view.View.GONE
    }

    private fun setupNavigation() {
        binding.navHome.setOnClickListener {
            val intent = Intent(this, HomeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
        }
        binding.navTraining.setOnClickListener {
            startActivity(Intent(this, TrainingActivity::class.java))
        }
        binding.navRecord.setOnClickListener { }
    }

    private fun setupClickListeners() {
        binding.btnSetting.setOnClickListener {
            startActivity(Intent(this, SettingActivity::class.java))
        }
    }

    data class BattingRecord(
        val pitchCount: Int,
        val battingAvg: Float,
        val reactionAvg: Float,
        val hitCount: Float,
        val foulCount: Float,
        val strikeCount: Float,
        val baseCorrectPct: Float,
        val baseCorrect: Float,
        val date: com.google.firebase.Timestamp? = null
    )

    data class DefenseRecord(
        val pitchCount: Int,
        val successRate: Float,
        val reactionAvg: Float,
        val successCount: Float,
        val failCount: Float,
        val date: com.google.firebase.Timestamp? = null
    )
}