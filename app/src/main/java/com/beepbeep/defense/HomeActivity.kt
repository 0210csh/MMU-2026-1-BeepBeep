package com.beepbeep.defense

import android.content.Intent
import android.os.Bundle
import com.google.firebase.firestore.FirebaseFirestore
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment

class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_home)

        val mainLayout      = findViewById<ConstraintLayout>(R.id.main_layout)
        val layoutHeader    = findViewById<LinearLayout>(R.id.layoutHeader)
        val btnSetting      = findViewById<ImageButton>(R.id.btnSetting)
        val layoutBottomNav = findViewById<LinearLayout>(R.id.layoutBottomNav)

        ViewCompat.setOnApplyWindowInsetsListener(layoutBottomNav) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, view.paddingTop, 0, systemBars.bottom)
            insets
        }

        mainLayout.post {
            mainLayout.postDelayed({
                layoutHeader.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                    null
                )
            }, 1500)
        }

        val cardTraining = findViewById<CardView>(R.id.cardTraining)
        val cardMyRecord = findViewById<CardView>(R.id.cardMyRecord)

        val navHome     = findViewById<LinearLayout>(R.id.navHome)
        val navTraining = findViewById<LinearLayout>(R.id.navTraining)
        val navRecord   = findViewById<LinearLayout>(R.id.navRecord)

        // ── 훈련 선택 카드 ──
        cardTraining?.setOnClickListener {
            startActivity(Intent(this, TrainingActivity::class.java))
        }

        // ── 내 기록 카드 ──
        cardMyRecord?.setOnClickListener {
            startActivity(Intent(this, RecordActivity::class.java))
        }

        navHome.setOnClickListener {
            findViewById<LinearLayout>(R.id.contentHome).visibility = View.VISIBLE
            findViewById<FrameLayout>(R.id.fragment_container).visibility = View.GONE
            updateBottomNavSelection("home")
        }

        navTraining.setOnClickListener {
            startActivity(Intent(this, TrainingActivity::class.java))
        }

        navRecord.setOnClickListener {
            startActivity(Intent(this, RecordActivity::class.java))
        }

        btnSetting.setOnClickListener {
            startActivity(Intent(this, SettingActivity::class.java))
        }

        updateBottomNavSelection("home")

        val userId = getSharedPreferences("UserInfo", MODE_PRIVATE).getString("id", "anonymous") ?: "anonymous"
        FirebaseFirestore.getInstance().collection("admins").document(userId).get()
            .addOnSuccessListener { adminDoc ->
                getSharedPreferences("AdminCache", MODE_PRIVATE)
                    .edit().putBoolean("isAdmin", adminDoc.exists()).apply()
            }
    }

    fun replaceFragment(fragment: Fragment) {
        findViewById<LinearLayout>(R.id.contentHome).visibility = View.GONE
        findViewById<FrameLayout>(R.id.fragment_container).visibility = View.VISIBLE

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun updateBottomNavSelection(currentTab: String) {
        val navHome     = findViewById<LinearLayout>(R.id.navHome)
        val navTraining = findViewById<LinearLayout>(R.id.navTraining)
        val navRecord   = findViewById<LinearLayout>(R.id.navRecord)

        navHome.alpha     = 0.5f
        navTraining.alpha = 0.5f
        navRecord.alpha   = 0.5f

        when (currentTab) {
            "home"     -> navHome.alpha     = 1.0f
            "training" -> navTraining.alpha = 1.0f
            "record"   -> navRecord.alpha   = 1.0f
        }
    }
}