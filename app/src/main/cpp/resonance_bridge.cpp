#include <jni.h>
#include <cmath>
#include <android/log.h>
#include "resonance/resonance_audio/api/resonance_audio_api.h"

#define LOG_TAG "ResonanceBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace vraudio;

static ResonanceAudioApi*            gApi      = nullptr;
static ResonanceAudioApi::SourceId   gSourceId = ResonanceAudioApi::kInvalidSourceId;
static int    gFramesPerBuffer = 2048;
static int    gSampleRate      = 44100;
static double gBeepPhase       = 0.0;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_beepbeep_defense_audio_ResonanceBridge_nativeInit(
        JNIEnv*, jclass, jint sampleRate, jint framesPerBuffer) {
    gSampleRate      = sampleRate;
    gFramesPerBuffer = framesPerBuffer;

    gApi = CreateResonanceAudioApi(2, (size_t)framesPerBuffer, sampleRate);
    if (!gApi) { LOGE("CreateResonanceAudioApi failed"); return JNI_FALSE; }

    gSourceId = gApi->CreateSoundObjectSource(kBinauralHighQuality);
    if (gSourceId == ResonanceAudioApi::kInvalidSourceId) {
        LOGE("CreateSoundObjectSource failed"); return JNI_FALSE;
    }

    // 거리 감쇠는 Kotlin 쪽 gain 으로 직접 제어
    gApi->SetSourceDistanceModel(gSourceId, kNone, 0.0f, 500.0f);

    LOGI("Resonance Audio init OK  rate=%d  frames=%d", sampleRate, framesPerBuffer);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_beepbeep_defense_audio_ResonanceBridge_nativeSetSourcePosition(
        JNIEnv*, jclass, jfloat x, jfloat y, jfloat z) {
    if (gApi && gSourceId != ResonanceAudioApi::kInvalidSourceId)
        gApi->SetSourcePosition(gSourceId, x, y, z);
}

JNIEXPORT void JNICALL
Java_com_beepbeep_defense_audio_ResonanceBridge_nativeSetHeadRotation(
        JNIEnv*, jclass, jfloat qx, jfloat qy, jfloat qz, jfloat qw) {
    if (gApi)
        gApi->SetHeadRotation(qx, qy, qz, qw);
}

JNIEXPORT void JNICALL
Java_com_beepbeep_defense_audio_ResonanceBridge_nativeSetGain(
        JNIEnv*, jclass, jfloat gain) {
    if (gApi && gSourceId != ResonanceAudioApi::kInvalidSourceId)
        gApi->SetSourceVolume(gSourceId, gain);
}

JNIEXPORT jboolean JNICALL
Java_com_beepbeep_defense_audio_ResonanceBridge_nativeProcessChunk(
        JNIEnv* env, jclass,
        jfloat freq,
        jfloat startGain, jfloat endGain,
        jfloat startPan,  jfloat endPan,
        jshortArray outStereo) {
    if (!gApi || gSourceId == ResonanceAudioApi::kInvalidSourceId) return JNI_FALSE;

    const int frames = gFramesPerBuffer;

    // 1) 모노 비프 생성 — gain 샘플 단위 보간
    auto* mono = new int16[frames];
    for (int i = 0; i < frames; i++) {
        float t    = (frames > 1) ? (float)i / (float)(frames - 1) : 1.0f;
        float gain = startGain + (endGain - startGain) * t;
        mono[i] = static_cast<int16>(gain * 32767.0f * (float)sin(gBeepPhase));
        gBeepPhase += 2.0 * M_PI * freq / gSampleRate;
        if (gBeepPhase >= 2.0 * M_PI) gBeepPhase -= 2.0 * M_PI;
    }

    // 2) Resonance Audio 에 모노 입력
    gApi->SetInterleavedBuffer(gSourceId, mono, 1, (size_t)frames);
    delete[] mono;

    // 3) 공간화 스테레오 출력
    jshort* out = env->GetShortArrayElements(outStereo, nullptr);
    bool ok = gApi->FillInterleavedOutputBuffer(2, (size_t)frames, (int16*)out);

    // 4) pan 샘플 단위 보간 적용 (등전력 패닝)
    //    pan: -1=완전 왼쪽, 0=중앙, +1=완전 오른쪽
    if (ok) {
        for (int i = 0; i < frames; i++) {
            float t   = (frames > 1) ? (float)i / (float)(frames - 1) : 1.0f;
            float pan = startPan + (endPan - startPan) * t;
            // 등전력 패닝: 중앙에서 양쪽 모두 -3dB (0.707)
            float angle  = (pan + 1.0f) * 0.5f * (float)M_PI_2;  // 0 ~ π/2
            float lScale = cosf(angle);
            float rScale = sinf(angle);
            int   li = i * 2,  ri = i * 2 + 1;
            int   lv = (int)(out[li] * lScale);
            int   rv = (int)(out[ri] * rScale);
            out[li] = (int16)(lv < -32768 ? -32768 : lv > 32767 ? 32767 : lv);
            out[ri] = (int16)(rv < -32768 ? -32768 : rv > 32767 ? 32767 : rv);
        }
    }

    env->ReleaseShortArrayElements(outStereo, out, 0);

    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_beepbeep_defense_audio_ResonanceBridge_nativeRelease(JNIEnv*, jclass) {
    if (gApi) {
        if (gSourceId != ResonanceAudioApi::kInvalidSourceId) {
            gApi->DestroySource(gSourceId);
            gSourceId = ResonanceAudioApi::kInvalidSourceId;
        }
        delete gApi;
        gApi = nullptr;
    }
    gBeepPhase = 0.0;
    LOGI("Resonance Audio released");
}

} // extern "C"