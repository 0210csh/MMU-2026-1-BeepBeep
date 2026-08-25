package com.beepbeep.defense

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.Locale

class SettingActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop(); tts?.shutdown()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.setting)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                ttsReady = true
            }
        }

        // ※ 헤더 강제 포커스 로직 제거함
        // 토크백은 액티비티 진입 시 자동으로 상단부터 안내하므로 불필요

        val pref = getSharedPreferences("UserInfo", MODE_PRIVATE)
        val userId = pref.getString("id", "") ?: ""

        // 이름 변경
        findViewById<LinearLayout>(R.id.itemChangeName).setOnClickListener {
            val input = EditText(this)
            input.hint = "새 이름 입력"
            input.contentDescription = "새 이름 입력"
            AlertDialog.Builder(this)
                .setTitle("이름 변경")
                .setView(input)
                .setPositiveButton("변경") { _, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isEmpty()) {
                        Toast.makeText(this, "이름을 입력해주세요", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    db.collection("users").document(userId)
                        .update("name", newName)
                        .addOnSuccessListener {
                            pref.edit().putString("name", newName).apply()
                            Toast.makeText(this, "이름이 변경되었습니다", Toast.LENGTH_SHORT).show()
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "변경 실패", Toast.LENGTH_SHORT).show()
                        }
                }
                .setNegativeButton("취소", null)
                .show()
        }

        // 비밀번호 변경
        findViewById<LinearLayout>(R.id.itemChangePw).setOnClickListener {
            val input = EditText(this)
            input.hint = "새 비밀번호 입력"
            input.contentDescription = "새 비밀번호 입력"
            input.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            AlertDialog.Builder(this)
                .setTitle("비밀번호 변경")
                .setView(input)
                .setPositiveButton("변경") { _, _ ->
                    val newPw = input.text.toString().trim()
                    if (newPw.isEmpty()) {
                        Toast.makeText(this, "비밀번호를 입력해주세요", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    db.collection("users").document(userId)
                        .update("pw", newPw)
                        .addOnSuccessListener {
                            Toast.makeText(this, "비밀번호가 변경되었습니다", Toast.LENGTH_SHORT).show()
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "변경 실패", Toast.LENGTH_SHORT).show()
                        }
                }
                .setNegativeButton("취소", null)
                .show()
        }

        // 타격 튜토리얼 다시보기
        findViewById<LinearLayout>(R.id.itemResetBattingTutorial).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("타격 튜토리얼 초기화")
                .setMessage("다음 타격 훈련 시작 시 튜토리얼이 다시 진행됩니다.")
                .setPositiveButton("확인") { _, _ ->
                    // 로컬 초기화
                    getSharedPreferences("TutorialPrefs", MODE_PRIVATE)
                        .edit().putBoolean("batting_tutorial_done", false).apply()
                    // Firebase도 함께 초기화
                    if (userId.isNotEmpty()) {
                        db.collection("users").document(userId)
                            .set(mapOf("battingTutorialDone" to false), SetOptions.merge())
                    }
                    Toast.makeText(this, "타격 튜토리얼이 초기화되었습니다", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        // 수비 튜토리얼 다시보기
        findViewById<LinearLayout>(R.id.itemResetDefenseTutorial).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("수비 튜토리얼 초기화")
                .setMessage("다음 수비 훈련 시작 시 튜토리얼이 다시 진행됩니다.")
                .setPositiveButton("확인") { _, _ ->
                    // 로컬 초기화
                    getSharedPreferences("TutorialPrefs", MODE_PRIVATE)
                        .edit().putBoolean("defense_tutorial_done", false).apply()
                    // Firebase도 함께 초기화
                    if (userId.isNotEmpty()) {
                        db.collection("users").document(userId)
                            .set(mapOf("defenseTutorialDone" to false), SetOptions.merge())
                    }
                    Toast.makeText(this, "수비 튜토리얼이 초기화되었습니다", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        // 가중치 설정 — 다이얼로그 없이 TTS로만 안내 (변경 없음)
        findViewById<LinearLayout>(R.id.itemWeightInfo).setOnClickListener {
            speak(
                "타격 점수는 타율 60퍼센트, 주루 선택 반응속도 40퍼센트를 합산해서 계산됩니다. " +
                        "수비 점수는 성공률 60퍼센트, 반응속도 40퍼센트를 합산해서 계산됩니다."
            )
        }

        // 알림 받기 — Firestore users/{id}.notificationsEnabled 제어 (변경 없음)
        val switchNotification = findViewById<Switch>(R.id.switchNotification)
        if (userId.isNotEmpty()) {
            db.collection("users").document(userId).get()
                .addOnSuccessListener { doc ->
                    switchNotification.isChecked = doc.getBoolean("notificationsEnabled") ?: true
                }
        }
        switchNotification.setOnCheckedChangeListener { _, isChecked ->
            if (userId.isNotEmpty()) {
                db.collection("users").document(userId)
                    .set(mapOf("notificationsEnabled" to isChecked), SetOptions.merge())
            }
        }

        // 로그아웃
        findViewById<LinearLayout>(R.id.itemLogout).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("로그아웃")
                .setMessage("로그아웃 하시겠습니까?")
                .setPositiveButton("로그아웃") { _, _ ->
                    pref.edit().clear().apply()
                    val intent = Intent(this, LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                }
                .setNegativeButton("취소", null)
                .show()
        }

        // 회원탈퇴
        findViewById<LinearLayout>(R.id.itemDeleteAccount).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("회원탈퇴")
                .setMessage("정말 탈퇴하시겠습니까? 모든 데이터가 삭제됩니다.")
                .setPositiveButton("탈퇴") { _, _ ->
                    db.collection("users").document(userId)
                        .delete()
                        .addOnSuccessListener {
                            pref.edit().clear().apply()
                            getSharedPreferences("TrainingStats_$userId", MODE_PRIVATE)
                                .edit().clear().apply()
                            Toast.makeText(this, "탈퇴되었습니다", Toast.LENGTH_SHORT).show()
                            // 토크백이 "탈퇴되었습니다" 메시지를 읽을 시간을 확보한 후 화면 전환
                            Handler(Looper.getMainLooper()).postDelayed({
                                val intent = Intent(this, LoginActivity::class.java)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TASK
                                startActivity(intent)
                            }, 2000)
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "탈퇴 실패", Toast.LENGTH_SHORT).show()
                        }
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }
}