package eu.kanade.tachiyomi.extension.pt.hentaicomics

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
import kotlin.time.Duration.Companion.seconds

@Source
class HentaiComics(
    override val lang: String = "pt-BR",
    override val id: Long = 2025000003L, // ⚠️ Troque por um ID único se necessário
) : HttpSource() {

    override val name = "HentaiComics"
    override val baseUrl = "https://hentaicomics.biz"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")
        .add("Cookie", "ageVerified=true")

    // ==================== LISTAGEM ====================

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/top-hentais/" else "$baseUrl/top-hentais/page/$page/"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.post").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { return@mapNotNull null }
            val title = a.attr("title").ifBlank { a.selectFirst("img")?.attr("alt").orEmpty() }
            val thumb = a.selectFirst("img")?.attr("src").orEmpty()

            if (title.isBlank()) return@mapNotNull null

            SManga.create().apply {
                this.title = title
                this.thumbnail_url = thumb
                setUrlWithoutDomain(href)
            }
        }

        // ✅ CORREÇÃO: Ignora segmentos vazios antes de extrair o número da página atual.
        // Sem o .filter { it.isNotEmpty() }, o último segmento "" (da barra final) era
        // pego por engano, fazendo currentPage sempre ser 1 e travando o scroll na página 2.
        val currentPage = response.request.url.pathSegments
            .filter { it.isNotEmpty() }
            .lastOrNull { segment -> segment.all { c -> c.isDigit() } }
            ?.toIntOrNull() ?: 1

        val hasNextPage = document.selectFirst("div.paginator a[href*='/page/${currentPage + 1}/']") != null

        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) baseUrl else "$baseUrl/page/$page/"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== BUSCA ====================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (page == 1) {
            "$baseUrl/?s=$query"
        } else {
            "$baseUrl/page/$page/?s=$query"
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== DETALHES ====================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        val title = document.selectFirst("h1.post-title")?.text()?.trim() ?: ""

        val description = document.select("div.single-post > p")
            .joinToString("\n") { it.text().trim() }
            .trim()

        val cover = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.single-post p img")?.attr("src").orEmpty()

        val genres = document.select("a[rel=tag]").map { it.text().trim() }

        return SManga.create().apply {
            this.title = title
            this.thumbnail_url = cover
            this.description = description
            this.genre = genres.joinToString(", ")
            this.status = SManga.COMPLETED
        }
    }

    // ==================== CAPÍTULOS ====================

    override fun chapterListParse(response: Response): List<SChapter> {
        val url = response.request.url.toString()
        return listOf(
            SChapter.create().apply {
                name = "Capítulo Único"
                chapter_number = 1f
                setUrlWithoutDomain(url)
            },
        )
    }

    // ==================== PÁGINAS ====================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        val images = document.select("div.single-post img").filterNot { img ->
            img.parents().any { parent ->
                parent.tagName() == "div" && parent.attr("style").contains("text-align")
            }
        }

        return images.mapIndexedNotNull { index, img ->
            val src = img.attr("src").ifBlank { img.attr("data-src") }.trim()
            if (src.isBlank() || src.startsWith("data:image")) {
                null
            } else {
                Page(index, url = baseUrl, imageUrl = src)
            }
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
