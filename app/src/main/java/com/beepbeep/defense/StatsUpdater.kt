package com.beepbeep.defense

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore

/**
 * 훈련 종료 시 batting_stats/{userId}, defense_stats/{userId} 문서를 갱신한다.
 * RecordActivity는 이 요약 문서 1개만 읽으면 되므로 Firestore 읽기가 대폭 감소한다.
 */
object StatsUpdater {

    private const val MAX_RECENT = 80

    private fun battingRef(db: FirebaseFirestore, userId: String) =
        db.collection("batting_stats").document(userId)

    private fun defenseRef(db: FirebaseFirestore, userId: String) =
        db.collection("defense_stats").document(userId)

    fun updateBattingStats(
        userId: String,
        pitchCount: Int,
        hitCount: Int,
        foulCount: Int,
        strikeCount: Int,
        baseCorrectCount: Int,
        reactionTimesMs: List<Long>,
        perPitchRecords: List<Map<String, Any?>>,
        sessionDateMs: Long
    ) {
        if (userId == "anonymous" || pitchCount <= 0) return
        val db = FirebaseFirestore.getInstance()
        val ref = battingRef(db, userId)

        val newPitches: List<Map<String, Any>> = perPitchRecords.map { r ->
            mapOf(
                "result"      to (r["판정"] as? String ?: ""),
                "baseCorrect" to (r["베이스정답여부"] as? Boolean ?: false),
                "reactionMs"  to ((r["주루반응속도"] as? Number)?.toLong() ?: -1L),
                "dateMs"      to sessionDateMs
            )
        }

        db.runTransaction { tx ->
            val snap = tx.get(ref)
            @Suppress("UNCHECKED_CAST")
            val prev = if (snap.exists())
                (snap.get("recentPitches") as? List<Map<String, Any>>) ?: emptyList()
            else emptyList()

            val updated = (prev + newPitches).takeLast(MAX_RECENT)

            tx.set(ref, hashMapOf(
                "totalPitches"        to ((snap.getLong("totalPitches")        ?: 0L) + pitchCount),
                "hitCount"            to ((snap.getLong("hitCount")            ?: 0L) + hitCount),
                "foulCount"           to ((snap.getLong("foulCount")           ?: 0L) + foulCount),
                "strikeCount"         to ((snap.getLong("strikeCount")         ?: 0L) + strikeCount),
                "baseCorrectCount"    to ((snap.getLong("baseCorrectCount")    ?: 0L) + baseCorrectCount),
                "reactionSumMs"       to ((snap.getLong("reactionSumMs")       ?: 0L) + reactionTimesMs.sum()),
                "reactionSampleCount" to ((snap.getLong("reactionSampleCount") ?: 0L) + reactionTimesMs.size),
                "recentPitches"       to updated,
                "updatedAt"           to Timestamp.now()
            ))
            null
        }.addOnFailureListener { e ->
            Log.e("StatsUpdater", "타격 통계 갱신 실패: ${e.message}", e)
        }
    }

    fun updateDefenseStats(
        userId: String,
        attemptCount: Int,
        successCount: Int,
        reactionTimesMs: List<Long>,
        perCatchRecords: List<Map<String, Any?>>,
        sessionDateMs: Long
    ) {
        if (userId == "anonymous" || attemptCount <= 0) return
        val db = FirebaseFirestore.getInstance()
        val ref = defenseRef(db, userId)

        val newCatches: List<Map<String, Any>> = perCatchRecords.map { r ->
            mapOf(
                "success"    to ((r["결과"] as? String) == "성공"),
                "reactionMs" to ((r["반응속도"] as? Number)?.toLong() ?: -1L),
                "dateMs"     to sessionDateMs
            )
        }

        db.runTransaction { tx ->
            val snap = tx.get(ref)
            @Suppress("UNCHECKED_CAST")
            val prev = if (snap.exists())
                (snap.get("recentCatches") as? List<Map<String, Any>>) ?: emptyList()
            else emptyList()

            val updated = (prev + newCatches).takeLast(MAX_RECENT)

            tx.set(ref, hashMapOf(
                "totalAttempts"       to ((snap.getLong("totalAttempts")       ?: 0L) + attemptCount),
                "successCount"        to ((snap.getLong("successCount")        ?: 0L) + successCount),
                "reactionSumMs"       to ((snap.getLong("reactionSumMs")       ?: 0L) + reactionTimesMs.sum()),
                "reactionSampleCount" to ((snap.getLong("reactionSampleCount") ?: 0L) + reactionTimesMs.size),
                "recentCatches"       to updated,
                "updatedAt"           to Timestamp.now()
            ))
            null
        }.addOnFailureListener { e ->
            Log.e("StatsUpdater", "수비 통계 갱신 실패: ${e.message}", e)
        }
    }
}
