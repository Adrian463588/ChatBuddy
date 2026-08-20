package com.chatbuddy.utils

import java.util.Locale

fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    return when {
        value < 1_000.0 -> "${value.toLong()} B"
        value < 1_000_000.0 -> compact(value / 1_000.0, "KB")
        value < 1_000_000_000.0 -> compact(value / 1_000_000.0, "MB")
        else -> compact(value / 1_000_000_000.0, "GB")
    }
}

private fun compact(value: Double, unit: String): String {
    val rounded = String.format(Locale.US, "%.1f", value).trimEnd('0').trimEnd('.')
    return "$rounded $unit"
}
