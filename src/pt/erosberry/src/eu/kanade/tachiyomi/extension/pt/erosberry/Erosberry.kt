package eu.kanade.tachiyomi.extension.pt.erosberry

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
abstract class Erosberry : KeiSource() {

    override val supportsLatest = false

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2, 1.seconds)
        .addInterceptor { chain ->
            val req = chain.request()
            val builder = req.newBuilder()
            if (req.header("User-Agent") == null) {
                builder.header("User-Agent", USER_AGENT)
            }
            if (req.header("Referer") == null) {
                builder.header("Referer", "$baseUrl/")
            }
            chain.proceed(builder.build())
        }

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

    private fun parseListing(document: Document): MangasPage {
        val mangas = document.select("div.item-post").mapNotNull { it.toSManga() }
        val hasNext = document.selectFirst("div.pagination div.pager.next a") != null
        return MangasPage(mangas, hasNext)
    }

    // Aba única = listagem recente do site
    override suspend fun getPopularManga(page: Int): MangasPage = parseListing(client.get("$baseUrl/page/$page").asJsoup())

    // supportsLatest = false → não aparece na UI, mas mantemos por paridade
    override suspend fun getLatestUpdates(page: Int): MangasPage = parseListing(client.get("$baseUrl/page/$page").asJsoup())

    // ---------- BUSCA ----------
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val searchUrl = if (page == 1) {
            "$baseUrl/search?search=${query.replace(" ", "+")}"
        } else {
            "$baseUrl/search?search=${query.replace(" ", "+")}&page=$page"
        }
        return parseListing(client.get(searchUrl).asJsoup())
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

    // ---------- DETALHES + CAPÍTULOS ----------
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        val updated = SManga.create().apply {
            this.url = manga.url
            this.title = document.selectFirst("h1.title")?.text()?.trim().orEmpty()
            this.thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            this.description = document.selectFirst("meta[name=description]")?.attr("content").orEmpty()
            this.artist = document.selectFirst("a[href^='/model/']")?.text()?.trim()
            this.author = this.artist
            this.genre = document.select("a.item-tag").joinToString(", ") { it.text().trim() }
        }

        val chapterList = if (fetchChapters) {
            val dateText = document.selectFirst("span.item:has(i.icon-calendar) span.value")?.text()
            val date = dateText?.let { runCatching { dateFormat.parse(it)?.time }.getOrNull() } ?: 0L
            listOf(
                SChapter.create().apply {
                    name = "Gallery"
                    this.url = manga.url
                    this.date_upload = date
                },
            )
        } else {
            emptyList()
        }

        return SMangaUpdate(updated, chapterList)
    }

    // ---------- PÁGINAS ----------
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val galleryUrl = getChapterUrl(chapter)
        val document = client.get(galleryUrl).asJsoup()
        return document.select("a.thumb-card").mapIndexedNotNull { i, a ->
            val img = a.selectFirst("img.thumb-image") ?: return@mapIndexedNotNull null
            val url = pickLargestImageUrl(img) ?: return@mapIndexedNotNull null
            Page(i, url = galleryUrl, imageUrl = url)
        }
    }

    private fun pickLargestImageUrl(img: Element): String? {
        // 1) srcset/data-srcset (maior resolução disponível)
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
        // 2) data-src (lazy load)
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

    // ---------- IMAGEM ----------
    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .header("Referer", page.url)
        .header("User-Agent", USER_AGENT)
        .build()

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    }
}
