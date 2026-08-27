package com.chatbuddy.data.document

import android.content.ContentResolver
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.io.StringReader
import java.io.Writer
import java.util.ArrayDeque
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamingDocumentTokenReader @Inject constructor(
    private val contentResolver: ContentResolver
) {
    fun tokens(uri: Uri, displayName: String, mimeType: String): Sequence<String> = when {
        mimeType == TEXT_MIME || displayName.endsWith(".txt", ignoreCase = true) ->
            textTokens(uri)
        mimeType == PDF_MIME || displayName.endsWith(".pdf", ignoreCase = true) ->
            pdfTokens(uri)
        mimeType == DOCX_MIME || displayName.endsWith(".docx", ignoreCase = true) ->
            docxTokens(uri)
        else -> throw IOException("Unsupported document type")
    }

    private fun textTokens(uri: Uri): Sequence<String> = sequence {
        openLimited(uri).bufferedReader(Charsets.UTF_8).use { reader ->
            yieldAll(streamDocumentTokens(reader))
        }
    }

    private fun pdfTokens(uri: Uri): Sequence<String> = sequence {
        openLimited(uri).use { input ->
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                val stripper = PDFTextStripper()
                for (page in 1..document.numberOfPages) {
                    stripper.startPage = page
                    stripper.endPage = page
                    val writer = BoundedTokenWriter(MAX_PDF_PAGE_BUFFER_CHARS)
                    stripper.writeText(document, writer)
                    writer.finish()
                    yieldAll(writer.drain())
                }
            }
        }
    }

    private fun docxTokens(uri: Uri): Sequence<String> = sequence {
        ZipInputStream(BufferedInputStream(openLimited(uri))).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                if (entry.name == DOCUMENT_XML) {
                    val parser = XmlPullParserFactory.newInstance().newPullParser()
                    parser.setInput(archive, Charsets.UTF_8.name())
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        if (event == XmlPullParser.TEXT) {
                            val text = parser.text.orEmpty()
                            if (text.length > MAX_XML_TEXT_EVENT_CHARS) {
                                throw IOException("DOCX text event exceeds bounded memory limit")
                            }
                            yieldWords(text)
                        }
                        event = parser.next()
                    }
                    return@use
                }
                archive.closeEntry()
            }
        }
    }

    private suspend fun SequenceScope<String>.yieldWords(text: String) {
        yieldAll(streamDocumentTokens(StringReader(text)))
    }

    private fun openLimited(uri: Uri): InputStream {
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open document from SAF")
        return SizeLimitedInputStream(input, MAX_DOCUMENT_BYTES)
    }

    companion object {
        private const val TEXT_MIME = "text/plain"
        private const val PDF_MIME = "application/pdf"
        private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private const val DOCUMENT_XML = "word/document.xml"
        private const val MAX_DOCUMENT_BYTES = 200L * 1024L * 1024L
        private const val MAX_PDF_PAGE_BUFFER_CHARS = 4 * 1024 * 1024
        private const val MAX_XML_TEXT_EVENT_CHARS = 1 * 1024 * 1024
    }
}

/**
 * Tokenizes reader content with fixed-size character and token buffers. Very
 * long non-whitespace runs are split so one malformed line cannot consume the
 * document-size budget as a single String.
 */
internal fun streamDocumentTokens(
    reader: Reader,
    maxTokenLength: Int = MAX_STREAM_TOKEN_LENGTH
): Sequence<String> {
    require(maxTokenLength > 0) { "maxTokenLength must be positive" }
    return sequence {
        val chars = CharArray(STREAM_READ_BUFFER_SIZE)
        val token = StringBuilder(maxTokenLength.coerceAtMost(STREAM_READ_BUFFER_SIZE))
        while (true) {
            val read = reader.read(chars)
            if (read < 0) break
            for (index in 0 until read) {
                val character = chars[index]
                if (character.isWhitespace()) {
                    if (token.isNotEmpty()) {
                        yield(token.toString())
                        token.setLength(0)
                    }
                } else {
                    token.append(character)
                    if (token.length == maxTokenLength) {
                        yield(token.toString())
                        token.setLength(0)
                    }
                }
            }
        }
        if (token.isNotEmpty()) yield(token.toString())
    }
}

/**
 * Collects one PDF page only, with a hard character budget. PDFBox writes
 * incrementally into this writer instead of creating one String for a page.
 */
internal class BoundedTokenWriter(
    private val maxQueuedChars: Int,
    private val maxTokenLength: Int = MAX_STREAM_TOKEN_LENGTH
) : Writer() {
    private val tokens = ArrayDeque<String>()
    private val token = StringBuilder(maxTokenLength.coerceAtMost(STREAM_READ_BUFFER_SIZE))
    private var queuedChars = 0
    private var finished = false

    override fun write(cbuf: CharArray, off: Int, len: Int) {
        check(!finished) { "Writer is already finished" }
        require(off >= 0 && len >= 0 && off <= cbuf.size - len) { "Invalid writer range" }
        for (index in off until off + len) {
            val character = cbuf[index]
            if (character.isWhitespace()) {
                emitToken()
            } else {
                token.append(character)
                if (token.length == maxTokenLength) emitToken()
            }
        }
    }

    override fun flush() = Unit

    override fun close() {
        finish()
    }

    fun finish() {
        if (!finished) {
            emitToken()
            finished = true
        }
    }

    fun drain(): Sequence<String> = sequence {
        check(finished) { "Writer must be finished before draining" }
        while (tokens.isNotEmpty()) {
            val next = tokens.removeFirst()
            queuedChars -= next.length
            yield(next)
        }
    }

    private fun emitToken() {
        if (token.isEmpty()) return
        val nextLength = queuedChars + token.length
        if (nextLength > maxQueuedChars) {
            throw IOException("PDF page text exceeds bounded memory limit")
        }
        tokens.addLast(token.toString())
        queuedChars = nextLength
        token.setLength(0)
    }
}

/**
 * Bounds read(), read(buffer), and skip(). At the limit, one extra byte is
 * probed to distinguish an exactly-sized document from an oversized one.
 */
internal class SizeLimitedInputStream(
    input: InputStream,
    private val limit: Long
) : FilterInputStream(input) {
    init {
        require(limit >= 0) { "limit must not be negative" }
    }

    private var count = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) record(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val remaining = limit - count
        if (remaining <= 0) {
            val extra = super.read()
            if (extra >= 0) throw IOException("Document exceeds the 200 MB limit")
            return -1
        }
        val allowed = minOf(remaining, length.toLong()).toInt()
        val read = super.read(buffer, offset, allowed)
        if (read > 0) record(read.toLong())
        return read
    }

    override fun skip(byteCount: Long): Long {
        if (byteCount <= 0) return 0
        val remaining = limit - count
        if (remaining <= 0) {
            val extra = super.read()
            if (extra >= 0) throw IOException("Document exceeds the 200 MB limit")
            return 0
        }
        val skipped = super.skip(minOf(remaining, byteCount))
        if (skipped > 0) record(skipped)
        return skipped
    }

    private fun record(bytes: Long) {
        count += bytes
        if (count > limit) throw IOException("Document exceeds the 200 MB limit")
    }
}

private const val STREAM_READ_BUFFER_SIZE = 8 * 1024
private const val MAX_STREAM_TOKEN_LENGTH = 16 * 1024
