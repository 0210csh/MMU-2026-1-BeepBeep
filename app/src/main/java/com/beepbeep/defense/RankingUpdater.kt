package com.beepbeep.defense

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Calendar
import java.util.Date

/**
 * 분기제 랭킹 집계(rankings_batting/rankings_defense) 갱신.
 * 세션 종료마다 트랜잭션으로 현재 분기 누적값을 읽어 더하고, 분기가 바뀌었으면 0부터 다시 시작한다.
 */
object RankingUpdater {

    private const val BATTING_MIN_MS = 300.0
    private const val BATTING_MAX_MS = 2000.0
    private const val DEFENSE_MIN_MS = 500.0
    private const val DEFENSE_MAX_MS = 3000.0

    private fun currentQuarter(): String {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val quarter = cal.get(Calendar.MONTH) / 3 + 1
        return "${year}Q$quarter"
    }

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
        val ref = db.collection("rankings_batting").document(userId)
        val quarter = currentQuarter()

        db.runTransaction { tx ->
            val snap = tx.get(ref)
            val sameQuarter = snap.exists() && snap.getString("quarter") == quarter

            val totalPitches = (if (sameQuarter) snap.getLong("totalPitches") ?: 0L else 0L) + pitchCount
            val hits         = (if (sameQuarter) snap.getLong("hitCount") ?: 0L else 0L) + hitCount
            var reactionSum  = if (sameQuarter) snap.getLong("reactionSumMs") ?: 0L else 0L
            var reactionCnt  = if (sameQuarter) snap.getLong("reactionSampleCount") ?: 0L else 0L
            reactionTimesMs.forEach { ms -> reactionSum += ms; reactionCnt += 1 }

            val battingAvg   = if (totalPitches > 0) hits.toDouble() / totalPitches * 100.0 else 0.0
            val avgReaction  = if (reactionCnt > 0) reactionSum.toDouble() / reactionCnt else null
            val reactScore   = reactionScore(avgReaction, BATTING_MIN_MS, BATTING_MAX_MS)
            val score        = (0.6 * battingAvg + 0.4 * reactScore).coerceIn(0.0, 100.0)

            tx.set(
                ref,
                hashMapOf(
                    "quarter" to quarter,
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
            .addOnSuccessListener { Log.d("RankingUpdater", "타격 랭킹 갱신 성공: $userId") }
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
        val ref = db.collection("rankings_defense").document(userId)
        val quarter = currentQuarter()

        db.runTransaction { tx ->
            val snap = tx.get(ref)
            val sameQuarter = snap.exists() && snap.getString("quarter") == quarter

            val totalAttempts = (if (sameQuarter) snap.getLong("attemptCount") ?: 0L else 0L) + attemptCount
            val successes     = (if (sameQuarter) snap.getLong("successCount") ?: 0L else 0L) + successCount
            var reactionSum   = if (sameQuarter) snap.getLong("reactionSumMs") ?: 0L else 0L
            var reactionCnt   = if (sameQuarter) snap.getLong("reactionSampleCount") ?: 0L else 0L
            reactionTimesMs.forEach { ms -> reactionSum += ms; reactionCnt += 1 }

            val successRate  = if (totalAttempts > 0) successes.toDouble() / totalAttempts * 100.0 else 0.0
            val avgReaction  = if (reactionCnt > 0) reactionSum.toDouble() / reactionCnt else null
            val reactScore   = reactionScore(avgReaction, DEFENSE_MIN_MS, DEFENSE_MAX_MS)
            val score        = (0.6 * successRate + 0.4 * reactScore).coerceIn(0.0, 100.0)

            tx.set(
                ref,
                hashMapOf(
                    "quarter" to quarter,
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
            .addOnSuccessListener { Log.d("RankingUpdater", "수비 랭킹 갱신 성공: $userId") }
            .addOnFailureListener { e -> Log.e("RankingUpdater", "수비 랭킹 갱신 실패: ${e.message}", e) }
    }

    /** 최소 표본(20) 이상인지 여부 — 랭킹 리스트/순위 계산 포함 조건 */
    const val MIN_SAMPLE = 20

    /**
     * 세션 종료 훅(updateBattingRanking)은 그 이후에 끝난 세션만 누적하기 때문에,
     * 이 기능을 붙이기 전에 이미 쌓여있던 과거 훈련기록은 반영되지 않는다.
     * 랭킹 화면을 열 때 이번 분기의 훈련기록/수비훈련기록을 원본에서 다시 집계해
     * rankings_batting/defense를 덮어써서 소급 반영 + 자가 치유(self-heal) 한다.
     */
    fun syncBattingFromHistory(userId: String, userName: String, onComplete: () -> Unit = {}) {
        if (userId == "anonymous") { onComplete(); return }
        val db = FirebaseFirestore.getInstance()
        val quarter = currentQuarter()

        db.collection("users").document(userId).collection("훈련기록")
            .whereGreaterThanOrEqualTo("생성일시", Timestamp(quarterStartDate()))
            .get()
            .addOnSuccessListener { docs ->
                var totalPitches = 0L
                var hits = 0L
                var reactionSum = 0L
                var reactionCnt = 0L
                docs.documents.forEach { doc ->
                    val pitches = doc.getLong("실제투구수") ?: doc.getLong("목표투구수") ?: 0L
                    totalPitches += pitches
                    val result = doc.get("종합결과") as? Map<*, *> ?: return@forEach
                    hits += (result["정타수"] as? Number)?.toLong() ?: 0L
                    val baseCorrect = (result["베이스정답수"] as? Number)?.toLong() ?: 0L
                    val avgReaction = (result["반응속도평균"] as? Number)?.toLong() ?: -1L
                    if (baseCorrect > 0 && avgReaction >= 0) {
                        reactionSum += avgReaction * baseCorrect
                        reactionCnt += baseCorrect
                    }
                }

                val battingAvg  = if (totalPitches > 0) hits.toDouble() / totalPitches * 100.0 else 0.0
                val avgReaction = if (reactionCnt > 0) reactionSum.toDouble() / reactionCnt else null
                val reactScore  = reactionScore(avgReaction, BATTING_MIN_MS, BATTING_MAX_MS)
                val score       = (0.6 * battingAvg + 0.4 * reactScore).coerceIn(0.0, 100.0)

                db.collection("rankings_batting").document(userId).set(
                    hashMapOf(
                        "quarter" to quarter,
                        "name" to userName,
                        "totalPitches" to totalPitches,
                        "hitCount" to hits,
                        "reactionSumMs" to reactionSum,
                        "reactionSampleCount" to reactionCnt,
                        "score" to score,
                        "updatedAt" to Timestamp.now()
                    )
                ).addOnSuccessListener {
                    Log.d("RankingUpdater", "타격 랭킹 소급 동기화 성공: $userId totalPitches=$totalPitches")
                    onComplete()
                }.addOnFailureListener { e ->
                    Log.e("RankingUpdater", "타격 랭킹 소급 동기화 저장 실패: ${e.message}", e)
                    onComplete()
                }
            }
            .addOnFailureListener { e ->
                Log.e("RankingUpdater", "타격 훈련기록 조회 실패: ${e.message}", e)
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
            .addOnSuccessListener { docs ->
                var totalAttempts = 0L
                var successes = 0L
                var reactionSum = 0L
                var reactionCnt = 0L
                docs.documents.forEach { doc ->
                    val result = doc.get("종합결과") as? Map<*, *> ?: return@forEach
                    val success = (result["성공횟수"] as? Number)?.toLong() ?: 0L
                    val fail    = (result["실패횟수"] as? Number)?.toLong() ?: 0L
                    successes += success
                    totalAttempts += success + fail
                    val avgReaction = (result["평균반응속도"] as? Number)?.toLong() ?: -1L
                    if (success > 0 && avgReaction >= 0) {
                        reactionSum += avgReaction * success
                        reactionCnt += success
                    }
                }

                val successRate = if (totalAttempts > 0) successes.toDouble() / totalAttempts * 100.0 else 0.0
                val avgReaction = if (reactionCnt > 0) reactionSum.toDouble() / reactionCnt else null
                val reactScore  = reactionScore(avgReaction, DEFENSE_MIN_MS, DEFENSE_MAX_MS)
                val score       = (0.6 * successRate + 0.4 * reactScore).coerceIn(0.0, 100.0)

                db.collection("rankings_defense").document(userId).set(
                    hashMapOf(
                        "quarter" to quarter,
                        "name" to userName,
                        "attemptCount" to totalAttempts,
                        "successCount" to successes,
                        "reactionSumMs" to reactionSum,
                        "reactionSampleCount" to reactionCnt,
                        "score" to score,
                        "updatedAt" to Timestamp.now()
                    )
                ).addOnSuccessListener {
                    Log.d("RankingUpdater", "수비 랭킹 소급 동기화 성공: $userId totalAttempts=$totalAttempts")
                    onComplete()
                }.addOnFailureListener { e ->
                    Log.e("RankingUpdater", "수비 랭킹 소급 동기화 저장 실패: ${e.message}", e)
                    onComplete()
                }
            }
            .addOnFailureListener { e ->
                Log.e("RankingUpdater", "수비 훈련기록 조회 실패: ${e.message}", e)
                onComplete()
            }
    }
}
