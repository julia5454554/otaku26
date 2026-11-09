package eu.kanade.tachiyomi.extension.pt.nudelas

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
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Nudelas : KeiSource() {

    override val supportsLatest = false

    override fun OkHttpClient.Builder.configureClient() = rateLimit(1, 2.seconds)
        .addInterceptor { chain ->
            val req = chain.request()
            val builder = req.newBuilder()
            if (req.header("User-Agent") == null) builder.header("User-Agent", UA)
            if (req.header("Accept") == null) builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            if (req.header("Accept-Language") == null) builder.header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            if (req.header("Referer") == null) builder.header("Referer", "$baseUrl/")
            if (req.header("Sec-Fetch-Dest") == null) builder.header("Sec-Fetch-Dest", "document")
            if (req.header("Sec-Fetch-Mode") == null) builder.header("Sec-Fetch-Mode", "navigate")
            if (req.header("Sec-Fetch-Site") == null) builder.header("Sec-Fetch-Site", "same-origin")
            if (req.header("Upgrade-Insecure-Requests") == null) builder.header("Upgrade-Insecure-Requests", "1")
            chain.proceed(builder.build())
        }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)

    // ============================================================
    // LISTAGEM
    // ============================================================
    private fun Element.toSManga(): SManga? {
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

    private fun parseListPage(document: Document): MangasPage {
        val mangas = document.select("a.gallery-card").mapNotNull { it.toSManga() }

        val hasNext = document.selectFirst(
            "a.next.page-numbers[href], nav.pagination a.next[href], link[rel=next], a[rel=next][href]",
        ) != null

        return MangasPage(mangas, hasNext)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        return parseListPage(client.get(url).asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    // ============================================================
    // BUSCA
    // ============================================================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val encoded = query.replace(" ", "+")
        val url = if (page == 1) "$baseUrl/?s=$encoded" else "$baseUrl/page/$page/?s=$encoded"
        return parseListPage(client.get(url).asJsoup())
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

    // ============================================================
    // DETALHES + CAPÍTULOS
    // ============================================================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        val genres = document.select(
            "span.post-cat a.category, a[rel=category], .cat-links a, article .card-flag span",
        )
            .map { it.text().trim() }
            .filter { it.isNotEmpty() && !it.contains("publi", ignoreCase = true) }
            .distinct()
            .take(6)

        val description = document.select(
            "div.post-content.description > p, div.entry-content p, article p",
        )
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .take(3)
            .joinToString("\n\n")

        val updated = SManga.create().apply {
            this.url = manga.url
            this.title = document.selectFirst("h1")?.text()?.trim().orEmpty()
            this.thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            this.description = description
            this.genre = genres.joinToString(", ")
        }

        val chapterList = if (fetchChapters) {
            val dateText = document.selectFirst("meta[property=article:published_time]")?.attr("content")
            val date = dateText?.take(19)?.let {
                runCatching { dateFormat.parse(it)?.time }.getOrNull()
            } ?: 0L

            listOf(
                SChapter.create().apply {
                    this.name = "Gallery"
                    this.url = manga.url
                    this.date_upload = date
                },
            )
        } else {
            emptyList()
        }

        return SMangaUpdate(updated, chapterList)
    }

    // ============================================================
    // PÁGINAS — Busca todas as fotos originais no HTML inteiro
    // ============================================================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val galleryUrl = getChapterUrl(chapter)
        val document = client.get(galleryUrl).asJsoup()

        // 1. Pega o HTML bruto inteiro, onde as 70 fotos estão escondidas
        val html = document.outerHtml()

        // 2. Regex que busca apenas arquivos .jpg originais terminando em -NÚMERO.jpg
        // Isso descarta automaticamente .webp, -1024x682.jpg, etc.
        val regex = Regex("""https://www\.nudelas\.com/wp-content/uploads/[^"\s]+?-(\d+)\.jpg""", RegexOption.IGNORE_CASE)

        val bestUrls = mutableMapOf<Int, String>()

        // 3. Varre o HTML guardando apenas uma URL por número de foto
        regex.findAll(html).forEach { match ->
            val num = match.groupValues[1].toIntOrNull() ?: return@forEach
            val url = match.value

            if (!bestUrls.containsKey(num)) {
                bestUrls[num] = url
            }
        }

        // 4. Fallback de segurança, caso o HTML mude
        if (bestUrls.isEmpty()) {
            document.select("div#image-feed img.gallery-image").forEach { img ->
                val src = img.absUrl("src")
                val match = Regex("""-(\d+)\.jpg""").find(src)
                if (match != null) {
                    val num = match.groupValues[1].toIntOrNull()
                    if (num != null && !bestUrls.containsKey(num)) {
                        bestUrls[num] = src
                    }
                }
            }
        }

        // 5. Ordena do 1 ao 70 e entrega pro Mihon
        return bestUrls.toSortedMap().map { (num, url) ->
            Page(num - 1, url = galleryUrl, imageUrl = url)
        }
    }

    // ============================================================
    // IMAGEM
    // ============================================================
    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .header("User-Agent", UA)
        .header("Referer", page.url)
        .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
        .header("Sec-Fetch-Dest", "image")
        .header("Sec-Fetch-Mode", "no-cors")
        .header("Sec-Fetch-Site", "same-origin")
        .build()

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    }
}
