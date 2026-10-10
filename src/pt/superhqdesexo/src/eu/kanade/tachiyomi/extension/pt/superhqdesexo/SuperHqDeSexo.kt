package eu.kanade.tachiyomi.extension.pt.superhqdesexo

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.Headers
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
class SuperHqDeSexo(
    override val lang: String = "pt-BR",
    override val id: Long = 0L, // troque por um ID único na publicação
) : HttpSource() {

    override val name = "SuperHqDeSexo"
    override val baseUrl = "https://superhqdesexo.com"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

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
    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/wp-json/wp/v2/posts".toHttpUrl().newBuilder()
            .addQueryParameter("per_page", "20")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val jsonArray = JSONArray(response.body.string())
        val mangas = mutableListOf<SManga>()

        val adKeywords = listOf(
            "download", "grátis", "acesse", "clique", "patrocinado",
            "publicidade", "site", "anúncio", "ads", "vazou", "torrent"
        )

        for (i in 0 until jsonArray.length()) {
            val post = jsonArray.getJSONObject(i)
            val id = post.getInt("id")
            val title = Jsoup.parse(post.getJSONObject("title").getString("rendered")).text()
            val link = post.getString("link")

            if (!link.contains("superhqdesexo.com")) continue

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
                setUrlWithoutDomain(apiUrl)
            }.let { mangas.add(it) }
        }

        val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 1
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = currentPage < totalPages

        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== BUSCA (API REST) ====================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/wp-json/wp/v2/posts".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("per_page", "20")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== DETALHES (API REST) ====================
    override fun mangaDetailsParse(response: Response): SManga {
        val post = JSONObject(response.body.string())
        val title = Jsoup.parse(post.getJSONObject("title").getString("rendered")).text()
        val thumb = post.optString("jetpack_featured_media_url", "").ifEmpty {
            val content = post.getJSONObject("content").getString("rendered")
            val doc = Jsoup.parse(content)
            doc.selectFirst("img")?.let { extractImageUrl(it) } ?: ""
        }
        val description = Jsoup.parse(post.getJSONObject("excerpt").getString("rendered")).text().trim()

        return SManga.create().apply {
            this.title = title
            this.thumbnail_url = thumb
            this.description = description
            this.status = SManga.COMPLETED
            this.genre = ""
            this.author = ""
            update_strategy = UpdateStrategy.ALWAYS_UPDATE
        }
    }

    // ==================== CAPÍTULOS (API REST) ====================
    override fun chapterListParse(response: Response): List<SChapter> {
        val basePath = response.request.url.toString().removePrefix(baseUrl)
        return listOf(
            SChapter.create().apply {
                name = "Capítulo Único"
                chapter_number = 1f
                setUrlWithoutDomain(basePath)
            }
        )
    }

    // ==================== PÁGINAS (API REST) ====================
    override fun pageListParse(response: Response): List<Page> {
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

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Referer", page.url)
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }
}
