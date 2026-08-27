package com.chatbuddy.domain.model

/** Terminally distinguishable result from a local native generation step. */
sealed interface NativeGenerationResult {
    data class Token(val value: String) : NativeGenerationResult
    data object EOS : NativeGenerationResult
    data class DecodeError(val message: String) : NativeGenerationResult
    data object CANCELLED : NativeGenerationResult
}
