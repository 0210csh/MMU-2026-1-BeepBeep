package com.beepbeep.defense

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Calendar
import java.util.Date

/**
 * 분기제 랭킹 집계. 분기 자체를 문서 경로에 포함시켜(rankings_batting/{quarter}/entries/{userId})
 * 분기가 지나도 그 시점 기록이 그대로 보존되고(과거 조회 가능), quarter 필드 비교 로직도 필요 없다.
 * 세션 종료마다 그 경로의 문서를 읽어 누적하고, 없으면(그 분기 첫 기록) 0부터 시작한다.
 */
object RankingUpdater {

    const val BATTING_MIN_MS = 300.0
    const val BATTING_MAX_MS = 2000.0
    const val DEFENSE_MIN_MS = 5000.0
    const val DEFENSE_MAX_MS = 40000.0

    /** 최소 표본(20) 이상인지 여부 — 랭킹 리스트/순위 계산 포함 조건 */
    const val MIN_SAMPLE = 20

    fun currentQuarter(): String = quarterOf(Calendar.getInstance())

    fun quarterOf(year: Int, quarterNum: Int): String = "${year}Q$quarterNum"

    private fun quarterOf(cal: Calendar): String {
        val year = cal.get(Calendar.YEAR)
        val quarter = cal.get(Calendar.MONTH) / 3 + 1
        return quarterOf(year, quarter)
    }

    fun currentQuarterParts(): Pair<Int, Int> {
        val cal = Calendar.getInstance()
        return Pair(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) / 3 + 1)
    }

    fun prevQuarter(year: Int, quarterNum: Int): Pair<Int, Int> =
        if (quarterNum == 1) Pair(year - 1, 4) else Pair(year, quarterNum - 1)

    fun nextQuarter(year: Int, quarterNum: Int): Pair<Int, Int> =
        if (quarterNum == 4) Pair(year + 1, 1) else Pair(year, quarterNum + 1)

    fun entriesPath(category: String, quarter: String): String =
        "${if (category == "batting") "rankings_batting" else "rankings_defense"}/$quarter/entries"

    /** 이번 분기가 시작된 날짜의 자정 (예: 7~9월이면 7월 1일 00:00) */
    private fun quarterStartDate(): Date {
        val cal = Calendar.getInstance()
        val quarterStartMonth = (cal.get(Calendar.MONTH) / 3) * 3
        cal.set(Calendar.MONTH, quarterStartMonth)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.time
    }

    private fun reactionScore(avgMs: Double?, minMs: Double, maxMs: Double): Double {
        if (avgMs == null) return 0.0
        return (((maxMs - avgMs) / (maxMs - minMs)) * 100.0).coerceIn(0.0, 100.0)
    }

    fun updateBattingRanking(
        userId: String,
        userName: String,
        pitchCount: Int,
        hitCount: Int,
        reactionTimesMs: List<Long>
    ) {
        if (userId == "anonymous" || pitchCount <= 0) return
        val db = FirebaseFirestore.getInstance()
        val quarter = currentQuarter()
        val ref = db.collection(entriesPath("batting", quarter)).document(userId)

        db.runTransaction { tx ->
            val snap = tx.get(ref)

            val totalPitches = (snap.getLong("totalPitches") ?: 0L) + pitchCount
            val hits         = (snap.getLong("hitCount") ?: 0L) + hitCount
            var reactionSum  = snap.getLong("reactionSumMs") ?: 0L
            var reactionCnt  = snap.getLong("reactionSampleCount") ?: 0L
            reactionTimesMs.forEach { ms -> reactionSum += ms; reactionCnt += 1 }

            val battingAvg   = if (totalPitches > 0) hits.toDouble() / totalPitches * 100.0 else 0.0
            val avgReaction  = if (reactionCnt > 0) reactionSum.toDouble() / reactionCnt else null
            val reactScore   = reactionScore(avgReaction, BATTING_MIN_MS, BATTING_MAX_MS)
            val score        = (0.6 * battingAvg + 0.4 * reactScore).coerceIn(0.0, 100.0)

            tx.set(
                ref,
                hashMapOf(
                    "name" to userName,
                    "totalPitches" to totalPitches,
                    "hitCount" to hits,
                    "reactionSumMs" to reactionSum,
                    "reactionSampleCount" to reactionCnt,
                    "score" to score,
                    "updatedAt" to Timestamp.now()
                )
            )
            null
        }
            .addOnSuccessListener { Log.d("RankingUpdater", "타격 랭킹 갱신 성공: $userId ($quarter)") }
            .addOnFailureListener { e -> Log.e("RankingUpdater", "타격 랭킹 갱신 실패: ${e.message}", e) }
    }

    fun updateDefenseRanking(
        userId: String,
        userName: String,
        attemptCount: Int,
        successCount: Int,
        reactionTimesMs: List<Long>
    ) {
        if (userId == "anonymous" || attemptCount <= 0) return
        val db = FirebaseFirestore.getInstance()
        val quarter = currentQuarter()
        val ref = db.collection(entriesPath("defense", quarter)).document(userId)

        db.runTransaction { tx ->
            val snap = tx.get(ref)

            val totalAttempts = (snap.getLong("attemptCount") ?: 0L) + attemptCount
            val successes     = (snap.getLong("successCount") ?: 0L) + successCount
            var reactionSum   = snap.getLong("reactionSumMs") ?: 0L
            var reactionCnt   = snap.getLong("reactionSampleCount") ?: 0L
            reactionTimesMs.forEach { ms -> reactionSum += ms; reactionCnt += 1 }

            val successRate  = if (totalAttempts > 0) successes.toDouble() / totalAttempts * 100.0 else 0.0
            val avgReaction  = if (reactionCnt > 0) reactionSum.toDouble() / reactionCnt else null
            val reactScore   = reactionScore(avgReaction, DEFENSE_MIN_MS, DEFENSE_MAX_MS)
            val score        = (0.6 * successRate + 0.4 * reactScore).coerceIn(0.0, 100.0)

            tx.set(
                ref,
                hashMapOf(
                    "name" to userName,
                    "attemptCount" to totalAttempts,
                    "successCount" to successes,
                    "reactionSumMs" to reactionSum,
                    "reactionSampleCount" to reactionCnt,
                    "score" to score,
                    "updatedAt" to Timestamp.now()
                )
            )
            null
        }
            .addOnSuccessListener { Log.d("RankingUpdater", "수비 랭킹 갱신 성공: $userId ($quarter)") }
            .addOnFailureListener { e -> Log.e("RankingUpdater", "수비 랭킹 갱신 실패: ${e.message}", e) }
    }

    /**
     * 세션 종료 훅(updateBattingRanking)은 그 이후에 끝난 세션만 누적하기 때문에,
     * 이 기능을 붙이기 전에 이미 쌓여있던 과거 훈련기록은 반영되지 않는다.
     * 랭킹 화면을 열 때(이번 분기를 보고 있을 때만) 이번 분기의 훈련기록/수비훈련기록을
     * 원본에서 다시 집계해 rankings_batting/{이번분기}/entries를 덮어써서
     * 소급 반영 + 자가 치유(self-heal) 한다. 지난 분기는 절대 재계산하지 않는다(고정 보존).
     *
     * 세션 문서의 "종합결과" 요약 필드가 아니라 "투구별기록"/"포구별기록" 서브컬렉션의
     * 개별 판정을 직접 집계한다 — 세션 요약 필드는 과거 버전의 게임 로직 버그로 실제
     * 투구별 판정과 어긋나는 경우가 있었기 때문에("내 기록" 화면과 다른 값이 나오는 원인),
     * 개별 기록을 유일한 진실 공급원(source of truth)으로 삼는다.
     */
    fun syncBattingFromHistory(userId: String, userName: String, onComplete: () -> Unit = {}) {
        if (userId == "anonymous") { onComplete(); return }
        val db = FirebaseFirestore.getInstance()
        val quarter = currentQuarter()

        db.collection("users").document(userId).collection("훈련기록")
            .whereGreaterThanOrEqualTo("생성일시", Timestamp(quarterStartDate()))
            .get()
            .addOnSuccessListener { sessionDocs ->
                val sessions = sessionDocs.documents
                if (sessions.isEmpty()) {
                    writeBattingAggregate(db, quarter, userId, userName, 0L, 0L, 0L, 0L, onComplete)
                    return@addOnSuccessListener
                }

                var totalPitches = 0L
                var hits = 0L
                var reactionSum = 0L
                var reactionCnt = 0L
                var completed = 0

                sessions.forEach { sessionDoc ->
                    sessionDoc.reference.collection("투구별기록").get()
                        .addOnSuccessListener { pitchDocs ->
                            pitchDocs.documents.forEach { p ->
                                totalPitches++
                                if (p.getString("판정") == "정타") {
                                    hits++
                                    val baseCorrect = p.getBoolean("베이스정답여부") == true
                                    val reaction = p.getLong("주루반응속도")
                                    if (baseCorrect && reaction != null && reaction >= 0) {
                                        reactionSum += reaction
                                        reactionCnt++
                                    }
                                }
                            }
                            completed++
                            if (completed == sessions.size) {
                                writeBattingAggregate(db, quarter, userId, userName, totalPitches, hits, reactionSum, reactionCnt, onComplete)
                            }
                        }
                        .addOnFailureListener {
                            completed++
                            if (completed == sessions.size) {
                                writeBattingAggregate(db, quarter, userId, userName, totalPitches, hits, reactionSum, reactionCnt, onComplete)
                            }
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e("RankingUpdater", "타격 훈련기록 조회 실패: ${e.message}", e)
                onComplete()
            }
    }

    private fun writeBattingAggregate(
        db: FirebaseFirestore, quarter: String, userId: String, userName: String,
        totalPitches: Long, hits: Long, reactionSum: Long, reactionCnt: Long,
        onComplete: () -> Unit
    ) {
        val battingAvg  = if (totalPitches > 0) hits.toDouble() / totalPitches * 100.0 else 0.0
        val avgReaction = if (reactionCnt > 0) reactionSum.toDouble() / reactionCnt else null
        val reactScore  = reactionScore(avgReaction, BATTING_MIN_MS, BATTING_MAX_MS)
        val score       = (0.6 * battingAvg + 0.4 * reactScore).coerceIn(0.0, 100.0)

        db.collection(entriesPath("batting", quarter)).document(userId).set(
            hashMapOf(
                "name" to userName,
                "totalPitches" to totalPitches,
                "hitCount" to hits,
                "reactionSumMs" to reactionSum,
                "reactionSampleCount" to reactionCnt,
                "score" to score,
                "updatedAt" to Timestamp.now()
            )
        ).addOnSuccessListener {
            Log.d("RankingUpdater", "타격 랭킹 소급 동기화 성공(투구별기록 기준): $userId totalPitches=$totalPitches hits=$hits")
            onComplete()
        }.addOnFailureListener { e ->
            Log.e("RankingUpdater", "타격 랭킹 소급 동기화 저장 실패: ${e.message}", e)
            onComplete()
        }
    }

    fun syncDefenseFromHistory(userId: String, userName: String, onComplete: () -> Unit = {}) {
        if (userId == "anonymous") { onComplete(); return }
        val db = FirebaseFirestore.getInstance()
        val quarter = currentQuarter()

        db.collection("users").document(userId).collection("수비훈련기록")
            .whereGreaterThanOrEqualTo("생성일시", Timestamp(quarterStartDate()))
            .get()
            .addOnSuccessListener { sessionDocs ->
                val sessions = sessionDocs.documents
                if (sessions.isEmpty()) {
                    writeDefenseAggregate(db, quarter, userId, userName, 0L, 0L, 0L, 0L, onComplete)
                    return@addOnSuccessListener
                }

                var totalAttempts = 0L
                var successes = 0L
                var reactionSum = 0L
                var reactionCnt = 0L
                var completed = 0

                sessions.forEach { sessionDoc ->
                    sessionDoc.reference.collection("포구별기록").get()
                        .addOnSuccessListener { catchDocs ->
                            catchDocs.documents.forEach { c ->
                                totalAttempts++
                                if (c.getString("결과") == "성공") {
                                    successes++
                                    val reaction = c.getLong("반응속도")
                                    if (reaction != null && reaction >= 0) {
                                        reactionSum += reaction
                                        reactionCnt++
                                    }
                                }
                            }
                            completed++
                            if (completed == sessions.size) {
                                writeDefenseAggregate(db, quarter, userId, userName, totalAttempts, successes, reactionSum, reactionCnt, onComplete)
                            }
                        }
                        .addOnFailureListener {
                            completed++
                            if (completed == sessions.size) {
                                writeDefenseAggregate(db, quarter, userId, userName, totalAttempts, successes, reactionSum, reactionCnt, onComplete)
                            }
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e("RankingUpdater", "수비 훈련기록 조회 실패: ${e.message}", e)
                onComplete()
            }
    }

    private fun writeDefenseAggregate(
        db: FirebaseFirestore, quarter: String, userId: String, userName: String,
        totalAttempts: Long, successes: Long, reactionSum: Long, reactionCnt: Long,
        onComplete: () -> Unit
    ) {
        val successRate = if (totalAttempts > 0) successes.toDouble() / totalAttempts * 100.0 else 0.0
        val avgReaction = if (reactionCnt > 0) reactionSum.toDouble() / reactionCnt else null
        val reactScore  = reactionScore(avgReaction, DEFENSE_MIN_MS, DEFENSE_MAX_MS)
        val score       = (0.6 * successRate + 0.4 * reactScore).coerceIn(0.0, 100.0)

        db.collection(entriesPath("defense", quarter)).document(userId).set(
            hashMapOf(
                "name" to userName,
                "attemptCount" to totalAttempts,
                "successCount" to successes,
                "reactionSumMs" to reactionSum,
                "reactionSampleCount" to reactionCnt,
                "score" to score,
                "updatedAt" to Timestamp.now()
            )
        ).addOnSuccessListener {
            Log.d("RankingUpdater", "수비 랭킹 소급 동기화 성공(포구별기록 기준): $userId totalAttempts=$totalAttempts successes=$successes")
            onComplete()
        }.addOnFailureListener { e ->
            Log.e("RankingUpdater", "수비 랭킹 소급 동기화 저장 실패: ${e.message}", e)
            onComplete()
        }
    }
}
