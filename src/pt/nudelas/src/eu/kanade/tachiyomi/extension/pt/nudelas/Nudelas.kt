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

    // Só uma aba ("Populares" mostra o feed cronológico)
    override val supportsLatest = false

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)

    // ---------- LISTAGEM ----------
    private fun Element.toSManga(): SManga? {
        // Pula publieditoriais (só texto, sem galeria)
        if (classNames().contains("category-publieditorial")) return null

        val a = selectFirst("a.image-link") ?: return null
        val href = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val title = selectFirst("h2.post-title-alt a, h2.is-title a")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val thumb = pickThumb(selectFirst("span.wp-post-image"))
        return SManga.create().apply {
            this.title = title
            this.thumbnail_url = thumb
            this.url = href.removePrefix(baseUrl)
        }
    }

    private fun pickThumb(span: Element?): String {
        if (span == null) return ""
        // data-bgset tem formato srcset: "url 768w, url 800w, ..."
        val bgset = span.attr("data-bgset")
        if (bgset.isNotBlank()) {
            val best = bgset.split(",")
                .mapNotNull { entry ->
                    val parts = entry.trim().split(Regex("\\s+"))
                    if (parts.size < 2) return@mapNotNull null
                    val w = parts[1].removeSuffix("w").toIntOrNull() ?: return@mapNotNull null
                    parts[0] to w
                }
                .maxByOrNull { it.second }
                ?.first
            if (best != null) return best
        }
        // data-bgsrc é uma única URL
        val bgsrc = span.attr("data-bgsrc")
        if (bgsrc.isNotBlank()) return bgsrc
        // fallback: background-image inline
        val style = span.attr("style")
        val match = Regex("background-image:\\s*url\\(['\"]?([^'\")]+)['\"]?\\)").find(style)
        return match?.groupValues?.get(1).orEmpty()
    }

    private fun parseListPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("article.grid-post").mapNotNull { it.toSManga() }
        val hasNext = document.selectFirst("nav.main-pagination span.label-next a[href]") != null
        return MangasPage(mangas, hasNext)
    }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/page/$page/", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseListPage(response)

    // Obrigatórios pela HttpSource, mas NÃO são usados (supportsLatest = false)
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseListPage(response)

    // ---------- BUSCA ----------
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = query.replace(" ", "+")
        val url = if (page == 1) {
            "$baseUrl/?s=$encoded"
        } else {
            "$baseUrl/page/$page/?s=$encoded"
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseListPage(response)

    // ---------- DETALHES ----------
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        // Apenas as categorias reais (poucas), ignorando as tags SEO infinitas
        val genres = document.select("span.post-cat a.category")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        return SManga.create().apply {
            title = document.selectFirst("h1.post-title-alt, h1.post-title")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            description = document.select("div.post-content.description > p")
                .take(3)
                .joinToString("\n\n") { it.text().trim() }
                .ifBlank { document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty() }
            genre = genres.joinToString(", ")
        }
    }

    // ---------- CAPÍTULOS ----------
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

    // ---------- PÁGINAS ----------
    override fun pageListParse(response: Response): List<Page> {
        val galleryUrl = response.request.url.toString()
        val document = response.asJsoup()
        // Só galerias WordPress (figure.gallery-item). Publieditoriais são filtrados na listagem.
        return document.select("figure.gallery-item").mapIndexedNotNull { i, figure ->
            val a = figure.selectFirst("div.gallery-icon > a") ?: return@mapIndexedNotNull null
            val href = a.absUrl("href")
            // Filtra anúncios (ex: clothoff.app) — só aceitar uploads do próprio site
            if (href.isEmpty() || !href.contains("/wp-content/uploads/")) return@mapIndexedNotNull null
            Page(i, url = galleryUrl, imageUrl = href)
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
