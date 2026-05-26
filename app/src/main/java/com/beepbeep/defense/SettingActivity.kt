package com.beepbeep.defense

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore

class SettingActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.setting)

        findViewById<LinearLayout>(R.id.ll_setting_header).let { header ->
            header.postDelayed({
                header.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                    null
                )
            }, 1500)
        }

        val pref = getSharedPreferences("UserInfo", MODE_PRIVATE)
        val userId = pref.getString("id", "") ?: ""

        // 이름 변경
        findViewById<LinearLayout>(R.id.itemChangeName).setOnClickListener {
            val input = EditText(this)
            input.hint = "새 이름 입력"
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
                    getSharedPreferences("TutorialPrefs", MODE_PRIVATE)
                        .edit().putBoolean("batting_tutorial_done", false).apply()
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
                    getSharedPreferences("TutorialPrefs", MODE_PRIVATE)
                        .edit().putBoolean("defense_tutorial_done", false).apply()
                    Toast.makeText(this, "수비 튜토리얼이 초기화되었습니다", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        // 훈련 기록 초기화
        findViewById<LinearLayout>(R.id.itemResetRecord).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("훈련 기록 초기화")
                .setMessage("모든 훈련 기록이 삭제됩니다. 계속하시겠습니까?")
                .setPositiveButton("초기화") { _, _ ->
                    getSharedPreferences("TrainingStats_$userId", MODE_PRIVATE)
                        .edit().clear().apply()
                    Toast.makeText(this, "훈련 기록이 초기화되었습니다", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
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
                            val intent = Intent(this, LoginActivity::class.java)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TASK
                            startActivity(intent)
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