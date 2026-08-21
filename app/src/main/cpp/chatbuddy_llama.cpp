#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <unistd.h>
#include <vector>

#include "llama.h"

namespace {

constexpr char kLogTag[] = "ChatBuddyLlama";
constexpr int32_t kContextSize = 4096;
constexpr int32_t kBatchSize = 512;

llama_model *g_model = nullptr;
llama_context *g_context = nullptr;
llama_sampler *g_sampler = nullptr;
llama_batch g_batch{};
const llama_vocab *g_vocab = nullptr;
int32_t g_position = 0;
int32_t g_generated = 0;
int32_t g_maxGenerated = 0;
std::vector<llama_token> g_cachedPromptTokens;
int32_t g_lastCacheHitTokens = 0;
bool g_backendInitialized = false;
std::mutex g_mutex;

void logError(const char *message) {
    __android_log_write(ANDROID_LOG_ERROR, kLogTag, message);
}

void freeSampler() {
    if (g_sampler != nullptr) {
        llama_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
}

void freeRuntime() {
    freeSampler();
    if (g_batch.token != nullptr) {
        llama_batch_free(g_batch);
        g_batch = {};
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
    g_cachedPromptTokens.clear();
    g_lastCacheHitTokens = 0;
}

bool decodeTokens(const std::vector<llama_token> &tokens) {
    for (size_t offset = 0; offset < tokens.size(); offset += kBatchSize) {
        const auto count = static_cast<int32_t>(std::min<size_t>(
            kBatchSize, tokens.size() - offset));
        llama_batch batch = llama_batch_init(count, 0, 1);
        if (batch.token == nullptr || batch.pos == nullptr || batch.n_seq_id == nullptr ||
            batch.seq_id == nullptr || batch.logits == nullptr) {
            llama_batch_free(batch);
            return false;
        }

        for (int32_t index = 0; index < count; ++index) {
            batch.token[index] = tokens[offset + index];
            batch.pos[index] = g_position + index;
            batch.n_seq_id[index] = 1;
            batch.seq_id[index][0] = 0;
            batch.logits[index] = (offset + index + 1 == tokens.size()) ? 1 : 0;
        }

        const int32_t result = llama_decode(g_context, batch);
        llama_batch_free(batch);
        if (result != 0) {
            return false;
        }
        g_position += count;
    }
    return true;
}

void clearMemoryAndPromptCache() {
    llama_memory_clear(llama_get_memory(g_context), true);
    g_position = 0;
    g_cachedPromptTokens.clear();
    g_lastCacheHitTokens = 0;
}

bool preparePrompt(const std::vector<llama_token> &tokens) {
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
    g_lastCacheHitTokens = static_cast<int32_t>(cachedSize == 0 ? 0 : commonSize);
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

bool applyChatTemplate(const char *systemPrompt, const char *userPrompt, std::string &result) {
    const char *templateName = llama_model_chat_template(g_model, nullptr);
    if (templateName == nullptr || std::strlen(templateName) == 0) {
        return false;
    }
    llama_chat_message messages[2] = {
        {"system", systemPrompt},
        {"user", userPrompt},
    };
    const int32_t required = llama_chat_apply_template(
        templateName, messages, 2, true, nullptr, 0);
    if (required <= 0) {
        return false;
    }
    result.resize(static_cast<size_t>(required));
    const int32_t written = llama_chat_apply_template(
        templateName, messages, 2, true, result.data(), required);
    if (written < 0) {
        return false;
    }
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
        llama_backend_init();
        g_backendInitialized = true;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_chatbuddy_ai_llm_LlamaNative_nativeLoad(JNIEnv *, jclass, jint fileDescriptor) {
    std::lock_guard<std::mutex> lock(g_mutex);
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
    contextParams.n_ubatch = kBatchSize;
    const long cores = sysconf(_SC_NPROCESSORS_ONLN);
    const int32_t threads = static_cast<int32_t>(std::clamp<long>(
        cores > 0 ? cores - 1 : 1, 1, 4));
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
    return g_batch.token == nullptr ? 6 : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_chatbuddy_ai_llm_LlamaNative_nativeStart(
    JNIEnv *env,
    jclass,
    jstring systemPromptString,
    jstring userPromptString,
    jint maxTokens,
    jfloat temperature,
    jfloat topP) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model == nullptr || g_context == nullptr || g_vocab == nullptr) {
        return 1;
    }
    const char *systemPrompt = env->GetStringUTFChars(systemPromptString, nullptr);
    const char *userPrompt = env->GetStringUTFChars(userPromptString, nullptr);
    std::string formatted;
    const bool formattedOk = applyChatTemplate(systemPrompt, userPrompt, formatted);
    env->ReleaseStringUTFChars(systemPromptString, systemPrompt);
    env->ReleaseStringUTFChars(userPromptString, userPrompt);
    if (!formattedOk) {
        return 2;
    }

    std::vector<llama_token> tokens;
    if (!tokenize(formatted, tokens) || tokens.empty() || tokens.size() >= kContextSize) {
        return 3;
    }
    g_generated = 0;
    g_maxGenerated = 0;
    freeSampler();
    const auto chainParams = llama_sampler_chain_default_params();
    g_sampler = llama_sampler_chain_init(chainParams);
    if (g_sampler == nullptr) {
        return 4;
    }
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(
        std::clamp(topP, 0.05f, 1.0f), 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(std::max(temperature, 0.01f)));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(0xC0FFEEu));
    if (!preparePrompt(tokens)) {
        freeSampler();
        return 5;
    }
    g_maxGenerated = std::clamp<int32_t>(
        maxTokens, 1, std::max<int32_t>(1, kContextSize - g_position - 1));
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_chatbuddy_ai_llm_LlamaNative_nativeNext(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_sampler == nullptr || g_context == nullptr || g_generated >= g_maxGenerated) {
        return nullptr;
    }
    const llama_token token = llama_sampler_sample(g_sampler, g_context, -1);
    llama_sampler_accept(g_sampler, token);
    if (llama_vocab_is_eog(g_vocab, token)) {
        return nullptr;
    }
    g_batch.n_tokens = 1;
    g_batch.token[0] = token;
    g_batch.pos[0] = g_position;
    g_batch.n_seq_id[0] = 1;
    g_batch.seq_id[0][0] = 0;
    g_batch.logits[0] = 1;
    if (llama_decode(g_context, g_batch) != 0) {
        return nullptr;
    }
    ++g_position;
    ++g_generated;
    const std::string piece = tokenToString(token);
    return env->NewStringUTF(piece.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_chatbuddy_ai_llm_LlamaNative_nativeClose(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    freeRuntime();
}
