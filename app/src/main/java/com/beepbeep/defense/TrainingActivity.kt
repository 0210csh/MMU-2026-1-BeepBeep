package com.beepbeep.defense

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.beepbeep.defense.databinding.ActivityTrainingBinding

class TrainingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrainingBinding

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
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                null
            )
        }, 1500)

        loadRecentRecords()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        loadRecentRecords()
    }

    private fun loadRecentRecords() {
        val userId = getSharedPreferences("UserInfo", MODE_PRIVATE).getString("id", "anonymous") ?: "anonymous"
        val statsPref = getSharedPreferences("TrainingStats_$userId", Context.MODE_PRIVATE)
        val count = statsPref.getInt("count", 0)

        if (count > 0) {
            val avgBattingAvg     = statsPref.getFloat("sum_batting_avg", 0f) / count
            val avgBaseCorrectPct = statsPref.getFloat("sum_base_correct_pct", 0f) / count
            val avgReaction       = statsPref.getFloat("sum_reaction", 0f) / count
            val avgHit            = statsPref.getFloat("sum_hit", 0f) / count
            val avgFoul           = statsPref.getFloat("sum_foul", 0f) / count
            val avgStrike         = statsPref.getFloat("sum_strike", 0f) / count
            val avgBaseCorrectNum = statsPref.getFloat("sum_base_correct", 0f) / count

            binding.tvBatRate.text = buildString {
                appendLine("${count}판 평균")
                appendLine("타율: ${"%.3f".format(avgBattingAvg)}")
                appendLine("정타: ${"%.1f".format(avgHit)} / 파울: ${"%.1f".format(avgFoul)} / 스트라이크: ${"%.1f".format(avgStrike)}")
                appendLine("베이스 정답률: ${"%.0f".format(avgBaseCorrectPct)}%")
                appendLine("베이스 정답수: ${"%.1f".format(avgBaseCorrectNum)}")
                append("반응속도 평균: ${"%.0f".format(avgReaction)}ms")
            }
            binding.tvBatRate.setTextColor(resources.getColor(android.R.color.white, null))
        } else {
            binding.tvBatRate.text = "기록 없음"
            binding.tvBatRate.setTextColor(0xFF5CF387.toInt())
        }
    }

    private fun setupClickListeners() {

        binding.btnSetting.setOnClickListener {
            startActivity(Intent(this, SettingActivity::class.java))
        }

        binding.btnStartBatting.setOnClickListener {
            startActivity(Intent(this, com.beepbeep.defense.batting.SwingTestActivity::class.java))
        }

        binding.btnStartDefense.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
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