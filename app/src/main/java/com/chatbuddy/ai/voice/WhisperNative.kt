package com.chatbuddy.ai.voice

internal object WhisperNative {
    init {
        System.loadLibrary("chatbuddy_whisper")
    }

    external fun nativeInit(): Boolean
    external fun nativeLoad(modelPath: String): Int
    external fun nativeIsLoaded(): Boolean
    external fun nativeTranscribe(samples: ShortArray, languageTag: String): String
    external fun nativeClose()
}
