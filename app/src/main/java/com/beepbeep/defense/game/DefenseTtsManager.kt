package com.beepbeep.defense.game

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class DefenseTtsManager(private val context: Context) {

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
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_defense")
    }

    suspend fun speakAndWait(text: String, locale: Locale = Locale.KOREAN) {
        if (!isReady) return
        val deferred = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            tts?.setLanguage(locale)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?)  { deferred.complete(Unit) }
                override fun onError(utteranceId: String?) { deferred.complete(Unit) }
            })
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_defense_wait")
        }
        deferred.await()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}