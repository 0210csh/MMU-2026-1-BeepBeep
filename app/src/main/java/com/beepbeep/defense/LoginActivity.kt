package com.beepbeep.defense

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore

class LoginActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // 자동로그인 체크
        val pref = getSharedPreferences("UserInfo", MODE_PRIVATE)
        if (pref.getBoolean("auto_login", false)) {
            val userId = pref.getString("id", "anonymous") ?: "anonymous"
            db.collection("admins").document(userId).get()
                .addOnSuccessListener { adminDoc ->
                    getSharedPreferences("AdminCache", MODE_PRIVATE)
                        .edit().putBoolean("isAdmin", adminDoc.exists()).apply()
                    startActivity(Intent(this, HomeActivity::class.java))
                    finish()
                }
                .addOnFailureListener {
                    startActivity(Intent(this, HomeActivity::class.java))
                    finish()
                }
            return
        }

        findViewById<android.widget.LinearLayout>(R.id.ll_login_header).let { header ->
            header.postDelayed({
                header.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                    null
                )
            }, 1500)
        }

        val etId = findViewById<EditText>(R.id.et_login_id)
        val etPw = findViewById<EditText>(R.id.et_login_pw)
        val btnLogin = findViewById<Button>(R.id.btn_login)
        val btnGotoSignup = findViewById<Button>(R.id.btn_goto_signup)
        val cbAutoLogin = findViewById<CheckBox>(R.id.cb_auto_login)
        val tvFindAccount = findViewById<TextView>(R.id.tv_find_account)

        // 로그인 버튼
        btnLogin.setOnClickListener {
            val id = etId.text.toString().trim()
            val pw = etPw.text.toString().trim()

            if (id.isEmpty() || pw.isEmpty()) {
                Toast.makeText(this, "아이디와 비번을 입력해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            db.collection("users").document(id).get()
                .addOnSuccessListener { document ->
                    if (document.exists()) {
                        val dbPw = document.getString("pw")
                        if (dbPw == pw) {
                            pref.edit()
                                .putString("name", document.getString("name"))
                                .putString("id", id)
                                .putBoolean("auto_login", cbAutoLogin.isChecked)
                                .apply()

                            db.collection("admins").document(id).get()
                                .addOnSuccessListener { adminDoc ->
                                    getSharedPreferences("AdminCache", MODE_PRIVATE)
                                        .edit().putBoolean("isAdmin", adminDoc.exists()).apply()
                                    Toast.makeText(this, "로그인 성공!", Toast.LENGTH_SHORT).show()
                                    startActivity(Intent(this, HomeActivity::class.java))
                                    finish()
                                }
                                .addOnFailureListener {
                                    getSharedPreferences("AdminCache", MODE_PRIVATE)
                                        .edit().putBoolean("isAdmin", false).apply()
                                    Toast.makeText(this, "로그인 성공!", Toast.LENGTH_SHORT).show()
                                    startActivity(Intent(this, HomeActivity::class.java))
                                    finish()
                                }
                        } else {
                            Toast.makeText(this, "비밀번호가 틀렸습니다", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this, "가입되지 않은 아이디입니다", Toast.LENGTH_SHORT).show()
                    }
                }
        }

        // 회원가입 버튼
        btnGotoSignup.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }

        // 아이디 / 비밀번호 찾기
        tvFindAccount.setOnClickListener {
            val options = arrayOf("아이디 찾기", "비밀번호 찾기")
            AlertDialog.Builder(this)
                .setTitle("무엇을 찾으시나요?")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> showFindId()
                        1 -> showFindPw()
                    }
                }
                .show()
        }
    }

    // 아이디 찾기
    private fun showFindId() {
        val input = EditText(this)
        input.hint = "가입한 이름을 입력하세요"

        AlertDialog.Builder(this)
            .setTitle("아이디 찾기")
            .setView(input)
            .setPositiveButton("확인") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "이름을 입력해주세요", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                db.collection("users")
                    .whereEqualTo("name", name)
                    .get()
                    .addOnSuccessListener { documents ->
                        if (!documents.isEmpty) {
                            val id = documents.documents[0].getString("id")
                            AlertDialog.Builder(this)
                                .setTitle("아이디 확인")
                                .setMessage("아이디는 [ $id ] 입니다")
                                .setPositiveButton("확인", null)
                                .show()
                        } else {
                            Toast.makeText(this, "해당 이름으로 가입된 계정이 없습니다", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "조회 실패, 다시 시도해주세요", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // 비밀번호 찾기
    private fun showFindPw() {
        val input = EditText(this)
        input.hint = "가입한 아이디를 입력하세요"

        AlertDialog.Builder(this)
            .setTitle("비밀번호 찾기")
            .setView(input)
            .setPositiveButton("확인") { _, _ ->
                val id = input.text.toString().trim()
                if (id.isEmpty()) {
                    Toast.makeText(this, "아이디를 입력해주세요", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                db.collection("users").document(id).get()
                    .addOnSuccessListener { document ->
                        if (document.exists()) {
                            val pw = document.getString("pw")
                            AlertDialog.Builder(this)
                                .setTitle("비밀번호 확인")
                                .setMessage("비밀번호는 [ $pw ] 입니다")
                                .setPositiveButton("확인", null)
                                .show()
                        } else {
                            Toast.makeText(this, "가입되지 않은 아이디입니다", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "조회 실패, 다시 시도해주세요", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("취소", null)
            .show()
    }
}