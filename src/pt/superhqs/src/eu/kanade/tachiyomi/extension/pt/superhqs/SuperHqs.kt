package eu.kanade.tachiyomi.extension.pt.superhqs

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import kotlin.time.Duration.Companion.seconds

@Source
class SuperHqs(
    override val lang: String = "pt-BR",
    override val id: Long = 2026000005L,
) : HttpSource() {

    override val name = "Super HQs"
    override val baseUrl = "https://superhq.com.br"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(1, 2.seconds)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
        .add("Referer", "$baseUrl/")

    // ============================================================
    // POPULARES / ÚLTIMOS
    // ============================================================
    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage =
        parseMangaList(response.asJsoup())

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================================================
    // BUSCA
    // ============================================================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/".toHttpUrl().newBuilder().apply {
            addQueryParameter("s", query)
            if (page > 1) {
                addEncodedPathSegments("page/$page/")
            }
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage =
        parseMangaList(response.asJsoup())

    // ============================================================
    // DETALHES
    // ============================================================
    override fun mangaDetailsParse(response: Response): SManga =
        parseMangaDetails(response.asJsoup())

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        val ogTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
        val rawTitle = document.selectFirst("h1.title")?.text() ?: ogTitle ?: ""
        title = Parser.unescapeEntities(rawTitle, false)

        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")?.let {
            if (it.startsWith("/")) "$baseUrl$it" else it
        } ?: document.selectFirst(".entry-content img")?.attr("src")?.let {
            if (it.startsWith("/")) "$baseUrl$it" else it
        }

        description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst(".entry-content p")?.text()
            ?: ""

        val categories = document.select(".category a, a[rel='category tag']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        genre = categories.joinToString(", ")
        status = SManga.COMPLETED
    }

    // ============================================================
    // CAPÍTULOS — capítulo único
    // ============================================================
    override fun chapterListParse(response: Response): List<SChapter> {
        val mangaUrl = response.request.url.toString().removePrefix(baseUrl)
        return listOf(
            SChapter.create().apply {
                name = "Capítulo Único"
                url = mangaUrl
                chapter_number = 1f
            },
        )
    }

    // ============================================================
    // PÁGINAS — recursivo (coleta via /pagina/N/)
    // ============================================================
    override fun pageListRequest(chapter: SChapter): Request {
        val url = if (chapter.url.startsWith("http")) chapter.url else {
            "$baseUrl${if (chapter.url.startsWith("/")) chapter.url else "/${chapter.url}"}"
        }
        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString()
        val pages = mutableListOf<Page>()
        val visited = mutableSetOf<String>()
        collectPages(chapterUrl, pages, visited)
        return pages
    }

    private fun collectPages(
        url: String,
        pages: MutableList<Page>,
        visited: MutableSet<String>,
    ) {
        if (url in visited) return
        visited.add(url)

        val response = client.newCall(GET(url, headers)).execute()
        val document = response.asJsoup()

        val images = document.select("img[src*='/wp-content/uploads/']")

        var baseSignature: String? = null
        for (img in images) {
            if (img.hasClass("thumb") || img.parents().any { it.hasClass("thumb") }) continue

            val imageUrl = extractImageUrlRaw(img)
            if (imageUrl != null && isPageImage(imageUrl)) {
                baseSignature = getImageSignature(imageUrl)
                if (baseSignature != null) break
            }
        }

        if (baseSignature != null) {
            for (img in images) {
                if (img.hasClass("thumb") || img.parents().any { it.hasClass("thumb") }) continue

                val imageUrl = extractImageUrlRaw(img)
                if (imageUrl != null && isImageUrl(imageUrl)) {
                    val sig = getImageSignature(imageUrl)
                    if (sig != null && areSignaturesSimilar(baseSignature, sig, 0.85)) {
                        pages.add(Page(pages.size, imageUrl = imageUrl))
                    }
                }
            }
        } else {
            for (img in images) {
                if (img.hasClass("thumb") || img.parents().any { it.hasClass("thumb") }) continue

                val imageUrl = extractImageUrlWithNumericPattern(img)
                if (imageUrl != null) {
                    pages.add(Page(pages.size, imageUrl = imageUrl))
                }
            }
        }

        val nextLink = document.selectFirst("a.botao-r[href*='/pagina/'], a[rel='next']")
            ?: document.selectFirst("a[href*='/pagina/']")
                ?.takeIf { it.text().contains("Próxima", ignoreCase = true) }

        if (nextLink != null) {
            val nextUrl = nextLink.attr("abs:href")
            if (nextUrl.isNotBlank() && nextUrl != url) {
                collectPages(nextUrl, pages, visited)
            }
        }
    }

    private fun extractImageUrlRaw(img: Element): String? {
        val attrs = listOf("data-full-url", "data-original", "data-src", "data-lazy-src", "src")
        for (attr in attrs) {
            val value = img.attr(attr).trim()
            if (value.isNotBlank() && isImageUrl(value)) {
                val fullUrl = if (value.startsWith("/")) "$baseUrl$value" else value
                if (fullUrl.startsWith("$baseUrl/wp-content/uploads/")) {
                    return fullUrl
                }
            }
        }

        val srcset = img.attr("srcset")
        if (srcset.isNotBlank()) {
            val srcsetUrls = srcset.split(",").map { it.trim().substringBefore(" ") }
            for (u in srcsetUrls.reversed()) {
                if (u.isNotBlank() && isImageUrl(u)) {
                    val fullUrl = if (u.startsWith("/")) "$baseUrl$u" else u
                    if (fullUrl.startsWith("$baseUrl/wp-content/uploads/")) {
                        return fullUrl
                    }
                }
            }
        }

        return null
    }

    private fun getImageSignature(imageUrl: String): String? {
        var fileName = imageUrl.substringAfterLast('/').substringBeforeLast('.')
        fileName = fileName.replace(Regex("""(scaled|copy)""", RegexOption.IGNORE_CASE), "")
        fileName = fileName.replace(Regex("""[-]?\d+$"""), "")
        val signature = fileName.filter { it.isLetter() }.lowercase()
        return if (signature.isNotEmpty()) signature else null
    }

    private fun areSignaturesSimilar(a: String, b: String, threshold: Double): Boolean {
        if (a == b) return true
        val maxLength = maxOf(a.length, b.length)
        if (maxLength == 0) return false
        val matches = a.zip(b).count { it.first == it.second }
        val similarity = matches.toDouble() / maxLength
        return similarity >= threshold
    }

    private fun isPageImage(url: String): Boolean {
        val fileName = url.substringAfterLast('/').lowercase()
        return fileName.matches(Regex(""".*-\d+.*\.(jpg|jpeg|png|webp)$"""))
    }

    private fun extractImageUrlWithNumericPattern(img: Element): String? {
        val imageUrl = extractImageUrlRaw(img)
        if (imageUrl != null) {
            val fileName = imageUrl.substringAfterLast('/').lowercase()
            if (fileName.matches(Regex(""".*-\d+-\d+\.(jpg|jpeg|png|webp)$""")) ||
                fileName.matches(Regex(""".*-\d+\.(jpg|jpeg|png|webp)$"""))
            ) {
                return imageUrl
            }
        }
        return null
    }

    private fun isImageUrl(url: String): Boolean {
        return url.substringBefore('?').substringBefore('#').matches(
            Regex(""".*\.(jpg|jpeg|png|webp)$""", RegexOption.IGNORE_CASE),
        )
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================================================
    // FILTROS — vazio
    // ============================================================
    override fun getFilterList(): FilterList = FilterList()

    // ============================================================
    // LISTAGEM — parse comum
    // ============================================================
    private fun parseMangaList(document: Document): MangasPage {
        val mangas = mutableListOf<SManga>()
        val seenUrls = mutableSetOf<String>()
        val elements = document.select("article.entry-item")
        for (element in elements) {
            val link = element.selectFirst("header a[rel='bookmark']") ?: continue
            val href = link.attr("href").trim()
            if (href.isBlank() || seenUrls.contains(href)) continue
            seenUrls.add(href)

            val mangaTitle = link.selectFirst("h3.entry-title")?.text()?.trim()
                ?: link.attr("title")?.trim()
                ?: link.text().trim()
            if (mangaTitle.isBlank()) continue

            val img = element.selectFirst("img.wp-post-image")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }?.let { if (it.startsWith("/")) "$baseUrl$it" else it }

            mangas.add(
                SManga.create().apply {
                    title = Parser.unescapeEntities(mangaTitle, false)
                    url = href.removePrefix(baseUrl)
                    thumbnail_url = img
                },
            )
        }

        val hasNextPage = document.selectFirst(
            "link[rel='next'], a.next.page-numbers, .next, .pagination .next, a[rel='next']",
        ) != null
        return MangasPage(mangas, hasNextPage)
    }
}
