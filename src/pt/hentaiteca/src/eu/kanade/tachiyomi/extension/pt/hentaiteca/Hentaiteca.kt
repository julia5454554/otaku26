package eu.kanade.tachiyomi.extension.pt.hentaiteca

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
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Hentaiteca : KeiSource() {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2, 1.seconds)
        .addInterceptor { chain ->
            val req = chain.request()
            val builder = req.newBuilder()
            if (req.header("User-Agent") == null) builder.header("User-Agent", UA)
            if (req.header("Referer") == null) builder.header("Referer", "$baseUrl/")
            chain.proceed(builder.build())
        }

    // ==================== LISTAGEM ====================

    private fun parseListing(document: Document): MangasPage {
        val mangas = mutableListOf<SManga>()

        document.select("div.page-item-detail.manga").forEach { item: Element ->
            parseMangaItem(item)?.let { mangas.add(it) }
        }

        // Fallback: seletor genérico (busca quebra o markup padrão)
        if (mangas.isEmpty()) {
            document.select("a[href*='/manga/']").forEach { link: Element ->
                val title = link.attr("title").ifBlank { link.text().trim() }
                val img = link.selectFirst("img")
                val thumb = img?.attr("data-src")?.ifBlank { img.attr("src") } ?: ""
                if (title.isNotBlank() && thumb.isNotBlank()) {
                    SManga.create().apply {
                        this.title = title
                        this.thumbnail_url = enhanceThumbnailQuality(thumb)
                        this.url = link.attr("href").removePrefix(baseUrl)
                    }.let { mangas.add(it) }
                }
            }
        }

        val uniqueMangas = mangas.distinctBy { it.url }
        val hasNextPage = document.selectFirst("a.nextpostslink, a.next") != null
        return MangasPage(uniqueMangas, hasNextPage)
    }

    private fun parseMangaItem(item: Element): SManga? {
        val thumbLink = item.selectFirst("div.item-thumb a[href*='/manga/']")
            ?: item.selectFirst("a[href*='/manga/']")
            ?: return null

        val titleElement = item.selectFirst("div.post-title h3 a")
        // ⚠️ Correção do Warning 1: Removido o "?: 'Sem título'" redundante
        val title = titleElement?.text()?.trim()
            ?: thumbLink.attr("title").ifBlank { thumbLink.text().trim() }

        val img = item.selectFirst("div.item-thumb img")
        val thumb = img?.attr("data-src")?.ifBlank { img.attr("src") } ?: ""
        val highQualityThumb = enhanceThumbnailQuality(thumb)

        if (title.isBlank() || highQualityThumb.isBlank()) return null

        return SManga.create().apply {
            this.title = title
            this.thumbnail_url = highQualityThumb
            this.url = thumbLink.attr("href").removePrefix(baseUrl)
        }
    }

    private fun enhanceThumbnailQuality(url: String): String {
        val cleaned = url.trim()
        val withLarger = cleaned.replace(Regex("-\\d+x\\d+\\."), "-350x476.")
        return if (withLarger != cleaned) withLarger else cleaned
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page == 1) baseUrl else "$baseUrl/page/$page/"
        return parseListing(client.get(url).asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    // ==================== BUSCA ====================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = if (page == 1) {
            "$baseUrl/?s=$query&post_type=wp-manga"
        } else {
            "$baseUrl/page/$page/?s=$query&post_type=wp-manga"
        }
        return parseListing(client.get(url).asJsoup())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.none { it.isNotBlank() }) return null
        return fetchMangaUpdate(
            SManga.create().apply { this.url = url.encodedPath },
            emptyList(),
            true,
            false,
        ).manga
    }

    // ==================== DETALHES + CAPÍTULOS ====================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // ⚠️ Correção do Warning 2: Removido o ".toString()" redundante
        val mangaUrl = getMangaUrl(manga)
        val document = client.get(mangaUrl).asJsoup()

        val title = document.selectFirst("div.post-title h1")?.text()?.trim() ?: "Sem título"
        val cover = document.selectFirst("div.summary_image img")?.attr("data-src")?.ifBlank {
            document.selectFirst("div.summary_image img")?.attr("src") ?: ""
        } ?: ""

        val genres = mutableListOf<String>()
        document.select("div.genres-content a, div.tags-content a, div.artist-content a")
            .forEach { element: Element ->
                val text = element.text().trim()
                if (text.isNotBlank()) genres.add(text)
            }

        val author = document.selectFirst("div.artist-content a")?.text()?.trim() ?: ""

        val updated = SManga.create().apply {
            this.url = manga.url
            this.title = title
            this.thumbnail_url = enhanceThumbnailQuality(cover)
            this.description = ""
            this.genre = genres.joinToString(", ")
            this.status = SManga.COMPLETED
            this.author = author
            this.update_strategy = UpdateStrategy.ALWAYS_UPDATE
        }

        val chapterList = if (fetchChapters) {
            fetchChaptersViaAjax(mangaUrl)
        } else {
            emptyList()
        }

        return SMangaUpdate(updated, chapterList)
    }

    /**
     * Capítulos vêm via POST ajax/chapters/ (endpoint Madara).
     */
    private fun fetchChaptersViaAjax(mangaUrl: String): List<SChapter> {
        val cleanUrl = if (mangaUrl.endsWith("/")) mangaUrl else "$mangaUrl/"
        val request = Request.Builder()
            .url("${cleanUrl}ajax/chapters/")
            .post(ByteArray(0).toRequestBody())
            .build()

        val response: Response = client.newCall(request).execute()
        val document = response.asJsoup()
        response.close()

        val chapters = mutableListOf<SChapter>()
        val elements = document.select("li.wp-manga-chapter")
            .ifEmpty { document.select("ul.main li") }

        for (element in elements) {
            val link = element.selectFirst("a") ?: continue
            val href = link.attr("href").ifBlank { continue }
            val name = link.text().trim()
            if (name.isBlank()) continue

            chapters.add(
                SChapter.create().apply {
                    this.name = name
                    this.url = href.removePrefix(baseUrl)
                },
            )
        }

        return chapters.distinctBy { it.url }
    }

    // ==================== PÁGINAS ====================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val pages = mutableListOf<Page>()
        var index = 0

        document.select("div.reading-content img.wp-manga-chapter-img").forEach { img: Element ->
            val src = extractImageUrl(img)
            if (src.isNotBlank()) {
                pages.add(Page(index++, url = baseUrl, imageUrl = src))
            }
        }

        return pages
    }

    private fun extractImageUrl(img: Element): String {
        val raw = img.attr("data-lazy-src")
            .ifBlank { img.attr("data-src") }
            .ifBlank { img.attr("abs:src") }
            .ifBlank { img.attr("src") }
        val clean = raw.trim()
        if (clean.isEmpty()) return ""
        return if (clean.startsWith("http://") || clean.startsWith("https://")) {
            clean
        } else {
            baseUrl + clean.removePrefix("/")
        }
    }

    // ==================== IMAGEM ====================

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .header("Referer", page.url)
        .header("User-Agent", UA)
        .build()

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    }
}
