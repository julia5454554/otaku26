package eu.kanade.tachiyomi.extension.pt.hqerotica

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

@Source
abstract class HqErotica : KeiSource() {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2, 1.seconds)
        .addInterceptor { chain ->
            val req = chain.request()
            val builder = req.newBuilder()
            if (req.header("User-Agent") == null) builder.header("User-Agent", UA)
            if (req.header("Referer") == null) builder.header("Referer", "$baseUrl/")
            chain.proceed(builder.build())
        }

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
    private fun parseListing(document: Document): MangasPage {
        val items = document.select("div.thumb-conteudo")

        val mangas = items.mapNotNull { item ->
            val linkEl = item.selectFirst("a[href]") ?: return@mapNotNull null
            val href = linkEl.attr("abs:href")
            if (href.isEmpty() || href.contains("/page/")) return@mapNotNull null

            val title = item.selectFirst("span.thumb-titulo")?.text()?.trim()
                ?: linkEl.attr("title").trim()

            val imgEl = item.selectFirst("img")
            val thumb = imgEl?.let { extractImageUrl(it) } ?: ""

            SManga.create().apply {
                this.title = title
                this.thumbnail_url = thumb
                this.url = href.removePrefix(baseUrl)
            }
        }.distinctBy { it.url }

        val hasNextPage = document.selectFirst(
            "a.next, a.next.page-numbers, .pagination a:contains(›), ul.paginacao li.next",
        ) != null

        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val pageStr = if (page != 1) "page/$page/" else ""
        val document = client.get("$baseUrl/$pageStr").asJsoup()
        return parseListing(document)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    // ==================== BUSCA (SCRAPING) ====================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (query.isNotEmpty()) addQueryParameter("s", query)
            if (page > 1) addQueryParameter("paged", page.toString())
        }.build()
        val document = client.get(url.toString()).asJsoup()
        return parseListing(document)
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

    // ==================== DETALHES + CAPÍTULOS (SCRAPING) ====================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        val updated = SManga.create().apply {
            this.url = manga.url
            this.title = document.selectFirst("h1.post-titulo, h1.entry-title, h1")?.text()?.trim()
                ?: document.title()
            this.author = document.selectFirst(".post-itens li:contains(Artista:) a, .post-itens li:contains(Tradutor:) a")?.text() ?: ""
            this.genre = document.select("ul.post-itens a[rel='tag'], .post-itens li a[href*='/category/'], .post-itens li a[href*='/tag/']")
                .map { it.text() }.distinct().joinToString(", ")
            this.description = document.selectFirst("meta[name='description']")?.attr("content")
                ?: document.selectFirst("div.post-texto p")?.text()
                ?: ""
            this.status = SManga.COMPLETED

            val mainImg = document.selectFirst("div.post-capa img, .entry-content img, article img")
            this.thumbnail_url = mainImg?.let { extractImageUrl(it) } ?: ""
            this.update_strategy = UpdateStrategy.ALWAYS_UPDATE
        }

        val chapterList = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    name = "Capítulo Único"
                    chapter_number = 1f
                    this.url = manga.url
                },
            )
        } else {
            emptyList()
        }

        return SMangaUpdate(updated, chapterList)
    }

    // ==================== PÁGINAS (SCRAPING) ====================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val document = client.get(chapterUrl).asJsoup()

        // Seletores para capturar as imagens do capítulo
        val selectors = listOf(
            "ul.post-fotos img", // principal (estrutura real)
            "div.listaImagens img", // fallback
            "div.post-box img", // fallback
            "span.aneSliderImagem img", // fallback
            "div.foto img", // fallback
            "article img", // fallback
            ".entry-content img", // fallback
            "img", // último recurso
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
                pages.add(Page(index++, url = chapterUrl, imageUrl = src))
            }
        }

        return pages
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
