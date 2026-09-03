package com.beepbeep.defense

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Patterns
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class LoginActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val LOGIN_FAIL_MSG = "아이디 또는 비밀번호가 올바르지 않습니다"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // 자동로그인 체크
        val pref = getSharedPreferences("UserInfo", MODE_PRIVATE)
        if (pref.getBoolean("auto_login", false)) {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
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

            btnLogin.isEnabled = false

            db.collection("users").document(id).get()
                .addOnSuccessListener { document ->
                    if (!document.exists()) {
                        btnLogin.isEnabled = true
                        Toast.makeText(this, LOGIN_FAIL_MSG, Toast.LENGTH_SHORT).show()
                        return@addOnSuccessListener
                    }

                    val email = document.getString("email")
                    if (email != null) {
                        // 이메일이 등록된(마이그레이션 완료된) 계정 -> Firebase Authentication으로 인증
                        signInWithFirebase(id, email, pw, cbAutoLogin.isChecked, pref) {
                            btnLogin.isEnabled = true
                        }
                    } else {
                        // 이메일이 아직 없는 기존(레거시) 계정 -> 평문 비밀번호로 본인 확인 후 이메일 등록 유도
                        val legacyPw = document.getString("pw")
                        btnLogin.isEnabled = true
                        if (legacyPw == null || legacyPw != pw) {
                            Toast.makeText(this, LOGIN_FAIL_MSG, Toast.LENGTH_SHORT).show()
                        } else {
                            showEmailMigrationDialog(id, pw, cbAutoLogin.isChecked, pref)
                        }
                    }
                }
                .addOnFailureListener {
                    btnLogin.isEnabled = true
                    Toast.makeText(this, "네트워크 오류로 실패했습니다. 다시 시도해주세요", Toast.LENGTH_SHORT).show()
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

    // 이메일이 등록된 계정의 로그인 처리
    private fun signInWithFirebase(
        id: String, email: String, pw: String, autoLogin: Boolean,
        pref: SharedPreferences, onDone: () -> Unit
    ) {
        auth.signInWithEmailAndPassword(email, pw)
            .addOnSuccessListener {
                db.collection("users").document(id).get()
                    .addOnSuccessListener { document ->
                        onDone()
                        finishLogin(id, document.getString("name"), autoLogin, pref)
                    }
                    .addOnFailureListener {
                        onDone()
                        Toast.makeText(this, "네트워크 오류로 실패했습니다. 다시 시도해주세요", Toast.LENGTH_SHORT).show()
                    }
            }
            .addOnFailureListener {
                onDone()
                Toast.makeText(this, LOGIN_FAIL_MSG, Toast.LENGTH_SHORT).show()
            }
    }

    // 레거시 계정(이메일 미등록) 대상 — 본인 확인이 끝난 상태에서 이메일을 받아 Firebase Authentication 계정을 새로 만들고 pw 필드를 제거
    private fun showEmailMigrationDialog(
        id: String, pw: String, autoLogin: Boolean, pref: SharedPreferences
    ) {
        val input = EditText(this)
        input.hint = "이메일 주소"
        input.contentDescription = "이메일 주소 입력"
        input.inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS

        AlertDialog.Builder(this)
            .setTitle("계정 보안 업그레이드")
            .setMessage("본인 확인이 완료되었습니다. 비밀번호 찾기 등에 사용할 이메일을 한 번만 등록해주세요.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("등록") { _, _ ->
                val email = input.text.toString().trim()
                if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    Toast.makeText(this, "올바른 이메일 형식이 아닙니다", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                auth.createUserWithEmailAndPassword(email, pw)
                    .addOnSuccessListener {
                        db.collection("users").document(id)
                            .update(mapOf("email" to email, "pw" to FieldValue.delete()))
                            .addOnSuccessListener {
                                db.collection("users").document(id).get()
                                    .addOnSuccessListener { document ->
                                        finishLogin(id, document.getString("name"), autoLogin, pref)
                                    }
                            }
                    }
                    .addOnFailureListener { e ->
                        val msg = if (e is FirebaseAuthWeakPasswordException)
                            "기존 비밀번호가 너무 짧아 자동 등록할 수 없습니다. 설정 화면에서 비밀번호를 먼저 변경해주세요"
                        else "이메일 등록에 실패했습니다: ${e.message}"
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
            }
            .setNegativeButton("나중에") { _, _ ->
                Toast.makeText(this, "다음 로그인 시 다시 안내됩니다", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun finishLogin(id: String, name: String?, autoLogin: Boolean, pref: SharedPreferences) {
        pref.edit()
            .putString("name", name)
            .putString("id", id)
            .putBoolean("auto_login", autoLogin)
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

    // 비밀번호 찾기 — 등록된 이메일로 Firebase Authentication 재설정 메일 발송
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
                        val email = document.getString("email")
                        if (email != null) {
                            auth.sendPasswordResetEmail(email)
                        }
                        // 계정 존재 여부를 노출하지 않도록 항상 동일한 안내만 표시
                        AlertDialog.Builder(this)
                            .setTitle("비밀번호 재설정")
                            .setMessage("입력하신 아이디에 등록된 이메일로 재설정 링크를 보냈습니다.")
                            .setPositiveButton("확인", null)
                            .show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "조회 실패, 다시 시도해주세요", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
