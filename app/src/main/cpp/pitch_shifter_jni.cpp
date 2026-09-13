#include <jni.h>

#include <algorithm>
#include <cmath>
#include <exception>
#include <limits>
#include <stdexcept>
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
        if (frames < 0 || frames > (std::numeric_limits<int>::max() / channels)) {
            throw std::invalid_argument("Pitch shifter buffer size is invalid");
        }
        inputFloat.resize(static_cast<size_t>(frames) * channels);
        outputFloat.resize(static_cast<size_t>(frames) * channels);
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

void throwRuntimeException(JNIEnv* env, const char* message) noexcept {
    if (env == nullptr || env->ExceptionCheck()) return;
    jclass exceptionClass = env->FindClass("java/lang/RuntimeException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
        env->DeleteLocalRef(exceptionClass);
    }
}

void throwRuntimeException(JNIEnv* env, const std::exception& error) noexcept {
    throwRuntimeException(env, error.what());
}

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
    if (resultData == nullptr) {
        if (!env->ExceptionCheck()) {
            throwRuntimeException(env, "Unable to access pitch shifter output");
        }
        env->DeleteLocalRef(result);
        return nullptr;
    }

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
Java_com_aistudio_voicenote_cvtr_audio_PitchShifterJni_create(
    JNIEnv* env,
    jobject /*thisObject*/,
    jint sampleRate,
    jint channels
) {
    try {
        if (sampleRate <= 0 || channels <= 0) return 0;
        auto* context = new PitchShifterContext(sampleRate, channels);
        return reinterpret_cast<jlong>(context);
    } catch (const std::exception& error) {
        throwRuntimeException(env, error);
        return 0;
    } catch (...) {
        throwRuntimeException(env, "Unknown native pitch shifter error");
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_aistudio_voicenote_cvtr_audio_PitchShifterJni_setTranspose(
    JNIEnv* env,
    jobject /*thisObject*/,
    jlong handle,
    jfloat semitones,
    jfloat tonalityLimit
) {
    try {
        auto* context = reinterpret_cast<PitchShifterContext*>(handle);
        if (context == nullptr) return;
        context->stretch.setTransposeSemitones(semitones, tonalityLimit);
    } catch (const std::exception& error) {
        throwRuntimeException(env, error);
    } catch (...) {
        throwRuntimeException(env, "Unknown native pitch shifter error");
    }
}

JNIEXPORT jshortArray JNICALL
Java_com_aistudio_voicenote_cvtr_audio_PitchShifterJni_process(
    JNIEnv* env,
    jobject /*thisObject*/,
    jlong handle,
    jshortArray inputArray,
    jint inputFrames
) {
    jshort* inputData = nullptr;
    try {
        auto* context = reinterpret_cast<PitchShifterContext*>(handle);
        if (context == nullptr || inputArray == nullptr || inputFrames <= 0) {
            return emptyArray(env);
        }

        const jsize inputLength = env->GetArrayLength(inputArray);
        if (env->ExceptionCheck()) return nullptr;
        const int maxFrames = inputLength / context->channels;
        const int frames = std::min(inputFrames, maxFrames);
        if (frames <= 0) return emptyArray(env);

        inputData = env->GetShortArrayElements(inputArray, nullptr);
        if (inputData == nullptr) {
            if (!env->ExceptionCheck()) {
                throwRuntimeException(env, "Unable to access pitch shifter input");
            }
            return nullptr;
        }

        context->resizeBuffers(frames);
        for (int frame = 0; frame < frames; ++frame) {
            for (int channel = 0; channel < context->channels; ++channel) {
                context->inputFloat[channel * frames + frame] =
                    inputData[frame * context->channels + channel] * kShortToFloat;
            }
        }
        env->ReleaseShortArrayElements(inputArray, inputData, JNI_ABORT);
        inputData = nullptr;

        context->stretch.process(
            context->inputBuffers,
            frames,
            context->outputBuffers,
            frames
        );
        return toShortArray(env, context->outputFloat, frames, context->channels);
    } catch (const std::exception& error) {
        if (inputData != nullptr) {
            env->ReleaseShortArrayElements(inputArray, inputData, JNI_ABORT);
        }
        throwRuntimeException(env, error);
        return nullptr;
    } catch (...) {
        if (inputData != nullptr) {
            env->ReleaseShortArrayElements(inputArray, inputData, JNI_ABORT);
        }
        throwRuntimeException(env, "Unknown native pitch shifter error");
        return nullptr;
    }
}

JNIEXPORT jshortArray JNICALL
Java_com_aistudio_voicenote_cvtr_audio_PitchShifterJni_flush(
    JNIEnv* env,
    jobject /*thisObject*/,
    jlong handle
) {
    try {
        auto* context = reinterpret_cast<PitchShifterContext*>(handle);
        if (context == nullptr) return emptyArray(env);

        const int inputLatency = std::max(context->stretch.inputLatency(), 0);
        const int outputLatency = std::max(context->stretch.outputLatency(), 0);
        const int totalFrames = inputLatency + outputLatency;
        if (totalFrames <= 0) return emptyArray(env);

        std::vector<float> processOutput;
        if (inputLatency > 0) {
            context->resizeBuffers(inputLatency);
            std::fill(context->inputFloat.begin(), context->inputFloat.end(), 0.0f);
            context->stretch.process(
                context->inputBuffers,
                inputLatency,
                context->outputBuffers,
                inputLatency
            );
            processOutput = context->outputFloat;
        }

        std::vector<float> flushOutput;
        if (outputLatency > 0) {
            context->resizeBuffers(outputLatency);
            context->stretch.flush(context->outputBuffers, outputLatency);
            flushOutput = context->outputFloat;
        }

        std::vector<float> combined(
            static_cast<size_t>(totalFrames) * context->channels
        );
        for (int channel = 0; channel < context->channels; ++channel) {
            if (inputLatency > 0) {
                std::copy(
                    processOutput.begin() + channel * inputLatency,
                    processOutput.begin() + (channel + 1) * inputLatency,
                    combined.begin() + channel * totalFrames
                );
            }
            if (outputLatency > 0) {
                std::copy(
                    flushOutput.begin() + channel * outputLatency,
                    flushOutput.begin() + (channel + 1) * outputLatency,
                    combined.begin() + channel * totalFrames + inputLatency
                );
            }
        }
        return toShortArray(env, combined, totalFrames, context->channels);
    } catch (const std::exception& error) {
        throwRuntimeException(env, error);
        return nullptr;
    } catch (...) {
        throwRuntimeException(env, "Unknown native pitch shifter error");
        return nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_aistudio_voicenote_cvtr_audio_PitchShifterJni_destroy(
    JNIEnv* env,
    jobject /*thisObject*/,
    jlong handle
) {
    try {
        delete reinterpret_cast<PitchShifterContext*>(handle);
    } catch (const std::exception& error) {
        throwRuntimeException(env, error);
    } catch (...) {
        throwRuntimeException(env, "Unknown native pitch shifter error");
    }
}

} // extern "C"
