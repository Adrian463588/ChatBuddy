package com.chatbuddy.ai.llm

internal object LlamaNative {
    init {
        System.loadLibrary("chatbuddy_llama")
    }

    external fun nativeInit(): Boolean
    external fun nativeLoad(fileDescriptor: Int): Int
    external fun nativeStart(
        systemPrompt: String,
        userPrompt: String,
        cacheKey: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        useGemma4Template: Boolean
    ): Int
    external fun nativeNext(): String?
    external fun nativeLastStatus(): Int
    external fun nativeCancel()
    external fun nativeClose()
}
