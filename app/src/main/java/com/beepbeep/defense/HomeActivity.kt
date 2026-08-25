package com.beepbeep.defense

import android.content.Intent
import android.os.Bundle
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
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

        // ※ 헤더 강제 포커스 로직 제거함
        // 토크백은 액티비티 진입 시 자동으로 상단부터 안내하므로 불필요

        val cardTraining   = findViewById<CardView>(R.id.cardTraining)
        val cardMyRecord   = findViewById<CardView>(R.id.cardMyRecord)
        val cardRanking    = findViewById<CardView>(R.id.cardRanking)
        val cardReservation = findViewById<CardView>(R.id.cardReservation)

        val navHome     = findViewById<LinearLayout>(R.id.navHome)
        val navTraining = findViewById<LinearLayout>(R.id.navTraining)
        val navRecord   = findViewById<LinearLayout>(R.id.navRecord)
        val navRanking  = findViewById<LinearLayout>(R.id.navRanking)
        val navReservation = findViewById<LinearLayout>(R.id.navReservation)

        // ── 훈련 선택 카드 ──
        cardTraining?.setOnClickListener {
            startActivity(Intent(this, TrainingActivity::class.java))
        }

        // ── 내 기록 카드 ──
        cardMyRecord?.setOnClickListener {
            startActivity(Intent(this, RecordActivity::class.java))
        }

        // ── 랭킹 카드 ──
        cardRanking?.setOnClickListener {
            startActivity(Intent(this, RankingActivity::class.java))
        }

        // ── 예약 카드 ──
        cardReservation?.setOnClickListener {
            startActivity(Intent(this, ReservationActivity::class.java))
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

        navRanking.setOnClickListener {
            startActivity(Intent(this, RankingActivity::class.java))
        }

        navReservation.setOnClickListener {
            startActivity(Intent(this, ReservationActivity::class.java))
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

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 200)
        }
        registerFcmToken(userId)
    }

    private fun registerFcmToken(userId: String) {
        if (userId == "anonymous") return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            FirebaseFirestore.getInstance().collection("users").document(userId)
                .set(mapOf("fcmToken" to token), com.google.firebase.firestore.SetOptions.merge())
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
        val navHome        = findViewById<LinearLayout>(R.id.navHome)
        val navTraining    = findViewById<LinearLayout>(R.id.navTraining)
        val navRecord      = findViewById<LinearLayout>(R.id.navRecord)
        val navRanking     = findViewById<LinearLayout>(R.id.navRanking)
        val navReservation = findViewById<LinearLayout>(R.id.navReservation)

        navHome.alpha        = 0.5f
        navTraining.alpha    = 0.5f
        navRecord.alpha      = 0.5f
        navRanking.alpha     = 0.5f
        navReservation.alpha = 0.5f

        when (currentTab) {
            "home"     -> navHome.alpha     = 1.0f
            "training" -> navTraining.alpha = 1.0f
            "record"   -> navRecord.alpha   = 1.0f
            "ranking"  -> navRanking.alpha  = 1.0f
            // "reservation"은 새 액티비티로 바로 이동하니 홈 화면에 남아있을 일이 없어서
            // 별도 case 없이도 괜찮아요 (다른 화면 눌러도 마찬가지 패턴)
        }
    }
}