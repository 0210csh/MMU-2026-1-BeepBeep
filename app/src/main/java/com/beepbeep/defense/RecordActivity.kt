package com.beepbeep.defense

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.beepbeep.defense.databinding.ActivityRecordBinding

class RecordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordBinding

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
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                null
            )
        }, 1500)

        loadStats()
        setupNavigation()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        loadStats()
    }

    private fun loadStats() {
        val userPref = getSharedPreferences("UserInfo", MODE_PRIVATE)
        binding.tvUserName.text = userPref.getString("name", "로그인이 필요합니다")

        val userId = userPref.getString("id", "anonymous") ?: "anonymous"
        val statsPref = getSharedPreferences("TrainingStats_$userId", Context.MODE_PRIVATE)
        val count = statsPref.getInt("total_count", 0)

        if (count > 0) {
            binding.tvStatCount.text = "전체 ${count}판 평균"

            val avgBattingAvg     = statsPref.getFloat("total_sum_batting_avg", 0f) / count
            val avgReaction       = statsPref.getFloat("total_sum_reaction", 0f) / count
            val avgHit            = statsPref.getFloat("total_sum_hit", 0f) / count
            val avgFoul           = statsPref.getFloat("total_sum_foul", 0f) / count
            val avgStrike         = statsPref.getFloat("total_sum_strike", 0f) / count
            val avgBaseCorrectPct = statsPref.getFloat("total_sum_base_correct_pct", 0f) / count
            val avgBaseCorrect    = statsPref.getFloat("total_sum_base_correct", 0f) / count

            binding.tvStatBattingAvg.text    = "%.3f".format(avgBattingAvg)
            binding.tvStatReaction.text      = "${"%.0f".format(avgReaction)}ms"
            binding.tvStatHit.text           = "%.1f".format(avgHit)
            binding.tvStatFoul.text          = "%.1f".format(avgFoul)
            binding.tvStatStrike.text        = "%.1f".format(avgStrike)
            binding.tvStatBaseCorrectPct.text = "${"%.0f".format(avgBaseCorrectPct)}%"
            binding.tvStatBaseCorrect.text   = "%.1f".format(avgBaseCorrect)
        } else {
            binding.tvStatCount.text          = "기록 없음"
            binding.tvStatBattingAvg.text     = "-"
            binding.tvStatReaction.text       = "-"
            binding.tvStatHit.text            = "-"
            binding.tvStatFoul.text           = "-"
            binding.tvStatStrike.text         = "-"
            binding.tvStatBaseCorrectPct.text = "-"
            binding.tvStatBaseCorrect.text    = "-"
        }
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
}