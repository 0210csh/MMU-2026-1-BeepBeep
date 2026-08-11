package com.beepbeep.defense.notification

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.beepbeep.defense.R
import com.beepbeep.defense.ReservationActivity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.Locale

/**
 * 예약/모집 알림 수신. 앱이 포그라운드면 TTS+진동으로 즉시 안내하고,
 * 백그라운드/종료 상태면 표준 시스템 알림으로 표시한다.
 */
class BeepMessagingService : FirebaseMessagingService() {

    private var tts: TextToSpeech? = null

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val userId = getSharedPreferences("UserInfo", MODE_PRIVATE).getString("id", null) ?: return
        FirebaseFirestore.getInstance().collection("users").document(userId)
            .update("fcmToken", token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title ?: message.data["title"] ?: "BeepBeep 알림"
        val body  = message.notification?.body  ?: message.data["body"]  ?: ""

        if (isAppInForeground()) {
            speakAndVibrate(body.ifEmpty { title })
        } else {
            showSystemNotification(title, body)
        }
    }

    private fun isAppInForeground(): Boolean {
        val am = getSystemService(ActivityManager::class.java) ?: return false
        val processes = am.runningAppProcesses ?: return false
        return processes.any {
            it.processName == packageName &&
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    private fun speakAndVibrate(text: String) {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fcm_notice")
            }
        }
        val vibrator = getSystemService(Vibrator::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(longArrayOf(0, 200, 100, 200), -1)
        }
    }

    private fun showSystemNotification(title: String, body: String) {
        val channelId = "beepbeep_reservation"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "예약/모집 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
            }
            nm?.createNotificationChannel(channel)
        }
        val intent = Intent(this, ReservationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_ranking)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm?.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop(); tts?.shutdown()
    }
}
