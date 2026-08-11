package com.beepbeep.defense

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.beepbeep.defense.reservation.MonthCalendarView
import com.beepbeep.defense.reservation.ReservationTtsManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

class ReservationActivity : AppCompatActivity() {

    companion object {
        private const val COL = "training_sessions"
        private const val ROLE_BLIND   = "시각장애선수"
        private const val ROLE_SPOTTER = "스포터"
    }

    private val db = FirebaseFirestore.getInstance()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var ttsManager: ReservationTtsManager

    private lateinit var panelCalendar: LinearLayout
    private lateinit var panelCreateForm: LinearLayout
    private lateinit var panelSessionDetail: LinearLayout

    private lateinit var tvMonthLabel: TextView
    private lateinit var calendarView: MonthCalendarView
    private lateinit var tvSelectedDateLabel: TextView
    private lateinit var btnCreateSession: TextView
    private lateinit var llDaySessions: LinearLayout

    private lateinit var tvFormDate: TextView
    private lateinit var tvStartHour: TextView
    private lateinit var tvStartMinute: TextView
    private lateinit var tvEndHour: TextView
    private lateinit var tvEndMinute: TextView
    private lateinit var etRegion: EditText
    private lateinit var tvMinBlindCount: TextView
    private lateinit var tvMinSpotterCount: TextView

    private lateinit var tvDetailInfo: TextView
    private lateinit var btnRoleBlind: TextView
    private lateinit var btnRoleSpotter: TextView
    private lateinit var tvSpotterDesc: TextView
    private lateinit var btnApply: TextView
    private lateinit var btnConfirmMatch: TextView

    private var displayYear  = Calendar.getInstance().get(Calendar.YEAR)
    private var displayMonth = Calendar.getInstance().get(Calendar.MONTH) + 1

    private var selectedYear = -1
    private var selectedMonth = -1
    private var selectedDay = -1

    private var startHour = 9
    private var startMinute = 0
    private var endHour = 11
    private var endMinute = 0
    private var minBlind = 1
    private var minSpotter = 1

    private var currentSessionId: String? = null
    private var currentSessionDoc: DocumentSnapshot? = null
    private var selectedRole: String? = null

    private val SPOTTER_DESC = "스포터: 투수·포수처럼 시각장애 선수가 하기 어려운 역할을 맡아 " +
            "경기를 돕고, 원한다면 시각장애 선수처럼 안대를 쓰고 타격을 직접 체험해볼 수도 있는 역할입니다."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_reservation)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.layoutBottomNav)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, view.paddingTop, 0, systemBars.bottom)
            insets
        }

        findViewById<View>(R.id.ll_reservation_header).postDelayed({
            findViewById<View>(R.id.ll_reservation_header).performAccessibilityAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }, 1500)

        ttsManager = ReservationTtsManager(this)
        ttsManager.init()

        bindViews()
        setupNavigation()
        setupCalendarPanel()
        setupCreateFormPanel()
        setupDetailPanel()

        refreshMonth()
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
        scope.cancel()
    }

    private fun bindViews() {
        panelCalendar      = findViewById(R.id.panelCalendar)
        panelCreateForm    = findViewById(R.id.panelCreateForm)
        panelSessionDetail = findViewById(R.id.panelSessionDetail)

        tvMonthLabel        = findViewById(R.id.tvMonthLabel)
        calendarView        = findViewById(R.id.monthCalendarView)
        tvSelectedDateLabel = findViewById(R.id.tvSelectedDateLabel)
        btnCreateSession    = findViewById(R.id.btnCreateSession)
        llDaySessions       = findViewById(R.id.llDaySessions)

        tvFormDate        = findViewById(R.id.tvFormDate)
        tvStartHour       = findViewById(R.id.tvStartHour)
        tvStartMinute     = findViewById(R.id.tvStartMinute)
        tvEndHour         = findViewById(R.id.tvEndHour)
        tvEndMinute       = findViewById(R.id.tvEndMinute)
        etRegion          = findViewById(R.id.etRegion)
        tvMinBlindCount   = findViewById(R.id.tvMinBlindCount)
        tvMinSpotterCount = findViewById(R.id.tvMinSpotterCount)

        tvDetailInfo    = findViewById(R.id.tvDetailInfo)
        btnRoleBlind    = findViewById(R.id.btnRoleBlind)
        btnRoleSpotter  = findViewById(R.id.btnRoleSpotter)
        tvSpotterDesc   = findViewById(R.id.tvSpotterDesc)
        btnApply        = findViewById(R.id.btnApply)
        btnConfirmMatch = findViewById(R.id.btnConfirmMatch)

        tvSpotterDesc.contentDescription = SPOTTER_DESC
        btnRoleSpotter.contentDescription = "스포터로 신청. $SPOTTER_DESC"
    }

    // ─────────────────────────────────────────────
    // 캘린더 패널
    // ─────────────────────────────────────────────
    private fun setupCalendarPanel() {
        findViewById<TextView>(R.id.btnPrevMonth).setOnClickListener {
            displayMonth--
            if (displayMonth < 1) { displayMonth = 12; displayYear-- }
            refreshMonth()
        }
        findViewById<TextView>(R.id.btnNextMonth).setOnClickListener {
            displayMonth++
            if (displayMonth > 12) { displayMonth = 1; displayYear++ }
            refreshMonth()
        }
        calendarView.onDateClick = { y, m, d, statuses ->
            selectedYear = y; selectedMonth = m; selectedDay = d
            tvSelectedDateLabel.text = "${m}월 ${d}일"
            btnCreateSession.visibility = View.VISIBLE
            loadDaySessions(y, m, d)
            val desc = if (statuses.isEmpty()) "${m}월 ${d}일. 등록된 모집이 없습니다."
                       else "${m}월 ${d}일. 모집 ${statuses.size}건 있습니다."
            ttsManager.speak(desc)
        }
        btnCreateSession.setOnClickListener { openCreateForm() }
    }

    private fun refreshMonth() {
        tvMonthLabel.text = "${displayYear}년 ${displayMonth}월"
        calendarView.setMonth(displayYear, displayMonth)

        val monthStart = dateKey(displayYear, displayMonth, 1)
        val lastDay = Calendar.getInstance().apply {
            set(displayYear, displayMonth - 1, 1)
        }.getActualMaximum(Calendar.DAY_OF_MONTH)
        val monthEnd = dateKey(displayYear, displayMonth, lastDay)

        db.collection(COL)
            .whereGreaterThanOrEqualTo("날짜", monthStart)
            .whereLessThanOrEqualTo("날짜", monthEnd)
            .get()
            .addOnSuccessListener { docs ->
                val map = mutableMapOf<Int, MutableList<String>>()
                docs.documents.forEach { doc ->
                    val dateStr = doc.getString("날짜") ?: return@forEach
                    val day = dateStr.substringAfterLast("-").toIntOrNull() ?: return@forEach
                    val status = doc.getString("상태") ?: "모집중"
                    map.getOrPut(day) { mutableListOf() }.add(status)
                }
                calendarView.setSessionData(map)
            }
            .addOnFailureListener { showNetworkError() }
    }

    private fun loadDaySessions(y: Int, m: Int, d: Int) {
        llDaySessions.removeAllViews()
        db.collection(COL)
            .whereEqualTo("날짜", dateKey(y, m, d))
            .get()
            .addOnSuccessListener { docs ->
                llDaySessions.removeAllViews()
                if (docs.isEmpty) {
                    llDaySessions.addView(TextView(this).apply {
                        text = "등록된 모집이 없습니다"
                        textSize = 15f; setTextColor(0xFF888888.toInt()); setPadding(4, 8, 4, 8)
                    })
                    return@addOnSuccessListener
                }
                docs.documents.forEach { doc -> llDaySessions.addView(buildSessionRow(doc)) }
            }
            .addOnFailureListener { showNetworkError() }
    }

    private fun buildSessionRow(doc: DocumentSnapshot): View {
        val status = doc.getString("상태") ?: "모집중"
        val region = doc.getString("지역") ?: ""
        val start  = doc.getLong("시작시간") ?: 0L
        val end    = doc.getLong("종료시간") ?: 0L
        val roleMap = doc.get("역할정원") as? Map<*, *>
        val blindMap = roleMap?.get(ROLE_BLIND) as? Map<*, *>
        val spotterMap = roleMap?.get(ROLE_SPOTTER) as? Map<*, *>
        val blindCur = (blindMap?.get("current") as? Number)?.toInt() ?: 0
        val blindMin = (blindMap?.get("min") as? Number)?.toInt() ?: 0
        val spotterCur = (spotterMap?.get("current") as? Number)?.toInt() ?: 0
        val spotterMin = (spotterMap?.get("min") as? Number)?.toInt() ?: 0

        val timeText = "${formatTime(start)} ~ ${formatTime(end)}"
        val desc = "$timeText, $region, $status, 시각장애선수 $blindCur/$blindMin, 스포터 $spotterCur/$spotterMin"

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A1A1A.toInt())
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, 8)
            layoutParams = lp
            setPadding(16, 14, 16, 14)
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = desc

            addView(TextView(context).apply {
                text = "$timeText  ·  $region"
                textSize = 16f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            addView(TextView(context).apply {
                text = "$status  ·  시각장애선수 $blindCur/$blindMin  ·  스포터 $spotterCur/$spotterMin"
                textSize = 14f; setTextColor(statusColor(status))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            setOnClickListener { openSessionDetail(doc.id) }
        }
    }

    private fun statusColor(status: String) = MonthCalendarView.STATUS_COLORS[status] ?: 0xFFAAAAAA.toInt()

    // ─────────────────────────────────────────────
    // 모집 생성 폼
    // ─────────────────────────────────────────────
    private fun setupCreateFormPanel() {
        findViewById<TextView>(R.id.btnStartHourMinus).setOnClickListener { adjustStartHour(-1) }
        findViewById<TextView>(R.id.btnStartHourPlus).setOnClickListener { adjustStartHour(1) }
        findViewById<TextView>(R.id.btnStartMinuteMinus).setOnClickListener { adjustStartMinute() }
        findViewById<TextView>(R.id.btnStartMinutePlus).setOnClickListener { adjustStartMinute() }
        findViewById<TextView>(R.id.btnEndHourMinus).setOnClickListener { adjustEndHour(-1) }
        findViewById<TextView>(R.id.btnEndHourPlus).setOnClickListener { adjustEndHour(1) }
        findViewById<TextView>(R.id.btnEndMinuteMinus).setOnClickListener { adjustEndMinute() }
        findViewById<TextView>(R.id.btnEndMinutePlus).setOnClickListener { adjustEndMinute() }
        findViewById<TextView>(R.id.btnMinBlindMinus).setOnClickListener {
            minBlind = (minBlind - 1).coerceAtLeast(0); tvMinBlindCount.text = minBlind.toString()
        }
        findViewById<TextView>(R.id.btnMinBlindPlus).setOnClickListener {
            minBlind = (minBlind + 1).coerceAtMost(20); tvMinBlindCount.text = minBlind.toString()
        }
        findViewById<TextView>(R.id.btnMinSpotterMinus).setOnClickListener {
            minSpotter = (minSpotter - 1).coerceAtLeast(0); tvMinSpotterCount.text = minSpotter.toString()
        }
        findViewById<TextView>(R.id.btnMinSpotterPlus).setOnClickListener {
            minSpotter = (minSpotter + 1).coerceAtMost(20); tvMinSpotterCount.text = minSpotter.toString()
        }
        findViewById<TextView>(R.id.btnSubmitCreate).setOnClickListener { submitCreateForm() }
        findViewById<TextView>(R.id.btnCancelCreate).setOnClickListener { showCalendarPanel() }
    }

    private fun openCreateForm() {
        startHour = 9; startMinute = 0
        endHour = 11; endMinute = 0
        minBlind = 1; minSpotter = 1
        tvStartHour.text = "${startHour}시"
        tvStartMinute.text = "${startMinute}분"
        tvEndHour.text = "${endHour}시"
        tvEndMinute.text = "${endMinute}분"
        etRegion.setText("")
        tvMinBlindCount.text = "1"
        tvMinSpotterCount.text = "1"
        tvFormDate.text = "${selectedYear}년 ${selectedMonth}월 ${selectedDay}일 모집 만들기"

        panelCalendar.visibility = View.GONE
        panelSessionDetail.visibility = View.GONE
        panelCreateForm.visibility = View.VISIBLE

        scope.launch {
            ttsManager.speakAndWait("모집 만들기. 시작 시간을 시와 분 버튼으로 설정하세요. 시간은 한 시간 단위, 분은 30분 단위입니다.")
            ttsManager.speakAndWait("이어서 종료 시간, 지역, 시각장애 선수와 스포터의 최소 인원을 입력하세요.")
            ttsManager.speakAndWait("다 입력했으면 등록 버튼을 눌러주세요.")
        }
    }

    // 시간(0~23, 1시간 단위 순환) / 분(0 또는 30, 서로 독립적으로 토글) 스텝퍼
    private fun adjustStartHour(delta: Int) {
        startHour = ((startHour + delta) + 24) % 24
        tvStartHour.text = "${startHour}시"
        ttsManager.speak("${startHour}시")
    }

    private fun adjustStartMinute() {
        startMinute = if (startMinute == 0) 30 else 0
        tvStartMinute.text = "${startMinute}분"
        ttsManager.speak("${startMinute}분")
    }

    private fun adjustEndHour(delta: Int) {
        endHour = ((endHour + delta) + 24) % 24
        tvEndHour.text = "${endHour}시"
        ttsManager.speak("${endHour}시")
    }

    private fun adjustEndMinute() {
        endMinute = if (endMinute == 0) 30 else 0
        tvEndMinute.text = "${endMinute}분"
        ttsManager.speak("${endMinute}분")
    }

    private fun timeToMillis(hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.set(selectedYear, selectedMonth - 1, selectedDay, hour, minute, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun submitCreateForm() {
        val region = etRegion.text.toString().trim()
        if (region.isEmpty()) {
            Toast.makeText(this, "지역을 입력해주세요", Toast.LENGTH_SHORT).show()
            return
        }
        val startTimeMillis = timeToMillis(startHour, startMinute)
        val endTimeMillis = timeToMillis(endHour, endMinute)
        if (endTimeMillis <= startTimeMillis) {
            Toast.makeText(this, "종료 시간이 시작 시간보다 뒤여야 합니다", Toast.LENGTH_SHORT).show()
            return
        }
        val userId = getSharedPreferences("UserInfo", MODE_PRIVATE).getString("id", "anonymous") ?: "anonymous"
        if (userId == "anonymous") {
            Toast.makeText(this, "로그인이 필요합니다", Toast.LENGTH_SHORT).show()
            return
        }

        val summary = "${selectedMonth}월 ${selectedDay}일 ${formatTime(startTimeMillis)}부터 " +
                "${formatTime(endTimeMillis)}까지, $region 에서 모집을 시작합니다. " +
                "시각장애 선수 최소 ${minBlind}명, 스포터 최소 ${minSpotter}명입니다."

        scope.launch {
            ttsManager.speakAndWait(summary)

            val sessionData = hashMapOf(
                "생성자ID" to userId,
                "생성일시" to Timestamp.now(),
                "날짜" to dateKey(selectedYear, selectedMonth, selectedDay),
                "시작시간" to startTimeMillis,
                "종료시간" to endTimeMillis,
                "지역" to region,
                "상태" to "모집중",
                "시작알림전송여부" to false,
                "역할정원" to hashMapOf(
                    ROLE_BLIND   to hashMapOf("min" to minBlind, "current" to 0),
                    ROLE_SPOTTER to hashMapOf("min" to minSpotter, "current" to 0)
                )
            )

            db.collection(COL).add(sessionData)
                .addOnSuccessListener {
                    ttsManager.speak("등록이 완료되었습니다.")
                    Toast.makeText(this@ReservationActivity, "모집이 등록되었습니다", Toast.LENGTH_SHORT).show()
                    refreshMonth()
                    loadDaySessions(selectedYear, selectedMonth, selectedDay)
                    showCalendarPanel()
                }
                .addOnFailureListener { showNetworkError() }
        }
    }

    // ─────────────────────────────────────────────
    // 세션 상세 / 신청 / 확정
    // ─────────────────────────────────────────────
    private fun setupDetailPanel() {
        btnRoleBlind.setOnClickListener {
            selectedRole = ROLE_BLIND
            highlightRoleButtons()
            ttsManager.speak("시각장애 선수로 신청합니다.")
        }
        btnRoleSpotter.setOnClickListener {
            selectedRole = ROLE_SPOTTER
            highlightRoleButtons()
            ttsManager.speak(SPOTTER_DESC)
        }
        btnApply.setOnClickListener { applyToSession() }
        btnConfirmMatch.setOnClickListener { confirmMatch() }
        findViewById<TextView>(R.id.btnBackFromDetail).setOnClickListener { showCalendarPanel() }
    }

    private fun highlightRoleButtons() {
        btnRoleBlind.setBackgroundResource(
            if (selectedRole == ROLE_BLIND) R.drawable.bg_input_green_selected else R.drawable.bg_dark_rounded_input
        )
        btnRoleBlind.setTextColor(if (selectedRole == ROLE_BLIND) 0xFF5CF387.toInt() else Color.WHITE)
        btnRoleSpotter.setBackgroundResource(
            if (selectedRole == ROLE_SPOTTER) R.drawable.bg_input_green_selected else R.drawable.bg_dark_rounded_input
        )
        btnRoleSpotter.setTextColor(if (selectedRole == ROLE_SPOTTER) 0xFF5CF387.toInt() else Color.WHITE)
    }

    private fun openSessionDetail(sessionId: String) {
        db.collection(COL).document(sessionId).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) return@addOnSuccessListener
                currentSessionId = sessionId
                currentSessionDoc = doc
                selectedRole = null
                highlightRoleButtons()
                renderDetail(doc)

                panelCalendar.visibility = View.GONE
                panelCreateForm.visibility = View.GONE
                panelSessionDetail.visibility = View.VISIBLE
            }
            .addOnFailureListener { showNetworkError() }
    }

    private fun renderDetail(doc: DocumentSnapshot) {
        val dateStr = doc.getString("날짜") ?: ""
        val status  = doc.getString("상태") ?: "모집중"
        val region  = doc.getString("지역") ?: ""
        val start   = doc.getLong("시작시간") ?: 0L
        val end     = doc.getLong("종료시간") ?: 0L
        val creatorId = doc.getString("생성자ID") ?: ""
        val roleMap = doc.get("역할정원") as? Map<*, *>
        val blindMap = roleMap?.get(ROLE_BLIND) as? Map<*, *>
        val spotterMap = roleMap?.get(ROLE_SPOTTER) as? Map<*, *>
        val blindCur = (blindMap?.get("current") as? Number)?.toInt() ?: 0
        val blindMin = (blindMap?.get("min") as? Number)?.toInt() ?: 0
        val spotterCur = (spotterMap?.get("current") as? Number)?.toInt() ?: 0
        val spotterMin = (spotterMap?.get("min") as? Number)?.toInt() ?: 0

        val info = "$dateStr\n${formatTime(start)} ~ ${formatTime(end)}\n$region\n상태: $status\n" +
                "시각장애 선수 ${blindCur}/${blindMin}명 · 스포터 ${spotterCur}/${spotterMin}명"
        tvDetailInfo.text = info
        tvDetailInfo.contentDescription = info.replace("\n", ", ")

        val userId = getSharedPreferences("UserInfo", MODE_PRIVATE).getString("id", "anonymous") ?: "anonymous"
        val isOwner = userId == creatorId
        btnConfirmMatch.visibility = if (isOwner && status == "모집완료") View.VISIBLE else View.GONE
        val canApply = status == "모집중" || status == "모집완료"
        btnApply.alpha = if (canApply) 1f else 0.4f
        btnApply.isEnabled = canApply
    }

    private fun applyToSession() {
        val sessionId = currentSessionId ?: return
        val role = selectedRole
        if (role == null) {
            Toast.makeText(this, "역할을 선택해주세요", Toast.LENGTH_SHORT).show()
            return
        }
        val userId = getSharedPreferences("UserInfo", MODE_PRIVATE).getString("id", "anonymous") ?: "anonymous"
        if (userId == "anonymous") {
            Toast.makeText(this, "로그인이 필요합니다", Toast.LENGTH_SHORT).show()
            return
        }
        val doc = currentSessionDoc ?: return
        val dateStr = doc.getString("날짜") ?: ""
        val start   = doc.getLong("시작시간") ?: 0L
        val region  = doc.getString("지역") ?: ""
        val summary = "$dateStr ${formatTime(start)}, $region, ${role}로 신청하시겠습니까?"

        scope.launch {
            ttsManager.speakAndWait(summary)

            val sessionRef   = db.collection(COL).document(sessionId)
            val applicantRef = sessionRef.collection("신청자").document(userId)

            db.runTransaction { tx ->
                val existing = tx.get(applicantRef)
                if (existing.exists()) return@runTransaction "ALREADY"

                val snap = tx.get(sessionRef)
                @Suppress("UNCHECKED_CAST")
                val roleMapRaw = snap.get("역할정원") as? Map<String, Any> ?: emptyMap()
                val roleMap = HashMap<String, Any>(roleMapRaw)

                @Suppress("UNCHECKED_CAST")
                val roleInfoRaw = roleMap[role] as? Map<String, Any> ?: mapOf("min" to 0L, "current" to 0L)
                val roleInfo = HashMap<String, Any>(roleInfoRaw)
                val curCount = ((roleInfo["current"] as? Number)?.toInt() ?: 0) + 1
                roleInfo["current"] = curCount
                roleMap[role] = roleInfo

                @Suppress("UNCHECKED_CAST")
                val blindInfo = roleMap[ROLE_BLIND] as? Map<String, Any>
                @Suppress("UNCHECKED_CAST")
                val spotterInfo = roleMap[ROLE_SPOTTER] as? Map<String, Any>
                val blindOk   = ((blindInfo?.get("current") as? Number)?.toInt() ?: 0) >= ((blindInfo?.get("min") as? Number)?.toInt() ?: 0)
                val spotterOk = ((spotterInfo?.get("current") as? Number)?.toInt() ?: 0) >= ((spotterInfo?.get("min") as? Number)?.toInt() ?: 0)

                tx.update(sessionRef, "역할정원", roleMap)
                if (blindOk && spotterOk && snap.getString("상태") == "모집중") {
                    tx.update(sessionRef, "상태", "모집완료")
                }
                tx.set(applicantRef, hashMapOf("역할" to role, "신청일시" to Timestamp.now()))
                "OK"
            }.addOnSuccessListener { result ->
                if (result == "ALREADY") {
                    Toast.makeText(this@ReservationActivity, "이미 신청한 모집입니다", Toast.LENGTH_SHORT).show()
                    ttsManager.speak("이미 신청한 모집입니다.")
                } else {
                    ttsManager.speak("신청이 완료되었습니다.")
                    Toast.makeText(this@ReservationActivity, "신청이 완료되었습니다", Toast.LENGTH_SHORT).show()
                    refreshMonth()
                    showCalendarPanel()
                }
            }.addOnFailureListener { showNetworkError() }
        }
    }

    private fun confirmMatch() {
        val sessionId = currentSessionId ?: return
        db.collection(COL).document(sessionId).update("상태", "경기확정")
            .addOnSuccessListener {
                ttsManager.speak("경기가 확정되었습니다.")
                Toast.makeText(this, "경기가 확정되었습니다", Toast.LENGTH_SHORT).show()
                refreshMonth()
                showCalendarPanel()
            }
            .addOnFailureListener { showNetworkError() }
    }

    private fun showCalendarPanel() {
        panelCreateForm.visibility = View.GONE
        panelSessionDetail.visibility = View.GONE
        panelCalendar.visibility = View.VISIBLE
    }

    private fun showNetworkError() {
        Toast.makeText(this, "네트워크 오류로 실패했습니다. 다시 시도해주세요", Toast.LENGTH_SHORT).show()
        ttsManager.speak("네트워크 오류로 실패했습니다. 다시 시도해주세요.")
    }

    private fun dateKey(y: Int, m: Int, d: Int) = String.format(Locale.US, "%04d-%02d-%02d", y, m, d)

    private fun formatTime(millis: Long): String {
        if (millis <= 0L) return "-"
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val fmt = java.text.SimpleDateFormat("a h:mm", Locale.KOREAN)
        return fmt.format(cal.time)
    }

    private fun setupNavigation() {
        findViewById<LinearLayout>(R.id.navHome).setOnClickListener {
            val intent = Intent(this, HomeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
        }
        findViewById<LinearLayout>(R.id.navTraining).setOnClickListener {
            startActivity(Intent(this, TrainingActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.navRecord).setOnClickListener {
            startActivity(Intent(this, RecordActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.navRanking).setOnClickListener {
            startActivity(Intent(this, RankingActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.navReservation).setOnClickListener { }
        findViewById<android.widget.ImageButton>(R.id.btnSetting).setOnClickListener {
            startActivity(Intent(this, SettingActivity::class.java))
        }
    }
}
