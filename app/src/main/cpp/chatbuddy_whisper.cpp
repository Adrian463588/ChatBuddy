#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <mutex>
#include <string>
#include <vector>

#include "whisper.h"

namespace {

constexpr char kLogTag[] = "ChatBuddyWhisper";
constexpr int kThreadCount = 4;

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

// Apply a 1st-order high-pass IIR filter (cutoff ~80 Hz @ 16 kHz).
// alpha = RC/(RC+dt) = (1/(2*pi*80)) / (1/(2*pi*80) + 1/16000) ≈ 0.9944
// Removes low-frequency rumble (HVAC, desk vibration, electrical hum) that
// contains no meaningful speech information but can confuse the log-mel extractor.
static constexpr float kHpAlpha = 0.9944f;

void applyHighPassFilter(std::vector<float> &pcm) {
    if (pcm.size() < 2) return;
    float prevIn  = pcm[0];
    float prevOut = pcm[0];
    for (size_t i = 1; i < pcm.size(); ++i) {
        float x   = pcm[i];
        float y   = kHpAlpha * (prevOut + x - prevIn);
        prevIn    = x;
        prevOut   = y;
        // Clip to valid float range (shouldn't clip for typical speech signals).
        pcm[i]    = (y > 1.0f) ? 1.0f : (y < -1.0f) ? -1.0f : y;
    }
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

    // Convert PCM-16 → float32 [-1.0, 1.0] then apply audio preprocessing.
    std::vector<float> pcm(static_cast<size_t>(sample_count));
    std::transform(input, input + sample_count, pcm.begin(), [](jshort value) {
        return static_cast<float>(value) / 32768.0f;
    });
    env->ReleaseShortArrayElements(samples, input, JNI_ABORT);

    // High-pass filter: remove low-frequency content (<80 Hz) before log-mel extraction.
    applyHighPassFilter(pcm);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads        = kThreadCount;
    params.language         = nullptr;
    params.detect_language  = true;
    params.translate        = false;
    // Each JNI call corresponds to exactly one VAD-segmented utterance.
    params.single_segment   = true;
    params.no_context       = true;   // prevent cross-utterance hallucination drift
    params.no_timestamps    = true;
    params.print_special    = false;
    params.print_progress   = false;
    params.print_realtime   = false;
    params.print_timestamps = false;
    params.suppress_blank   = true;
    params.suppress_nst     = true;

    // ── Temperature fallback ──────────────────────────────────────────────────
    // Start with temperature=0.0 (deterministic/greedy). If entropy_thold or
    // logprob_thold checks detect low-quality output (repetition, hallucinations),
    // the decoder automatically retries with temperature += temperature_inc:
    //   attempt 1: T=0.0  attempt 2: T=0.2  attempt 3: T=0.4 … up to T=1.0
    params.temperature      = 0.0f;
    params.temperature_inc  = 0.2f;

    // ── Hallucination / quality thresholds ───────────────────────────────────
    // entropy_thold: Shannon entropy of last-32 tokens. If entropy < threshold,
    //   model is producing a repetition loop → triggers fallback.
    //   Raised from default 2.40 → 2.60 to catch repetition earlier.
    params.entropy_thold    = 2.60f;
    // logprob_thold: average log-probability of generated tokens. If avg < threshold,
    //   model has low overall confidence → triggers fallback.
    //   Slightly tightened from default -1.00 → -1.10.
    params.logprob_thold    = -1.10f;
    // no_speech_thold: if the model's internal silence classifier assigns
    //   P(silence) > threshold, the segment is discarded (returns empty string).
    //   Prevents hallucinating text over silent/near-silent frames.
    params.no_speech_thold  = 0.60f;

    // Limit the decoder context window. Default is 16384 tokens which is far more
    // than needed for a single real-time utterance (typically <50 tokens).
    // Keeping it small prevents context from growing stale and causing drift.
    params.n_max_text_ctx   = 64;

    // Apply explicit language hint when provided by the Kotlin layer.
    // Whisper uses 2-letter ISO 639-1 codes ("id" for Indonesian, "en" for English).
    const std::string language_tag = readString(env, language);
    if (!language_tag.empty() && language_tag != "auto") {
        params.language = language_tag.c_str();
        params.detect_language = false;
    }

    if (whisper_full(g_context, params, pcm.data(), static_cast<int>(pcm.size())) != 0) {
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
