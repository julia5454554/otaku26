package eu.kanade.tachiyomi.extension.pt.hqerotico

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

@Source
class HqErotico(
    override val lang: String = "pt-BR",
    override val id: Long = 0L, // troque por um ID único na publicação
) : HttpSource() {

    override val name = "HqErotico"
    override val baseUrl = "https://hqerotico.com"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ==================== EXTRAÇÃO DE URL DE IMAGEM ====================
    private fun extractImageUrl(element: Element): String {
        val raw = element.attr("data-lazy-src")
            .ifEmpty { element.attr("data-src") }
            .ifEmpty { element.attr("abs:src") }
            .ifEmpty { element.attr("src") }

        if (raw.isEmpty()) return ""

        return if (raw.startsWith("http://") || raw.startsWith("https://")) {
            raw
        } else {
            baseUrl + raw.removePrefix("/")
        }
    }

    // ==================== LISTAGEM (SCRAPING) ====================
    override fun popularMangaRequest(page: Int): Request {
        val pageStr = if (page != 1) "page/$page/" else ""
        return GET("$baseUrl/$pageStr", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val items = document.select("div.thumb-conteudo")

        val mangas = items.mapNotNull { item ->
            val linkEl = item.selectFirst("a[href]") ?: return@mapNotNull null
            val href = linkEl.attr("abs:href")

            // Filtro de anúncios: ignora links externos ou que contenham "ads"
            if (href.isEmpty() ||
                href.contains("/page/") ||
                !href.contains("hqerotico.com") ||
                item.selectFirst("span.thumb-ads") != null) {
                return@mapNotNull null
            }

            val title = item.selectFirst("span.thumb-titulo")?.text()?.trim()
                ?: linkEl.attr("title").trim()

            val imgEl = item.selectFirst("img")
            val thumb = imgEl?.let { extractImageUrl(it) } ?: ""

            SManga.create().apply {
                this.title = title
                this.thumbnail_url = thumb
                setUrlWithoutDomain(href.removePrefix(baseUrl))
            }
        }.distinctBy { it.url }

        val hasNextPage = document.selectFirst(
            "a.next, a.next.page-numbers, .pagination a:contains(›), ul.paginacao li.next"
        ) != null

        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== BUSCA (SCRAPING) ====================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (query.isNotEmpty()) addQueryParameter("s", query)
            if (page > 1) addQueryParameter("paged", page.toString())
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== DETALHES (SCRAPING) ====================
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1.post-titulo, h1.entry-title, h1.pagina-titulo")?.text()?.trim()
                ?: document.title()
            author = document.selectFirst(".post-itens li:contains(Artista:) a, .post-itens li:contains(Tradutor:) a")?.text() ?: ""
            genre = document.select("ul.post-itens a[rel='tag'], .post-itens li a[href*='/categoria/'], .post-itens li a[href*='/tag/']")
                .map { it.text() }.distinct().joinToString(", ")
            description = document.selectFirst("meta[name='description']")?.attr("content")
                ?: document.selectFirst("div.post-texto p")?.text()
                ?: ""
            status = SManga.COMPLETED
            val mainImg = document.selectFirst("div.post-capa img, .entry-content img, article img")
            thumbnail_url = mainImg?.let { extractImageUrl(it) } ?: ""
            update_strategy = UpdateStrategy.ALWAYS_UPDATE
        }
    }

    // ==================== CAPÍTULOS (SCRAPING) ====================
    override fun chapterListParse(response: Response): List<SChapter> {
        val basePath = response.request.url.toString().removePrefix(baseUrl)
        return listOf(
            SChapter.create().apply {
                name = "Capítulo Único"
                chapter_number = 1f
                setUrlWithoutDomain(basePath)
            }
        )
    }

    // ==================== PÁGINAS (SCRAPING) ====================
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val currentUrl = response.request.url.toString()

        val selectors = listOf(
            "ul.post-fotos img",          // principal (padrão dos sites da rede)
            "div.listaImagens img",       // fallback
            "div.post-box img",           // fallback
            "span.aneSliderImagem img",   // fallback
            "div.foto img",               // fallback
            "article img",                // fallback
            ".entry-content img",         // fallback
            "img"                         // último recurso
        )

        var images = emptyList<Element>()
        for (selector in selectors) {
            images = document.select(selector)
            if (images.isNotEmpty()) break
        }

        val pages = mutableListOf<Page>()
        var index = 0

        images.forEach { img ->
            val src = extractImageUrl(img)
            if (src.isNotEmpty() && !src.startsWith("data:image")) {
                pages.add(Page(index++, url = currentUrl, imageUrl = src))
            }
        }

        return pages
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Referer", page.url)
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }
}
