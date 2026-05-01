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

    @Volatile var  ballX = 0f
    @Volatile var  ballY = 0f
    @Volatile var  ballZ = -5f
    @Volatile var  currentHeadingDeg = 0f

    @Volatile private var prevBallX = 0f
    @Volatile private var prevBallZ = -5f

    companion object {
        private const val SAMPLE_RATE    = 44100
        private const val FRAMES         = 512
        private const val SPEED_OF_SOUND = 343f
        private const val BEEP_FREQ      = 880f
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
            .setBufferSizeInBytes(minBuf * 6)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
    }

    fun updateBallPosition(x: Float, y: Float, z: Float) {
        ballX = x; ballY = y; ballZ = z
        ResonanceBridge.nativeSetSourcePosition(x, y, z)
    }

    fun updateHeading(deg: Float) {
        currentHeadingDeg = deg
        val rad = Math.toRadians(deg.toDouble())
        ResonanceBridge.nativeSetHeadRotation(
            0f, sin(rad / 2).toFloat(), 0f, cos(rad / 2).toFloat())
    }

    /**
     * 리스너 기준 공의 상대 X 위치 계산 → pan 값 (-1=왼쪽, +1=오른쪽)
     * currentHeadingDeg 가 음수 = 오른쪽 회전 (MainActivity 센서 부호 기준)
     *   relX = ballX*cos(H) - ballZ*sin(H)
     */
    private fun computePan(): Float {
        val h    = Math.toRadians(currentHeadingDeg.toDouble())
        val relX = (ballX * cos(h) - ballZ * sin(h)).toFloat()
        // 최대 수평 거리 ~6m 기준으로 정규화
        return (relX / 5f).coerceIn(-1f, 1f)
    }

    fun startBeep() {
        beepJob?.cancel()
        beepJob = scope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            val stereoOut  = ShortArray(FRAMES * 2)
            val silenceBuf = ShortArray(FRAMES * 2)

            val chunksOn  = (SAMPLE_RATE * 0.10f / FRAMES).toInt().coerceAtLeast(2)
            val chunksOff = (SAMPLE_RATE * 0.20f / FRAMES).toInt().coerceAtLeast(1)
            val fadeChunks = 1

            var smoothedGain = 1f

            while (isActive) {
                // ── 도플러 계산 ───────────────────────────────────────
                val horizDist     = sqrt(ballX*ballX + ballZ*ballZ).coerceAtLeast(0.5f)
                val prevHorizDist = sqrt(prevBallX*prevBallX + prevBallZ*prevBallZ).coerceAtLeast(0.5f)
                val dt            = FRAMES.toFloat() / SAMPLE_RATE
                val vel           = (prevHorizDist - horizDist) / dt.coerceAtLeast(0.001f)
                val doppFreq      = BEEP_FREQ * (SPEED_OF_SOUND / (SPEED_OF_SOUND - vel.coerceIn(-150f, 80f)))

                // ── 비프 ON ───────────────────────────────────────────
                ResonanceBridge.nativeSetGain(smoothedGain)
                var prevFade = 0f
                var prevPan  = computePan()
                repeat(chunksOn) { i ->
                    if (!isActive) return@repeat

                    // 헤드 회전 + 소스 위치 갱신
                    val r = Math.toRadians(currentHeadingDeg.toDouble())
                    ResonanceBridge.nativeSetHeadRotation(
                        0f, sin(r / 2).toFloat(), 0f, cos(r / 2).toFloat())
                    ResonanceBridge.nativeSetSourcePosition(ballX, ballY, ballZ)

                    // 거리 감쇠
                    val curHoriz = sqrt(ballX*ballX + ballZ*ballZ).coerceAtLeast(0.5f)
                    val ratio    = (4f / curHoriz).coerceAtMost(1f)
                    smoothedGain = smoothedGain * 0.7f + (ratio * ratio) * 0.3f
                    ResonanceBridge.nativeSetGain(smoothedGain)

                    val fadeIn  = if (i < fadeChunks) (i + 1).toFloat() / fadeChunks else 1f
                    val fadeOut = if (i >= chunksOn - fadeChunks)
                                      (chunksOn - i - 1).toFloat() / fadeChunks else 1f
                    val curFade = 0.8f * fadeIn * fadeOut.coerceAtLeast(0f)
                    val curPan  = computePan()

                    // startGain→endGain, startPan→endPan 모두 C++에서 샘플 단위 보간
                    val ok = ResonanceBridge.nativeProcessChunk(
                        doppFreq, prevFade, curFade, prevPan, curPan, stereoOut)
                    if (ok) audioTrack?.write(stereoOut, 0, stereoOut.size)
                    prevFade = curFade
                    prevPan  = curPan
                }

                // ── 무음 OFF ─────────────────────────────────────────
                ResonanceBridge.nativeSetGain(0f)
                repeat(chunksOff) {
                    if (!isActive) return@repeat
                    val r = Math.toRadians(currentHeadingDeg.toDouble())
                    ResonanceBridge.nativeSetHeadRotation(
                        0f, sin(r / 2).toFloat(), 0f, cos(r / 2).toFloat())
                    audioTrack?.write(silenceBuf, 0, silenceBuf.size)
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
