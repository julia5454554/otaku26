package eu.kanade.tachiyomi.extension.pt.melhorhentai

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
abstract class MelhorHentai : KeiSource() {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient() = rateLimit(5, 1.seconds)

    // ============================================================
    // LISTAGENS
    // ============================================================

    private fun Element.toCardManga(): SManga? {
        val href = attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = selectFirst("div.card-title")
            ?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val cover = selectFirst("img.mh-capa-img")?.let {
            val s = it.absUrl("src").ifBlank { it.absUrl("data-lazy-src") }
            s.replace(Regex("-\\d+x\\d+(?=\\.(webp|jpg|jpeg|png))"), "")
        }.orEmpty()

        return SManga.create().apply {
            this.title = title
            this.url = href.removePrefix(baseUrl)
            this.thumbnail_url = cover
        }
    }

    private fun Document.parseListing(): MangasPage {
        val mangas = select("a.card[href]").mapNotNull { it.toCardManga() }
        val hasNext = selectFirst(
            "a.next.page-numbers[href], a[rel=next][href], link[rel=next]",
        ) != null
        return MangasPage(mangas, hasNext)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page == 1) {
            "$baseUrl/categoria/manga-hentai/"
        } else {
            "$baseUrl/categoria/manga-hentai/page/$page/"
        }
        return client.get(url).asJsoup().parseListing()
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        return client.get(url).asJsoup().parseListing()
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getLatestUpdates(page)
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page == 1) "$baseUrl/?s=$q" else "$baseUrl/page/$page/?s=$q"
        return client.get(url).asJsoup().parseListing()
    }

    // ============================================================
    // OBRA
    // ============================================================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.none { it.isNotBlank() }) return null
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
        val doc = client.get(getMangaUrl(manga)).asJsoup()

        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val cover = doc.selectFirst("img.attachment-mh-capa-grande")?.absUrl("src").orEmpty()
        val description = extractDescription(doc)

        val genres = doc.select("a[href*=/tags/], a[href*=/categoria/]")
            .map { it.text().replace(Regex("\\s*\\d+\\s*$"), "").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")

        return SMangaUpdate(
            SManga.create().apply {
                this.url = manga.url
                this.title = title.ifBlank { manga.title }
                this.thumbnail_url = cover
                this.description = description
                this.genre = genres
                this.status = SManga.UNKNOWN
            },
            if (fetchChapters) parseChapters(doc, manga.url) else emptyList(),
        )
    }

    private fun extractDescription(doc: Document): String {
        val paragraphs = doc.select("div.synopsis-txt p")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("Títulos alternativos", ignoreCase = true) }
        if (paragraphs.isNotEmpty()) return paragraphs.joinToString("\n\n")

        return doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim().orEmpty()
    }

    // ============================================================
    // CAPÍTULOS — JSON caps → DOM cards → virtual
    // ============================================================

    private fun parseChapters(doc: Document, mangaUrl: String): List<SChapter> {
        chaptersFromJsonCaps(doc)?.takeIf { it.isNotEmpty() }?.let { return it }
        chaptersFromDomCards(doc)?.takeIf { it.isNotEmpty() }?.let { return it }
        return listOf(virtualChapter(mangaUrl))
    }

    private fun chaptersFromJsonCaps(doc: Document): List<SChapter>? {
        val script = doc.select("script:containsData(MHT_LEITOR)").firstOrNull()?.data()
            ?: return null

        val start = script.indexOf("\"caps\":")
        if (start < 0) return null
        val end = script.indexOf("\"idx\":", start).takeIf { it > start } ?: return null
        val block = script.substring(start, end)

        val urls = Regex("\"u\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(block).map { it.groupValues[1].replace("\\/", "/") }.toList()
        val nums = Regex("\"n\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(block).map { it.groupValues[1].toIntOrNull() ?: 0 }.toList()
        val titles = Regex("\"t\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(block).map { it.groupValues[1].replace("\\/", "/") }.toList()

        if (urls.isEmpty() || urls.size != nums.size || urls.size != titles.size) return null

        val now = System.currentTimeMillis()
        val maxNum = nums.maxOrNull() ?: 0

        return urls.indices.map { i ->
            val num = nums[i]
            val rawTitle = titles[i].trim()
            val name = if (rawTitle.isNotEmpty()) "Capítulo $num — $rawTitle" else "Capítulo $num"
            SChapter.create().apply {
                this.url = urls[i].removePrefix(baseUrl)
                this.name = name
                this.chapter_number = num.toFloat()
                this.date_upload = now - (maxNum - num).toLong() * 60_000L
            }
        }.sortedByDescending { it.chapter_number }
    }

    private fun chaptersFromDomCards(doc: Document): List<SChapter>? {
        val cards = doc.select("a.chapter-card[href]")
        if (cards.isEmpty()) return null

        val now = System.currentTimeMillis()
        val parsed = cards.mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val num = a.attr("data-s").toIntOrNull()
                ?: Regex("(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0
            val rawName = a.text().trim()
            val name = when {
                rawName.isEmpty() -> "Capítulo $num"
                rawName.startsWith("Cap", ignoreCase = true) -> rawName
                else -> "Capítulo $num — $rawName"
            }
            Triple(href.removePrefix(baseUrl), name, num)
        }
        if (parsed.isEmpty()) return null

        val maxNum = parsed.maxOf { it.third }
        return parsed.sortedByDescending { it.third }.map { (url, name, num) ->
            SChapter.create().apply {
                this.url = url
                this.name = name
                this.chapter_number = num.toFloat()
                this.date_upload = now - (maxNum - num).toLong() * 60_000L
            }
        }
    }

    private fun virtualChapter(mangaUrl: String): SChapter = SChapter.create().apply {
        this.url = mangaUrl
        this.name = "Capítulo Único"
        this.chapter_number = 1f
        this.date_upload = System.currentTimeMillis()
    }

    // ============================================================
    // PÁGINAS — JSON MHT_LEITOR.paginas → DOM #leitura
    // ============================================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val targetUrl = "$baseUrl${chapter.url}"
        val doc = client.get(targetUrl).asJsoup()

        val fromJson = pagesFromJson(doc)
        val fromDom = doc.select("div#leitura img").mapNotNull { img ->
            val src = img.absUrl("src").ifBlank { img.absUrl("data-lazy-src") }.trim()
            src.takeIf { it.startsWith("http") && !it.contains("/wp-content/plugins/", ignoreCase = true) }
        }

        val images = fromJson.ifEmpty { fromDom }.distinct()
        return images.mapIndexed { i, url -> Page(i, url = targetUrl, imageUrl = url) }
    }

    private fun pagesFromJson(doc: Document): List<String> {
        val script = doc.select("script:containsData(MHT_LEITOR)").firstOrNull()?.data()
            ?: return emptyList()

        val block = Regex("\"paginas\"\\s*:\\s*(\\[\\s*\\[.*?\\]\\s*\\])", RegexOption.DOT_MATCHES_ALL)
            .find(script)?.groupValues?.get(1)
            ?: return emptyList()

        return Regex("\"((?:[^\"\\\\]|\\\\.)+)\"")
            .findAll(block)
            .map { it.groupValues[1].replace("\\/", "/") }
            .filter { it.startsWith("http") && !it.contains("/wp-content/plugins/", ignoreCase = true) }
            .toList()
    }

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .header("Referer", "$baseUrl/")
        .header(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        )
        .build()
}
