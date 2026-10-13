package eu.kanade.tachiyomi.extension.pt.quadrinhosdesexo

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.time.Duration.Companion.seconds

@Source
abstract class QuadrinhosDeSexo : KeiSource() {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient() = rateLimit(5, 1.seconds)

    // ─── Filtros de URL ───────────────────────────────────────────────────

    private val adHostRegex = Regex(
        "(?i)(hqporno\\.com\\.br|webpsts\\.com|pornojapa\\.com)",
    )

    private val nonContentPaths = setOf(
        "acesso", "cadastro", "contato", "entrar", "registrar", "login",
        "category", "categorias", "categoria", "tag", "tags",
        "author", "wp-json", "feed", "page", "search", "busca",
    )

    private fun isAdUrl(href: String): Boolean = adHostRegex.containsMatchIn(href)

    private fun isNonContentUrl(href: String): Boolean {
        val path = href.removePrefix(baseUrl).trim('/').lowercase()
        if (path.isEmpty()) return true
        return path in nonContentPaths ||
            path.split("/").any { it in nonContentPaths }
    }

    // ─── Busca ────────────────────────────────────────────────────────────

    private fun buildSearchQuery(rawQuery: String): String = URLEncoder.encode(rawQuery.trim(), "UTF-8")

    // ─── Listagem ─────────────────────────────────────────────────────────

    private fun Element.toSManga(): SManga? {
        // Se o card inteiro for anúncio → pular
        val adAnchor = selectFirst("a.ane-ads")
        if (adAnchor != null) return null

        val a = select("a[href]").firstOrNull {
            val h = it.absUrl("href")
            h.startsWith(baseUrl) &&
                !isAdUrl(h) &&
                !isNonContentUrl(h) &&
                it.selectFirst("img") != null
        } ?: return null

        val href = a.absUrl("href")
        if (href.isEmpty() || isAdUrl(href) || isNonContentUrl(href)) return null

        val title = selectFirst("span.cn-title a")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: a.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        val thumb = a.selectFirst("img, div.cn-thumb img")
            ?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }
            ?.replace(Regex("(-\\d+x\\d+)(?=\\.(webp|jpg|jpeg|png|gif))"), "")
            .orEmpty()

        return SManga.create().apply {
            this.title = title
            this.thumbnail_url = thumb
            this.url = href.removePrefix(baseUrl)
        }
    }

    private fun parseListPage(document: Document): MangasPage {
        val mangas = document.select("li.cn-item")
            .mapNotNull { it.toSManga() }
            .distinctBy { it.url }

        val hasNext = document.selectFirst(
            "a.next.page-numbers[href], " +
                "link[rel=next], " +
                "a[rel=next][href]",
        ) != null

        return MangasPage(mangas, hasNext)
    }

    override suspend fun getPopularManga(page: Int) = parseListPage(client.get("$baseUrl/page/$page/").asJsoup())

    override suspend fun getLatestUpdates(page: Int) = getPopularManga(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)
        val encoded = buildSearchQuery(query)
        val url = if (page == 1) {
            "$baseUrl/?s=$encoded"
        } else {
            "$baseUrl/page/$page/?s=$encoded"
        }
        return parseListPage(client.get(url).asJsoup())
    }

    // ─── Detalhes do mangá ────────────────────────────────────────────────

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.count { it.isNotBlank() } < 1) return null
        val fullUrl = url.toString()
        if (isAdUrl(fullUrl) || isNonContentUrl(fullUrl)) return null
        return fetchMangaUpdate(
            SManga.create().apply { this.url = url.encodedPath },
            emptyList(),
            true,
            true,
        ).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        val imageCount = document.select("div.cn-texts img.attachment-full").size
        if (imageCount == 0) {
            return SMangaUpdate(
                SManga.create().apply {
                    url = manga.url
                    title = extractTitle(document).ifBlank { "Sem título" }
                    description = "⚠️ Este conteúdo não parece ser quadrinho."
                    status = SManga.CANCELLED
                },
                emptyList(),
            )
        }

        val tags = document.select("a[rel=tag], .cn-links .tag a, .cn-links a[href*=/tag/]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        // Descrição completa: pega o primeiro <p> real dentro de .cn-texts
        // (ignora parágrafos com iframe de ad e parágrafos muito curtos)
        val description = document.select("div.cn-texts > p")
            .map { it.text().trim() }
            .firstOrNull { it.length > 30 && !it.contains("iframe", ignoreCase = true) }
            .orEmpty()
            .ifBlank {
                document.selectFirst("meta[property=og:description]")
                    ?.attr("content")?.trim().orEmpty()
            }

        val cover = document.selectFirst("meta[property=og:image]")
            ?.attr("content").orEmpty()

        return SMangaUpdate(
            SManga.create().apply {
                url = manga.url
                title = extractTitle(document)
                thumbnail_url = cover
                this.description = description
                genre = tags.joinToString(", ")
            },
            parseChapters(manga.url),
        )
    }

    private fun extractTitle(document: Document): String {
        document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        val og = document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        if (og.isNotEmpty()) {
            return og.substringBefore(" - Hentai").substringBefore(" - Quadrinhos").trim()
        }
        return document.title().substringBefore(" -").trim()
    }

    private fun parseChapters(mangaUrl: String): List<SChapter> {
        // Sempre capítulo único (cada post = 1 quadrinho completo)
        return listOf(
            SChapter.create().apply {
                name = "Capítulo Único"
                url = mangaUrl
                date_upload = 0L
            },
        )
    }

    // ─── Páginas ──────────────────────────────────────────────────────────

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val rawUrl = chapter.url.substringBefore("#")
        val pageUrl = if (rawUrl.startsWith("http")) rawUrl else "$baseUrl$rawUrl"
        val document = client.get(pageUrl).asJsoup()

        val scope: Element = document.selectFirst("div.cn-texts") ?: document

        val images = scope.select("img").mapNotNull { img ->
            val src = img.absUrl("src").ifBlank { img.absUrl("data-src") }
            if (src.isBlank()) return@mapNotNull null

            // Só aceita imagens hospedadas no domínio/CDN do site
            val isOwnHost = src.contains("assets.quadrinhosdesexo.com") ||
                src.contains("quadrinhosdesexo.com/wp-content/uploads/")
            if (!isOwnHost) return@mapNotNull null

            // Ignora ads disfarçados de imagem
            val id = img.id()
            if (id.equals("imageAd", ignoreCase = true)) return@mapNotNull null

            val cls = img.className()
            // Aceita só as imagens da galeria (attachment-full)
            val isGallery = cls.contains("attachment-full") ||
                cls.contains("size-full") ||
                img.parent()?.tagName() == "dt"

            if (!isGallery) return@mapNotNull null

            // Ignora thumbs redimensionadas (sufixos tipo -213x300)
            if (Regex("-\\d+x\\d+(?=\\.(webp|jpg|jpeg|png))").containsMatchIn(src)) {
                return@mapNotNull null
            }

            src
        }.distinct()

        return images.mapIndexed { i, url -> Page(i, url = pageUrl, imageUrl = url) }
    }

    // ─── Requests de imagem ───────────────────────────────────────────────

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .header("Referer", page.url)
        .header(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        )
        .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
        .header("Sec-Fetch-Dest", "image")
        .header("Sec-Fetch-Mode", "no-cors")
        .header("Sec-Fetch-Site", "same-origin")
        .build()
}
