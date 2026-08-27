package com.chatbuddy.data.repository

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.WebEvidence
import com.chatbuddy.domain.repository.WebSearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaWikiWebSearchRepository @Inject constructor(
    private val client: OkHttpClient
) : WebSearchRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val webClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun search(query: String, limit: Int): AppResult<List<WebEvidence>> =
        withContext(Dispatchers.IO) {
            val normalizedQuery = query.trim()
            if (normalizedQuery.length < MIN_QUERY_LENGTH) {
                return@withContext AppResult.Error("Web search needs at least two characters")
            }
            if (normalizedQuery.length > MAX_QUERY_LENGTH) {
                return@withContext AppResult.Error("Web search query is too long")
            }

            val request = Request.Builder()
                .url(buildUrl(normalizedQuery, limit.coerceIn(1, MAX_RESULTS)))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            try {
                webClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext AppResult.Error(
                            "Web search failed with HTTP ${response.code}"
                        )
                    }
                    val contentType = response.header("Content-Type").orEmpty()
                    if (!contentType.lowercase().startsWith("application/json")) {
                        return@withContext AppResult.Error("Web search returned an unexpected content type")
                    }
                    val payload = json.decodeFromString<MediaWikiResponse>(
                        response.readBoundedBody(MAX_RESPONSE_BYTES)
                    )
                    val retrievedAt = System.currentTimeMillis()
                    val evidence = payload.query?.pages.orEmpty().mapNotNull { page ->
                        val content = page.extract?.trim().orEmpty()
                        val url = page.fullUrl?.trim().orEmpty()
                        if (page.pageId == null || page.title.isBlank() || content.isBlank() ||
                            !url.isTrustedWikipediaUrl()
                        ) {
                            null
                        } else {
                            WebEvidence(
                                title = page.title.trim(),
                                url = url,
                                excerpt = content.replace(WHITESPACE, " ").take(EXCERPT_LENGTH),
                                content = content.take(CONTENT_LENGTH),
                                provider = PROVIDER,
                                sourceId = "$SOURCE_PREFIX${page.pageId}",
                                retrievedAtEpochMs = retrievedAt
                            )
                        }
                    }
                    AppResult.Success(evidence)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppResult.Error("Web search could not be completed", error)
            }
        }

    private fun buildUrl(query: String, limit: Int) = BASE_URL.toHttpUrl().newBuilder()
        .addQueryParameter("action", "query")
        .addQueryParameter("generator", "search")
        .addQueryParameter("gsrsearch", query)
        .addQueryParameter("gsrnamespace", "0")
        .addQueryParameter("gsrlimit", limit.toString())
        .addQueryParameter("prop", "extracts|info")
        .addQueryParameter("explaintext", "1")
        .addQueryParameter("exchars", CONTENT_LENGTH.toString())
        .addQueryParameter("inprop", "url")
        .addQueryParameter("format", "json")
        .addQueryParameter("formatversion", "2")
        .build()

    private fun String.isTrustedWikipediaUrl(): Boolean {
        val url = toHttpUrlOrNull() ?: return false
        return url.scheme == "https" &&
            url.host == "en.wikipedia.org" &&
            url.username.isEmpty() &&
            url.password.isEmpty()
    }

    @Serializable
    private data class MediaWikiResponse(
        val query: SearchQuery? = null
    )

    @Serializable
    private data class SearchQuery(
        val pages: List<SearchPage> = emptyList()
    )

    @Serializable
    private data class SearchPage(
        val title: String = "",
        val extract: String? = null,
        val fullurl: String? = null,
        val pageid: Long? = null
    ) {
        val fullUrl: String?
            get() = fullurl
        val pageId: Long?
            get() = pageid
    }

    companion object {
        private const val BASE_URL = "https://en.wikipedia.org/w/api.php"
        private const val PROVIDER = "Wikipedia"
        private const val SOURCE_PREFIX = "wikipedia:"
        private const val USER_AGENT =
            "ChatBuddy/0.1 (https://github.com/Adrian463588/ChatBuddy)"
        private const val MIN_QUERY_LENGTH = 2
        private const val MAX_QUERY_LENGTH = 400
        private const val MAX_RESULTS = 5
        private const val CONTENT_LENGTH = 1200
        private const val EXCERPT_LENGTH = 320
        private const val MAX_RESPONSE_BYTES = 512L * 1024L
        private val WHITESPACE = Regex("\\s+")
    }
}
