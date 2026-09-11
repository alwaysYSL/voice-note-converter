#include <jni.h>

#include <algorithm>
#include <cmath>
#include <vector>

#include "signalsmith-stretch.h"

using Stretch = signalsmith::stretch::SignalsmithStretch<float>;

namespace {

constexpr float kShortToFloat = 1.0f / 32768.0f;
constexpr float kFloatToShort = 32767.0f;

struct PitchShifterContext {
    explicit PitchShifterContext(int sampleRate, int channels)
        : sampleRate(sampleRate), channels(std::max(channels, 1)) {
        stretch.presetDefault(this->channels, sampleRate);
    }

    void resizeBuffers(int frames) {
        inputFloat.resize(frames * channels);
        outputFloat.resize(frames * channels);
        inputBuffers.resize(channels);
        outputBuffers.resize(channels);
        for (int channel = 0; channel < channels; ++channel) {
            inputBuffers[channel] = inputFloat.data() + channel * frames;
            outputBuffers[channel] = outputFloat.data() + channel * frames;
        }
    }

    Stretch stretch;
    int sampleRate;
    int channels;
    std::vector<float> inputFloat;
    std::vector<float> outputFloat;
    std::vector<float*> inputBuffers;
    std::vector<float*> outputBuffers;
};

jshortArray toShortArray(
    JNIEnv* env,
    const std::vector<float>& planarSamples,
    int frames,
    int channels
) {
    const int totalSamples = frames * channels;
    jshortArray result = env->NewShortArray(totalSamples);
    if (result == nullptr) return nullptr;

    jshort* resultData = env->GetShortArrayElements(result, nullptr);
    if (resultData == nullptr) return result;

    for (int frame = 0; frame < frames; ++frame) {
        for (int channel = 0; channel < channels; ++channel) {
            float sample = planarSamples[channel * frames + frame] * kFloatToShort;
            sample = std::max(-32768.0f, std::min(32767.0f, sample));
            resultData[frame * channels + channel] =
                static_cast<jshort>(std::lround(sample));
        }
    }
    env->ReleaseShortArrayElements(result, resultData, 0);
    return result;
}

jshortArray emptyArray(JNIEnv* env) {
    return env->NewShortArray(0);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_audio_PitchShifterJni_create(
    JNIEnv* /*env*/,
    jobject /*thisObject*/,
    jint sampleRate,
    jint channels
) {
    if (sampleRate <= 0 || channels <= 0) return 0;
    auto* context = new PitchShifterContext(sampleRate, channels);
    return reinterpret_cast<jlong>(context);
}

JNIEXPORT void JNICALL
Java_com_example_audio_PitchShifterJni_setTranspose(
    JNIEnv* /*env*/,
    jobject /*thisObject*/,
    jlong handle,
    jfloat semitones,
    jfloat tonalityLimit
) {
    auto* context = reinterpret_cast<PitchShifterContext*>(handle);
    if (context == nullptr) return;
    context->stretch.setTransposeSemitones(semitones, tonalityLimit);
}

JNIEXPORT jshortArray JNICALL
Java_com_example_audio_PitchShifterJni_process(
    JNIEnv* env,
    jobject /*thisObject*/,
    jlong handle,
    jshortArray inputArray,
    jint inputFrames
) {
    auto* context = reinterpret_cast<PitchShifterContext*>(handle);
    if (context == nullptr || inputArray == nullptr || inputFrames <= 0) {
        return emptyArray(env);
    }

    const jsize inputLength = env->GetArrayLength(inputArray);
    const int maxFrames = inputLength / context->channels;
    const int frames = std::min(inputFrames, maxFrames);
    if (frames <= 0) return emptyArray(env);

    jshort* inputData = env->GetShortArrayElements(inputArray, nullptr);
    if (inputData == nullptr) return emptyArray(env);

    context->resizeBuffers(frames);
    for (int frame = 0; frame < frames; ++frame) {
        for (int channel = 0; channel < context->channels; ++channel) {
            context->inputFloat[channel * frames + frame] =
                inputData[frame * context->channels + channel] * kShortToFloat;
        }
    }
    env->ReleaseShortArrayElements(inputArray, inputData, JNI_ABORT);

    context->stretch.process(
        context->inputBuffers,
        frames,
        context->outputBuffers,
        frames
    );
    return toShortArray(env, context->outputFloat, frames, context->channels);
}

JNIEXPORT jshortArray JNICALL
Java_com_example_audio_PitchShifterJni_flush(
    JNIEnv* env,
    jobject /*thisObject*/,
    jlong handle
) {
    auto* context = reinterpret_cast<PitchShifterContext*>(handle);
    if (context == nullptr) return emptyArray(env);

    // Advance the processing position to the end of the fixed-length input.
    const int inputLatency = std::max(context->stretch.inputLatency(), 0);
    if (inputLatency > 0) {
        context->resizeBuffers(inputLatency);
        std::fill(context->inputFloat.begin(), context->inputFloat.end(), 0.0f);
        context->stretch.process(
            context->inputBuffers,
            inputLatency,
            context->outputBuffers,
            inputLatency
        );
    }

    const int outputLatency = std::max(context->stretch.outputLatency(), 0);
    if (outputLatency <= 0) return emptyArray(env);

    context->resizeBuffers(outputLatency);
    context->stretch.flush(context->outputBuffers, outputLatency);
    return toShortArray(env, context->outputFloat, outputLatency, context->channels);
}

JNIEXPORT void JNICALL
Java_com_example_audio_PitchShifterJni_destroy(
    JNIEnv* /*env*/,
    jobject /*thisObject*/,
    jlong handle
) {
    delete reinterpret_cast<PitchShifterContext*>(handle);
}

} // extern "C"
