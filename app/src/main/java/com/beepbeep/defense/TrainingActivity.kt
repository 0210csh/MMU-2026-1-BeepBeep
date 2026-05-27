package com.beepbeep.defense

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.beepbeep.defense.databinding.ActivityTrainingBinding
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

class TrainingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrainingBinding
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityTrainingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutBottomNav) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, view.paddingTop, 0, systemBars.bottom)
            insets
        }

        binding.root.postDelayed({
            binding.llTrainingHeader.performAccessibilityAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }, 1500)

        loadRecentRecords()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        loadRecentRecords()
    }

    private fun loadRecentRecords() {
        val userId = getSharedPreferences("UserInfo", MODE_PRIVATE)
            .getString("id", "anonymous") ?: "anonymous"
        loadBattingRecentRecord(userId)
        loadDefenseRecentRecord(userId)
    }

    // ── 타격: 마지막 세션 투구별기록 ──
    private fun loadBattingRecentRecord(userId: String) {
        db.collection("users").document(userId).collection("훈련기록")
            .orderBy("생성일시", Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .addOnSuccessListener { sessions ->
                if (sessions.isEmpty) { showBattingEmpty(); return@addOnSuccessListener }
                val lastSession = sessions.documents[0]
                val sessionId   = lastSession.id

                db.collection("users").document(userId)
                    .collection("훈련기록").document(sessionId)
                    .collection("투구별기록")
                    .orderBy("투구번호", Query.Direction.ASCENDING)
                    .get()
                    .addOnSuccessListener { pitches ->
                        if (pitches.isEmpty) { showBattingSummary(lastSession); return@addOnSuccessListener }

                        val totalCount = pitches.size()
                        var hitCount = 0; var foulCount = 0; var strikeCount = 0
                        for (doc in pitches.documents) {
                            when (doc.getString("판정")?.replace("(무스윙)", "")?.trim()) {
                                "정타"     -> hitCount++
                                "파울"     -> foulCount++
                                "스트라이크" -> strikeCount++
                            }
                        }
                        val battingAvg = if (totalCount > 0) hitCount.toFloat() / totalCount else 0f
                        binding.tvBatSummary.text =
                            "최근 ${totalCount}회  |  타율 ${"%.3f".format(battingAvg)}\n정타 $hitCount / 파울 $foulCount / 스트라이크 $strikeCount"
                        binding.tvBatSummary.visibility = android.view.View.VISIBLE

                        binding.llBatCards.removeAllViews()
                        for (doc in pitches.documents) {
                            val num        = doc.getLong("투구번호") ?: 0
                            val judgment   = doc.getString("판정") ?: "-"
                            val reaction   = doc.getLong("주루반응속도")
                            val baseAnswer = doc.getBoolean("베이스정답여부")

                            val displayJudgment = judgment.replace("(무스윙)", "").trim()

                            val cardColor = when (displayJudgment) {
                                "정타"     -> 0xFF1A3A1A.toInt()
                                "파울"     -> 0xFF3A2E00.toInt()
                                "스트라이크" -> 0xFF3A1A1A.toInt()
                                else       -> 0xFF1A1A1A.toInt()
                            }
                            val judgmentColor = when (displayJudgment) {
                                "정타"     -> 0xFF5CF387.toInt()
                                "파울"     -> 0xFFFBBF24.toInt()
                                "스트라이크" -> 0xFFF87171.toInt()
                                else       -> 0xFFAAAAAA.toInt()
                            }
                            val emoji = when (displayJudgment) {
                                "정타"     -> "🟢"
                                "파울"     -> "🟡"
                                "스트라이크" -> "🔴"
                                else       -> "⚪"
                            }

                            val reactionText = if (reaction != null && reaction > 0)
                                "반응속도 ${"%.2f".format(reaction / 1000.0)}초" else ""
                            val baseText = when (baseAnswer) {
                                true  -> "베이스 정답"
                                false -> "베이스 오답"
                                null  -> ""
                            }
                            val cardDesc = listOf("${num}번 투구", displayJudgment, reactionText, baseText)
                                .filter { it.isNotEmpty() }.joinToString(" ")

                            val card = LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity     = Gravity.CENTER_VERTICAL
                                setBackgroundColor(cardColor)
                                val lp = LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                )
                                lp.setMargins(0, 0, 0, 6)
                                layoutParams = lp
                                setPadding(16, 14, 16, 14)
                                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
                                contentDescription = cardDesc
                                isFocusable = true
                            }

                            val tvNum = TextView(this).apply {
                                text = "${num}번"
                                textSize = 16f
                                setTextColor(0xFF888888.toInt())
                                layoutParams = LinearLayout.LayoutParams(120, ViewGroup.LayoutParams.WRAP_CONTENT)
                                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            }

                            val tvJudgment = TextView(this).apply {
                                text = "$emoji $displayJudgment"
                                textSize = 17f
                                setTextColor(judgmentColor)
                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            }

                            val tvReaction = TextView(this).apply {
                                text = if (reaction != null && reaction > 0)
                                    "${"%.2f".format(reaction / 1000.0)}s" else "-"
                                textSize = 15f
                                setTextColor(0xFFFFB74D.toInt())
                                layoutParams = LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                )
                                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            }

                            val tvBase = TextView(this).apply {
                                text = when (baseAnswer) {
                                    true  -> "  베이스 ✓"
                                    false -> "  베이스 ✗"
                                    null  -> ""
                                }
                                textSize = 15f
                                setTextColor(when (baseAnswer) {
                                    true  -> 0xFF5CF387.toInt()
                                    false -> 0xFFF87171.toInt()
                                    null  -> 0xFF888888.toInt()
                                })
                                layoutParams = LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                )
                                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            }

                            card.addView(tvNum)
                            card.addView(tvJudgment)
                            card.addView(tvReaction)
                            card.addView(tvBase)
                            binding.llBatCards.addView(card)
                        }
                    }
                    .addOnFailureListener { showBattingSummary(lastSession) }
            }
            .addOnFailureListener { showBattingEmpty() }
    }

    private fun showBattingEmpty() {
        binding.tvBatSummary.visibility = android.view.View.GONE
        binding.llBatCards.removeAllViews()
        val tv = TextView(this).apply {
            text = "기록 없음"
            textSize = 16f
            setTextColor(0xFF5CF387.toInt())
            setPadding(4, 8, 4, 8)
        }
        binding.llBatCards.addView(tv)
    }

    private fun showBattingSummary(doc: com.google.firebase.firestore.DocumentSnapshot) {
        val result = doc.get("종합결과") as? Map<*, *>
        if (result == null) { showBattingEmpty(); return }
        val battingAvg = (result["타율"] as? Number)?.toFloat() ?: 0f
        val hit        = (result["정타수"] as? Number)?.toFloat() ?: 0f
        val foul       = (result["파울수"] as? Number)?.toFloat() ?: 0f
        val strike     = (result["스트라이크수"] as? Number)?.toFloat() ?: 0f
        val reaction   = (result["반응속도평균"] as? Number)?.toFloat() ?: 0f
        val basePct    = (result["베이스정답률"] as? Number)?.toFloat() ?: 0f
        binding.tvBatSummary.text = "타율 ${"%.3f".format(battingAvg)}  |  정타 ${"%.0f".format(hit)} / 파울 ${"%.0f".format(foul)} / 스트라이크 ${"%.0f".format(strike)}"
        binding.tvBatSummary.visibility = android.view.View.VISIBLE
        binding.llBatCards.removeAllViews()
        val tv = TextView(this).apply {
            text = "베이스 정답률 ${"%.0f".format(basePct)}%  |  반응속도 ${"%.2f".format(reaction / 1000.0)}s"
            textSize = 15f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(4, 4, 4, 4)
        }
        binding.llBatCards.addView(tv)
    }

    // ── 수비: 마지막 세션 회차별 기록 ──
    private fun loadDefenseRecentRecord(userId: String) {
        db.collection("users").document(userId).collection("수비훈련기록")
            .orderBy("생성일시", Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .addOnSuccessListener { sessions ->
                if (sessions.isEmpty) { showDefenseEmpty(); return@addOnSuccessListener }

                val doc         = sessions.documents[0]
                val sessionId   = doc.id
                val targetCount = (doc.get("목표횟수") as? Number)?.toInt() ?: 0
                val result      = doc.get("종합결과") as? Map<*, *>
                val successRate = (result?.get("성공률") as? Number)?.toFloat() ?: 0f
                val successCnt  = (result?.get("성공횟수") as? Number)?.toFloat() ?: 0f
                val failCnt     = (result?.get("실패횟수") as? Number)?.toFloat() ?: 0f

                binding.tvDefSummary.text =
                    "최근 ${targetCount}회  |  성공률 ${"%.0f".format(successRate)}%\n성공 ${"%.0f".format(successCnt)} / 실패 ${"%.0f".format(failCnt)}"
                binding.tvDefSummary.visibility = android.view.View.VISIBLE

                db.collection("users").document(userId)
                    .collection("수비훈련기록").document(sessionId)
                    .collection("포구별기록")
                    .orderBy("회차", Query.Direction.ASCENDING)
                    .get()
                    .addOnSuccessListener { catches ->
                        binding.llDefCards.removeAllViews()
                        if (catches.isEmpty) {
                            val tv = TextView(this).apply {
                                text = "회차별 기록 없음"
                                textSize = 15f
                                setTextColor(0xFF888888.toInt())
                                setPadding(4, 8, 4, 8)
                            }
                            binding.llDefCards.addView(tv)
                            return@addOnSuccessListener
                        }

                        for ((idx, catchDoc) in catches.documents.withIndex()) {
                            val round     = (catchDoc.get("회차") as? Number)?.toInt() ?: (idx + 1)
                            val isSuccess = catchDoc.getString("결과") == "성공"
                            val ms        = (catchDoc.get("반응속도") as? Number)?.toLong() ?: -1L

                            val cardColor   = if (isSuccess) 0xFF0D2233.toInt() else 0xFF2A1010.toInt()
                            val resultColor = if (isSuccess) 0xFF38BDF8.toInt() else 0xFFF87171.toInt()
                            val resultEmoji = if (isSuccess) "✅" else "❌"
                            val resultText  = if (isSuccess) "성공" else "실패"
                            val reactionStr = if (ms > 0) "반응속도 ${"%.2f".format(ms / 1000.0)}초" else ""

                            val cardDesc = listOf("${round}번", resultText, reactionStr)
                                .filter { it.isNotEmpty() }.joinToString(" ")

                            val card = LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity     = Gravity.CENTER_VERTICAL
                                setBackgroundColor(cardColor)
                                val lp = LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                )
                                lp.setMargins(0, 0, 0, 6)
                                layoutParams = lp
                                setPadding(16, 14, 16, 14)
                                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
                                contentDescription = cardDesc
                                isFocusable = true
                            }

                            val tvNum = TextView(this).apply {
                                text = "${round}번"
                                textSize = 16f
                                setTextColor(0xFF888888.toInt())
                                layoutParams = LinearLayout.LayoutParams(120, ViewGroup.LayoutParams.WRAP_CONTENT)
                                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            }

                            val tvResult = TextView(this).apply {
                                text = "$resultEmoji $resultText"
                                textSize = 17f
                                setTextColor(resultColor)
                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            }

                            val tvReaction = TextView(this).apply {
                                text = if (ms > 0) "${"%.2f".format(ms / 1000.0)}s" else "-"
                                textSize = 15f
                                setTextColor(0xFFFFB74D.toInt())
                                layoutParams = LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                )
                                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            }

                            card.addView(tvNum)
                            card.addView(tvResult)
                            card.addView(tvReaction)
                            binding.llDefCards.addView(card)
                        }
                    }
                    .addOnFailureListener { showDefenseEmpty() }
            }
            .addOnFailureListener { showDefenseEmpty() }
    }

    private fun showDefenseEmpty() {
        binding.tvDefSummary.visibility = android.view.View.GONE
        binding.llDefCards.removeAllViews()
        val tv = TextView(this).apply {
            text = "기록 없음"
            textSize = 16f
            setTextColor(0xFF38BDF8.toInt())
            setPadding(4, 8, 4, 8)
        }
        binding.llDefCards.addView(tv)
    }

    private fun setupClickListeners() {
        binding.btnSetting.setOnClickListener {
            startActivity(Intent(this, SettingActivity::class.java))
        }
        binding.btnStartBatting.setOnClickListener {
            startActivity(Intent(this, com.beepbeep.defense.batting.SwingTestActivity::class.java))
        }
        binding.btnStartDefense.setOnClickListener {
            val tutorialDone = getSharedPreferences("TutorialPrefs", MODE_PRIVATE)
                .getBoolean("defense_tutorial_done", false)
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("start_tutorial", !tutorialDone)
            startActivity(intent)
        }
        binding.navHome.setOnClickListener {
            val intent = Intent(this, HomeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
        }
        binding.navRecord.setOnClickListener {
            startActivity(Intent(this, RecordActivity::class.java))
        }
    }
}
