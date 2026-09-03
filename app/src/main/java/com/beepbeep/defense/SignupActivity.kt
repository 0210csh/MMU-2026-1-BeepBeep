package com.beepbeep.defense

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FirebaseFirestore

class SignupActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    companion object {
        // 네이버 가입 규칙 기준: 아이디 영문 소문자·숫자·-·_ 5~20자
        private val ID_REGEX = Regex("^[a-z0-9_-]{5,20}$")

        // 네이버 가입 규칙 기준: 비밀번호 8~32자, 영문/숫자/특수문자 중 2종류 이상 조합
        fun isValidPassword(pw: String): Boolean {
            if (pw.length !in 8..32) return false
            var categories = 0
            if (pw.any { it.isLetter() }) categories++
            if (pw.any { it.isDigit() }) categories++
            if (pw.any { !it.isLetterOrDigit() }) categories++
            return categories >= 2
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        // ※ 헤더 강제 포커스 로직 제거함
        // 토크백은 액티비티 진입 시 자동으로 상단부터 안내하므로 불필요

        val scrollView = findViewById<ScrollView>(R.id.scrollView_signup)

        val etName = findViewById<EditText>(R.id.et_name)
        val etId = findViewById<EditText>(R.id.et_id)
        val etEmail = findViewById<EditText>(R.id.et_email)
        val etPw = findViewById<EditText>(R.id.et_pw)
        val etPwConfirm = findViewById<EditText>(R.id.et_pw_confirm)
        val btnSignup = findViewById<Button>(R.id.btn_signup)
        val tvLogin = findViewById<TextView>(R.id.tv_login)
        val llWarning = findViewById<LinearLayout>(R.id.ll_warning)
        val tvPwStatus = findViewById<TextView>(R.id.tv_pw_status)

        // ── ScrollView 기준 정확한 세로 위치를 계산하는 함수 ──
        // view.top은 "바로 위 부모" 기준이라, 여러 겹 중첩된 레이아웃에서는
        // scrollView까지 부모를 하나씩 거슬러 올라가며 top 값을 누적해야
        // 진짜 스크롤 위치를 구할 수 있음
        fun getRelativeTop(view: View): Int {
            var offset = 0
            var current: View = view
            while (current !== scrollView) {
                offset += current.top
                val parent = current.parent
                if (parent !is View) break
                current = parent
            }
            return offset
        }

        // ── 입력창에 포커스가 갈 때 키보드에 가려지지 않도록 자동 스크롤 ──
        fun setupScrollOnFocus(editText: EditText) {
            editText.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    scrollView.postDelayed({
                        // 입력창이 화면 중간쯤(키보드 위)에 오도록 약간의 여유를 뺌
                        val targetY = getRelativeTop(view) - 150
                        scrollView.smoothScrollTo(0, targetY.coerceAtLeast(0))
                    }, 300)
                }
            }
        }
        setupScrollOnFocus(etName)
        setupScrollOnFocus(etId)
        setupScrollOnFocus(etEmail)
        setupScrollOnFocus(etPw)
        setupScrollOnFocus(etPwConfirm)

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
            val email = etEmail.text.toString().trim()
            val pw = etPw.text.toString().trim()
            val pwConfirm = etPwConfirm.text.toString().trim()

            // 빈칸 검사
            if (name.isEmpty() || id.isEmpty() || email.isEmpty() || pw.isEmpty()) {
                llWarning.visibility = View.VISIBLE
                return@setOnClickListener
            }
            llWarning.visibility = View.GONE

            // 아이디 형식 검사
            if (!ID_REGEX.matches(id)) {
                Toast.makeText(this, "아이디는 영문 소문자·숫자·-·_ 를 사용해 5~20자로 입력해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 이메일 형식 검사
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "올바른 이메일 형식이 아닙니다", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 비밀번호 형식 검사
            if (!isValidPassword(pw)) {
                Toast.makeText(this, "비밀번호는 8~32자, 영문/숫자/특수문자 중 2가지 이상을 조합해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 비밀번호 확인
            if (pw != pwConfirm) {
                Toast.makeText(this, "비밀번호가 일치하지 않습니다", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnSignup.isEnabled = false

            // 아이디 중복 확인 (Firebase Auth의 중복 검사는 이메일 기준이라 아이디는 직접 확인해야 함)
            db.collection("users").document(id).get()
                .addOnSuccessListener { existing ->
                    if (existing.exists()) {
                        btnSignup.isEnabled = true
                        Toast.makeText(this, "이미 사용 중인 아이디입니다", Toast.LENGTH_SHORT).show()
                        return@addOnSuccessListener
                    }

                    auth.createUserWithEmailAndPassword(email, pw)
                        .addOnSuccessListener {
                            val userMap = hashMapOf<String, Any>(
                                "name" to name,
                                "id" to id,
                                "email" to email
                            )
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
                                    btnSignup.isEnabled = true
                                    Toast.makeText(this, "가입 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                        }
                        .addOnFailureListener { e ->
                            btnSignup.isEnabled = true
                            val msg = when (e) {
                                is FirebaseAuthUserCollisionException -> "이미 가입된 이메일입니다"
                                is FirebaseAuthWeakPasswordException -> "비밀번호는 6자 이상이어야 합니다"
                                else -> "가입 실패, 다시 시도해주세요"
                            }
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        }
                }
                .addOnFailureListener {
                    btnSignup.isEnabled = true
                    Toast.makeText(this, "네트워크 오류로 실패했습니다. 다시 시도해주세요", Toast.LENGTH_SHORT).show()
                }
        }

        tvLogin.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
        }
    }
}