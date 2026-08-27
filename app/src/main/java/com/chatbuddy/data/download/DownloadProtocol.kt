package com.chatbuddy.data.download

import java.io.IOException

internal data class ParsedContentRange(
    val start: Long,
    val endInclusive: Long,
    val total: Long
)

internal sealed interface DownloadResponsePlan {
    data object RestartFromZero : DownloadResponsePlan

    data class Append(
        val expectedBodyBytes: Long,
        val totalBytes: Long
    ) : DownloadResponsePlan
}

/** A protocol/storage mismatch that should not be retried blindly. */
internal class NonRetryableDownloadException(message: String) : Exception(message)

/** A transient response or truncated stream that WorkManager can retry. */
internal class RetryableDownloadException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

internal object DownloadProtocol {
    private val contentRangePattern = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)")

    fun plan(
        offset: Long,
        totalBytes: Long,
        responseCode: Int,
        contentRange: String?,
        contentLength: Long
    ): DownloadResponsePlan {
        if (totalBytes <= 0L) {
            throw NonRetryableDownloadException("Manifest size must be greater than zero")
        }
        if (offset !in 0 until totalBytes) {
            throw NonRetryableDownloadException("Download offset is outside the manifest size")
        }

        if (offset == 0L) {
            return when {
                responseCode == HTTP_OK -> {
                    when {
                        contentLength > totalBytes -> throw NonRetryableDownloadException(
                            "Server returned more bytes than the manifest allows"
                        )

                        contentLength in 0 until totalBytes -> throw RetryableDownloadException(
                            "Server declared a response shorter than the manifest"
                        )
                    }
                    DownloadResponsePlan.Append(totalBytes, totalBytes)
                }

                responseCode == HTTP_PARTIAL_CONTENT -> partialPlan(
                    offset = offset,
                    totalBytes = totalBytes,
                    contentRange = contentRange,
                    contentLength = contentLength
                )

                isRetryableStatus(responseCode) -> throw RetryableDownloadException(
                    "Model server returned HTTP $responseCode"
                )

                else -> throw NonRetryableDownloadException(
                    "Model download failed with HTTP $responseCode"
                )
            }
        }

        return when {
            // A server may ignore Range. Restarting is safe because the .tmp file is
            // discarded before the zero-offset request; appending a 200 body would
            // corrupt the model.
            responseCode == HTTP_OK -> DownloadResponsePlan.RestartFromZero

            responseCode == HTTP_PARTIAL_CONTENT -> partialPlan(
                offset = offset,
                totalBytes = totalBytes,
                contentRange = contentRange,
                contentLength = contentLength
            )

            isRetryableStatus(responseCode) -> throw RetryableDownloadException(
                "Model server returned HTTP $responseCode"
            )

            else -> throw NonRetryableDownloadException(
                "Model download failed with HTTP $responseCode"
            )
        }
    }

    private fun partialPlan(
        offset: Long,
        totalBytes: Long,
        contentRange: String?,
        contentLength: Long
    ): DownloadResponsePlan.Append {
        val parsed = parseContentRange(contentRange)
            ?: throw NonRetryableDownloadException(
                "206 response did not include a valid Content-Range"
            )
        if (parsed.start != offset) {
            throw NonRetryableDownloadException(
                "Content-Range starts at ${parsed.start}, expected $offset"
            )
        }
        if (parsed.total != totalBytes) {
            throw NonRetryableDownloadException(
                "Content-Range total ${parsed.total} differs from manifest $totalBytes"
            )
        }
        if (parsed.endInclusive < parsed.start || parsed.endInclusive >= parsed.total) {
            throw NonRetryableDownloadException("Content-Range end is outside the manifest")
        }
        val expectedBodyBytes = parsed.endInclusive - parsed.start + 1L
        when {
            contentLength > expectedBodyBytes -> throw NonRetryableDownloadException(
                "206 response length exceeds Content-Range"
            )

            contentLength in 0 until expectedBodyBytes -> throw RetryableDownloadException(
                "206 response declared fewer bytes than Content-Range"
            )
        }
        return DownloadResponsePlan.Append(expectedBodyBytes, totalBytes)
    }

    fun parseContentRange(value: String?): ParsedContentRange? {
        val match = contentRangePattern.matchEntire(value?.trim() ?: return null) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        return ParsedContentRange(start = start, endInclusive = end, total = total)
    }

    fun validateBodyBytes(expectedBodyBytes: Long, actualBodyBytes: Long) {
        when {
            actualBodyBytes < expectedBodyBytes -> throw RetryableDownloadException(
                "Download stream ended early: received $actualBodyBytes of $expectedBodyBytes bytes"
            )

            actualBodyBytes > expectedBodyBytes -> throw NonRetryableDownloadException(
                "Download stream exceeded the declared response range"
            )
        }
    }

    private fun isRetryableStatus(code: Int): Boolean =
        code == 408 || code == 425 || code == 429 || code in 500..599

    private const val HTTP_OK = 200
    private const val HTTP_PARTIAL_CONTENT = 206
}
