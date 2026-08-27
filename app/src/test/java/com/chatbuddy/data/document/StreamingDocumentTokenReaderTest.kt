package com.chatbuddy.data.document

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StreamingDocumentTokenReaderTest {
    @Test
    fun tokenStreamBoundsLongNonWhitespaceRuns() {
        val tokens = streamDocumentTokens(
            StringReader("abcdefghij value"),
            maxTokenLength = 4
        ).toList()

        assertEquals(listOf("abcd", "efgh", "ij", "valu", "e"), tokens)
    }

    @Test
    fun sizeLimitCoversBufferedReadAndSkip() {
        val buffered = SizeLimitedInputStream(
            ByteArrayInputStream("12345".toByteArray()),
            limit = 4
        )
        assertEquals(4, buffered.read(ByteArray(4)))
        assertThrows(IOException::class.java) { buffered.read() }

        val skipped = SizeLimitedInputStream(
            ByteArrayInputStream("12345".toByteArray()),
            limit = 4
        )
        assertEquals(4L, skipped.skip(10))
        assertThrows(IOException::class.java) { skipped.read() }
    }

    @Test
    fun exactSizeInputEndsNormally() {
        val input = SizeLimitedInputStream(
            ByteArrayInputStream("1234".toByteArray()),
            limit = 4
        )
        assertEquals(4, input.read(ByteArray(8)))
        assertEquals(-1, input.read())
    }
}
