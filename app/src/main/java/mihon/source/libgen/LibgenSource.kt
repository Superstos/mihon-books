package mihon.source.libgen

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.text.isNotBlank
import okhttp3.Request
import okhttp3.Response

class LibgenSource : HttpSource() {

    private val libgenClient: LibgenClient by lazy {
        LibgenClient(
            client = client,
            userAgent = network.defaultUserAgentProvider(),
            baseUrls = BASE_URLS,
        )
    }

    override val name: String = NAME
    override val lang: String = LANG
    override val supportsLatest: Boolean = false
    override val versionId: Int = VERSION_ID
    override val baseUrl: String
        get() = libgenClient.currentBaseUrl()

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) {
            return MangasPage(emptyList(), hasNextPage = false)
        }

        val curatedResults = fetchCuratedPopularBooks()
        if (curatedResults.isNotEmpty()) {
            return curatedResults.toMangasPage(page)
        }

        val fallbackResults = libgenClient.search(DEFAULT_POPULAR_QUERY, LibgenSearchType.TITLE)
        return fallbackResults.toMangasPage(page)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val searchType = filters.find<SearchTypeFilter>()
            ?.let { LibgenSearchType.entries.getOrNull(it.state) }
            ?: LibgenSearchType.TITLE

        val exactMatch = filters.find<ExactMatchFilter>()?.state ?: true

        val additionalFilters = filters.filterIsInstance<FieldTextFilter>()
            .mapNotNull { filter ->
                val value = filter.state.trim()
                if (value.isEmpty()) {
                    null
                } else {
                    filter.fieldKey to value
                }
            }
            .toMap()

        val results = libgenClient.search(query, searchType)
        val filteredResults = if (additionalFilters.isEmpty()) {
            results
        } else {
            libgenClient.filterResults(results, additionalFilters, exactMatch)
        }

        return filteredResults.toMangasPage(page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        return MangasPage(emptyList(), hasNextPage = false)
    }

    override fun getFilterList(): FilterList {
        return FilterList(
            Filter.Header("Query options"),
            SearchTypeFilter(),
            ExactMatchFilter(),
            Filter.Separator(),
            Filter.Header("Optional field filters"),
            LanguageFilter(),
            ExtensionFilter(),
            PublisherFilter(),
            YearFilter(),
        )
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = manga

    override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()

    override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()

    private fun List<LibgenBook>.toMangasPage(page: Int): MangasPage {
        if (isEmpty()) {
            return MangasPage(emptyList(), hasNextPage = false)
        }

        val (slice, hasNext) = paginate(page)
        val mangaList = slice.map { it.toSManga() }
        return MangasPage(mangaList, hasNextPage = hasNext)
    }

    private fun List<LibgenBook>.paginate(page: Int): Pair<List<LibgenBook>, Boolean> {
        val safePage = page.coerceAtLeast(1)
        val fromIndex = (safePage - 1) * PAGE_SIZE
        if (fromIndex >= size) {
            return emptyList<LibgenBook>() to false
        }

        val toIndex = min(size, fromIndex + PAGE_SIZE)
        val subList = subList(fromIndex, toIndex)
        val hasNext = toIndex < size
        return subList to hasNext
    }

    private fun LibgenBook.toSManga(): SManga {
        val metadataLines = buildList {
            if (author.isNotBlank()) add("Author: $author")
            if (publisher.isNotBlank()) add("Publisher: $publisher")
            if (year.isNotBlank()) add("Year: $year")
            if (pages.isNotBlank()) add("Pages: $pages")
            if (language.isNotBlank()) add("Language: $language")
            if (fileSize.isNotBlank()) add("Size: $fileSize")
            if (extension.isNotBlank()) add("Format: $extension")
        }

        val mirrorLines = mirrors().map { (label, url) -> "$label: $url" }

        val descriptionText = buildString {
            if (metadataLines.isNotEmpty()) {
                metadataLines.forEach { appendLine(it) }
            }
            if (mirrorLines.isNotEmpty()) {
                if (metadataLines.isNotEmpty()) appendLine()
                appendLine("Download mirrors:")
                mirrorLines.forEach { appendLine(it) }
            }
        }.trim().ifEmpty { null }

        val genres = listOfNotNull(
            language.takeUnless { it.isBlank() },
            extension.takeUnless { it.isBlank() },
            fileSize.takeUnless { it.isBlank() },
        ).joinToString(", ").ifBlank { null }

        val sanitizedBase = this@toSManga.baseUrl
        val primaryUrl = if (mirror1.isNotBlank()) {
            mirror1
        } else {
            "$sanitizedBase/libgen/id/$id"
        }

        return SManga.create().apply {
            url = primaryUrl
            title = this@toSManga.title.ifBlank { "Untitled #$id" }
            author = this@toSManga.author.takeUnless { it.isBlank() }
            artist = null
            description = descriptionText
            genre = genres
            status = SManga.COMPLETED
            thumbnail_url = null
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            initialized = true
        }
    }

    private inline fun <reified T> FilterList.find(): T? {
        return this.filterIsInstance<T>().firstOrNull()
    }

    private suspend fun fetchCuratedPopularBooks(): List<LibgenBook> {
        val collected = mutableListOf<LibgenBook>()
        val seenKeys = mutableSetOf<String>()

        fun LibgenBook.register(): Boolean {
            val key = id.ifBlank { title.lowercase() }
            if (key.isBlank() || !seenKeys.add(key)) return false
            collected += this
            return true
        }

        for (title in CURATED_TITLE_CANDIDATES) {
            if (collected.size >= CURATED_RESULTS_LIMIT) break

            val matches = try {
                libgenClient.search(title, LibgenSearchType.TITLE)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList()
            }

            if (matches.isEmpty()) continue

            val preferred = matches.firstOrNull { it.title.equals(title, ignoreCase = true) }
                ?: matches.first()

            preferred.register()
        }

        if (collected.size < CURATED_RESULTS_LIMIT) {
            val fallback = try {
                libgenClient.search(DEFAULT_POPULAR_QUERY, LibgenSearchType.TITLE)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList()
            }

            for (book in fallback) {
                if (collected.size >= CURATED_RESULTS_LIMIT) break
                book.register()
            }
        }

        return collected.take(CURATED_RESULTS_LIMIT)
    }

    override fun popularMangaRequest(page: Int): Request = unsupported()

    override fun popularMangaParse(response: Response): MangasPage = unsupported()

    override fun latestUpdatesRequest(page: Int): Request = unsupported()

    override fun latestUpdatesParse(response: Response): MangasPage = unsupported()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = unsupported()

    override fun searchMangaParse(response: Response): MangasPage = unsupported()

    override fun mangaDetailsParse(response: Response): SManga = unsupported()

    override fun chapterListParse(response: Response): List<SChapter> = unsupported()

    override fun pageListParse(response: Response): List<Page> = unsupported()

    override fun imageUrlParse(response: Response): String = unsupported()

    override fun chapterPageParse(response: Response): SChapter = unsupported()

    private fun <T> unsupported(): T {
        throw UnsupportedOperationException("LibgenSource does not use HttpSource request/parse APIs.")
    }

    private class SearchTypeFilter :
        Filter.Select<String>("Search column", arrayOf("Title", "Author"))

    private class ExactMatchFilter : Filter.CheckBox("Exact field match", true)

    private open class FieldTextFilter(
        name: String,
        val fieldKey: String,
    ) : Filter.Text(name)

    private class LanguageFilter : FieldTextFilter("Language", "Language")

    private class ExtensionFilter : FieldTextFilter("Extension", "Extension")

    private class PublisherFilter : FieldTextFilter("Publisher", "Publisher")

    private class YearFilter : FieldTextFilter("Year", "Year")

    companion object {
        private const val NAME = "Library Genesis"
        private const val LANG = "en"
        private const val VERSION_ID = 3
        private val BASE_URLS = listOf(
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
        private const val DEFAULT_POPULAR_QUERY = "the"
        private const val PAGE_SIZE = 25
        private const val CURATED_RESULTS_LIMIT = 10
        private val CURATED_TITLE_CANDIDATES = listOf(
            "Pride and Prejudice",
            "1984",
            "To Kill a Mockingbird",
            "The Great Gatsby",
            "Moby-Dick",
            "War and Peace",
            "Jane Eyre",
            "Brave New World",
            "The Hobbit",
            "The Catcher in the Rye",
            "Crime and Punishment",
            "Harry Potter and the Sorcerer's Stone",
            "The Lord of the Rings",
            "The Little Prince",
            "The Alchemist",
        )
    }
}
