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
    external fun nativeProcessChunk(
        freq: Float,
        startGain: Float, endGain: Float,
        startPan: Float,  endPan: Float,
        outStereo: ShortArray
    ): Boolean
    external fun nativeRelease()
}
