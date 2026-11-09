package eu.kanade.tachiyomi.extension.pt.superhqs

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import kotlin.time.Duration.Companion.seconds

@Source
abstract class SuperHqs : KeiSource() {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient() = rateLimit(1, 2.seconds)
        .addInterceptor { chain ->
            val req = chain.request()
            val builder = req.newBuilder()
            if (req.header("User-Agent") == null) builder.header("User-Agent", UA)
            if (req.header("Accept") == null) builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            if (req.header("Accept-Language") == null) builder.header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            if (req.header("Referer") == null) builder.header("Referer", "$baseUrl/")
            chain.proceed(builder.build())
        }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        return parseMangaList(client.get(url).asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/".toHttpUrl().newBuilder().apply {
            addQueryParameter("s", query)
            if (page > 1) {
                addEncodedPathSegments("page/$page/")
            }
        }.build()
        return parseMangaList(client.get(url.toString()).asJsoup())
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

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val updated = parseMangaDetails(document, manga.url)

        val chapterList = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    name = "Capítulo Único"
                    this.url = manga.url
                    chapter_number = 1f
                },
            )
        } else {
            emptyList()
        }

        return SMangaUpdate(updated, chapterList)
    }

    private fun parseMangaDetails(document: Document, mangaUrl: String): SManga = SManga.create().apply {
        this.url = mangaUrl

        val ogTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
        val rawTitle = document.selectFirst("h1.title")?.text() ?: ogTitle ?: ""
        title = Parser.unescapeEntities(rawTitle, false)

        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")?.let {
            if (it.startsWith("/")) "$baseUrl$it" else it
        } ?: document.selectFirst(".entry-content img")?.attr("src")?.let {
            if (it.startsWith("/")) "$baseUrl$it" else it
        }

        // --- EXTRAÇÃO CIRÚRGICA DA DESCRIÇÃO ---
        var fullDescription = ""

        // 1. Tenta pegar do bloco específico de sinopse do tema
        val summaryBlock = document.selectFirst("div.description-summary, div.summary_content, div.manga-excerpt")
        if (summaryBlock != null) {
            fullDescription = summaryBlock.text().trim()
        } else {
            // 2. Se não tem bloco específico, vasculha o conteúdo principal
            val contentDiv = document.selectFirst("div.entry-content")
            if (contentDiv != null) {
                // Limpa tags de script, iframe e anúncios de dentro do texto
                contentDiv.select("script, style, iframe, .ads, .ad, ins").remove()

                // Pega os parágrafos de texto
                val paragraphs = contentDiv.select("p")
                    .map { it.text().trim() }
                    .filter { it.isNotBlank() }
                    // Filtra avisos comuns e textos muito curtos (botões/links soltos)
                    .filterNot { p ->
                        p.contains("Leia também", ignoreCase = true) ||
                            p.contains("publicidade", ignoreCase = true) ||
                            p.contains("comentário", ignoreCase = true) ||
                            p.contains("Acesse", ignoreCase = true) ||
                            p.contains("Baixe", ignoreCase = true) ||
                            p.length < 20
                    }

                if (paragraphs.isNotEmpty()) {
                    fullDescription = paragraphs.joinToString("\n\n")
                }
            }
        }

        // 3. Fallback final: meta tag (só se tudo falhar)
        if (fullDescription.isBlank()) {
            fullDescription = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim() ?: ""
        }

        // 4. Limpa o prefixo "Sinopse:" se existir
        if (fullDescription.contains("Sinopse", ignoreCase = true)) {
            fullDescription = fullDescription.split(Regex("(?i)Sinopse"), limit = 2).getOrNull(1)?.trim() ?: fullDescription
        }

        description = fullDescription

        val categories = document.select(".category a, a[rel='category tag']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        genre = categories.joinToString(", ")
        status = SManga.COMPLETED
    }

    // ============================================================
    // PÁGINAS — recursivo (coleta via /pagina/N/)
    // ============================================================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val pages = mutableListOf<Page>()
        val visited = mutableSetOf<String>()
        collectPages(chapterUrl, pages, visited)
        return pages
    }

    private suspend fun collectPages(
        url: String,
        pages: MutableList<Page>,
        visited: MutableSet<String>,
    ) {
        if (url in visited) return
        visited.add(url)

        val document = client.get(url).asJsoup()

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
                        pages.add(Page(pages.size, url = url, imageUrl = imageUrl))
                    }
                }
            }
        } else {
            for (img in images) {
                if (img.hasClass("thumb") || img.parents().any { it.hasClass("thumb") }) continue

                val imageUrl = extractImageUrlWithNumericPattern(img)
                if (imageUrl != null) {
                    pages.add(Page(pages.size, url = url, imageUrl = imageUrl))
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

    private fun isImageUrl(url: String): Boolean = url.substringBefore('?').substringBefore('#').matches(
        Regex(""".*\.(jpg|jpeg|png|webp)$""", RegexOption.IGNORE_CASE),
    )

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .header("Referer", page.url)
        .header("User-Agent", UA)
        .build()

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
                ?: link.attr("title").trim().takeIf { it.isNotBlank() }
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

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    }
}
