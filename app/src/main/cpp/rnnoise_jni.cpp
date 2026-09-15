#include <jni.h>
#include "rnnoise/include/rnnoise.h"
#include <vector>

extern "C" JNIEXPORT jlong JNICALL
Java_com_aistudio_voicenote_cvtr_editor_audio_RnNoiseJni_create(JNIEnv *env, jobject thiz) {
    DenoiseState *st = rnnoise_create(NULL);
    return reinterpret_cast<jlong>(st);
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_aistudio_voicenote_cvtr_editor_audio_RnNoiseJni_processFrame(JNIEnv *env, jobject thiz, jlong handle, jshortArray frame) {
    DenoiseState *st = reinterpret_cast<DenoiseState *>(handle);
    
    jsize len = env->GetArrayLength(frame);
    jshort *elements = env->GetShortArrayElements(frame, NULL);
    
    std::vector<float> in_floats(len);
    for (int i = 0; i < len; ++i) {
        in_floats[i] = elements[i];
    }
    
    std::vector<float> out_floats(len);
    rnnoise_process_frame(st, out_floats.data(), in_floats.data());
    
    jshortArray out = env->NewShortArray(len);
    jshort *out_elements = env->GetShortArrayElements(out, NULL);
    for (int i = 0; i < len; ++i) {
        float sample = out_floats[i];
        if (sample > 32767.0f) sample = 32767.0f;
        if (sample < -32768.0f) sample = -32768.0f;
        out_elements[i] = static_cast<jshort>(sample);
    }
    env->ReleaseShortArrayElements(out, out_elements, 0);
    env->ReleaseShortArrayElements(frame, elements, JNI_ABORT);
    
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aistudio_voicenote_cvtr_editor_audio_RnNoiseJni_destroy(JNIEnv *env, jobject thiz, jlong handle) {
    DenoiseState *st = reinterpret_cast<DenoiseState *>(handle);
    if (st != NULL) {
        rnnoise_destroy(st);
    }
}
