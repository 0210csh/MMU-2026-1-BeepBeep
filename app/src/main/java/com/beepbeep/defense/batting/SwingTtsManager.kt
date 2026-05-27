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

    // speakTwoSequentially 진행 중 stop() 호출 시 두 번째 발화 차단
    @Volatile private var sequenceActive = false

    fun init(onReady: () -> Unit = {}) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                isReady = true
                // 워밍업: 영어/한국어 음성 모델 모두 미리 로드 → 첫 SET 딜레이 제거
                val params = android.os.Bundle()
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0f)
                tts?.setLanguage(Locale.ENGLISH)
                tts?.setSpeechRate(1.2f)
                tts?.speak("SET", TextToSpeech.QUEUE_FLUSH, params, "warmup_en")
                tts?.setLanguage(Locale.KOREAN)
                tts?.setSpeechRate(1.0f)
                tts?.speak(" ", TextToSpeech.QUEUE_ADD, params, "warmup_kr")
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
        sequenceActive = true
        val deferred = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            // stop()이 먼저 호출된 경우 발화 없이 즉시 완료
            if (!sequenceActive) { deferred.complete(Unit); return@withContext }
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
        sequenceActive = false
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
