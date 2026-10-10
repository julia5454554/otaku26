package eu.kanade.tachiyomi.extension.pt.hentaigratis

import eu.kanade.tachiyomi.network.GET
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
class HentaiGratis(
    override val lang: String = "pt-BR",
    override val id: Long = 2025000002L, // ⚠️ Troque por um ID único
) : HttpSource() {

    override val name = "HentaiGrátis"
    override val baseUrl = "https://hentaigratis.biz"
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

        document.select("article").forEach { article: Element ->
            val titleLink = article.selectFirst("h2.entry-title a") ?: return@forEach
            val title = titleLink.text().trim()
            val href = titleLink.attr("href")
            val img = article.selectFirst("div.entry-content img")
            val thumb = img?.attr("src") ?: ""

            val isHostedOnSite = thumb.startsWith("https://hentaigratis.biz") ||
                thumb.startsWith("http://hentaigratis.biz")

            if (title.isNotBlank() && href.isNotBlank() && thumb.isNotBlank() && isHostedOnSite) {
                SManga.create().apply {
                    this.title = title
                    this.thumbnail_url = thumb
                    setUrlWithoutDomain(href)
                }.let { mangas.add(it) }
            }
        }

        val hasNextPage = document.selectFirst("a.next") != null
        return MangasPage(mangas.distinctBy { it.url }, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== BUSCA ====================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (page == 1) {
            "$baseUrl/?s=$query"
        } else {
            "$baseUrl/page/$page/?s=$query"
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = mutableListOf<SManga>()

        document.select("article").forEach { article: Element ->
            val titleLink = article.selectFirst("h2.entry-title a") ?: return@forEach
            val title = titleLink.text().trim()
            val href = titleLink.attr("href")
            // Tenta obter thumbnail de div.entry-content img ou de div.entry-summary img
            val img = article.selectFirst("div.entry-content img, div.entry-summary img")
            val thumb = img?.attr("src") ?: ""

            if (title.isNotBlank() && href.isNotBlank()) {
                SManga.create().apply {
                    this.title = title
                    this.thumbnail_url = thumb // pode ser vazio, o app mostrará placeholder
                    setUrlWithoutDomain(href)
                }.let { mangas.add(it) }
            }
        }

        val hasNextPage = document.selectFirst("a.next") != null
        return MangasPage(mangas.distinctBy { it.url }, hasNextPage)
    }

    // ==================== DETALHES ====================
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        val rawTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("h1.entry-title")?.text()
            ?: "Sem título"
        val title = rawTitle.removeSuffix(" - Hentai Grátis").removeSuffix(" - Hentai Grátis").trim()

        val cover = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.entry-content img")?.attr("src") ?: ""

        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst("div.entry-content p")?.text()?.trim() ?: ""

        val genres = mutableListOf<String>()
        document.select("a[rel='tag']").forEach { genres.add(it.text().trim()) }

        return SManga.create().apply {
            this.title = title
            this.thumbnail_url = cover
            this.description = description
            this.genre = genres.joinToString(", ")
            this.status = SManga.COMPLETED
            this.author = ""
            update_strategy = UpdateStrategy.ALWAYS_UPDATE
        }
    }

    // ==================== CAPÍTULOS ====================
    override fun chapterListParse(response: Response): List<SChapter> {
        val url = response.request.url.toString()
        return listOf(
            SChapter.create().apply {
                name = "Capítulo Único"
                chapter_number = 1f
                setUrlWithoutDomain(url)
            },
        )
    }

    // ==================== PÁGINAS ====================
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val images = document.select("div.entry-content img")
        val pages = mutableListOf<Page>()
        var index = 0

        val currentUrl = response.request.url.toString()
        val mangaSlug = currentUrl.substringAfter("hentaigratis.biz/")
            .substringBefore("?")
            .trimEnd('/')
            .split("/")
            .lastOrNull { it.isNotBlank() }
            ?: ""

        val normalizedSlug = normalize(mangaSlug)

        images.forEach { img: Element ->
            val src = img.attr("src").trim()
            if (src.isBlank() || src.startsWith("data:image")) return@forEach

            val fileName = src.substringAfterLast("/").substringBefore("?")
            val normalizedFileName = normalize(fileName)

            if (normalizedSlug.isNotEmpty() && normalizedFileName.contains(normalizedSlug)) {
                pages.add(Page(index++, url = baseUrl, imageUrl = src))
            }
        }

        return pages
    }

    private fun normalize(input: String): String = input.lowercase()
        .replace("-", "")
        .replace("_", "")
        .replace(" ", "")
        .replace(".jpg", "")
        .replace(".png", "")
        .replace(".webp", "")

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Referer", page.url)
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }
}
