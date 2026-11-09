package eu.kanade.tachiyomi.extension.pt.hentaicomics

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
import okhttp3.Response
import kotlin.time.Duration.Companion.seconds

@Source
abstract class HentaiComics : KeiSource() {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2, 1.seconds)
        .addInterceptor { chain ->
            val req = chain.request()
            val builder = req.newBuilder()
            if (req.header("User-Agent") == null) builder.header("User-Agent", USER_AGENT)
            if (req.header("Referer") == null) builder.header("Referer", "$baseUrl/")
            if (req.header("Cookie") == null) builder.header("Cookie", "ageVerified=true")
            chain.proceed(builder.build())
        }

    // ==================== LISTAGEM ====================

    private fun parseListing(response: Response): MangasPage {
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
                this.url = href.removePrefix(baseUrl)
            }
        }

        // ✅ Ignora segmentos vazios antes de extrair o número da página atual.
        val currentPage = response.request.url.pathSegments
            .filter { it.isNotEmpty() }
            .lastOrNull { segment -> segment.all { c -> c.isDigit() } }
            ?.toIntOrNull() ?: 1

        val hasNextPage = document.selectFirst("div.paginator a[href*='/page/${currentPage + 1}/']") != null

        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page == 1) "$baseUrl/top-hentais/" else "$baseUrl/top-hentais/page/$page/"
        return parseListing(client.get(url))
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (page == 1) baseUrl else "$baseUrl/page/$page/"
        return parseListing(client.get(url))
    }

    // ==================== BUSCA ====================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = if (page == 1) {
            "$baseUrl/?s=$query"
        } else {
            "$baseUrl/page/$page/?s=$query"
        }
        return parseListing(client.get(url))
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

    // ==================== DETALHES + CAPÍTULOS ====================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get(getMangaUrl(manga))
        val document = response.asJsoup()

        val title = document.selectFirst("h1.post-title")?.text()?.trim().orEmpty()

        val description = document.select("div.single-post > p")
            .joinToString("\n") { it.text().trim() }
            .trim()

        val cover = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.single-post p img")?.attr("src").orEmpty()

        val genres = document.select("a[rel=tag]").map { it.text().trim() }

        val updated = SManga.create().apply {
            this.url = manga.url
            this.title = title
            this.thumbnail_url = cover
            this.description = description
            this.genre = genres.joinToString(", ")
            this.status = SManga.COMPLETED
        }

        // URL do capítulo = mesma URL da obra (capítulo único)
        val chapterList = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    name = "Capítulo Único"
                    chapter_number = 1f
                    this.url = manga.url
                },
            )
        } else {
            emptyList()
        }

        return SMangaUpdate(updated, chapterList)
    }

    // ==================== PÁGINAS ====================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()

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

    // ==================== IMAGEM ====================

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .header("Referer", page.url)
        .header("User-Agent", USER_AGENT)
        .header("Cookie", "ageVerified=true")
        .build()

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    }
}
