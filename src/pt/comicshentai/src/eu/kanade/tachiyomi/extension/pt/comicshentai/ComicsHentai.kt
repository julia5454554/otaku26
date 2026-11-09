package eu.kanade.tachiyomi.extension.pt.comicshentai

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

@Source
abstract class ComicsHentai : KeiSource() {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2, 1.seconds)

    // ==================== EXTRAÇÃO DE URL DE IMAGEM ====================
    private fun extractImageUrl(element: Element): String {
        val raw = element.attr("data-lazy-src")
            .ifEmpty { element.attr("data-src") }
            .ifEmpty { element.attr("abs:src") }
            .ifEmpty { element.attr("src") }

        if (raw.isEmpty()) return ""

        return if (raw.startsWith("http://") || raw.startsWith("https://")) {
            raw
        } else {
            baseUrl + raw.removePrefix("/")
        }
    }

    // ==================== LISTAGEM (API REST) ====================
    private fun parseListing(response: Response): MangasPage {
        val jsonArray = JSONArray(response.body.string())
        val mangas = mutableListOf<SManga>()

        val adKeywords = listOf(
            "download", "grátis", "acesse", "clique", "patrocinado",
            "publicidade", "site", "anúncio", "ads", "vazou", "torrent",
        )

        for (i in 0 until jsonArray.length()) {
            val post = jsonArray.getJSONObject(i)
            val id = post.getInt("id")
            val title = Jsoup.parse(post.getJSONObject("title").getString("rendered")).text()
            val link = post.getString("link")

            if (!link.contains("thehentaicomics.com")) continue

            val lowerTitle = title.lowercase()
            if (adKeywords.any { lowerTitle.contains(it) }) continue

            val contentHtml = post.getJSONObject("content").getString("rendered")
            val doc = Jsoup.parse(contentHtml)
            val images = doc.select("img")

            if (images.size < 2) continue

            val apiUrl = "/wp-json/wp/v2/posts/$id"
            val thumb = post.optString("jetpack_featured_media_url", "").ifEmpty {
                images.firstOrNull()?.let { extractImageUrl(it) } ?: ""
            }

            SManga.create().apply {
                this.title = title
                this.thumbnail_url = thumb
                this.url = apiUrl
            }.let { mangas.add(it) }
        }

        val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 1
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = currentPage < totalPages

        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/wp-json/wp/v2/posts".toHttpUrl().newBuilder()
            .addQueryParameter("per_page", "20")
            .addQueryParameter("page", page.toString())
            .build()
        return parseListing(client.get(url.toString()))
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/wp-json/wp/v2/posts".toHttpUrl().newBuilder()
            .addQueryParameter("search", query.trim())
            .addQueryParameter("per_page", "20")
            .addQueryParameter("page", page.toString())
            .build()
        return parseListing(client.get(url.toString()))
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
        val post = JSONObject(response.body.string())

        val title = Jsoup.parse(post.getJSONObject("title").getString("rendered")).text()
        val thumb = post.optString("jetpack_featured_media_url", "").ifEmpty {
            val content = post.getJSONObject("content").getString("rendered")
            val doc = Jsoup.parse(content)
            doc.selectFirst("img")?.let { extractImageUrl(it) } ?: ""
        }
        val description = Jsoup.parse(post.getJSONObject("excerpt").getString("rendered")).text().trim()

        val updated = SManga.create().apply {
            this.url = manga.url
            this.title = title
            this.thumbnail_url = thumb
            this.description = description
            this.status = SManga.COMPLETED
            this.genre = ""
            this.author = ""
            this.update_strategy = UpdateStrategy.ALWAYS_UPDATE
        }

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
        val response = client.get(getChapterUrl(chapter))
        val post = JSONObject(response.body.string())
        val contentHtml = post.getJSONObject("content").getString("rendered")
        val doc = Jsoup.parse(contentHtml)

        val images = doc.select("img")
        val pages = mutableListOf<Page>()
        var index = 0

        images.forEach { img ->
            val src = extractImageUrl(img)
            if (src.isNotEmpty() && !src.startsWith("data:image")) {
                pages.add(Page(index++, url = baseUrl, imageUrl = src))
            }
        }

        return pages
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
