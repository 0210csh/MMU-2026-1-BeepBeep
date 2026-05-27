package com.beepbeep.defense

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 네트워크 오프라인 시 훈련 데이터를 로컬(SharedPreferences)에 임시 저장하고,
 * 네트워크 복구 후 Firebase Firestore에 자동 동기화하는 유틸.
 *
 * - 타격: users/{userId}/훈련기록/{sessionId}  + 투구별기록 서브컬렉션
 * - 수비: users/{userId}/수비훈련기록/{sessionId} + 포구별기록 서브컬렉션
 *
 * Firebase Timestamp는 JSON 직렬화 불가 → sessionMillis(Long)로 저장 후 업로드 시 복원.
 */
object PendingUploadManager {

    private const val PREF_NAME   = "PendingUploads"
    private const val KEY_BATTING = "pending_batting"
    private const val KEY_DEFENSE = "pending_defense"
    private const val TAG         = "PendingUpload"

    // ── 네트워크 가용 여부 ──────────────────────────────────
    fun isNetworkAvailable(context: Context): Boolean {
        val cm      = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps    = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ── 타격 데이터 로컬 저장 ──────────────────────────────
    fun saveBatting(
        context:         Context,
        userId:          String,
        sessionId:       String,
        sessionMillis:   Long,
        sessionData:     HashMap<*, *>,
        perPitchRecords: List<HashMap<*, *>>
    ) {
        try {
            val item = JSONObject().apply {
                put("userId",          userId)
                put("sessionId",       sessionId)
                put("sessionMillis",   sessionMillis)
                put("sessionData",     hashMapToJson(sessionData))
                put("perPitchRecords", listToJsonArray(perPitchRecords))
            }
            appendToQueue(context, KEY_BATTING, item)
            Log.d(TAG, "타격 데이터 로컬 저장 완료: $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "타격 데이터 로컬 저장 실패: ${e.message}")
        }
    }

    // ── 수비 데이터 로컬 저장 ──────────────────────────────
    fun saveDefense(
        context:       Context,
        userId:        String,
        sessionId:     String,
        sessionMillis: Long,
        sessionData:   HashMap<*, *>,
        catchRecords:  List<HashMap<*, *>>
    ) {
        try {
            val item = JSONObject().apply {
                put("userId",        userId)
                put("sessionId",     sessionId)
                put("sessionMillis", sessionMillis)
                put("sessionData",   hashMapToJson(sessionData))
                put("catchRecords",  listToJsonArray(catchRecords))
            }
            appendToQueue(context, KEY_DEFENSE, item)
            Log.d(TAG, "수비 데이터 로컬 저장 완료: $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "수비 데이터 로컬 저장 실패: ${e.message}")
        }
    }

    // ── 타격 Firebase 동기화 ────────────────────────────────
    fun syncBatting(context: Context) {
        if (!isNetworkAvailable(context)) return
        val queue = readQueue(context, KEY_BATTING)
        if (queue.length() == 0) return
        Log.d(TAG, "타격 펜딩 업로드 시작: ${queue.length()}건")

        for (i in 0 until queue.length()) {
            try {
                val item          = queue.getJSONObject(i)
                val userId        = item.getString("userId")
                val sessionId     = item.getString("sessionId")
                val sessionMillis = item.getLong("sessionMillis")
                // sessionData 복원 + 생성일시(Timestamp) 재삽입
                val sessionData   = jsonToHashMap(item.getJSONObject("sessionData")).apply {
                    put("생성일시", com.google.firebase.Timestamp(
                        sessionMillis / 1000,
                        ((sessionMillis % 1000) * 1_000_000).toInt()
                    ))
                }
                val perPitchRecords = jsonArrayToList(item.getJSONArray("perPitchRecords"))

                val db         = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                val sessionRef = db.collection("users").document(userId)
                    .collection("훈련기록").document(sessionId)

                sessionRef.set(sessionData)
                    .addOnSuccessListener {
                        perPitchRecords.forEachIndexed { index, record ->
                            sessionRef.collection("투구별기록")
                                .document("${index + 1}번투구").set(record)
                        }
                        removeFromQueue(context, KEY_BATTING, sessionId)
                        Log.d(TAG, "타격 펜딩 업로드 성공: $sessionId")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "타격 펜딩 업로드 실패 (큐 유지): ${e.message}")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "타격 펜딩 항목 처리 오류: ${e.message}")
            }
        }
    }

    // ── 수비 Firebase 동기화 ────────────────────────────────
    fun syncDefense(context: Context) {
        if (!isNetworkAvailable(context)) return
        val queue = readQueue(context, KEY_DEFENSE)
        if (queue.length() == 0) return
        Log.d(TAG, "수비 펜딩 업로드 시작: ${queue.length()}건")

        for (i in 0 until queue.length()) {
            try {
                val item          = queue.getJSONObject(i)
                val userId        = item.getString("userId")
                val sessionId     = item.getString("sessionId")
                val sessionMillis = item.getLong("sessionMillis")
                val sessionData   = jsonToHashMap(item.getJSONObject("sessionData")).apply {
                    put("생성일시", com.google.firebase.Timestamp(
                        sessionMillis / 1000,
                        ((sessionMillis % 1000) * 1_000_000).toInt()
                    ))
                }
                val catchRecords = jsonArrayToList(item.getJSONArray("catchRecords"))

                val db         = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                val sessionRef = db.collection("users").document(userId)
                    .collection("수비훈련기록").document(sessionId)

                sessionRef.set(sessionData)
                    .addOnSuccessListener {
                        catchRecords.forEachIndexed { index, record ->
                            sessionRef.collection("포구별기록")
                                .document("${index + 1}번포구").set(record)
                        }
                        removeFromQueue(context, KEY_DEFENSE, sessionId)
                        Log.d(TAG, "수비 펜딩 업로드 성공: $sessionId")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "수비 펜딩 업로드 실패 (큐 유지): ${e.message}")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "수비 펜딩 항목 처리 오류: ${e.message}")
            }
        }
    }

    // ── 펜딩 여부 확인 ──────────────────────────────────────
    fun hasPendingBatting(context: Context): Boolean = readQueue(context, KEY_BATTING).length() > 0
    fun hasPendingDefense(context: Context): Boolean = readQueue(context, KEY_DEFENSE).length() > 0

    // ── 큐 내부 유틸 ────────────────────────────────────────
    private fun appendToQueue(context: Context, key: String, item: JSONObject) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val arr   = readQueue(context, key)
        arr.put(item)
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun removeFromQueue(context: Context, key: String, sessionId: String) {
        val prefs  = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val arr    = readQueue(context, key)
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("sessionId") != sessionId) newArr.put(obj)
        }
        prefs.edit().putString(key, newArr.toString()).apply()
    }

    private fun readQueue(context: Context, key: String): JSONArray {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(key, null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
    }

    // ── 직렬화 유틸 ─────────────────────────────────────────
    private fun hashMapToJson(map: Map<*, *>): JSONObject {
        val obj = JSONObject()
        for ((k, v) in map) {
            val key = k.toString()
            when (v) {
                is Map<*, *>  -> obj.put(key, hashMapToJson(v))
                is Boolean    -> obj.put(key, v)
                is Int        -> obj.put(key, v)
                is Long       -> obj.put(key, v)
                is Float      -> obj.put(key, v.toDouble())
                is Double     -> obj.put(key, v)
                is String     -> obj.put(key, v)
                // Timestamp는 sessionMillis로 별도 저장하므로 skip
                is com.google.firebase.Timestamp -> { }
                null          -> obj.put(key, JSONObject.NULL)
                else          -> obj.put(key, v.toString())
            }
        }
        return obj
    }

    private fun jsonToHashMap(json: JSONObject): HashMap<String, Any> {
        val map = HashMap<String, Any>()
        for (key in json.keys()) {
            when (val v = json.get(key)) {
                is JSONObject -> map[key] = jsonToHashMap(v)
                is JSONArray  -> map[key] = jsonArrayToList(v)
                else          -> map[key] = v
            }
        }
        return map
    }

    private fun listToJsonArray(list: List<Map<*, *>>): JSONArray {
        val arr = JSONArray()
        list.forEach { arr.put(hashMapToJson(it)) }
        return arr
    }

    private fun jsonArrayToList(arr: JSONArray): List<HashMap<String, Any>> {
        val list = mutableListOf<HashMap<String, Any>>()
        for (i in 0 until arr.length()) list.add(jsonToHashMap(arr.getJSONObject(i)))
        return list
    }
}
