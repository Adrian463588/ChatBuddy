package com.chatbuddy.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProtocolTest {
    @Test
    fun `full response at zero offset is accepted`() {
        val plan = DownloadProtocol.plan(
            offset = 0L,
            totalBytes = 100L,
            responseCode = 200,
            contentRange = null,
            contentLength = 100L
        )

        assertEquals(DownloadResponsePlan.Append(100L, 100L), plan)
    }

    @Test
    fun `server ignoring range requests a safe restart`() {
        val plan = DownloadProtocol.plan(
            offset = 40L,
            totalBytes = 100L,
            responseCode = 200,
            contentRange = null,
            contentLength = 100L
        )

        assertEquals(DownloadResponsePlan.RestartFromZero, plan)
    }

    @Test
    fun `partial response requires matching start and total`() {
        val plan = DownloadProtocol.plan(
            offset = 40L,
            totalBytes = 100L,
            responseCode = 206,
            contentRange = "bytes 40-79/100",
            contentLength = 40L
        )

        assertEquals(DownloadResponsePlan.Append(40L, 100L), plan)
    }

    @Test
    fun `partial response at zero offset is accepted`() {
        val plan = DownloadProtocol.plan(
            offset = 0L,
            totalBytes = 100L,
            responseCode = 206,
            contentRange = "bytes 0-39/100",
            contentLength = 40L
        )

        assertEquals(DownloadResponsePlan.Append(40L, 100L), plan)
    }

    @Test
    fun `content range parser rejects wildcard total`() {
        assertEquals(null, DownloadProtocol.parseContentRange("bytes 40-79/*"))
    }

    @Test
    fun `early EOF is retryable IOException`() {
        val error = runCatching {
            DownloadProtocol.validateBodyBytes(expectedBodyBytes = 60L, actualBodyBytes = 59L)
        }.exceptionOrNull()

        assertTrue(error is RetryableDownloadException)
        assertTrue(error is java.io.IOException)
    }

    @Test
    fun `transient HTTP response is retryable`() {
        val error = runCatching {
            DownloadProtocol.plan(
                offset = 20L,
                totalBytes = 100L,
                responseCode = 503,
                contentRange = null,
                contentLength = -1L
            )
        }.exceptionOrNull()

        assertTrue(error is RetryableDownloadException)
    }

    @Test
    fun `wrong range start is non retryable`() {
        val error = runCatching {
            DownloadProtocol.plan(
                offset = 40L,
                totalBytes = 100L,
                responseCode = 206,
                contentRange = "bytes 41-99/100",
                contentLength = 59L
            )
        }.exceptionOrNull()

        assertTrue(error is NonRetryableDownloadException)
    }
}
