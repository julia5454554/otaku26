package eu.kanade.tachiyomi.extension.pt.hentaitecanet

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
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Hentaitecanet : KeiSource() {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient() = rateLimit(5, 1.seconds)

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR"))

    // ============================================================
    // LISTAGEM — GRID (Home)
    // ============================================================

    private fun Element.toGridManga(): SManga? {
        val link = selectFirst("h2.h5 > a, div.item-summary div.post-title a") ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = link.text().trim().takeIf { it.isNotEmpty() } ?: return null

        val cover = selectFirst("img.madara-thumbnail")
            ?.let {
                val s = it.absUrl("src").ifBlank { it.absUrl("data-src") }
                s.replace(Regex("-\\d+x\\d+(?=\\.(webp|jpg|jpeg|png))"), "")
            }
            .orEmpty()

        return SManga.create().apply {
            this.title = title
            this.url = href.removePrefix(baseUrl)
            this.thumbnail_url = cover
        }
    }

    // ============================================================
    // LISTAGEM — LISTA (Busca / Tags)
    // ============================================================

    private fun Element.toListManga(): SManga? {
        val a = selectFirst("div.tab-thumb a[href], div.item-thumb a[href]") ?: return null
        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return null

        val title = selectFirst("div.tab-summary div.post-title h3 a, div.tab-summary div.post-title a, div.post-title a")
            ?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: a.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        val cover = selectFirst("img.madara-thumbnail")
            ?.let {
                val s = it.absUrl("src").ifBlank { it.absUrl("data-src") }
                s.replace(Regex("-\\d+x\\d+(?=\\.(webp|jpg|jpeg|png))"), "")
            }
            .orEmpty()

        return SManga.create().apply {
            this.title = title
            this.url = href.removePrefix(baseUrl)
            this.thumbnail_url = cover
        }
    }

    private fun parseListing(document: Document): MangasPage {
        var mangas = document.select("div.page-item-detail.manga").mapNotNull { it.toGridManga() }
        if (mangas.isEmpty()) {
            mangas = document.select("div.c-tabs-item__content, div.page-listing-item").mapNotNull { it.toListManga() }
        }
        val hasNext = document.selectFirst(
            "a.nextpostslink[href], " +
                "a.next.page-numbers[href], " +
                "nav.pagination a.next[href], " +
                "link[rel=next], " +
                "a[rel=next][href]",
        ) != null
        return MangasPage(mangas, hasNext)
    }

    // ============================================================
    // POPULAR (Doujinshi) — /tag/doujinshi/
    // ============================================================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page == 1) "$baseUrl/tag/doujinshi/" else "$baseUrl/tag/doujinshi/page/$page/"
        return parseListing(client.get(url).asJsoup())
    }

    // ============================================================
    // RECENTES (Home) — /page/N/
    // ============================================================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        return parseListing(client.get(url).asJsoup())
    }

    // ============================================================
    // BUSCA — paginação via /page/N/?s=... (confirmado)
    // ============================================================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getLatestUpdates(page)
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page == 1) {
            "$baseUrl/?s=$encoded&post_type=wp-manga"
        } else {
            "$baseUrl/page/$page/?s=$encoded&post_type=wp-manga"
        }
        return parseListing(client.get(url).asJsoup())
    }

    // ============================================================
    // DETALHES
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
        val document = client.get(getMangaUrl(manga)).asJsoup()

        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val cover = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        val description = extractDescription(document)

        val genres = document.select("div.genres-content a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")

        val statusText = document.selectFirst("div.post-status div.summary-content")
            ?.text()?.trim()?.lowercase().orEmpty()
        val status = when {
            statusText.contains("completo") || statusText.contains("concluído") -> SManga.COMPLETED
            statusText.contains("lançamento") || statusText.contains("em andamento") ||
                statusText.contains("ativo") -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }

        return SMangaUpdate(
            SManga.create().apply {
                this.url = manga.url
                this.title = title.ifBlank { manga.title }
                this.thumbnail_url = cover
                this.description = description
                this.genre = genres
                this.status = status
            },
            if (fetchChapters) parseChapters(document, manga.url) else emptyList(),
        )
    }

    private fun extractDescription(document: Document): String {
        var rawText = document.selectFirst("div.description-summary")?.text()?.trim().orEmpty()

        if (rawText.isBlank() || rawText.contains("loader", ignoreCase = true)) {
            rawText = document.selectFirst("div.manga-excerpt")?.text()?.trim()
                ?: document.selectFirst("div.summary_content div.post-content")?.text()?.trim().orEmpty()
        }

        if (rawText.contains("Sinopse", ignoreCase = true)) {
            val parts = rawText.split(Regex("(?i)Sinopse"), limit = 2)
            if (parts.size > 1) {
                return parts[1].replace(Regex("^[\\s\\-–—:]+"), "").trim()
            }
        }

        return rawText.ifBlank {
            document.selectFirst("meta[property=og:description]")
                ?.attr("content")
                ?.replace(Regex("Continue Reading.*$", RegexOption.IGNORE_CASE), "")
                ?.trim()
                .orEmpty()
        }
    }

    private fun parseChapters(document: Document, mangaUrl: String): List<SChapter> {
        return document.select("li.wp-manga-chapter").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = a.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val dateText = li.selectFirst("span.chapter-release-date i")?.text()?.trim()

            SChapter.create().apply {
                this.url = href.removePrefix(baseUrl)
                this.name = name
                this.date_upload = parseDate(dateText)
            }
        }
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return runCatching { dateFormat.parse(dateStr)?.time ?: 0L }.getOrDefault(0L)
    }

    // ============================================================
    // PÁGINAS
    // ============================================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()

        val images = document.select("img.wp-manga-chapter-img").mapNotNull { img ->
            val src = img.absUrl("data-src").ifBlank { img.absUrl("src") }.trim()
            if (src.isBlank() || !src.startsWith("http")) return@mapNotNull null
            if (src.contains("/wp-content/plugins/", ignoreCase = true)) return@mapNotNull null
            src
        }.distinct()

        return images.mapIndexed { i, url -> Page(i, url = chapter.url, imageUrl = url) }
    }

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .header("Referer", page.url)
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
        .header("Sec-Fetch-Dest", "image")
        .header("Sec-Fetch-Mode", "no-cors")
        .header("Sec-Fetch-Site", "same-origin")
        .build()
}
