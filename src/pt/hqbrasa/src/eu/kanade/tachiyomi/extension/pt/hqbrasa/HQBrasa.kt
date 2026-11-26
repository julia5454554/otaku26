package eu.kanade.tachiyomi.extension.pt.hqbrasa

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class HQBrasa : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/populares".toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()

        return fetchListing(url)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/lancamentos".toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()

        return fetchListing(url)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = if (query.isNotBlank()) {
            "$baseUrl/busca".toHttpUrl().newBuilder().apply {
                addQueryParameter("q", query.trim())
                if (page > 1) addQueryParameter("page", page.toString())
            }.build()
        } else {
            val category = filters.firstInstanceOrNull<CategoryFilter>()?.selected().orEmpty()
            val order = filters.firstInstanceOrNull<OrderFilter>()?.selected().orEmpty()

            if (category.isNotEmpty()) {
                "$baseUrl/categoria/$category".toHttpUrl().newBuilder().apply {
                    if (page > 1) addQueryParameter("page", page.toString())
                }.build()
            } else {
                "$baseUrl/lista".toHttpUrl().newBuilder().apply {
                    addQueryParameter("sort", order.ifEmpty { "latest" })
                    if (page > 1) addQueryParameter("page", page.toString())
                }.build()
            }
        }

        return fetchListing(url)
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Os filtros são aplicados quando a busca por texto está vazia"),
        CategoryFilter(),
        OrderFilter(),
    )

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = mangaSlug(url) ?: return null
        val manga = SManga.create().apply { this.url = slug }

        return fetchMangaUpdate(
            manga = manga,
            chapters = emptyList(),
            fetchDetails = true,
            fetchChapters = false,
        ).manga
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/hq/${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        val updatedManga = SManga.create().apply {
            url = manga.url
            title = document.selectFirst(".manga-hero-info h1")?.text()
                ?: document.selectFirst("main h1")?.text()
                ?: error("Missing manga title")
            thumbnail_url = document.selectFirst(".manga-hero-cover img")?.absUrl("src").orEmpty()
            description = document.selectFirst(".manga-hero-desc")?.text()
                ?: document.selectFirst("meta[name=description]")?.attr("content").orEmpty()
            genre = document.select(".manga-hero-tags a.badge").joinToString { it.text() }
            status = SManga.ONGOING
            update_strategy = UpdateStrategy.ALWAYS_UPDATE
        }

        val chapterList = document.select("a.chapter-list-item[href]").mapNotNull { element ->
            element.toChapter()
        }.sortedByDescending { it.chapter_number }

        return SMangaUpdate(updatedManga, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val document = client.get(chapterUrl).asJsoup()

        return document.select("img.reader-page[src]").mapIndexedNotNull { index, element ->
            val imageUrl = element.absUrl("src").toHttpUrlOrNull()
                ?.newBuilder()
                ?.removeAllQueryParameters("width")
                ?.setQueryParameter("quality", "100")
                ?.build()
                ?.toString()
                ?: return@mapIndexedNotNull null

            Page(index, url = chapterUrl, imageUrl = imageUrl)
        }
    }

    private suspend fun fetchListing(url: HttpUrl): MangasPage = parseListing(client.get(url).asJsoup())

    private fun parseListing(document: Document): MangasPage {
        val mangas = document.select("article.manga-card").mapNotNull { element ->
            val link = element.selectFirst("a.manga-card-link[href]") ?: return@mapNotNull null
            val slug = link.absUrl("href").toHttpUrlOrNull()?.let { mangaSlug(it) } ?: return@mapNotNull null
            val title = link.attr("title").ifEmpty { link.selectFirst(".title")?.text().orEmpty() }
            if (title.isEmpty()) return@mapNotNull null

            SManga.create().apply {
                url = slug
                this.title = title
                thumbnail_url = element.selectFirst("img")?.absUrl("src").orEmpty()
            }
        }.distinctBy { it.url }

        val hasNextPage = document.selectFirst(
            "head link[rel=next][href], .pagination a[title=Próximo], .pagination a[rel=next]",
        ) != null

        return MangasPage(mangas, hasNextPage)
    }

    private fun Element.toChapter(): SChapter? {
        val chapterUrl = absUrl("href").toHttpUrlOrNull() ?: return null
        val name = selectFirst(".chapter-list-title")?.text()?.takeIf { it.isNotEmpty() } ?: return null
        val chapterNumber = chapterUrl.pathSegments.lastOrNull()?.toFloatOrNull()
            ?: CHAPTER_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toFloatOrNull()
            ?: -1f

        return SChapter.create().apply {
            this.name = name
            url = chapterUrl.encodedPath
            this.chapter_number = chapterNumber
        }
    }

    private fun mangaSlug(url: HttpUrl): String? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val hqIndex = url.pathSegments.indexOf("hq")
        return url.pathSegments.getOrNull(hqIndex + 1)?.takeIf { it.isNotEmpty() }
    }

    companion object {
        private val CHAPTER_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")
    }
}
