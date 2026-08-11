package com.beepbeep.defense

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.util.Calendar
import java.util.Locale

class RankingActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var currentCategory = "batting" // "batting" | "defense"

    private lateinit var btnCategoryBatting: TextView
    private lateinit var btnCategoryDefense: TextView
    private lateinit var tvMyRank: TextView
    private lateinit var llMyBreakdown: LinearLayout
    private lateinit var tvMinSampleNotice: TextView
    private lateinit var llRankingList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_ranking)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.layoutBottomNav)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, view.paddingTop, 0, systemBars.bottom)
            insets
        }

        findViewById<View>(R.id.ll_ranking_header).postDelayed({
            findViewById<View>(R.id.ll_ranking_header).performAccessibilityAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }, 1500)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                ttsReady = true
            }
        }

        btnCategoryBatting = findViewById(R.id.btnCategoryBatting)
        btnCategoryDefense = findViewById(R.id.btnCategoryDefense)
        tvMyRank           = findViewById(R.id.tvMyRank)
        llMyBreakdown      = findViewById(R.id.llMyBreakdown)
        tvMinSampleNotice  = findViewById(R.id.tvMinSampleNotice)
        llRankingList      = findViewById(R.id.llRankingList)

        btnCategoryBatting.setOnClickListener { switchCategory("batting") }
        btnCategoryDefense.setOnClickListener { switchCategory("defense") }

        setupNavigation()
        switchCategory("batting")
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop(); tts?.shutdown()
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun switchCategory(category: String) {
        currentCategory = category
        val battingActive = category == "batting"
        btnCategoryBatting.setBackgroundColor(if (battingActive) 0xFF5CF387.toInt() else 0xFF1A1A1A.toInt())
        btnCategoryBatting.setTextColor(if (battingActive) 0xFF0A0A0A.toInt() else 0xFFAAAAAA.toInt())
        btnCategoryDefense.setBackgroundColor(if (!battingActive) 0xFF38BDF8.toInt() else 0xFF1A1A1A.toInt())
        btnCategoryDefense.setTextColor(if (!battingActive) 0xFF0A0A0A.toInt() else 0xFFAAAAAA.toInt())
        loadRanking(category)
    }

    private fun currentQuarter(): String {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val quarter = cal.get(Calendar.MONTH) / 3 + 1
        return "${year}Q$quarter"
    }

    private fun collectionFor(category: String) =
        if (category == "batting") "rankings_batting" else "rankings_defense"

    private fun sampleCountOf(category: String, doc: com.google.firebase.firestore.DocumentSnapshot): Long =
        if (category == "batting") doc.getLong("totalPitches") ?: 0L else doc.getLong("attemptCount") ?: 0L

    private fun loadRanking(category: String) {
        val userId   = getSharedPreferences("UserInfo", MODE_PRIVATE).getString("id", "anonymous") ?: "anonymous"
        val userName = getSharedPreferences("UserInfo", MODE_PRIVATE).getString("name", "") ?: ""
        val quarter = currentQuarter()
        val collectionName = collectionFor(category)

        // 이 화면에 들어올 때마다 이번 분기 훈련기록을 원본에서 다시 집계해 덮어쓴다.
        // 세션 종료 훅이 놓친 과거 기록(예: 랭킹 기능 도입 이전 기록)까지 소급 반영하기 위함.
        val onSynced = {
            loadMyRank(category, collectionName, userId, quarter)
            loadTopList(category, collectionName, quarter)
        }
        if (category == "batting") {
            RankingUpdater.syncBattingFromHistory(userId, userName, onSynced)
        } else {
            RankingUpdater.syncDefenseFromHistory(userId, userName, onSynced)
        }
    }

    private fun loadTopList(category: String, collectionName: String, quarter: String) {
        db.collection(collectionName)
            .whereEqualTo("quarter", quarter)
            .orderBy("score", Query.Direction.DESCENDING)
            .limit(100)
            .get()
            .addOnSuccessListener { docs ->
                val qualified = docs.documents.filter { sampleCountOf(category, it) >= RankingUpdater.MIN_SAMPLE }
                renderList(category, qualified.take(50))
            }
            .addOnFailureListener { renderList(category, emptyList()) }
    }

    private fun loadMyRank(category: String, collectionName: String, userId: String, quarter: String) {
        if (userId == "anonymous") {
            tvMyRank.text = "로그인이 필요합니다"
            tvMinSampleNotice.visibility = View.GONE
            llMyBreakdown.removeAllViews()
            return
        }
        db.collection(collectionName).document(userId).get()
            .addOnSuccessListener { myDoc ->
                val sameQuarter = myDoc.exists() && myDoc.getString("quarter") == quarter
                val mySample = if (sameQuarter) sampleCountOf(category, myDoc) else 0L
                llMyBreakdown.removeAllViews()

                if (!sameQuarter || mySample < RankingUpdater.MIN_SAMPLE) {
                    tvMyRank.text = "아직 랭킹에 반영되지 않았습니다"
                    tvMinSampleNotice.visibility = View.VISIBLE
                    return@addOnSuccessListener
                }
                tvMinSampleNotice.visibility = View.GONE
                val myScore = myDoc.getDouble("score") ?: 0.0

                db.collection(collectionName)
                    .whereEqualTo("quarter", quarter)
                    .whereGreaterThan("score", myScore)
                    .count().get(AggregateSource.SERVER)
                    .addOnSuccessListener { agg ->
                        val rank = agg.count + 1
                        tvMyRank.text = "내 순위: ${rank}위 · 점수 ${"%.1f".format(myScore)}점"
                        tvMyRank.contentDescription = "내 순위 ${rank}위, 점수 ${"%.1f".format(myScore)}점"
                        addBreakdown(category, myDoc)
                        speak(
                            "${if (category == "batting") "타격" else "수비"} 랭킹, $rank 위 입니다."
                        )
                    }
                    .addOnFailureListener {
                        tvMyRank.text = "내 점수: ${"%.1f".format(myScore)}점"
                        addBreakdown(category, myDoc)
                    }
            }
            .addOnFailureListener {
                tvMyRank.text = "아직 랭킹에 반영되지 않았습니다"
                tvMinSampleNotice.visibility = View.VISIBLE
            }
    }

    private fun addBreakdown(category: String, doc: com.google.firebase.firestore.DocumentSnapshot) {
        val lines = if (category == "batting") {
            val totalPitches = doc.getLong("totalPitches") ?: 0L
            val hitCount      = doc.getLong("hitCount") ?: 0L
            val reactionSum   = doc.getLong("reactionSumMs") ?: 0L
            val reactionCnt   = doc.getLong("reactionSampleCount") ?: 0L
            val avg           = if (totalPitches > 0) hitCount.toDouble() / totalPitches * 100.0 else 0.0
            val avgReaction   = if (reactionCnt > 0) reactionSum.toDouble() / reactionCnt else null
            val reactScore    = reactionScore(avgReaction, 300.0, 2000.0)
            listOf(
                "타율 ${"%.0f".format(avg)}%  × 60%  =  ${"%.1f".format(avg * 0.6)}점",
                "반응속도 ${"%.0f".format(reactScore)}점  × 40%  =  ${"%.1f".format(reactScore * 0.4)}점"
            )
        } else {
            val attempts     = doc.getLong("attemptCount") ?: 0L
            val successes    = doc.getLong("successCount") ?: 0L
            val reactionSum  = doc.getLong("reactionSumMs") ?: 0L
            val reactionCnt  = doc.getLong("reactionSampleCount") ?: 0L
            val rate         = if (attempts > 0) successes.toDouble() / attempts * 100.0 else 0.0
            val avgReaction  = if (reactionCnt > 0) reactionSum.toDouble() / reactionCnt else null
            val reactScore   = reactionScore(avgReaction, 500.0, 3000.0)
            listOf(
                "성공률 ${"%.0f".format(rate)}%  × 60%  =  ${"%.1f".format(rate * 0.6)}점",
                "반응속도 ${"%.0f".format(reactScore)}점  × 40%  =  ${"%.1f".format(reactScore * 0.4)}점"
            )
        }
        val score = doc.getDouble("score") ?: 0.0
        (lines + "최종 점수  ${"%.1f".format(score)}점").forEach { line ->
            llMyBreakdown.addView(TextView(this).apply {
                text = line
                textSize = 14f
                setTextColor(0xFFCCCCCC.toInt())
                setPadding(0, 4, 0, 4)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            })
        }
    }

    private fun reactionScore(avgMs: Double?, minMs: Double, maxMs: Double): Double {
        if (avgMs == null) return 0.0
        return (((maxMs - avgMs) / (maxMs - minMs)) * 100.0).coerceIn(0.0, 100.0)
    }

    private fun renderList(category: String, docs: List<DocumentSnapshot>) {
        llRankingList.removeAllViews()
        if (docs.isEmpty()) {
            llRankingList.addView(TextView(this).apply {
                text = "이번 분기 아직 랭킹에 오른 사용자가 없습니다"
                textSize = 15f
                setTextColor(0xFF888888.toInt())
                setPadding(4, 8, 4, 8)
            })
            return
        }
        docs.forEachIndexed { index, doc ->
            val rank = index + 1
            val name = doc.getString("name")?.takeIf { it.isNotBlank() } ?: "익명"
            val score = doc.getDouble("score") ?: 0.0
            val scoreText = "%.1f".format(score)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0xFF1A1A1A.toInt())
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, 6)
                layoutParams = lp
                setPadding(16, 14, 16, 14)
                isFocusable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = "${rank}위, $name, ${scoreText}점"
            }
            val medalSizePx = (22 * resources.displayMetrics.density).toInt()
            val ivMedal = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_ranking)
                setColorFilter(medalColor(rank))
                layoutParams = LinearLayout.LayoutParams(medalSizePx, medalSizePx).apply {
                    marginEnd = (10 * resources.displayMetrics.density).toInt()
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val tvRank = TextView(this).apply {
                text = "${rank}위"
                textSize = 16f
                setTextColor(0xFF888888.toInt())
                layoutParams = LinearLayout.LayoutParams(70, ViewGroup.LayoutParams.WRAP_CONTENT)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val tvName = TextView(this).apply {
                text = name
                textSize = 17f
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val tvScore = TextView(this).apply {
                text = "${scoreText}점"
                textSize = 16f
                setTextColor(if (category == "batting") 0xFF5CF387.toInt() else 0xFF38BDF8.toInt())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(ivMedal); row.addView(tvRank); row.addView(tvName); row.addView(tvScore)
            row.setOnClickListener { speak("${rank}위, $name, ${scoreText}점") }
            llRankingList.addView(row)
        }
    }

    private fun medalColor(rank: Int): Int = when (rank) {
        1 -> 0xFFFFD700.toInt() // 금
        2 -> 0xFFC0C0C0.toInt() // 은
        3 -> 0xFFCD7F32.toInt() // 동
        else -> 0xFF555555.toInt()
    }

    private fun setupNavigation() {
        findViewById<LinearLayout>(R.id.navHome).setOnClickListener {
            val intent = Intent(this, HomeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
        }
        findViewById<LinearLayout>(R.id.navTraining).setOnClickListener {
            startActivity(Intent(this, TrainingActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.navRecord).setOnClickListener {
            startActivity(Intent(this, RecordActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.navRanking).setOnClickListener { }
        findViewById<LinearLayout>(R.id.navReservation).setOnClickListener {
            startActivity(Intent(this, ReservationActivity::class.java))
        }
        findViewById<android.widget.ImageButton>(R.id.btnSetting).setOnClickListener {
            startActivity(Intent(this, SettingActivity::class.java))
        }
    }
}
