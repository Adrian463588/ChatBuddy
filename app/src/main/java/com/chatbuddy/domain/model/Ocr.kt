package com.chatbuddy.domain.model

data class OcrTextBlock(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class OcrResult(
    val text: String,
    val blocks: List<OcrTextBlock>,
    val languageTag: String,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0
)
