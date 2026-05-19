package com.beepbeep.defense.batting

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class SwingTtsManager(private val context: Context) {

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
        tts?.setLanguage(Locale.KOREAN)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_kr")
    }

    fun speakWithDone(text: String, onDone: () -> Unit) {
        if (!isReady) { onDone(); return }
        tts?.setLanguage(Locale.KOREAN)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?)  { onDone() }
            override fun onError(id: String?) { onDone() }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_done")
    }

    fun speakEnglish(text: String, speechRate: Float = 1.0f) {
        if (!isReady) return
        tts?.setLanguage(Locale.ENGLISH)
        tts?.setSpeechRate(speechRate)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_en")
    }

    suspend fun speakAndWait(text: String, locale: Locale = Locale.ENGLISH, speechRate: Float = 1.0f) {
        if (!isReady) return
        val deferred = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            tts?.setLanguage(locale)
            tts?.setSpeechRate(speechRate)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?)  { deferred.complete(Unit) }
                override fun onError(utteranceId: String?) { deferred.complete(Unit) }
            })
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_wait")
        }
        deferred.await()
    }

    /**
     * 두 문장을 TTS 큐에 연속 등록 — 첫 번째 완료 시 onFirstDone() 호출 (TTS 스레드에서 실행)
     * PITCH가 READY 직후 끊김 없이 이어지도록 QUEUE_ADD 사용
     */
    suspend fun speakTwoSequentially(
        first: String,
        second: String,
        locale: Locale = Locale.ENGLISH,
        speechRate: Float = 1.0f,
        onFirstDone: () -> Unit = {}
    ) {
        if (!isReady) return
        val deferred = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            tts?.setLanguage(locale)
            tts?.setSpeechRate(speechRate)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    when (id) {
                        "tts_seq_1" -> onFirstDone()
                        "tts_seq_2" -> deferred.complete(Unit)
                    }
                }
                override fun onError(id: String?) { deferred.complete(Unit) }
            })
            tts?.speak(first,  TextToSpeech.QUEUE_FLUSH, null, "tts_seq_1")
            tts?.speak(second, TextToSpeech.QUEUE_ADD,   null, "tts_seq_2")
        }
        deferred.await()
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
