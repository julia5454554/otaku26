package eu.kanade.tachiyomi.extension.pt.superhq

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.URLEncoder

@Source
abstract class SuperHQ : KeiSource() {

    override val supportsLatest = true

    private val apiUrl = "$baseUrl/wp-json/wp/v2/posts"
    private val staticHost = "https://static.superhq.net/galerias/"
    private val perPage = 20

    private val json = Json { ignoreUnknownKeys = true }

    override fun OkHttpClient.Builder.configureClient() = addInterceptor { chain ->
        val req = chain.request()
        val builder = req.newBuilder()
        if (req.header("User-Agent") == null) builder.header("User-Agent", UA)
        if (req.header("Referer") == null) builder.header("Referer", "$baseUrl/")
        chain.proceed(builder.build())
    }

    // ============================================================
    // MODELOS JSON
    // ============================================================

    @Serializable
    private data class WpRendered(val rendered: String = "")

    @Serializable
    private data class WpFeaturedMedia(
        @SerialName("source_url") val sourceUrl: String = "",
    )

    @Serializable
    private data class WpEmbedded(
        @SerialName("wp:featuredmedia") val featuredMedia: List<WpFeaturedMedia>? = null,
    )

    @Serializable
    private data class WpPost(
        val id: Long = 0,
        val slug: String = "",
        val link: String = "",
        val title: WpRendered = WpRendered(),
        @SerialName("content") val body: WpRendered = WpRendered(),
        val excerpt: WpRendered = WpRendered(),
        @SerialName("featured_media") val featuredMedia: Long = 0,
        @SerialName("class_list") val classList: List<String> = emptyList(),
        @SerialName("_embedded") val embedded: WpEmbedded? = null,
    )

    // ============================================================
    // HELPERS
    // ============================================================

    /** Decodifica entidades HTML (&#8211; → –, &amp; → &, &#8230; → …, etc.) */
    private fun cleanHtmlText(html: String): String = Jsoup.parse(html).text().trim()

    private fun extractGalleryUrls(contentHtml: String): List<String> {
        if (contentHtml.isBlank()) return emptyList()
        val doc = Jsoup.parse(contentHtml)
        return doc.select("img").mapNotNull { img ->
            val src = img.attr("src").trim()
            val lazy = img.attr("data-lazy-src").trim()
            when {
                lazy.startsWith(staticHost) -> lazy
                src.startsWith(staticHost) -> src
                else -> null
            }
        }.distinct()
    }

    private fun extractSlug(url: String): String = url.substringAfterLast("/").removeSuffix(".html")

    private fun WpPost.toSMangaOrNull(): SManga? {
        val href = link
        if (href.isBlank()) return null

        val gallery = extractGalleryUrls(body.rendered)
        if (gallery.isEmpty()) return null

        val cover = embedded?.featuredMedia?.firstOrNull()?.sourceUrl
            ?.takeIf { it.isNotBlank() }
            ?: gallery.firstOrNull()

        // ✅ Jsoup decodifica &#8211; → –
        val cleanTitle = cleanHtmlText(title.rendered).ifBlank { slug }

        return SManga.create().apply {
            this.title = cleanTitle
            this.url = href.removePrefix(baseUrl)
            this.thumbnail_url = cover
        }
    }

    private fun parseListing(response: Response): MangasPage {
        val bodyText = response.body.string()
        val posts: List<WpPost> = json.decodeFromString(bodyText)
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: currentPage
        val mangas = posts.mapNotNull { it.toSMangaOrNull() }
        return MangasPage(mangas, currentPage < totalPages)
    }

    // ============================================================
    // POPULAR / LATEST
    // ============================================================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$apiUrl?_embed&per_page=$perPage&page=$page"
        return parseListing(client.get(url))
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    // ============================================================
    // SEARCH
    // ============================================================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$apiUrl?_embed&search=$q&per_page=$perPage&page=$page"
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

    // ============================================================
    // DETAILS + CHAPTERS (1 fetch só na API)
    // ============================================================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = extractSlug(manga.url)
        val bodyText = client.get("$apiUrl?_embed&slug=$slug").body.string()
        val posts: List<WpPost> = json.decodeFromString(bodyText)
        val post = posts.firstOrNull()
            ?: throw Exception("SuperHQ: post não encontrado para slug=$slug")

        val genres = post.classList
            .filter { it.startsWith("tag-") || it.startsWith("category-") }
            .map { it.removePrefix("tag-").removePrefix("category-").replace("-", " ") }
            .distinct()

        val gallery = extractGalleryUrls(post.body.rendered)
        val cover = post.embedded?.featuredMedia?.firstOrNull()?.sourceUrl
            ?.takeIf { it.isNotBlank() }
            ?: gallery.firstOrNull()

        // ✅ Jsoup decodifica todas as entidades no título e na descrição
        val cleanTitle = cleanHtmlText(post.title.rendered).ifBlank { post.slug }
        val cleanDescription = cleanHtmlText(post.excerpt.rendered).takeIf { it.isNotBlank() }
        val cleanGenres = genres.joinToString(", ").takeIf { it.isNotBlank() }

        val updated = SManga.create().apply {
            this.url = manga.url
            this.title = cleanTitle
            this.thumbnail_url = cover
            this.description = cleanDescription
            this.genre = cleanGenres
            this.status = SManga.COMPLETED
            this.update_strategy = UpdateStrategy.ALWAYS_UPDATE
        }

        val chapterList = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    this.name = "Capítulo Único"
                    this.chapter_number = 1f
                    this.url = manga.url
                },
            )
        } else {
            emptyList()
        }

        return SMangaUpdate(updated, chapterList)
    }

    // ============================================================
    // PAGES
    // ============================================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val slug = extractSlug(chapterUrl)

        val bodyText = client.get("$apiUrl?slug=$slug&per_page=1").body.string()
        val posts: List<WpPost> = json.decodeFromString(bodyText)
        val post = posts.firstOrNull()
            ?: throw Exception("SuperHQ: nenhum post para slug=$slug")

        val urls = extractGalleryUrls(post.body.rendered)
        if (urls.isEmpty()) {
            throw Exception("SuperHQ: nenhuma imagem de galeria em $staticHost")
        }

        return urls.mapIndexed { i, u -> Page(i, chapterUrl, u) }
    }

    // ============================================================
    // IMAGEM
    // ============================================================

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .header("User-Agent", UA)
        .build()

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    }
}
