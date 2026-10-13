package eu.kanade.tachiyomi.extension.pt.hentaiteca

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

@Source
class Hentaiteca(
    override val lang: String = "pt-BR",
    override val id: Long = 2025000001L, // ⚠️ Troque por um ID único
) : HttpSource() {

    override val name = "Hentaiteca"
    override val baseUrl = "https://hentaiteca.online"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ==================== LISTAGEM ====================
    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) baseUrl else "$baseUrl/page/$page/"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = mutableListOf<SManga>()

        document.select("div.page-item-detail.manga").forEach { item: Element ->
            parseMangaItem(item)?.let { mangas.add(it) }
        }

        if (mangas.isEmpty()) {
            document.select("a[href*='/manga/']").forEach { link: Element ->
                val title = link.attr("title").ifBlank { link.text().trim() }
                val img = link.selectFirst("img")
                val thumb = img?.attr("data-src")?.ifBlank { img.attr("src") } ?: ""
                if (title.isNotBlank() && thumb.isNotBlank()) {
                    SManga.create().apply {
                        this.title = title
                        this.thumbnail_url = enhanceThumbnailQuality(thumb)
                        setUrlWithoutDomain(link.attr("href"))
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
        val title = titleElement?.text()?.trim()
            ?: thumbLink.attr("title").ifBlank { thumbLink.text().trim() }
            ?: "Sem título"

        val img = item.selectFirst("div.item-thumb img")
        val thumb = img?.attr("data-src")?.ifBlank { img.attr("src") } ?: ""
        val highQualityThumb = enhanceThumbnailQuality(thumb)

        if (title.isBlank() || highQualityThumb.isBlank()) return null

        return SManga.create().apply {
            this.title = title
            this.thumbnail_url = highQualityThumb
            setUrlWithoutDomain(thumbLink.attr("href"))
        }
    }

    private fun enhanceThumbnailQuality(url: String): String {
        val cleaned = url.trim()
        val withLarger = cleaned.replace(Regex("-\\d+x\\d+\\."), "-350x476.")
        return if (withLarger != cleaned) {
            withLarger
        } else {
            cleaned
        }
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== BUSCA ====================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (page == 1) {
            "$baseUrl/?s=$query&post_type=wp-manga"
        } else {
            "$baseUrl/page/$page/?s=$query&post_type=wp-manga"
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== DETALHES ====================
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val title = document.selectFirst("div.post-title h1")?.text()?.trim() ?: "Sem título"
        val cover = document.selectFirst("div.summary_image img")?.attr("data-src")?.ifBlank {
            document.selectFirst("div.summary_image img")?.attr("src") ?: ""
        } ?: ""

        val genres = mutableListOf<String>()
        document.select("div.genres-content a, div.tags-content a, div.artist-content a").forEach { element: Element ->
            val text = element.text().trim()
            if (text.isNotBlank()) genres.add(text)
        }

        val author = document.selectFirst("div.artist-content a")?.text()?.trim() ?: ""

        return SManga.create().apply {
            this.title = title
            this.thumbnail_url = enhanceThumbnailQuality(cover)
            this.description = ""
            this.genre = genres.joinToString(", ")
            this.status = SManga.COMPLETED
            this.author = author
            update_strategy = UpdateStrategy.ALWAYS_UPDATE
        }
    }

    // ==================== CAPÍTULOS ====================
    override fun chapterListRequest(manga: SManga): Request {
        val url = if (manga.url.startsWith("http")) manga.url else "$baseUrl${manga.url}"
        val cleanUrl = if (url.endsWith("/")) url else "$url/"
        return POST("${cleanUrl}ajax/chapters/", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
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
                    setUrlWithoutDomain(href)
                },
            )
        }

        return chapters.distinctBy { it.url }
    }

    // ==================== PÁGINAS ====================
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
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

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Referer", page.url)
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }
}
