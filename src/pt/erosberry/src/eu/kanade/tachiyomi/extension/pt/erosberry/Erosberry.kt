package eu.kanade.tachiyomi.extension.pt.erosberry

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
class Erosberry(
    override val lang: String = "pt-BR",
    override val id: Long = 2026000002L,
) : HttpSource() {

    override val name = "Erosberry"
    override val baseUrl = "https://www.erosberry.com"

    // ⚠️ false = só a aba "Populares" aparece no app (a aba "Recentes" some)
    override val supportsLatest = false

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    private val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH)

    // ---------- LISTAGEM ----------
    private fun Element.toSManga(): SManga? {
        val a = selectFirst("a.wrap_image") ?: return null
        val title = selectFirst("h2.elips a")?.text()?.trim()
            ?: a.attr("title").trim()
        val href = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val thumb = selectFirst("a.wrap_image img")?.absUrl("src").orEmpty()
        if (title.isEmpty() || href.isEmpty()) return null

        return SManga.create().apply {
            this.title = title
            this.thumbnail_url = thumb
            this.url = href.removePrefix(baseUrl)
            this.artist = selectFirst("a[href^='/model/']")?.text()?.trim()
        }
    }

    private fun parseListPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.item-post").mapNotNull { it.toSManga() }
        val hasNext = document.selectFirst("div.pagination div.pager.next a") != null
        return MangasPage(mangas, hasNext)
    }

    // Aba única = listagem recente do site
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/page/$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseListPage(response)

    // ⚠️ Obrigatórios pela classe HttpSource, mas NÃO são usados (supportsLatest = false)
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseListPage(response)

    // ---------- BUSCA ----------
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val searchUrl = if (page == 1) {
            "$baseUrl/search?search=${query.replace(" ", "+")}"
        } else {
            "$baseUrl/search?search=${query.replace(" ", "+")}&page=$page"
        }
        return GET(searchUrl, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseListPage(response)

    // ---------- DETALHES ----------
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1.title")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            description = document.selectFirst("meta[name=description]")?.attr("content").orEmpty()
            artist = document.selectFirst("a[href^='/model/']")?.text()?.trim()
            author = artist
            genre = document.select("a.item-tag").joinToString(", ") { it.text().trim() }
        }
    }

    // ---------- CAPÍTULOS (galeria única) ----------
    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val dateText = doc.selectFirst("span.item:has(i.icon-calendar) span.value")?.text()
        val date = dateText?.let { runCatching { dateFormat.parse(it)?.time }.getOrNull() } ?: 0L
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
        return document.select("a.thumb-card").mapIndexedNotNull { i, a ->
            val img = a.selectFirst("img.thumb-image") ?: return@mapIndexedNotNull null
            val url = pickLargestImageUrl(img) ?: return@mapIndexedNotNull null
            Page(i, url = galleryUrl, imageUrl = url)
        }
    }

    private fun pickLargestImageUrl(img: Element): String? {
        // 1) Preferir srcset/data-srcset (tem várias resoluções)
        val srcset = img.attr("data-srcset").ifBlank { img.attr("srcset") }
        if (srcset.isNotBlank()) {
            val largest = srcset.split(",")
                .mapNotNull { entry ->
                    val parts = entry.trim().split(Regex("\\s+"))
                    if (parts.size < 2) return@mapNotNull null
                    val url = parts[0]
                    if (url.contains("1px.png")) return@mapNotNull null
                    val w = parts[1].removeSuffix("w").toIntOrNull() ?: return@mapNotNull null
                    url to w
                }
                .maxByOrNull { it.second }
                ?.first
            if (largest != null) return largest
        }
        // 2) data-src (lazy load, mas sem srcset)
        val dataSrc = img.attr("data-src")
        if (dataSrc.isNotBlank() && !dataSrc.contains("1px.png")) {
            return dataSrc.replace(Regex("_\\d+\\.jpg$"), "_800.jpg")
        }
        // 3) src final
        val src = img.absUrl("src")
        if (src.isNotBlank() && !src.contains("1px.png")) {
            return src.replace(Regex("_\\d+\\.jpg$"), "_800.jpg")
        }
        return null
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Referer", page.url)
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }
}
