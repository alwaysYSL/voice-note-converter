#include <jni.h>
#include <opus.h>

#include <cstdint>

namespace {

OpusEncoder *fromHandle(jlong handle) {
    return reinterpret_cast<OpusEncoder *>(static_cast<intptr_t>(handle));
}

void throwJava(JNIEnv *env, const char *message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    if (exceptionClass != nullptr) env->ThrowNew(exceptionClass, message);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_aistudio_voicenote_cvtr_audio_SoftwareOpusJni_nativeCreate(
        JNIEnv *env,
        jobject,
        jint sampleRate,
        jint channels,
        jint bitrate,
        jint complexity) {
    int error = OPUS_OK;
    OpusEncoder *encoder = opus_encoder_create(
            sampleRate,
            channels,
            OPUS_APPLICATION_VOIP,
            &error);
    if (encoder == nullptr || error != OPUS_OK) {
        throwJava(env, opus_strerror(error));
        return 0;
    }
    if (opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate)) != OPUS_OK ||
        opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(complexity)) != OPUS_OK ||
        opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE)) != OPUS_OK) {
        opus_encoder_destroy(encoder);
        throwJava(env, "Gagal mengonfigurasi encoder Opus software");
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(encoder));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aistudio_voicenote_cvtr_audio_SoftwareOpusJni_nativeGetLookahead(
        JNIEnv *env,
        jobject,
        jlong handle) {
    OpusEncoder *encoder = fromHandle(handle);
    if (encoder == nullptr) {
        throwJava(env, "Encoder Opus software sudah ditutup");
        return 0;
    }
    int lookahead = 0;
    const int error = opus_encoder_ctl(encoder, OPUS_GET_LOOKAHEAD(&lookahead));
    if (error != OPUS_OK) {
        throwJava(env, opus_strerror(error));
        return 0;
    }
    return lookahead;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_aistudio_voicenote_cvtr_audio_SoftwareOpusJni_nativeEncode(
        JNIEnv *env,
        jobject,
        jlong handle,
        jshortArray samples,
        jint frameSize,
        jint maxPacketBytes) {
    OpusEncoder *encoder = fromHandle(handle);
    if (encoder == nullptr || samples == nullptr) {
        throwJava(env, "Input encoder Opus software tidak valid");
        return nullptr;
    }
    const jsize sampleCount = env->GetArrayLength(samples);
    constexpr int kPacketCapacity = 4000;
    if (sampleCount < frameSize || maxPacketBytes <= 0 || maxPacketBytes > kPacketCapacity) {
        throwJava(env, "Frame PCM Opus tidak lengkap");
        return nullptr;
    }
    auto *pcm = static_cast<jshort *>(env->GetPrimitiveArrayCritical(samples, nullptr));
    if (pcm == nullptr) return nullptr;
    unsigned char packet[kPacketCapacity];
    const int encodedBytes = opus_encode(
            encoder,
            reinterpret_cast<const opus_int16 *>(pcm),
            frameSize,
            packet,
            maxPacketBytes);
    env->ReleasePrimitiveArrayCritical(samples, pcm, JNI_ABORT);
    if (encodedBytes < 0) {
        throwJava(env, opus_strerror(encodedBytes));
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(encodedBytes);
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(
            result,
            0,
            encodedBytes,
            reinterpret_cast<const jbyte *>(packet));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aistudio_voicenote_cvtr_audio_SoftwareOpusJni_nativeDestroy(
        JNIEnv *,
        jobject,
        jlong handle) {
    OpusEncoder *encoder = fromHandle(handle);
    if (encoder != nullptr) opus_encoder_destroy(encoder);
}
