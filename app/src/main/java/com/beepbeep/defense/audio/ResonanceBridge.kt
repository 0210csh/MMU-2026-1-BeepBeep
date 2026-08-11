package com.beepbeep.defense.audio

object ResonanceBridge {
    init {
        System.loadLibrary("ResonanceAudioShared")
        System.loadLibrary("resonance_bridge")
    }

    external fun nativeInit(sampleRate: Int, framesPerBuffer: Int): Boolean
    external fun nativeSetSourcePosition(x: Float, y: Float, z: Float)
    external fun nativeSetHeadRotation(qx: Float, qy: Float, qz: Float, qw: Float)
    external fun nativeSetGain(gain: Float)

    // ✅ 팀원 코드 추가: 주석
    /**
     * outStereo 는 framesPerBuffer * 2 크기의 ShortArray (미리 할당해서 전달)
     * startGain→endGain, startPan→endPan 을 청크 내부에서 샘플 단위로 선형 보간
     * pan: -1.0=완전 왼쪽, 0.0=중앙, +1.0=완전 오른쪽
     */
    external fun nativeProcessChunk(
        freq: Float,
        startGain: Float, endGain: Float,
        startPan: Float,  endPan: Float,
        outStereo: ShortArray
    ): Boolean
    external fun nativeRelease()
}