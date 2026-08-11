package com.beepbeep.defense

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.beepbeep.defense.databinding.ActivityRecordBinding
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

class RecordActivity : AppCompatActivity() {

    internal lateinit var binding: ActivityRecordBinding
    internal val db = FirebaseFirestore.getInstance()

    internal var tts: TextToSpeech? = null
    internal var ttsReady = false

    internal var battingChartIndex = 0
    internal var defenseChartIndex = 0

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
        val userId   = userPref.getString("id", "anonymous") ?: "anonymous"
        loadBattingStatsFromFirebase(userId)
        loadDefenseStatsFromFirebase(userId)
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop(); tts?.shutdown()
    }

    internal fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
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
        binding.navRanking.setOnClickListener {
            startActivity(Intent(this, RankingActivity::class.java))
        }
        binding.navReservation.setOnClickListener {
            startActivity(Intent(this, ReservationActivity::class.java))
        }
    }

    private fun setupClickListeners() {
        binding.btnSetting.setOnClickListener {
            startActivity(Intent(this, SettingActivity::class.java))
        }
    }
}
