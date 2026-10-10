package eu.kanade.tachiyomi.extension.pt.nudelas

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
class Nudelas(
    override val lang: String = "pt-BR",
    override val id: Long = 2026000003L,
) : HttpSource() {

    override val name = "Nudelas"
    override val baseUrl = "https://www.nudelas.com"
    override val supportsLatest = false

    // Ritmo de navegação humana — evita bloqueio de IP
    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(1, 2.seconds)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
        .add("Referer", "$baseUrl/")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "same-origin")
        .add("Upgrade-Insecure-Requests", "1")

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)

    // ============================================================
    // LISTAGEM — tema novo (Tailwind + a.gallery-card)
    // ============================================================
    private fun Element.toSManga(): SManga? {
        // Filtra publieditorial pela flag do card
        val flagText = selectFirst(".card-flag")?.text().orEmpty()
        if (flagText.contains("publi", ignoreCase = true)) return null

        val href = absUrl("href").takeIf { it.isNotEmpty() && it.startsWith(baseUrl) } ?: return null

        val title = selectFirst("h3, h2, .card-title")
            ?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val img = selectFirst("img")
        val thumb = img?.let {
            it.absUrl("src").ifBlank { it.absUrl("data-src") }
        }.orEmpty()

        return SManga.create().apply {
            this.title = title
            this.thumbnail_url = thumb
            this.url = href.removePrefix(baseUrl)
        }
    }

    private fun parseListPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("a.gallery-card").mapNotNull { it.toSManga() }

        val hasNext = document.selectFirst(
            "a.next.page-numbers[href], " +
                "nav.pagination a.next[href], " +
                "link[rel=next], " +
                "a[rel=next][href]",
        ) != null

        return MangasPage(mangas, hasNext)
    }

    // /page/1/ dá 301 pra home → usa home direto na página 1
    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseListPage(response)

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================================================
    // BUSCA
    // ============================================================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = query.replace(" ", "+")
        val url = if (page == 1) "$baseUrl/?s=$encoded" else "$baseUrl/page/$page/?s=$encoded"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseListPage(response)

    // ============================================================
    // DETALHES
    // ============================================================
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        // Só categorias reais — ignora a navegação inteira do tema
        val genres = document.select(
            "span.post-cat a.category, " +
                "a[rel=category], " +
                ".cat-links a, " +
                "article .card-flag span",
        )
            .map { it.text().trim() }
            .filter { it.isNotEmpty() && !it.contains("publi", ignoreCase = true) }
            .distinct()
            .take(6)

        val description = document.select(
            "div.post-content.description > p, " +
                "div.entry-content p, " +
                "article p",
        )
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .take(3)
            .joinToString("\n\n")

        return SManga.create().apply {
            title = document.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            this.description = description
            genre = genres.joinToString(", ")
        }
    }

    // ============================================================
    // CAPÍTULOS
    // ============================================================
    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val dateText = doc.selectFirst("meta[property=article:published_time]")?.attr("content")
        val date = dateText?.take(19)?.let {
            runCatching { dateFormat.parse(it)?.time }.getOrNull()
        } ?: 0L

        return listOf(
            SChapter.create().apply {
                name = "Gallery"
                url = response.request.url.toString().removePrefix(baseUrl)
                date_upload = date
            },
        )
    }

    // ============================================================
    // PÁGINAS — filtra por slug do post (evita banner e relacionados)
    // ============================================================
    override fun pageListParse(response: Response): List<Page> {
        val galleryUrl = response.request.url.toString()
        val document = response.asJsoup()

        // Slug atual: /ketty-moon-pelada-bem-gostosinha-em-fotos-ousadas/
        val slug = response.request.url.pathSegments
            .lastOrNull { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyList()

        val seen = mutableSetOf<String>()
        val ordered = mutableListOf<String>()

        fun addUrl(raw: String) {
            if (raw.isBlank()) return
            if (!raw.contains("/wp-content/uploads/")) return
            if (!raw.contains(slug, ignoreCase = true)) return
            // Dedupe: tira .webp e sufixo -768x1024
            val key = raw.substringAfterLast("/")
                .removeSuffix(".webp")
                .replace(Regex("-\\d+x\\d+(?=\\.)"), "")
            if (seen.add(key)) ordered.add(raw)
        }

        // 1) Preferência: <a href> (imagem full-size, costuma não ter .webp)
        document.select("a[href*=/wp-content/uploads/]").forEach { addUrl(it.absUrl("href")) }
        // 2) Fallback: <img src>
        document.select("img[src*=/wp-content/uploads/]").forEach { addUrl(it.absUrl("src")) }

        return ordered.mapIndexed { i, url -> Page(i, url = galleryUrl, imageUrl = url) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Referer", page.url)
            .set("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .set("Sec-Fetch-Dest", "image")
            .set("Sec-Fetch-Mode", "no-cors")
            .set("Sec-Fetch-Site", "same-origin")
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }
}
