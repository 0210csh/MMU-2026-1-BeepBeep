package com.beepbeep.defense.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlinx.coroutines.*
import kotlin.math.*

class SpatialAudioEngine(private val context: Context) {

    var onHeadingChanged: ((Float) -> Unit)? = null

    private var audioTrack: AudioTrack? = null
    private var beepJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile var ballX = 0f
    @Volatile var ballY = 0f
    @Volatile var ballZ = -5f
    @Volatile var currentHeadingDeg = 0f

    @Volatile private var prevBallX = 0f
    @Volatile private var prevBallZ = -5f

    companion object {
        private const val SAMPLE_RATE    = 44100
        private const val FRAMES         = 128
        private const val SPEED_OF_SOUND = 343f
        private const val BEEP_FREQ      = 880f    // 고정 주파수 — 앞/뒤 구분은 HRTF에 위임
    }

    fun init() {
        val ok = ResonanceBridge.nativeInit(SAMPLE_RATE, FRAMES)
        android.util.Log.i("SpatialAudio", "Resonance Audio init: $ok")
        initAudioTrack()
    }

    private fun initAudioTrack() {
        audioTrack?.stop(); audioTrack?.release()
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setBufferSizeInBytes(minBuf * 1)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        audioTrack?.play()
    }

    // Resonance API 호출은 오디오 루프에서만 → 스레드 안전
    fun updateBallPosition(x: Float, y: Float, z: Float) {
        ballX = x; ballY = y; ballZ = z
    }

    fun updateHeading(deg: Float) {
        currentHeadingDeg = deg
    }

    private fun computePan(): Float {
        val h     = Math.toRadians(currentHeadingDeg.toDouble())
        // safeZ 동일하게 적용: ballZ≈0이면 atan2가 튀는 걸 방지
        val sz    = if (abs(ballZ) < 0.5f) (if (ballZ >= 0f) 0.5f else -0.5f) else ballZ
        val relX  = (ballX * cos(h) - sz * sin(h)).toFloat()
        val relZ  = (ballX * sin(h) + sz * cos(h)).toFloat()
        // atan2 기반 방위각 → 40°~90° 구간 선형 pan (sin 기반보다 측면 구분력 향상)
        val azimuth = atan2(relX, -relZ)           // -π ~ +π
        return (azimuth / (PI.toFloat() / 2f)).coerceIn(-1f, 1f)
    }

    fun startBeep() {
        beepJob?.cancel()
        beepJob = scope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            val stereoOut  = ShortArray(FRAMES * 2)
            val silenceBuf = ShortArray(FRAMES * 2)

            // 100ms ON / 200ms OFF (현실의 비프음 공과 동일한 고정 패턴)
            val chunksOn   = (SAMPLE_RATE * 0.10f / FRAMES).toInt().coerceAtLeast(2)
            val chunksOff  = (SAMPLE_RATE * 0.20f / FRAMES).toInt().coerceAtLeast(1)
            val fadeChunks = 2   // ~5.8ms 페이드 (클릭 노이즈 방지)

            var smoothedGain = 1f

            while (isActive) {
                // ── 거리 및 도플러 계산 ───────────────────────────────
                val horizDist     = sqrt(ballX*ballX + ballZ*ballZ).coerceAtLeast(0.5f)
                val prevHorizDist = sqrt(prevBallX*prevBallX + prevBallZ*prevBallZ).coerceAtLeast(0.5f)
                val dt            = FRAMES.toFloat() / SAMPLE_RATE
                val vel           = (prevHorizDist - horizDist) / dt.coerceAtLeast(0.001f)

                // 도플러만 적용 — 앞/뒤 방향 구분은 Resonance Audio HRTF가 담당
                val doppFreq = BEEP_FREQ * (SPEED_OF_SOUND / (SPEED_OF_SOUND - vel.coerceIn(-150f, 80f)))

                // ── 비프 ON ───────────────────────────────────────────
                var prevFade = 0f
                var prevPan  = computePan()
                repeat(chunksOn) { i ->
                    if (!isActive) return@repeat

                    val r = Math.toRadians(currentHeadingDeg.toDouble())
                    ResonanceBridge.nativeSetHeadRotation(
                        0f, sin(r / 2).toFloat(), 0f, cos(r / 2).toFloat())

                    val safeZ = if (abs(ballZ) < 0.5f) (if (ballZ >= 0f) 0.5f else -0.5f) else ballZ
                    ResonanceBridge.nativeSetSourcePosition(ballX, ballY, safeZ)

                    val curHoriz = sqrt(ballX*ballX + ballZ*ballZ).coerceAtLeast(0.5f)
                    val ratio    = (4f / curHoriz).coerceAtMost(1f)
                    smoothedGain = smoothedGain * 0.7f + (ratio * ratio) * 0.3f
                    ResonanceBridge.nativeSetGain(smoothedGain)

                    val fadeIn  = if (i < fadeChunks) (i + 1).toFloat() / fadeChunks else 1f
                    val fadeOut = if (i >= chunksOn - fadeChunks)
                                      (chunksOn - i - 1).toFloat() / fadeChunks else 1f
                    val curFade = 0.8f * fadeIn * fadeOut.coerceAtLeast(0f)
                    val curPan  = computePan()

                    val ok = ResonanceBridge.nativeProcessChunk(
                        doppFreq, prevFade, curFade, prevPan, curPan, stereoOut)
                    if (ok) audioTrack?.write(stereoOut, 0, stereoOut.size)
                    prevFade = curFade
                    prevPan  = curPan
                }

                // ── 무음 OFF ─────────────────────────────────────────
                // gain=0이지만 nativeProcessChunk를 계속 호출해 C++ 위상 연속성 유지
                // → 다음 ON 구간 시작 시 파형이 어긋나지 않아 딱 소리(클릭) 방지
                ResonanceBridge.nativeSetGain(0f)
                var offPan = computePan()
                repeat(chunksOff) {
                    if (!isActive) return@repeat
                    val r = Math.toRadians(currentHeadingDeg.toDouble())
                    ResonanceBridge.nativeSetHeadRotation(
                        0f, sin(r / 2).toFloat(), 0f, cos(r / 2).toFloat())
                    val newPan = computePan()
                    val ok = ResonanceBridge.nativeProcessChunk(
                        doppFreq, 0f, 0f, offPan, newPan, stereoOut)
                    if (ok) audioTrack?.write(stereoOut, 0, stereoOut.size)
                    else    audioTrack?.write(silenceBuf, 0, silenceBuf.size)
                    offPan = newPan
                }

                prevBallX = ballX; prevBallZ = ballZ
            }
        }
    }

    fun stopBeep() {
        beepJob?.cancel()
        beepJob = null
    }

    fun release() {
        stopBeep()
        audioTrack?.stop(); audioTrack?.release(); audioTrack = null
        ResonanceBridge.nativeRelease()
        scope.cancel()
    }
}
