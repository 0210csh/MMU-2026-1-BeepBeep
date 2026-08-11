package com.beepbeep.defense.reservation

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 모집 생성 / 신청 폼의 각 입력 단계를 순차적으로 안내하는 TTS 매니저.
 * SwingTutorialManager/DefenseTtsManager의 speakAndWait() 패턴을 그대로 재사용한다.
 * 튜토리얼(최초 1회)이 아니라 폼 입력 자체를 상시 안내하는 용도라는 점이 다르다.
 */
class ReservationTtsManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    var isReady: Boolean = false
        private set

    fun init(onReady: () -> Unit = {}) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                isReady = true
                onReady()
            }
        }
    }

    fun speak(text: String) {
        if (!isReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reservation_tts")
    }

    /** 발화가 끝날 때까지 suspend — 단계별 순차 안내(입력 → 다음 안내)에 사용 */
    suspend fun speakAndWait(text: String) {
        if (!isReady) return
        val deferred = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?)  { deferred.complete(Unit) }
                override fun onError(utteranceId: String?) { deferred.complete(Unit) }
            })
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reservation_tts_wait")
        }
        deferred.await()
    }

    fun stop() { tts?.stop() }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
