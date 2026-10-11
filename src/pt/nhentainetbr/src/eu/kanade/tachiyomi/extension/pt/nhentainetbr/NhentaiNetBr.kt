package eu.kanade.tachiyomi.extension.pt.nhentainetbr

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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
class NhentaiNetBr(
    override val lang: String = "pt-BR",
    override val id: Long = 0L,
) : HttpSource() {

    override val name = "NHentai Net BR"
    override val baseUrl = "https://nhentai.net.br"
    override val supportsLatest = true

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
    // LISTAGEM
    // ============================================================
    private fun Element.toSManga(): SManga? {
        val ad = selectFirst("a.thumbParodiaNome")
        if (ad != null && ad.text().contains("Publicidade", ignoreCase = true)) return null

        val a = select("a").firstOrNull { it.hasAttr("href") && it.selectFirst("img") != null }
            ?: selectFirst("a[href]:not(.thumbParodiaNome)")
            ?: return null

        val href = a.absUrl("href").takeIf { it.isNotEmpty() && it.startsWith(baseUrl) } ?: return null

        val title = selectFirst("span.thumb-titulo")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val thumb = a.selectFirst("img.wp-post-image, img")
            ?.let {
                val s = it.absUrl("src").ifBlank { it.absUrl("data-src") }
                s.replace(Regex("-\\d+x\\d+(?=\\.(webp|jpg|png))"), "")
            }
            .orEmpty()

        return SManga.create().apply {
            this.title = title
            this.thumbnail_url = thumb
            this.url = href.removePrefix(baseUrl)
        }
    }

    private fun parseListPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.thumb-conteudo")
            .mapNotNull { it.toSManga() }

        val hasNext = document.selectFirst(
            "a.next.page-numbers[href], " +
                "nav.pagination a.next[href], " +
                "link[rel=next], " +
                "a[rel=next][href]",
        ) != null

        return MangasPage(mangas, hasNext)
    }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/ultimos/page/$page/", headers)

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
    // DETALHES — SÓ TAGS (sem categoria/paródia)
    // ============================================================
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        val genres = extractLabeledTags(document).take(15)

        val description = document.select(
            "div.summary, div.description, div.sinopse, meta[property=og:description]",
        ).joinToString(" ") { it.text().trim() }
            .ifBlank {
                document.selectFirst("meta[name=description]")?.attr("content").orEmpty()
            }

        return SManga.create().apply {
            title = document.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            this.description = description
            genre = genres.joinToString(", ")
        }
    }

    /**
     * Extrai SÓ o bloco "Tags:" do HTML.
     * Estrutura: <li><strong>Tags:</strong> <a>Ahegao</a> <a>Creampie</a> ...</li>
     */
    private fun extractLabeledTags(document: Document): List<String> {
        val result = mutableListOf<String>()
        val labels = listOf("Tags")

        document.select("li").forEach { li ->
            val strong = li.selectFirst("strong") ?: li.selectFirst("b") ?: return@forEach
            val labelText = strong.text().trim().removeSuffix(":").trim()
            if (labels.none { labelText.equals(it, ignoreCase = true) }) return@forEach

            li.select("a").forEach { a ->
                val t = a.text().trim()
                if (t.isNotEmpty() &&
                    !t.equals("Login", true) &&
                    !t.equals("Registre-se", true) &&
                    !t.contains("Publicidade", true)
                ) {
                    result.add(t)
                }
            }
        }

        return result.distinct()
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
                name = "Capítulo 1"
                url = response.request.url.toString().removePrefix(baseUrl)
                date_upload = date
            },
        )
    }

    // ============================================================
    // PÁGINAS
    // ============================================================
    override fun pageListRequest(chapter: SChapter): Request {
        val url = if (chapter.url.startsWith("http")) chapter.url else "$baseUrl${chapter.url}"
        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val pageUrl = response.request.url.toString()
        val document = response.asJsoup()

        val images = document.select("img[style*=height]")
            .mapNotNull { img ->
                val src = img.absUrl("src").ifBlank { img.absUrl("data-src") }
                if (src.isBlank()) return@mapNotNull null
                if (!src.contains("/wp-content/uploads/")) return@mapNotNull null
                if (src.contains("Logo", ignoreCase = true)) return@mapNotNull null
                if (src.contains("mundohentaioficial", ignoreCase = true)) return@mapNotNull null
                src
            }

        return images.mapIndexed { i, url ->
            Page(i, url = pageUrl, imageUrl = url)
        }
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
