package com.beepbeep.defense

import android.content.Intent
import android.os.Bundle
import android.widget.*
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore

class SignupActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        findViewById<LinearLayout>(R.id.ll_signup_header).let { header ->
            header.postDelayed({
                header.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                    null
                )
            }, 1500)
        }

        val etName = findViewById<EditText>(R.id.et_name)
        val etId = findViewById<EditText>(R.id.et_id)
        val etPw = findViewById<EditText>(R.id.et_pw)
        val etPwConfirm = findViewById<EditText>(R.id.et_pw_confirm)
        val btnSignup = findViewById<Button>(R.id.btn_signup)
        val tvLogin = findViewById<TextView>(R.id.tv_login)
        val llWarning = findViewById<LinearLayout>(R.id.ll_warning)

        btnSignup.setOnClickListener {
            val name = etName.text.toString().trim()
            val id = etId.text.toString().trim()
            val pw = etPw.text.toString().trim()
            val pwConfirm = etPwConfirm.text.toString().trim()

            // 빈칸 검사
            if (name.isEmpty() || id.isEmpty() || pw.isEmpty()) {
                llWarning.visibility = android.view.View.VISIBLE
                return@setOnClickListener
            }
            llWarning.visibility = android.view.View.GONE

            // 비밀번호 확인
            if (pw != pwConfirm) {
                Toast.makeText(this, "비밀번호가 일치하지 않습니다", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Firebase에 저장할 데이터
            val userMap = hashMapOf<String, Any>(
                "name" to name,
                "id" to id,
                "pw" to pw
            )

            // Firebase 저장
            db.collection("users").document(id)
                .set(userMap)
                .addOnSuccessListener {
                    Toast.makeText(this, "회원가입을 축하합니다!", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this, LoginActivity::class.java)
                    startActivity(intent)
                    finish()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "가입 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }

        tvLogin.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
        }
    }
}