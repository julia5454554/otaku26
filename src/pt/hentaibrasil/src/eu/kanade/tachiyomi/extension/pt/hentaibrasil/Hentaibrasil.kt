package eu.kanade.tachiyomi.extension.pt.hentaibrasil

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
abstract class Hentaibrasil : KeiSource() {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient() = rateLimit(3, 1.seconds)

    // ------------------------------------------------------------------
    // Listagem
    // ------------------------------------------------------------------

    private fun Element.toSManga(): SManga? {
        val a = selectFirst("a[href]") ?: return null
        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return null
        if (!href.startsWith(baseUrl)) return null

        val title = selectFirst("span.thumb-titulo")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: a.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        val cover = selectFirst("img.attachment-post-thumbnail")
            ?.let { it.absUrl("data-src").ifBlank { it.absUrl("src") } }
            ?.takeIf { it.startsWith("http") }
            .orEmpty()

        return SManga.create().apply {
            this.title = title
            this.url = href.removePrefix(baseUrl)
            this.thumbnail_url = cover
        }
    }

    private fun parseListing(document: Document): MangasPage {
        val mangas = document.select("div.thumb-conteudo").mapNotNull { it.toSManga() }

        val hasNext = document.selectFirst(
            "a.next[href], a.next.page-numbers[href], link[rel=next], a[rel=next][href]",
        ) != null

        return MangasPage(mangas, hasNext)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        return parseListing(client.get(url).asJsoup())
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page == 1) {
            "$baseUrl/quadrinhos-eroticos/"
        } else {
            "$baseUrl/quadrinhos-eroticos/page/$page/"
        }
        return parseListing(client.get(url).asJsoup())
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getLatestUpdates(page)
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page == 1) {
            "$baseUrl/?s=$encoded"
        } else {
            "$baseUrl/page/$page/?s=$encoded"
        }
        return parseListing(client.get(url).asJsoup())
    }

    // ------------------------------------------------------------------
    // Detalhes
    // ------------------------------------------------------------------

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

        val title = document.selectFirst("h1.pagina-titulo, h1.post-titulo")
            ?.text()?.trim()
            ?.removeSuffix(" - Hentai Brasil")?.trim()
            ?: manga.title

        val cover = document.selectFirst("img.wp-post-image")
            ?.let { it.absUrl("data-src").ifBlank { it.absUrl("src") } }
            ?.takeIf { it.startsWith("http") }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()

        val description = document.selectFirst("meta[property=og:description]")
            ?.attr("content")?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty()

        val updated = SManga.create().apply {
            this.url = manga.url
            this.title = title
            this.thumbnail_url = cover
            this.description = description
            this.status = SManga.COMPLETED
        }

        // Cada obra é uma galeria única → um capítulo virtual
        val chapterList = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    name = "Capítulo Único"
                    url = manga.url
                    chapter_number = 1f
                    date_upload = 0L
                },
            )
        } else {
            emptyList()
        }

        return SMangaUpdate(updated, chapterList)
    }

    // ------------------------------------------------------------------
    // Páginas
    // ------------------------------------------------------------------

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val referer = getChapterUrl(chapter)
        val document = client.get(referer).asJsoup()

        // Fotos da galeria têm alt="<título> - Foto N"
        val images = document.select("img[alt*=' - Foto ']")
            .mapNotNull { img ->
                img.absUrl("src").ifBlank { img.absUrl("data-src") }
                    .takeIf { it.startsWith("http") && it.contains("/wp-content/uploads/") }
            }
            .distinct()

        return images.mapIndexed { i, url -> Page(i, url = referer, imageUrl = url) }
    }

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .header("Referer", page.url)
        .header(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        )
        .build()
}
