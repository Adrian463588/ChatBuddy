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
        openLimited(uri).bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                TOKEN_PATTERN.findAll(line).forEach { yield(it.value) }
            }
        }
    }

    private fun pdfTokens(uri: Uri): Sequence<String> = sequence {
        openLimited(uri).use { input ->
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                val stripper = PDFTextStripper()
                for (page in 1..document.numberOfPages) {
                    stripper.startPage = page
                    stripper.endPage = page
                    yieldWords(stripper.getText(document))
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
                            yieldWords(parser.text.orEmpty())
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
        TOKEN_PATTERN.findAll(text).forEach { yield(it.value) }
    }

    private fun openLimited(uri: Uri): InputStream {
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open document from SAF")
        return SizeLimitedInputStream(input, MAX_DOCUMENT_BYTES)
    }

    private class SizeLimitedInputStream(
        input: InputStream,
        private val limit: Long
    ) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) record(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val allowed = (limit - count + 1).coerceAtMost(length.toLong()).toInt()
            if (allowed <= 0) throw IOException("Document exceeds the 200 MB limit")
            val read = super.read(buffer, offset, allowed)
            if (read > 0) record(read.toLong())
            return read
        }

        private fun record(bytes: Long) {
            count += bytes
            if (count > limit) throw IOException("Document exceeds the 200 MB limit")
        }
    }

    companion object {
        private const val TEXT_MIME = "text/plain"
        private const val PDF_MIME = "application/pdf"
        private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private const val DOCUMENT_XML = "word/document.xml"
        private const val MAX_DOCUMENT_BYTES = 200L * 1024L * 1024L
        private val TOKEN_PATTERN = Regex("\\S+")
    }
}
