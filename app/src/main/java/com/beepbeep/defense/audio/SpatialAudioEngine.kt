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

    // 거리 감쇠 계수: 클수록 가까워질 때 gain 변화가 급격함
    // 수비 기본값: 0.092 (25m→0.5m = 20dB 범위)
    // 타격 권장값: 0.30 (6.5m→0.5m = 15.6dB 범위 → 접근감 극대화)
    @Volatile var gainCoeff = 0.092f

    // 3축 헤드 트래킹: 센서 원시값(라디안). 외부에서 갱신
    @Volatile var pitchRad = 0f
    @Volatile var rollRad  = 0f
    // 중립 기준값 — 세션 시작 시 captureNeutralPitchRoll() 으로 캡처
    @Volatile private var refPitchRad = 0f
    @Volatile private var refRollRad  = 0f

    @Volatile private var prevBallX = 0f
    @Volatile private var prevBallZ = -5f

    // ITD(양이 시간차) 딜레이 버퍼 — 좌/우 채널 tail 각각 보관
    private val itdTailL = ShortArray(ITD_MAX_SAMPLES)
    private val itdTailR = ShortArray(ITD_MAX_SAMPLES)

    companion object {
        private const val SAMPLE_RATE    = 48000  // 44100 → 48000:x 기기 네이티브 레이트 일치 → 리샘플링 제거 + FastMixer 경로 활성화
        private const val FRAMES         = 64    // 128 → 64: 청크당 1.3ms (기존 2.9ms) — HRTF 방향 갱신 응답 개선
        private const val SPEED_OF_SOUND = 343f
        private const val BEEP_FREQ      = 880f    // 고정 주파수 — 앞/뒤 구분은 HRTF에 위임
        private const val HEAD_RADIUS_M  = 0.0875f // 표준 두상 반지름 (m) — ITD 계산 기준
        // 최대 ITD = HEAD_RADIUS_M / SPEED_OF_SOUND * SAMPLE_RATE ≈ 12.3샘플 → 13으로 올림
        private const val ITD_MAX_SAMPLES = 13
    }

    fun init() {
        val ok = ResonanceBridge.nativeInit(SAMPLE_RATE, FRAMES)
        android.util.Log.i("SpatialAudio", "Resonance Audio init: $ok")
        initAudioTrack()
    }

    // Resonance Audio는 건드리지 않고 AudioTrack만 재초기화
    // → 블루투스 연결로 오디오 라우팅이 바뀔 때 사용
    fun reinitAudioTrack() {
        stopBeep()
        initAudioTrack()
    }

    private fun initAudioTrack() {
        audioTrack?.stop(); audioTrack?.release()
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setBufferSizeInBytes(minBuf * 1)  // ✅ 팀원: 버퍼 축소
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)  // ✅ 팀원: 저지연
            .build()
        // play() 전 버퍼를 무음으로 채워 시작 시 하드웨어 팝(웅) 방지
        audioTrack?.write(ShortArray(minBuf), 0, minBuf)
        audioTrack?.play()
    }

    // ✅ 팀원: Resonance API 호출은 오디오 루프에서만 → 스레드 안전
    fun updateBallPosition(x: Float, y: Float, z: Float) {
        ballX = x; ballY = y; ballZ = z
    }

    fun updateHeading(deg: Float) {
        currentHeadingDeg = deg
    }

    /** pitch/roll 업데이트 — 센서 라디안 값 그대로 전달 */
    fun updatePitchRoll(pitchRad: Float, rollRad: Float) {
        this.pitchRad = pitchRad
        this.rollRad  = rollRad
    }

    /**
     * 현재 pitch/roll을 중립으로 캡처.
     * 모자에 폰을 장착하고 정면을 바라볼 때 세션 시작 직전 호출.
     * 이후 HRTF에는 상대적 pitch/roll 변화만 전달됨.
     */
    fun captureNeutralPitchRoll() {
        refPitchRad = pitchRad
        refRollRad  = rollRad
    }

    /**
     * yaw + 상대 pitch/roll 로부터 Resonance Audio 용 쿼터니언 생성.
     *
     * q_total = q_yaw(Y축) × q_pitch(X축) × q_roll(Z축)
     *
     * 반환: FloatArray(x, y, z, w) — nativeSetHeadRotation 파라미터 순서
     */
    private fun buildHeadQuaternion(yawRad: Double): FloatArray {
        val relPitch = (pitchRad - refPitchRad).toDouble()
        val relRoll  = (rollRad  - refRollRad ).toDouble()

        // 각 단일축 쿼터니언 성분
        val yw = cos(yawRad    / 2)   // q_yaw.w
        val yy = sin(yawRad    / 2)   // q_yaw.y  (Y축 회전)
        val pw = cos(relPitch  / 2)   // q_pitch.w
        val px = sin(relPitch  / 2)   // q_pitch.x (X축 회전)
        val rw = cos(relRoll   / 2)   // q_roll.w
        val rz = sin(relRoll   / 2)   // q_roll.z  (Z축 회전)

        // q_yp = q_yaw × q_pitch  (yx=yz=py=pz=0 이므로 단순화)
        val ypw =  yw * pw
        val ypx =  yw * px
        val ypy =  yy * pw
        val ypz = -yy * px

        // q_total = q_yp × q_roll  (rx=ry=0 이므로 단순화)
        val tw = (ypw * rw - ypz * rz).toFloat()
        val tx = (ypx * rw + ypy * rz).toFloat()
        val ty = (-ypx * rz + ypy * rw).toFloat()
        val tz = (ypw * rz + ypz * rw).toFloat()

        return floatArrayOf(tx, ty, tz, tw)
    }

    /** 현재 공 위치 → 헤드 기준 방위각(라디안). 양수=오른쪽, 음수=왼쪽 */
    private fun computeAzimuth(): Float {
        val h    = Math.toRadians(currentHeadingDeg.toDouble())
        val relX = (ballX * cos(h) - ballZ * sin(h)).toFloat()
        val relZ = (ballX * sin(h) + ballZ * cos(h)).toFloat()
        return atan2(relX, -relZ)
    }

    private fun computePanFromAzimuth(az: Float): Float {
        val rawPan = (az / (PI.toFloat() / 2f)).coerceIn(-1f, 1f)
        // 비선형 커브 (0.6승): 중간 각도에서 좌우 구분력 강화
        val abs = abs(rawPan)
        return if (abs > 0f) (rawPan / abs) * Math.pow(abs.toDouble(), 0.6).toFloat() else 0f
    }

    private fun computePan() = computePanFromAzimuth(computeAzimuth())

    /**
     * ITD(양이 시간차) 적용 — 방위각에 따라 한쪽 귀를 샘플 단위로 지연.
     *
     * Woodworth 근사: ITD = (HEAD_RADIUS_M / c) × sin(θ)
     * 880Hz에서 최대 ITD ≈ 255μs → ~12샘플 → 약 80° 위상차 → 좌우 구분 강화.
     * 스펙트럼 변형 없이 순수 물리 현상(소리 도달 시간 차이)만 적용.
     */
    private fun applyItd(stereoOut: ShortArray, azimuthRad: Float) {
        val d = (HEAD_RADIUS_M / SPEED_OF_SOUND * abs(sin(azimuthRad)) * SAMPLE_RATE)
            .toInt().coerceIn(0, ITD_MAX_SAMPLES - 1)
        if (d == 0) return
        if (azimuthRad >= 0f) {
            // 음원이 오른쪽 → 오른쪽 귀 먼저 도달 → 왼쪽 귀 d샘플 지연
            applyChannelDelay(stereoOut, d, channel = 0, tail = itdTailL)
            itdTailR.fill(0)
        } else {
            // 음원이 왼쪽 → 왼쪽 귀 먼저 도달 → 오른쪽 귀 d샘플 지연
            applyChannelDelay(stereoOut, d, channel = 1, tail = itdTailR)
            itdTailL.fill(0)
        }
    }

    /** stereoOut의 지정 채널(0=L, 1=R)에 d샘플 지연 적용. tail로 청크 경계 연속성 유지. */
    private fun applyChannelDelay(stereoOut: ShortArray, d: Int, channel: Int, tail: ShortArray) {
        val buf = ShortArray(FRAMES) { stereoOut[it * 2 + channel] }
        for (i in 0 until d)      stereoOut[i * 2 + channel] = tail[i]
        for (i in d until FRAMES) stereoOut[i * 2 + channel] = buf[i - d]
        for (i in 0 until d)      tail[i] = buf[FRAMES - d + i]
    }

    fun startBeep() {
        val prevJob = beepJob
        // 위치 이력 초기화 → 첫 프레임 도플러 속도 점프 방지
        prevBallX = ballX
        prevBallZ = ballZ
        beepJob = scope.launch {
            // 이전 beepJob이 AudioTrack에 쓰기를 완전히 마친 후 시작
            // cancel()만 하면 비동기 취소라 구/신 job이 동시에 write() → 양쪽 노이즈 발생
            prevJob?.cancelAndJoin()
            audioTrack?.setVolume(1f)  // stopBeep()의 setVolume(0f) 복원 → 다음 비프 무음 방지
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            val stereoOut  = ShortArray(FRAMES * 2)
            val silenceBuf = ShortArray(FRAMES * 2)

            // 100ms ON / 200ms OFF (현실의 비프음 공과 동일한 고정 패턴)
            val chunksOn   = (SAMPLE_RATE * 0.10f / FRAMES).toInt().coerceAtLeast(2)
            val chunksOff  = (SAMPLE_RATE * 0.20f / FRAMES).toInt().coerceAtLeast(1)
            val fadeChunks = 2   // ~5.8ms 페이드 (클릭 노이즈 방지)

            // 시작 50ms 무음 출력 — 초기 노이즈 구간 스킵
            val warmupChunks = (SAMPLE_RATE * 0.05f / FRAMES).toInt()
            ResonanceBridge.nativeSetGain(0f)
            repeat(warmupChunks) {
                if (!isActive) return@repeat
                val r = Math.toRadians(currentHeadingDeg.toDouble())
                val qW = buildHeadQuaternion(r)
                ResonanceBridge.nativeSetHeadRotation(qW[0], qW[1], qW[2], qW[3])
                ResonanceBridge.nativeProcessChunk(BEEP_FREQ, 0f, 0f, 0f, 0f, stereoOut)
                audioTrack?.write(stereoOut, 0, stereoOut.size)
            }

            // 시작 3D 거리(고도 2배 증폭)에 맞는 gain으로 초기화 → 첫 청크 급하강 방지
            // Y를 2배 증폭: 포물선 꼭대기에서 gain이 더 크게 감소 → 올라갔다 내려오는 소리 변화 강화
            val initAmpY = ballY * 4f
            val initDist = sqrt(ballX*ballX + initAmpY*initAmpY + ballZ*ballZ).coerceAtLeast(0.5f)
            var smoothedGain = exp(-initDist * gainCoeff).toFloat()

            // HRTF 위치 EMA 스무딩 — 청크당 1.33ms, factor=0.7 → ~2ms 수렴
            var hrtfX = ballX
            var hrtfY = ballY  // Y(고도)도 EMA 스무딩
            var hrtfZ = ballZ
            var sectorFade = 1f   // HRTF 섹터 전환 크로스페이드 (0=묵음 → 1=풀볼륨)
            var prevRelZ   = 0f   // 이전 청크의 head-relative Z (섹터 전환 감지용)

            // ITD 딜레이 버퍼 초기화 — 이전 beepJob 잔류 tail 제거
            itdTailL.fill(0); itdTailR.fill(0)

            while (isActive) {
                // BLE 연결 등 외부 요인으로 AudioTrack이 멈춘 경우 자동 재개
                if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack?.play()
                }

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
                    val qOn = buildHeadQuaternion(r)
                    ResonanceBridge.nativeSetHeadRotation(qOn[0], qOn[1], qOn[2], qOn[3])

                    hrtfX = hrtfX * 0.3f + ballX * 0.7f
                    hrtfY = hrtfY * 0.3f + ballY * 0.7f
                    hrtfZ = hrtfZ * 0.3f + ballZ * 0.7f
                    val safeZ = if (hrtfZ == 0f) 0.01f else hrtfZ
                    ResonanceBridge.nativeSetSourcePosition(hrtfX * 1.5f, hrtfY * 2.5f, safeZ)

                    val ampY = ballY * 4f
                    val curDist = sqrt(ballX*ballX + ampY*ampY + ballZ*ballZ).coerceAtLeast(0.5f)
                    val targetGain = exp(-curDist * gainCoeff).toFloat()
                    smoothedGain = smoothedGain * 0.7f + targetGain * 0.3f

                    // HRTF 섹터 전환 감지 → 크로스페이드 (±90° 노이즈 마스킹)
                    val sinR    = sin(r).toFloat()
                    val cosR    = cos(r).toFloat()
                    val curRelZ = hrtfX * sinR + hrtfZ * cosR
                    if (prevRelZ * curRelZ < 0f) sectorFade = 0f
                    prevRelZ = curRelZ
                    ResonanceBridge.nativeSetGain(sectorFade)
                    sectorFade = (sectorFade + 0.2f).coerceAtMost(1f)

                    val fadeIn  = if (i < fadeChunks) (i + 1).toFloat() / fadeChunks else 1f
                    val fadeOut = if (i >= chunksOn - fadeChunks)
                        (chunksOn - i - 1).toFloat() / fadeChunks else 1f
                    val curFade = 0.8f * fadeIn * fadeOut.coerceAtLeast(0f) * smoothedGain
                    val curAz   = computeAzimuth()
                    val curPan  = computePanFromAzimuth(curAz)

                    val ok = ResonanceBridge.nativeProcessChunk(
                        doppFreq, prevFade, curFade, prevPan, curPan, stereoOut)
                    if (ok) {
                        applyItd(stereoOut, curAz)
                        audioTrack?.write(stereoOut, 0, stereoOut.size)
                    }
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
                    val qOff = buildHeadQuaternion(r)
                    ResonanceBridge.nativeSetHeadRotation(qOff[0], qOff[1], qOff[2], qOff[3])
                    // OFF 중에도 hrtfX/Y/Z 갱신 → 다음 ON 시작 시 위치 오차 최소화
                    hrtfX = hrtfX * 0.3f + ballX * 0.7f
                    hrtfY = hrtfY * 0.3f + ballY * 0.7f
                    hrtfZ = hrtfZ * 0.3f + ballZ * 0.7f
                    // OFF 구간에도 섹터 감지 + fade 회복 → 다음 ON 시작 시 정확한 상태 유지
                    val sinROff = sin(r).toFloat()
                    val cosROff = cos(r).toFloat()
                    val offRelZ = hrtfX * sinROff + hrtfZ * cosROff
                    if (prevRelZ * offRelZ < 0f) sectorFade = 0f
                    prevRelZ = offRelZ
                    sectorFade = (sectorFade + 0.2f).coerceAtMost(1f)
                    val offAz  = computeAzimuth()
                    val newPan = computePanFromAzimuth(offAz)
                    val ok = ResonanceBridge.nativeProcessChunk(
                        doppFreq, 0f, 0f, offPan, newPan, stereoOut)
                    if (ok) {
                        applyItd(stereoOut, offAz)
                        audioTrack?.write(stereoOut, 0, stereoOut.size)
                    } else audioTrack?.write(silenceBuf, 0, silenceBuf.size)
                    offPan = newPan
                }

                prevBallX = ballX; prevBallZ = ballZ
            }
        }
    }

    fun stopBeep() {
        beepJob?.cancel()
        beepJob = null
        // 잔여 버퍼의 팝 방지: 볼륨을 즉시 0으로 → 남은 샘플이 무음으로 재생
        audioTrack?.setVolume(0f)
    }

    fun release() {
        stopBeep()
        audioTrack?.stop(); audioTrack?.release(); audioTrack = null
        ResonanceBridge.nativeRelease()
        scope.cancel()
    }
}
