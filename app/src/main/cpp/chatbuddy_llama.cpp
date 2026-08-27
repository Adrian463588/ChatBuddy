#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <mutex>
#include <string>
#include <unistd.h>
#include <vector>

#include "llama.h"

namespace {

constexpr char kLogTag[] = "ChatBuddyLlama";
constexpr int32_t kContextSize = 4096;
constexpr int32_t kBatchSize = 512;
constexpr int32_t kMicroBatchSize = kBatchSize;
constexpr int32_t kStartRuntimeNotReady = 1;
constexpr int32_t kStartPromptError = 2;
constexpr int32_t kStartTokenizeError = 3;
constexpr int32_t kStartSamplerError = 4;
constexpr int32_t kStartDecodePromptError = 5;
constexpr int32_t kStartJniStringError = 6;
constexpr int32_t kStartCancelled = 7;
constexpr int32_t kStartNativeException = 8;
constexpr int32_t kNextEndOfSequence = 1;
constexpr int32_t kNextDecodeError = 2;
constexpr int32_t kNextCancelled = 3;

llama_model *g_model = nullptr;
llama_context *g_context = nullptr;
llama_sampler *g_sampler = nullptr;
llama_batch g_batch{};
int32_t g_batchCapacity = 0;
const llama_vocab *g_vocab = nullptr;
int32_t g_position = 0;
int32_t g_generated = 0;
int32_t g_maxGenerated = 0;
int32_t g_lastNextStatus = 0;
std::vector<llama_token> g_cachedPromptTokens;
std::string g_promptCacheKey;
int32_t g_lastCacheHitTokens = 0;
bool g_backendInitialized = false;
std::atomic_bool g_cancelRequested{false};
std::mutex g_mutex;

void logError(const char *message) {
    __android_log_write(ANDROID_LOG_ERROR, kLogTag, message);
}

class ScopedUtfChars {
public:
    ScopedUtfChars(JNIEnv *env, jstring value)
        : env_(env), value_(value), chars_(
            value == nullptr ? nullptr : env->GetStringUTFChars(value, nullptr)) {}

    ~ScopedUtfChars() {
        if (chars_ != nullptr) {
            env_->ReleaseStringUTFChars(value_, chars_);
        }
    }

    const char *get() const { return chars_; }
    bool valid() const { return chars_ != nullptr; }

private:
    JNIEnv *env_;
    jstring value_;
    const char *chars_;
};

void llamaLogCallback(enum ggml_log_level level, const char *text, void *) {
    if (text == nullptr || level < GGML_LOG_LEVEL_WARN) {
        return;
    }
    bool hasVisibleCharacter = false;
    bool onlyProgressDots = true;
    for (const unsigned char *cursor = reinterpret_cast<const unsigned char *>(text);
         *cursor != '\0';
         ++cursor) {
        if (!std::isspace(*cursor)) {
            hasVisibleCharacter = true;
            if (*cursor != '.') {
                onlyProgressDots = false;
            }
        }
    }
    if (!hasVisibleCharacter || onlyProgressDots) {
        return;
    }
    const int androidLevel = level >= GGML_LOG_LEVEL_ERROR
        ? ANDROID_LOG_ERROR
        : ANDROID_LOG_WARN;
    __android_log_print(androidLevel, kLogTag, "llama.cpp: %s", text);
}

void freeSampler() {
    if (g_sampler != nullptr) {
        llama_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
}

void freeBatch(llama_batch &batch, int32_t capacity) {
    std::free(batch.token);
    std::free(batch.embd);
    std::free(batch.pos);
    std::free(batch.n_seq_id);
    if (batch.seq_id != nullptr) {
        for (int32_t index = 0; index <= capacity; ++index) {
            std::free(batch.seq_id[index]);
        }
        std::free(batch.seq_id);
    }
    std::free(batch.logits);
    batch = {};
}

bool hasUsableTokenBatch(const llama_batch &batch, int32_t capacity) {
    if (capacity <= 0 || batch.token == nullptr || batch.pos == nullptr ||
        batch.n_seq_id == nullptr || batch.seq_id == nullptr || batch.logits == nullptr) {
        return false;
    }
    for (int32_t index = 0; index < capacity; ++index) {
        if (batch.seq_id[index] == nullptr) {
            return false;
        }
    }
    return true;
}

void freeRuntime() {
    freeSampler();
    if (g_batchCapacity > 0) {
        freeBatch(g_batch, g_batchCapacity);
        g_batchCapacity = 0;
    }
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_vocab = nullptr;
    g_position = 0;
    g_generated = 0;
    g_maxGenerated = 0;
    g_lastNextStatus = 0;
    g_cachedPromptTokens.clear();
    g_promptCacheKey.clear();
    g_lastCacheHitTokens = 0;
    g_cancelRequested.store(true, std::memory_order_release);
}

bool decodeTokens(const std::vector<llama_token> &tokens) {
    for (size_t offset = 0; offset < tokens.size(); offset += kBatchSize) {
        if (g_cancelRequested.load(std::memory_order_acquire)) {
            return false;
        }
        const auto count = static_cast<int32_t>(std::min<size_t>(
            kBatchSize, tokens.size() - offset));
        llama_batch batch = llama_batch_init(count, 0, 1);
        if (!hasUsableTokenBatch(batch, count)) {
            __android_log_print(
                ANDROID_LOG_ERROR,
                kLogTag,
                "Prompt batch allocation failed at offset=%zu count=%d",
                offset,
                count);
            freeBatch(batch, count);
            return false;
        }
        batch.n_tokens = count;

        for (int32_t index = 0; index < count; ++index) {
            batch.token[index] = tokens[offset + index];
            batch.pos[index] = g_position + index;
            batch.n_seq_id[index] = 1;
            batch.seq_id[index][0] = 0;
            batch.logits[index] = (offset + index + 1 == tokens.size()) ? 1 : 0;
        }

        const int32_t result = llama_decode(g_context, batch);
        freeBatch(batch, count);
        if (result != 0) {
            __android_log_print(
                ANDROID_LOG_ERROR,
                kLogTag,
                "Prompt decode failed status=%d offset=%zu count=%d position=%d",
                result,
                offset,
                count,
                g_position);
            return false;
        }
        if (g_cancelRequested.load(std::memory_order_acquire)) {
            return false;
        }
        __android_log_print(
            ANDROID_LOG_DEBUG,
            kLogTag,
            "Prompt decode batch complete offset=%zu count=%d",
            offset,
            count);
        g_position += count;
    }
    return true;
}

void clearMemoryAndPromptCache() {
    llama_memory_clear(llama_get_memory(g_context), true);
    g_position = 0;
    g_cachedPromptTokens.clear();
    g_promptCacheKey.clear();
    g_lastCacheHitTokens = 0;
}

bool preparePrompt(const std::vector<llama_token> &tokens, const std::string &cacheKey) {
    if (g_promptCacheKey != cacheKey) {
        clearMemoryAndPromptCache();
    }
    llama_memory_t memory = llama_get_memory(g_context);
    const size_t cachedSize = g_cachedPromptTokens.size();
    const size_t commonSize = static_cast<size_t>(std::mismatch(
        g_cachedPromptTokens.begin(),
        g_cachedPromptTokens.end(),
        tokens.begin(),
        tokens.end()).first - g_cachedPromptTokens.begin());
    bool cacheReusable = false;

    if (cachedSize == 0) {
        clearMemoryAndPromptCache();
    } else {
        const llama_pos maxPosition = llama_memory_seq_pos_max(memory, 0);
        if (maxPosition < static_cast<llama_pos>(cachedSize - 1) ||
            !llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(cachedSize), -1) ||
            (commonSize < cachedSize && !llama_memory_seq_rm(
                memory, 0, static_cast<llama_pos>(commonSize), -1))) {
            clearMemoryAndPromptCache();
        } else {
            cacheReusable = true;
        }
    }

    const size_t reusableSize = cacheReusable ? commonSize : 0;
    g_position = static_cast<int32_t>(reusableSize);
    if (tokens.size() > reusableSize) {
        std::vector<llama_token> suffix(
            tokens.begin() + static_cast<std::vector<llama_token>::difference_type>(reusableSize),
            tokens.end());
        if (!decodeTokens(suffix)) {
            clearMemoryAndPromptCache();
            return false;
        }
    } else if (reusableSize > 0) {
        // Removing generated tokens does not restore the context logits. Re-decode
        // the last cached prompt token so sampling always starts from that prompt.
        if (!llama_memory_seq_rm(
                memory, 0, static_cast<llama_pos>(reusableSize - 1), -1)) {
            clearMemoryAndPromptCache();
            return false;
        }
        g_position = static_cast<int32_t>(reusableSize - 1);
        const std::vector<llama_token> lastToken(1, tokens[reusableSize - 1]);
        if (!decodeTokens(lastToken)) {
            clearMemoryAndPromptCache();
            return false;
        }
    }

    g_cachedPromptTokens = tokens;
    g_promptCacheKey = cacheKey;
    g_lastCacheHitTokens = static_cast<int32_t>(cacheReusable ? commonSize : 0);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        kLogTag,
        "Prompt KV cache reused %d/%d tokens",
        g_lastCacheHitTokens,
        static_cast<int32_t>(tokens.size()));
    return true;
}

bool tokenize(const std::string &text, std::vector<llama_token> &tokens) {
    const int32_t required = llama_tokenize(
        g_vocab,
        text.c_str(),
        static_cast<int32_t>(text.size()),
        nullptr,
        0,
        true,
        true
    );
    if (required >= 0) {
        return false;
    }
    tokens.resize(static_cast<size_t>(-required));
    const int32_t count = llama_tokenize(
        g_vocab,
        text.c_str(),
        static_cast<int32_t>(text.size()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        true,
        true
    );
    if (count < 0) {
        return false;
    }
    tokens.resize(static_cast<size_t>(count));
    return true;
}

std::string escapeControlMarkers(const char *value) {
    std::string escaped(value);
    const char *markers[] = {
        "<|turn>",
        "<turn|>",
        "<|role_start|>",
        "<|role_end|>",
        "<|channel|>",
        "<|message|>",
        "<start_of_turn>",
        "<end_of_turn>",
        "<start_of_image>",
        "<end_of_image>",
        "<|tool_call|>",
        "<|tool_response|>",
        "<|start|>",
        "<|end|>",
        "<bos>",
        "<eos>",
        "</s>"
    };
    for (const char *marker : markers) {
        const std::string valueToReplace(marker);
        const std::string replacement = "< " + valueToReplace.substr(1, valueToReplace.size() - 2) + " >";
        size_t position = 0;
        while ((position = escaped.find(valueToReplace, position)) != std::string::npos) {
            escaped.replace(position, valueToReplace.size(), replacement);
            position += replacement.size();
        }
    }
    return escaped;
}

bool applyGemma4ChatTemplate(
    const char *systemPrompt,
    const char *userPrompt,
    std::string &result) {
    // Gemma 4's canonical template is a Jinja template with turn markers
    // that the public llama_chat_apply_template API in the pinned runtime
    // cannot interpret. Keep this renderer deliberately narrow: it is only
    // selected by the Kotlin model manifest for the verified Gemma 4 artifact.
    result.clear();
    result.reserve(
        std::strlen(systemPrompt) + std::strlen(userPrompt) + 64);
    result.append("<|turn>system\n");
    result.append(systemPrompt);
    result.append("<turn|>\n<|turn>user\n");
    result.append(userPrompt);
    result.append("<turn|>\n<|turn>model\n");
    return true;
}

bool applyChatTemplate(
    const char *systemPrompt,
    const char *userPrompt,
    std::string &result,
    bool allowGemma4Fallback) {
    const std::string safeSystemPrompt = escapeControlMarkers(systemPrompt);
    const std::string safeUserPrompt = escapeControlMarkers(userPrompt);

    if (allowGemma4Fallback) {
        // The manifest has already restricted this path to the verified
        // Gemma 4 artifact. Do not let stale GGUF metadata select a legacy
        // template that would produce an invalid Gemma 4 prompt.
        __android_log_write(
            ANDROID_LOG_DEBUG,
            kLogTag,
            "Using canonical Gemma 4 chat template fallback");
        return applyGemma4ChatTemplate(
            safeSystemPrompt.c_str(),
            safeUserPrompt.c_str(),
            result);
    }

    const char *templateName = llama_model_chat_template(g_model, nullptr);
    if (templateName == nullptr || std::strlen(templateName) == 0) return false;
    llama_chat_message messages[2] = {
        {"system", safeSystemPrompt.c_str()},
        {"user", safeUserPrompt.c_str()},
    };
    const int32_t required = llama_chat_apply_template(
        templateName, messages, 2, true, nullptr, 0);
    if (required <= 0) return false;
    result.resize(static_cast<size_t>(required));
    const int32_t written = llama_chat_apply_template(
        templateName, messages, 2, true, result.data(), required);
    if (written < 0) return false;
    result.resize(static_cast<size_t>(written));
    return true;
}

std::string tokenToString(llama_token token) {
    char buffer[256];
    const int32_t size = llama_token_to_piece(g_vocab, token, buffer, sizeof(buffer), 0, false);
    if (size >= 0) {
        return std::string(buffer, static_cast<size_t>(size));
    }
    std::vector<char> expanded(static_cast<size_t>(-size));
    const int32_t expandedSize = llama_token_to_piece(
        g_vocab, token, expanded.data(), static_cast<int32_t>(expanded.size()), 0, false);
    if (expandedSize <= 0) {
        return {};
    }
    return std::string(expanded.data(), static_cast<size_t>(expandedSize));
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_chatbuddy_ai_llm_LlamaNative_nativeInit(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_backendInitialized) {
        llama_log_set(llamaLogCallback, nullptr);
        llama_backend_init();
        g_backendInitialized = true;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_chatbuddy_ai_llm_LlamaNative_nativeLoad(JNIEnv *, jclass, jint fileDescriptor) {
    std::lock_guard<std::mutex> lock(g_mutex);
    try {
        if (fileDescriptor < 0) {
            return 1;
        }
        freeRuntime();
        const int duplicated = dup(fileDescriptor);
        if (duplicated < 0) {
            return 2;
        }
        FILE *file = fdopen(duplicated, "rb");
        if (file == nullptr) {
            close(duplicated);
            return 3;
        }

        llama_model_params modelParams = llama_model_default_params();
        g_model = llama_model_load_from_file_ptr(file, modelParams);
        fclose(file);
        if (g_model == nullptr) {
            logError("Unable to load GGUF model from SAF file descriptor");
            freeRuntime();
            return 4;
        }

        llama_context_params contextParams = llama_context_default_params();
        contextParams.n_ctx = kContextSize;
        contextParams.n_batch = kBatchSize;
        contextParams.n_ubatch = kMicroBatchSize;
        // A single ChatBuddy conversation uses one sequence. Let sliding-window
        // attention keep its bounded window instead of reserving a full 4096-token
        // cache; this is the mobile-safe mode for Gemma 4's SWA layers.
        contextParams.swa_full = false;
        const long cores = sysconf(_SC_NPROCESSORS_ONLN);
        const int32_t threads = static_cast<int32_t>(std::clamp<long>(
            cores > 0 ? cores - 1 : 1, 1, 7));
        contextParams.n_threads = threads;
        contextParams.n_threads_batch = threads;
        g_context = llama_init_from_model(g_model, contextParams);
        if (g_context == nullptr) {
            logError("Unable to create llama context");
            freeRuntime();
            return 5;
        }
        g_vocab = llama_model_get_vocab(g_model);
        g_batch = llama_batch_init(1, 0, 1);
        g_batchCapacity = 1;
        if (!hasUsableTokenBatch(g_batch, g_batchCapacity)) {
            logError("Unable to allocate llama batch buffers");
            freeRuntime();
            return 6;
        }
        g_cancelRequested.store(false, std::memory_order_release);
        return 0;
    } catch (const std::exception &error) {
        logError(error.what());
        freeRuntime();
        return 7;
    } catch (...) {
        logError("Native model loading threw an unknown exception");
        freeRuntime();
        return 7;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_chatbuddy_ai_llm_LlamaNative_nativeStart(
    JNIEnv *env,
    jclass,
    jstring systemPromptString,
    jstring userPromptString,
    jstring cacheKeyString,
    jint maxTokens,
    jfloat temperature,
    jfloat topP,
    jboolean useGemma4Template) {
    std::lock_guard<std::mutex> lock(g_mutex);
    try {
        if (g_model == nullptr || g_context == nullptr || g_vocab == nullptr) {
            return kStartRuntimeNotReady;
        }
        g_cancelRequested.store(false, std::memory_order_release);
        ScopedUtfChars systemPrompt(env, systemPromptString);
        ScopedUtfChars userPrompt(env, userPromptString);
        ScopedUtfChars cacheKeyChars(env, cacheKeyString);
        if (!systemPrompt.valid() || !userPrompt.valid() || !cacheKeyChars.valid()) {
            return kStartJniStringError;
        }

        std::string formatted;
        const std::string cacheKey(cacheKeyChars.get());
        const bool formattedOk = applyChatTemplate(
            systemPrompt.get(),
            userPrompt.get(),
            formatted,
            useGemma4Template == JNI_TRUE);
        if (!formattedOk) {
            return kStartPromptError;
        }

        std::vector<llama_token> tokens;
        if (!tokenize(formatted, tokens) || tokens.empty() || tokens.size() >= kContextSize) {
            __android_log_print(
                ANDROID_LOG_ERROR,
                kLogTag,
                "Prompt tokenization failed or exceeded context tokens=%zu context=%d",
                tokens.size(),
                kContextSize);
            return kStartTokenizeError;
        }
        __android_log_print(
            ANDROID_LOG_DEBUG,
            kLogTag,
            "Prepared prompt tokens=%zu context=%d",
            tokens.size(),
            kContextSize);
        g_generated = 0;
        g_maxGenerated = 0;
        g_lastNextStatus = 0;
        freeSampler();
        const auto chainParams = llama_sampler_chain_default_params();
        g_sampler = llama_sampler_chain_init(chainParams);
        if (g_sampler == nullptr) {
            return kStartSamplerError;
        }
        const auto addSampler = [](llama_sampler *sampler) {
            if (sampler == nullptr) {
                return false;
            }
            llama_sampler_chain_add(g_sampler, sampler);
            return true;
        };
        if (!addSampler(llama_sampler_init_top_k(40)) ||
            !addSampler(llama_sampler_init_top_p(
                std::clamp(topP, 0.05f, 1.0f), 1)) ||
            !addSampler(llama_sampler_init_temp(std::max(temperature, 0.01f))) ||
            !addSampler(llama_sampler_init_dist(0xC0FFEEu))) {
            freeSampler();
            return kStartSamplerError;
        }
        if (!preparePrompt(tokens, cacheKey)) {
            freeSampler();
            return g_cancelRequested.load(std::memory_order_acquire)
                ? kStartCancelled
                : kStartDecodePromptError;
        }
        g_maxGenerated = std::clamp<int32_t>(
            maxTokens, 1, std::max<int32_t>(1, kContextSize - g_position - 1));
        return 0;
    } catch (const std::exception &error) {
        freeSampler();
        __android_log_print(
            ANDROID_LOG_ERROR,
            kLogTag,
            "Native prompt preparation threw: %s",
            error.what());
        return kStartNativeException;
    } catch (...) {
        freeSampler();
        logError("Native prompt preparation threw an unknown exception");
        return kStartNativeException;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_chatbuddy_ai_llm_LlamaNative_nativeNext(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    try {
        if (g_sampler == nullptr || g_context == nullptr || g_vocab == nullptr ||
            g_generated >= g_maxGenerated) {
            g_lastNextStatus = kNextEndOfSequence;
            return nullptr;
        }
        if (!hasUsableTokenBatch(g_batch, g_batchCapacity)) {
            g_lastNextStatus = kNextDecodeError;
            freeSampler();
            return nullptr;
        }
        if (g_cancelRequested.load(std::memory_order_acquire)) {
            g_lastNextStatus = kNextCancelled;
            freeSampler();
            return nullptr;
        }
        const llama_token token = llama_sampler_sample(g_sampler, g_context, -1);
        llama_sampler_accept(g_sampler, token);
        if (g_cancelRequested.load(std::memory_order_acquire)) {
            g_lastNextStatus = kNextCancelled;
            freeSampler();
            return nullptr;
        }
        if (llama_vocab_is_eog(g_vocab, token)) {
            g_lastNextStatus = kNextEndOfSequence;
            freeSampler();
            return nullptr;
        }
        g_batch.n_tokens = 1;
        g_batch.token[0] = token;
        g_batch.pos[0] = g_position;
        g_batch.n_seq_id[0] = 1;
        g_batch.seq_id[0][0] = 0;
        g_batch.logits[0] = 1;
        if (llama_decode(g_context, g_batch) != 0) {
            g_lastNextStatus = kNextDecodeError;
            freeSampler();
            return nullptr;
        }
        if (g_cancelRequested.load(std::memory_order_acquire)) {
            g_lastNextStatus = kNextCancelled;
            freeSampler();
            return nullptr;
        }
        ++g_position;
        ++g_generated;
        g_lastNextStatus = 0;
        const std::string piece = tokenToString(token);
        return env->NewStringUTF(piece.c_str());
    } catch (const std::exception &error) {
        g_lastNextStatus = kNextDecodeError;
        freeSampler();
        __android_log_print(
            ANDROID_LOG_ERROR,
            kLogTag,
            "Native generation threw: %s",
            error.what());
        return nullptr;
    } catch (...) {
        g_lastNextStatus = kNextDecodeError;
        freeSampler();
        logError("Native generation threw an unknown exception");
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_chatbuddy_ai_llm_LlamaNative_nativeLastStatus(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_lastNextStatus;
}

extern "C" JNIEXPORT void JNICALL
Java_com_chatbuddy_ai_llm_LlamaNative_nativeCancel(JNIEnv *, jclass) {
    g_cancelRequested.store(true, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_com_chatbuddy_ai_llm_LlamaNative_nativeClose(JNIEnv *, jclass) {
    g_cancelRequested.store(true, std::memory_order_release);
    std::lock_guard<std::mutex> lock(g_mutex);
    freeRuntime();
}
