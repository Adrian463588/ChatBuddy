package com.chatbuddy.data.repository

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.WebEvidence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BingWebSearchRepository @Inject constructor(
    client: OkHttpClient,
    private val deepWebScraper: DeepWebScraper
) {
    private val webClient = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun search(query: String, limit: Int = 3): AppResult<List<WebEvidence>> =
        withContext(Dispatchers.IO) {
            val normalizedQuery = query.trim()
            if (normalizedQuery.length < MIN_QUERY_LENGTH) {
                return@withContext AppResult.Error("Query is too short for web search")
            }

            val url = BASE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("q", normalizedQuery)
                .build()

            val httpRequest = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "id,en-US;q=0.9,en;q=0.8")
                .get()
                .build()

            try {
                webClient.newCall(httpRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext AppResult.Error("Bing search failed with HTTP ${response.code}")
                    }
                    val html = response.body?.string().orEmpty()
                    if (html.isBlank()) {
                        return@withContext AppResult.Success(emptyList())
                    }

                    val rawResults = parseSearchResults(html, limit.coerceIn(1, MAX_RESULTS))
                    val retrievedAt = System.currentTimeMillis()

                    // Deep-scrape top results for rich context
                    val evidenceList = rawResults.mapIndexed { index, raw ->
                        val scraped = deepWebScraper.scrape(raw.url)
                        val finalUrl = scraped?.finalUrl?.takeIf { it.isNotBlank() } ?: raw.url
                        val finalTitle = scraped?.title?.takeIf { it.isNotBlank() } ?: raw.title
                        val finalContent = scraped?.content?.takeIf { it.length > 80 } ?: raw.snippet
                        val finalExcerpt = scraped?.excerpt?.takeIf { it.isNotBlank() } ?: raw.snippet

                        WebEvidence(
                            title = finalTitle.take(TITLE_LENGTH),
                            url = finalUrl,
                            excerpt = finalExcerpt.take(EXCERPT_LENGTH),
                            content = finalContent.take(CONTENT_LENGTH),
                            provider = PROVIDER,
                            sourceId = "$SOURCE_PREFIX${stableId(finalUrl)}",
                            retrievedAtEpochMs = retrievedAt
                        )
                    }

                    AppResult.Success(evidenceList)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppResult.Error("Web search failed", error)
            }
        }

    private fun parseSearchResults(html: String, maxItems: Int): List<RawResult> {
        val results = mutableListOf<RawResult>()
        val blockMatcher = BLOCK_PATTERN.matcher(html)

        while (blockMatcher.find() && results.size < maxItems) {
            val block = blockMatcher.group(1).orEmpty()

            val h2Matcher = H2_LINK_PATTERN.matcher(block)
            if (!h2Matcher.find()) continue

            val rawUrl = h2Matcher.group(1).orEmpty().trim()
            val rawTitle = cleanHtml(h2Matcher.group(2).orEmpty())

            val citeMatcher = CITE_PATTERN.matcher(block)
            val citeUrl = if (citeMatcher.find()) cleanHtml(citeMatcher.group(1).orEmpty()).trim() else ""

            val pMatcher = P_PATTERN.matcher(block)
            val snippet = if (pMatcher.find()) cleanHtml(pMatcher.group(1).orEmpty()) else ""

            val targetUrl = when {
                rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
                citeUrl.startsWith("http://") || citeUrl.startsWith("https://") -> citeUrl
                citeUrl.isNotBlank() -> "https://${citeUrl.substringBefore(' ')}"
                else -> continue
            }

            if (rawTitle.isNotBlank() && targetUrl.isNotBlank()) {
                results.add(RawResult(url = targetUrl, title = rawTitle, snippet = snippet))
            }
        }
        return results
    }

    private fun cleanHtml(text: String): String =
        TAG_PATTERN.matcher(text).replaceAll(" ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#0183;", "·")
            .replace(WHITESPACE, " ")
            .trim()

    private fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(12)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class RawResult(
        val url: String,
        val title: String,
        val snippet: String
    )

    companion object {
        private const val BASE_URL = "https://www.bing.com/search"
        private const val PROVIDER = "Web Search"
        private const val SOURCE_PREFIX = "web:"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val MIN_QUERY_LENGTH = 2
        private const val MAX_RESULTS = 4
        private const val TITLE_LENGTH = 240
        private const val EXCERPT_LENGTH = 320
        private const val CONTENT_LENGTH = 2400
        private val BLOCK_PATTERN = Pattern.compile("<li class=\"b_algo\"[^>]*>(.*?)</li>", Pattern.DOTALL)
        private val H2_LINK_PATTERN = Pattern.compile("<h2[^>]*><a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL)
        private val CITE_PATTERN = Pattern.compile("<cite[^>]*>(.*?)</cite>", Pattern.DOTALL)
        private val P_PATTERN = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL)
        private val TAG_PATTERN = Pattern.compile("<[^>]+>")
        private val WHITESPACE = Regex("\\s+")
    }
}
