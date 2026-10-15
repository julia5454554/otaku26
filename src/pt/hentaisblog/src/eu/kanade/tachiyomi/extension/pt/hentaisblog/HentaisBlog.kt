package eu.kanade.tachiyomi.extension.pt.hentaisblog

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.URLEncoder

@Source
class HentaisBlog(
    override val lang: String = "pt-BR",
    override val id: Long = 2025000004L, // ⚠️ ID único — não colidir com SuperHQ (2025000003L)
) : HttpSource() {

    override val name = "Hentais Blog"
    override val baseUrl = "https://hentais.blog"
    override val supportsLatest = true

    private val apiUrl = "$baseUrl/wp-json/wp/v2/posts"
    private val staticHost = "$baseUrl/wp-content/uploads/"
    private val perPage = 20

    private val blockedCategories = listOf(
        "category-ads",
        "category-publicidade",
        "category-parceiros",
        "category-patrocado",
        "category-patrocinado",
        "category-promocao",
    )

    private val json = Json { ignoreUnknownKeys = true }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val original = chain.request()
            val req = if (original.header("Referer") == null) {
                original.newBuilder().header("Referer", "$baseUrl/").build()
            } else {
                original
            }
            chain.proceed(req)
        }
        .build()

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

    private fun extractSlug(url: String): String = url.trimEnd('/').substringAfterLast("/")

    /**
     * Jsoup.text() decodifica TODAS as entidades HTML automaticamente:
     *   &atilde; → ã,  &aacute; → á,  &ccedil; → ç,  &hellip; → …,
     *   &nbsp; → espaço,  &ndash; → –,  etc.
     * E ainda remove as tags HTML no mesmo passe.
     */
    private fun cleanHtmlText(html: String): String = Jsoup.parse(html).text().trim()

    private fun WpPost.toSMangaOrNull(): SManga? {
        val href = link

        if (href.isBlank() || !href.startsWith(baseUrl)) return null

        if (classList.any { it in blockedCategories }) return null

        val gallery = extractGalleryUrls(body.rendered)
        if (gallery.isEmpty()) return null

        val cover = embedded?.featuredMedia?.firstOrNull()?.sourceUrl
            ?.takeIf { it.isNotBlank() }
            ?: gallery.firstOrNull()

        val cleanTitle = title.rendered.trim().ifBlank { slug }

        return SManga.create().apply {
            this.title = cleanTitle
            setUrlWithoutDomain(href)
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
    // POPULAR
    // ============================================================

    override fun popularMangaRequest(page: Int): Request = GET("$apiUrl?_embed&per_page=$perPage&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseListing(response)

    // ============================================================
    // LATEST
    // ============================================================

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================================================
    // SEARCH
    // ============================================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = URLEncoder.encode(query, "UTF-8")
        return GET("$apiUrl?_embed&search=$q&per_page=$perPage&page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseListing(response)

    // ============================================================
    // DETAILS
    // ============================================================

    override fun mangaDetailsParse(response: Response): SManga {
        val slug = extractSlug(response.request.url.toString())

        val apiResponse = client.newCall(
            GET("$apiUrl?_embed&slug=$slug", headers),
        ).execute()
        val bodyText = apiResponse.body.string()
        apiResponse.close()

        val posts: List<WpPost> = json.decodeFromString(bodyText)
        val post = posts.firstOrNull()
            ?: throw Exception("HentaisBlog: post não encontrado para slug=$slug")

        val genres = post.classList
            .filter { it.startsWith("tag-") || it.startsWith("category-") }
            .map { it.removePrefix("tag-").removePrefix("category-").replace("-", " ") }
            .distinct()

        val gallery = extractGalleryUrls(post.body.rendered)
        val cover = post.embedded?.featuredMedia?.firstOrNull()?.sourceUrl
            ?.takeIf { it.isNotBlank() }
            ?: gallery.firstOrNull()

        val cleanTitle = cleanHtmlText(post.title.rendered).ifBlank { post.slug }
        val cleanDescription = cleanHtmlText(post.excerpt.rendered)
            .takeIf { it.isNotBlank() }
        val cleanGenres = genres.joinToString(", ").takeIf { it.isNotBlank() }

        return SManga.create().apply {
            this.title = cleanTitle
            this.thumbnail_url = cover
            this.description = cleanDescription
            this.genre = cleanGenres
            this.status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ALWAYS_UPDATE
        }
    }

    // ============================================================
    // CHAPTERS
    // ============================================================

    override fun chapterListParse(response: Response): List<SChapter> {
        val url = response.request.url.toString()
        return listOf(
            SChapter.create().apply {
                this.name = "Capítulo Único"
                this.chapter_number = 1f
                setUrlWithoutDomain(url)
            },
        )
    }

    // ============================================================
    // PAGES
    // ============================================================

    override fun pageListParse(response: Response): List<Page> {
        val url = response.request.url.toString()
        val slug = extractSlug(url)

        val apiResponse = client.newCall(
            GET("$apiUrl?slug=$slug&per_page=1", headers),
        ).execute()
        val bodyText = apiResponse.body.string()
        apiResponse.close()

        val posts: List<WpPost> = json.decodeFromString(bodyText)
        val post = posts.firstOrNull()
            ?: throw Exception("HentaisBlog: nenhum post para slug=$slug")

        val urls = extractGalleryUrls(post.body.rendered)
        if (urls.isEmpty()) {
            throw Exception("HentaisBlog: nenhuma imagem de galeria em $staticHost")
        }

        return urls.mapIndexed { i, u -> Page(i, "", u) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)
}
