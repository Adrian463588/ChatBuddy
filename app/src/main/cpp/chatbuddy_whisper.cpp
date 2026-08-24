#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <mutex>
#include <string>
#include <vector>

#include "whisper.h"

namespace {

constexpr char kLogTag[] = "ChatBuddyWhisper";
constexpr int kThreadCount = 2;

whisper_context *g_context = nullptr;
std::mutex g_mutex;

void freeContext() {
    if (g_context != nullptr) {
        whisper_free(g_context);
        g_context = nullptr;
    }
}

std::string readString(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    const std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_chatbuddy_ai_voice_WhisperNative_nativeInit(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_chatbuddy_ai_voice_WhisperNative_nativeLoad(
        JNIEnv *env,
        jclass,
        jstring model_path) {
    const std::string path = readString(env, model_path);
    if (path.empty()) return 1;

    std::lock_guard<std::mutex> lock(g_mutex);
    freeContext();
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    params.flash_attn = false;
    g_context = whisper_init_from_file_with_params(path.c_str(), params);
    if (g_context == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Unable to load Whisper model");
        return 2;
    }
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_chatbuddy_ai_voice_WhisperNative_nativeIsLoaded(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_context != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_chatbuddy_ai_voice_WhisperNative_nativeTranscribe(
        JNIEnv *env,
        jclass,
        jshortArray samples,
        jstring language) {
    if (samples == nullptr) return env->NewStringUTF("");

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_context == nullptr) return env->NewStringUTF("");

    const jsize sample_count = env->GetArrayLength(samples);
    if (sample_count <= 0) return env->NewStringUTF("");

    jshort *input = env->GetShortArrayElements(samples, nullptr);
    if (input == nullptr) return env->NewStringUTF("");

    std::vector<float> pcm(static_cast<size_t>(sample_count));
    std::transform(input, input + sample_count, pcm.begin(), [](jshort value) {
        return static_cast<float>(value) / 32768.0f;
    });
    env->ReleaseShortArrayElements(samples, input, JNI_ABORT);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = kThreadCount;
    params.language = nullptr;
    params.detect_language = true;
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.single_segment = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.suppress_nst = true;

    const std::string language_tag = readString(env, language);
    if (!language_tag.empty() && language_tag != "auto") {
        params.language = language_tag.c_str();
        params.detect_language = false;
    }

    if (whisper_full(g_context, params, pcm.data(), sample_count) != 0) {
        __android_log_write(ANDROID_LOG_ERROR, kLogTag, "Whisper transcription failed");
        return env->NewStringUTF("");
    }

    std::string text;
    const int segment_count = whisper_full_n_segments(g_context);
    for (int index = 0; index < segment_count; ++index) {
        const char *segment = whisper_full_get_segment_text(g_context, index);
        if (segment != nullptr) text.append(segment);
    }
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_chatbuddy_ai_voice_WhisperNative_nativeClose(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    freeContext();
}
