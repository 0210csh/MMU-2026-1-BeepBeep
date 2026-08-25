package com.beepbeep.defense

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore

class SignupActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        // ※ 헤더 강제 포커스 로직 제거함
        // 토크백은 액티비티 진입 시 자동으로 상단부터 안내하므로 불필요

        val etName = findViewById<EditText>(R.id.et_name)
        val etId = findViewById<EditText>(R.id.et_id)
        val etPw = findViewById<EditText>(R.id.et_pw)
        val etPwConfirm = findViewById<EditText>(R.id.et_pw_confirm)
        val btnSignup = findViewById<Button>(R.id.btn_signup)
        val tvLogin = findViewById<TextView>(R.id.tv_login)
        val llWarning = findViewById<LinearLayout>(R.id.ll_warning)
        val tvPwStatus = findViewById<TextView>(R.id.tv_pw_status)

        // 비밀번호 일치 여부를 실시간으로 확인하는 TextWatcher
        val pwCheckWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val pw = etPw.text.toString()
                val pwConfirm = etPwConfirm.text.toString()

                if (pwConfirm.isEmpty()) {
                    tvPwStatus.visibility = View.GONE
                    return
                }

                tvPwStatus.visibility = View.VISIBLE
                if (pw == pwConfirm) {
                    tvPwStatus.text = "비밀번호가 일치합니다"
                    tvPwStatus.setTextColor(getColor(android.R.color.holo_green_light))
                } else {
                    tvPwStatus.text = "비밀번호가 일치하지 않습니다"
                    tvPwStatus.setTextColor(getColor(android.R.color.holo_red_light))
                }
            }
        }
        etPw.addTextChangedListener(pwCheckWatcher)
        etPwConfirm.addTextChangedListener(pwCheckWatcher)

        btnSignup.setOnClickListener {
            val name = etName.text.toString().trim()
            val id = etId.text.toString().trim()
            val pw = etPw.text.toString().trim()
            val pwConfirm = etPwConfirm.text.toString().trim()

            // 빈칸 검사
            if (name.isEmpty() || id.isEmpty() || pw.isEmpty()) {
                llWarning.visibility = View.VISIBLE
                return@setOnClickListener
            }
            llWarning.visibility = View.GONE

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
                    // 토크백이 Toast 메시지를 읽을 시간을 확보한 후 화면 전환
                    Handler(Looper.getMainLooper()).postDelayed({
                        val intent = Intent(this, LoginActivity::class.java)
                        startActivity(intent)
                        finish()
                    }, 2000)
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