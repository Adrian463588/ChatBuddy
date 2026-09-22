package com.chatbuddy.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeepWebScraper @Inject constructor(
    client: OkHttpClient
) {
    private val scraperClient = client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun scrape(url: String): ScrapedPage? = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext null
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "id,en-US;q=0.9,en;q=0.8")
                .get()
                .build()

            scraperClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (!contentType.contains("text/html") &&
                    !contentType.contains("application/xhtml") &&
                    !contentType.contains("text/plain")
                ) {
                    return@withContext null
                }
                val body = response.body?.let { rb ->
                    val bytes = rb.byteStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        var totalRead = 0
                        while (totalRead < buffer.size) {
                            val read = input.read(buffer, totalRead, buffer.size - totalRead)
                            if (read == -1) break
                            totalRead += read
                        }
                        buffer.copyOf(totalRead)
                    }
                    String(bytes, Charsets.UTF_8)
                } ?: return@withContext null

                extractContent(body, response.request.url.toString())
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractContent(html: String, finalUrl: String): ScrapedPage {
        val titleMatcher = TITLE_PATTERN.matcher(html)
        val title = if (titleMatcher.find()) cleanText(titleMatcher.group(1).orEmpty()) else ""

        var cleanHtml = html
        cleanHtml = SCRIPT_STYLE_PATTERN.matcher(cleanHtml).replaceAll(" ")
        cleanHtml = NAV_FOOTER_PATTERN.matcher(cleanHtml).replaceAll(" ")

        val pMatcher = P_TAG_PATTERN.matcher(cleanHtml)
        val paragraphs = mutableListOf<String>()
        var totalChars = 0

        while (pMatcher.find() && totalChars < MAX_SCRAPED_CHARS) {
            val text = cleanText(pMatcher.group(1).orEmpty())
            if (text.length >= 35 &&
                !text.contains("cookie", ignoreCase = true) &&
                !text.contains("javascript", ignoreCase = true) &&
                !text.contains("browser", ignoreCase = true)
            ) {
                paragraphs.add(text)
                totalChars += text.length
            }
        }

        val content = if (paragraphs.isNotEmpty()) {
            paragraphs.joinToString("\n\n").take(MAX_SCRAPED_CHARS)
        } else {
            val stripped = TAG_PATTERN.matcher(cleanHtml).replaceAll(" ")
            cleanText(stripped).take(MAX_SCRAPED_CHARS)
        }

        val excerpt = content.take(300).trim()
        return ScrapedPage(
            finalUrl = finalUrl,
            title = title,
            excerpt = excerpt,
            content = content
        )
    }

    private fun cleanText(text: String): String {
        return TAG_PATTERN.matcher(text).replaceAll(" ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    data class ScrapedPage(
        val finalUrl: String,
        val title: String,
        val excerpt: String,
        val content: String
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
        private const val MAX_SCRAPED_CHARS = 2400
        private val SCRIPT_STYLE_PATTERN = Pattern.compile(
            "<(script|style|noscript|svg|iframe)[^>]*>.*?</\\1>",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        private val NAV_FOOTER_PATTERN = Pattern.compile(
            "<(nav|header|footer|aside|form)[^>]*>.*?</\\1>",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        private val P_TAG_PATTERN = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        private val TITLE_PATTERN = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        private val TAG_PATTERN = Pattern.compile("<[^>]+>")
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
