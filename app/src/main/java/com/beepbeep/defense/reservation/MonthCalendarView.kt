package com.beepbeep.defense.reservation

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Calendar

/**
 * 월간 캘린더 그리드. 안드로이드 기본 CalendarView는 TalkBack 접근성이 좋지 않아
 * GrowthChartView/FieldView와 동일하게 커스텀 Canvas View로 직접 그린다.
 * 월 이동은 이 뷰가 직접 하지 않고 외부(Activity)의 이전/다음 버튼이 setMonth()를 호출한다.
 */
class MonthCalendarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        val STATUS_COLORS = mapOf(
            "모집중"   to 0xFFFBBF24.toInt(),
            "모집완료" to 0xFF38BDF8.toInt(),
            "경기확정" to 0xFF5CF387.toInt(),
            "취소됨"   to 0xFF888888.toInt()
        )
        private val WEEKDAY_LABELS = listOf("일", "월", "화", "수", "목", "금", "토")
    }

    private var year  = Calendar.getInstance().get(Calendar.YEAR)
    private var month = Calendar.getInstance().get(Calendar.MONTH) + 1 // 1~12

    /** day(1~31) -> 그 날짜에 있는 세션들의 상태 목록 */
    private var sessionsByDay: Map<Int, List<String>> = emptyMap()

    private var selectedDay: Int = -1

    var onDateClick: ((year: Int, month: Int, day: Int, statuses: List<String>) -> Unit)? = null

    private val bgPaint = Paint().apply { color = 0xFF111111.toInt() }
    private val weekdayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt(); textSize = 28f; textAlign = Paint.Align.CENTER
    }
    private val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDDDDDD.toInt(); textSize = 32f; textAlign = Paint.Align.CENTER
    }
    private val todayRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFF5CF387.toInt()
    }
    private val selectedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFF2A2A2A.toInt()
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val todayYear  = Calendar.getInstance().get(Calendar.YEAR)
    private val todayMonth = Calendar.getInstance().get(Calendar.MONTH) + 1
    private val todayDay   = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)

    fun setMonth(y: Int, m: Int) {
        year = y; month = m
        selectedDay = -1
        invalidate()
    }

    fun setSessionData(data: Map<Int, List<String>>) {
        sessionsByDay = data
        invalidate()
    }

    fun getYear() = year
    fun getMonth() = month

    private fun firstWeekdayOfMonth(): Int {
        val cal = Calendar.getInstance()
        cal.set(year, month - 1, 1)
        return cal.get(Calendar.DAY_OF_WEEK) - 1 // 0=일요일
    }

    private fun daysInMonth(): Int {
        val cal = Calendar.getInstance()
        cal.set(year, month - 1, 1)
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    private fun cellSize(): Float = width / 7f
    private val headerH = 60f
    private fun rowH(): Float {
        val rows = ((firstWeekdayOfMonth() + daysInMonth() + 6) / 7)
        val available = (height - headerH).coerceAtLeast(1f)
        return available / rows.coerceAtLeast(1)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val cw = cellSize()
        WEEKDAY_LABELS.forEachIndexed { i, label ->
            canvas.drawText(label, cw * i + cw / 2f, headerH * 0.65f, weekdayPaint)
        }

        val startCol = firstWeekdayOfMonth()
        val totalDays = daysInMonth()
        val rh = rowH()

        for (day in 1..totalDays) {
            val idx = startCol + day - 1
            val col = idx % 7
            val row = idx / 7
            val cx = cw * col + cw / 2f
            val cy = headerH + rh * row + rh / 2f

            if (day == selectedDay) {
                canvas.drawCircle(cx, cy - 6f, cw.coerceAtMost(rh) * 0.38f, selectedFillPaint)
            }
            if (year == todayYear && month == todayMonth && day == todayDay) {
                canvas.drawCircle(cx, cy - 6f, cw.coerceAtMost(rh) * 0.38f, todayRingPaint)
            }

            dayPaint.color = if (sessionsByDay.containsKey(day)) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()
            canvas.drawText(day.toString(), cx, cy, dayPaint)

            val statuses = sessionsByDay[day]
            if (!statuses.isNullOrEmpty()) {
                val distinctStatuses = statuses.distinct().take(3)
                val dotSpacing = 14f
                val startX = cx - dotSpacing * (distinctStatuses.size - 1) / 2f
                distinctStatuses.forEachIndexed { i, status ->
                    dotPaint.color = STATUS_COLORS[status] ?: 0xFFFFFFFF.toInt()
                    canvas.drawCircle(startX + dotSpacing * i, cy + 22f, 5f, dotPaint)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val cw = cellSize()
        val rh = rowH()
        val col = (event.x / cw).toInt().coerceIn(0, 6)
        val row = ((event.y - headerH) / rh).toInt()
        if (row < 0) return true
        val idx = row * 7 + col
        val startCol = firstWeekdayOfMonth()
        val day = idx - startCol + 1
        if (day < 1 || day > daysInMonth()) return true

        selectedDay = day
        invalidate()
        onDateClick?.invoke(year, month, day, sessionsByDay[day] ?: emptyList())
        return true
    }

    override fun getAccessibilityClassName(): CharSequence = MonthCalendarView::class.java.name
}
