package com.chatbuddy.data.repository

import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException

internal class WebResponseTooLargeException : IOException("Web response exceeded the safety limit")

internal fun Response.readBoundedBody(maxBytes: Long): String {
    val responseBody = body ?: throw IOException("Web response returned no data")
    if (responseBody.contentLength() > maxBytes) {
        throw WebResponseTooLargeException()
    }
    val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024L).toInt())
    responseBody.byteStream().use { input ->
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) throw WebResponseTooLargeException()
            output.write(buffer, 0, read)
        }
    }
    return output.toString(Charsets.UTF_8.name())
}
