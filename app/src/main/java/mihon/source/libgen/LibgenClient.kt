package mihon.source.libgen

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.jvm.Volatile
import kotlin.text.isNotBlank
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Kotlin port of the libgen-api (v1.0.1) package used to query Library Genesis.
 *
 * The original Python implementation can be found at
 * https://pypi.org/project/libgen-api/ .
 */
class LibgenClient(
    private val client: OkHttpClient,
    private val userAgent: String,
    baseUrls: List<String> = DEFAULT_BASE_URLS,
) {

    private val baseHttpUrls = baseUrls
        .map { it.toHttpUrl() }
        .distinct()

    @Volatile
    private var lastSuccessfulBaseHttpUrl: HttpUrl = baseHttpUrls.first()

    init {
        require(baseHttpUrls.isNotEmpty()) {
            "At least one Libgen base URL must be provided."
        }
    }

    fun currentBaseUrl(): String {
        return lastSuccessfulBaseHttpUrl.asBaseUrlString()
    }

    suspend fun search(query: String, type: LibgenSearchType): List<LibgenBook> {
        val trimmedQuery = query.trim()
        require(trimmedQuery.length >= MIN_QUERY_LENGTH) { "Query must be at least $MIN_QUERY_LENGTH characters." }

        return withFallback { baseHttpUrl, headers ->
            val httpUrl = baseHttpUrl.newBuilder()
                .addPathSegment("search.php")
                .addQueryParameter("req", trimmedQuery)
                .addQueryParameter("column", type.column)
                .build()

            val request = GET(httpUrl, headers)
            val response = client.newCall(request).awaitSuccess()

            response.use { res ->
                parseSearchDocument(res.asJsoup(), baseHttpUrl)
            }
        }
    }

    fun filterResults(
        results: List<LibgenBook>,
        filters: Map<String, String>,
        exactMatch: Boolean,
    ): List<LibgenBook> {
        if (filters.isEmpty()) return results

        return if (exactMatch) {
            results.filter { result ->
                filters.all { (field, queryValue) ->
                    result[field]?.equals(queryValue, ignoreCase = false) == true
                }
            }
        } else {
            results.filter { result ->
                filters.all { (field, queryValue) ->
                    val candidate = result[field] ?: return@all false
                    candidate.contains(queryValue, ignoreCase = true)
                }
            }
        }
    }

    suspend fun resolveDownloadLinks(mirrorPageUrl: String?): Map<String, String> {
        if (mirrorPageUrl.isNullOrBlank()) return emptyMap()

        val request = GET(mirrorPageUrl, genericHeaders())
        val response = client.newCall(request).awaitSuccess()

        return response.use { res ->
            val document = res.asJsoup()
            document.select("a")
                .filter { element -> MIRROR_SOURCES.contains(element.text()) }
                .associate { element ->
                    val label = element.text()
                    val url = element.absUrl("href").ifBlank { element.attr("href") }
                    label to url
                }
        }
    }

    private fun parseSearchDocument(
        document: Document,
        baseHttpUrl: HttpUrl,
    ): List<LibgenBook> {
        document.select("i").forEach(Element::remove)

        val tables = document.select("table")
        val resultsTable = tables.getOrNull(RESULTS_TABLE_INDEX) ?: return emptyList()

        return resultsTable.select("tr")
            .drop(1) // Skip header row
            .mapNotNull { row ->
                val cells = row.select("td")
                if (cells.isEmpty()) {
                    null
                } else {
                    extractBook(cells, baseHttpUrl)
                }
            }
    }

    private fun extractBook(
        cells: List<Element>,
        baseHttpUrl: HttpUrl,
    ): LibgenBook {
        val values = COLUMN_NAMES.mapIndexed { index, column ->
            cells.getOrNull(index)?.let { cell ->
                extractCellValue(cell)
            } ?: ""
        }

        val raw = COLUMN_NAMES.zip(values).toMap()

        return LibgenBook(
            baseUrl = baseHttpUrl.asBaseUrlString(),
            id = raw["ID"].orEmpty(),
            author = raw["Author"].orEmpty(),
            title = raw["Title"].orEmpty(),
            publisher = raw["Publisher"].orEmpty(),
            year = raw["Year"].orEmpty(),
            pages = raw["Pages"].orEmpty(),
            language = raw["Language"].orEmpty(),
            fileSize = raw["Size"].orEmpty(),
            extension = raw["Extension"].orEmpty(),
            mirror1 = raw["Mirror_1"].orEmpty(),
            mirror2 = raw["Mirror_2"].orEmpty(),
            mirror3 = raw["Mirror_3"].orEmpty(),
            mirror4 = raw["Mirror_4"].orEmpty(),
            mirror5 = raw["Mirror_5"].orEmpty(),
            editUrl = raw["Edit"].orEmpty(),
            raw = raw,
        )
    }

    private fun extractCellValue(cell: Element): String {
        val anchor = cell.selectFirst("a")
        if (anchor != null) {
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            if (href.isNotBlank()) {
                return href
            }
            if (anchor.hasAttr("title") && anchor.attr("title").isNotBlank()) {
                return anchor.attr("title")
            }
        }
        return cell.text().trim()
    }

    companion object {
        private val DEFAULT_BASE_URLS = listOf(
            "https://libgen.rs",
            "http://libgen.rs",
            "https://libgen.is",
            "http://libgen.is",
            "https://libgen.st",
            "https://libgen.li",
            "https://libgen.gs",
            "https://libgen.rocks",
            "https://libgen.pm",
        )
        private const val MIN_QUERY_LENGTH = 3
        private const val RESULTS_TABLE_INDEX = 2

        private val COLUMN_NAMES = listOf(
            "ID",
            "Author",
            "Title",
            "Publisher",
            "Year",
            "Pages",
            "Language",
            "Size",
            "Extension",
            "Mirror_1",
            "Mirror_2",
            "Mirror_3",
            "Mirror_4",
            "Mirror_5",
            "Edit",
        )

        private val MIRROR_SOURCES = setOf("GET", "Cloudflare", "IPFS.io", "Infura")
    }

    private suspend fun <T> withFallback(
        block: suspend (HttpUrl, Headers) -> T,
    ): T {
        var lastException: Throwable? = null

        val orderedBases = sequence {
            val preferred = lastSuccessfulBaseHttpUrl
            yield(preferred)
            for (candidate in baseHttpUrls) {
                if (candidate != preferred) yield(candidate)
            }
        }

        for (baseHttpUrl in orderedBases) {
            val headers = headersFor(baseHttpUrl)
            try {
                val result = block(baseHttpUrl, headers)
                lastSuccessfulBaseHttpUrl = baseHttpUrl
                return result
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (ioe: IOException) {
                logcat(LogPriority.WARN) {
                    "Libgen request against ${baseHttpUrl.redactedHost()} failed with IO: ${ioe.message}"
                }
                lastException = ioe
                continue
            } catch (http: HttpException) {
                logcat(LogPriority.WARN) {
                    "Libgen request against ${baseHttpUrl.redactedHost()} failed with HTTP ${http.code}"
                }
                lastException = http
                continue
            }
        }

        val fallbackMessage = "All Libgen mirrors failed."
        val throwable = lastException ?: IOException(fallbackMessage)
        if (throwable is IOException) {
            throw throwable
        } else {
            throw IOException(fallbackMessage, throwable)
        }
    }

    private fun headersFor(baseHttpUrl: HttpUrl): Headers {
        return Headers.Builder()
            .add("User-Agent", userAgent)
            .add("Referer", baseHttpUrl.toString())
            .build()
    }

    private fun genericHeaders(): Headers {
        return Headers.Builder()
            .add("User-Agent", userAgent)
            .build()
    }

    private fun HttpUrl.asBaseUrlString(): String {
        val value = this.newBuilder()
            .encodedPath("/")
            .build()
            .toString()
        return if (value.endsWith("/")) value.removeSuffix("/") else value
    }

    private fun HttpUrl.redactedHost(): String {
        val usesDefaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        val portPart = if (usesDefaultPort) "" else ":$port"
        return "$scheme://$host$portPart"
    }
}

enum class LibgenSearchType(val column: String) {
    TITLE("title"),
    AUTHOR("author"),
}

data class LibgenBook(
    val baseUrl: String,
    val id: String,
    val author: String,
    val title: String,
    val publisher: String,
    val year: String,
    val pages: String,
    val language: String,
    val fileSize: String,
    val extension: String,
    val mirror1: String,
    val mirror2: String,
    val mirror3: String,
    val mirror4: String,
    val mirror5: String,
    val editUrl: String,
    private val raw: Map<String, String>,
) {
    operator fun get(field: String): String? = raw[field]

    fun mirrors(): List<Pair<String, String>> {
        return listOf(
            "Mirror 1" to mirror1,
            "Mirror 2" to mirror2,
            "Mirror 3" to mirror3,
            "Mirror 4" to mirror4,
            "Mirror 5" to mirror5,
        ).filter { (_, url) -> url.isNotBlank() }
    }
}
