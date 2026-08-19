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
        maxTokens: Int,
        temperature: Float,
        topP: Float
    ): Int
    external fun nativeNext(): String?
    external fun nativeClose()
}
