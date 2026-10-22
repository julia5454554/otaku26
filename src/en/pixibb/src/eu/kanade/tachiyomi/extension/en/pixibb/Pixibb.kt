package eu.kanade.tachiyomi.extension.en.pixibb

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
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Pixibb : KeiSource() {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient() = rateLimit(4, 1.seconds)

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val imageRegex = Regex(
        "<img\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']",
        RegexOption.IGNORE_CASE,
    )

    // -----------------------------------------------------------
    // DTOs — WP REST API v2
    // -----------------------------------------------------------

    @Serializable
    private class WPPostDto(
        val id: Int = 0,
        val slug: String = "",
        val date: String = "",
        val link: String = "",
        val title: Rendered = Rendered(),
        val content: Rendered? = null,
        val categories: List<Int>? = null,
        val tags: List<Int>? = null,
        @SerialName("yoast_head_json") val yoast: YoastDto? = null,
    )

    @Serializable
    private class Rendered(val rendered: String = "")

    @Serializable
    private class YoastDto(
        @SerialName("og_image") val ogImage: List<OgImage>? = null,
    )

    @Serializable
    private class OgImage(val url: String = "")

    // -----------------------------------------------------------
    // Listagens
    // -----------------------------------------------------------

    private suspend fun fetchListing(url: String, page: Int): MangasPage {
        val response = client.get(url)
        if (!response.isSuccessful) return MangasPage(emptyList(), false)

        val totalPages = response.headers["X-WP-TotalPages"]?.toIntOrNull()
        val posts = response.parseAs<List<WPPostDto>>()
        val hasNext = totalPages?.let { page < it } ?: (posts.size >= PER_PAGE)

        return MangasPage(posts.map(::toSManga), hasNext)
    }

    private fun toSManga(post: WPPostDto): SManga = SManga.create().apply {
        url = "/${post.slug}/"
        title = Jsoup.parse(post.title.rendered).text().ifBlank { post.slug }
        thumbnail_url = post.yoast?.ogImage?.firstOrNull()?.url.orEmpty()
        initialized = true
    }

    override suspend fun getPopularManga(page: Int): MangasPage = fetchListing(
        buildApiUrl(
            "per_page=$PER_PAGE",
            "page=$page",
            "orderby=modified",
            "order=desc",
            "_fields=$LIST_FIELDS",
        ),
        page,
    )

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchListing(
        buildApiUrl(
            "per_page=$PER_PAGE",
            "page=$page",
            "orderby=date",
            "order=desc",
            "_fields=$LIST_FIELDS",
        ),
        page,
    )

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val params = mutableListOf(
            "per_page=$PER_PAGE",
            "page=$page",
            "orderby=date",
            "order=desc",
            "_fields=$LIST_FIELDS",
        )
        if (query.isNotBlank()) params += "search=${URLEncoder.encode(query.trim(), "UTF-8")}"

        return fetchListing(buildApiUrl(*params.toTypedArray()), page)
    }

    // -----------------------------------------------------------
    // Detalhes
    // -----------------------------------------------------------

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = url.pathSegments.lastOrNull { it.isNotBlank() } ?: return null
        return fetchMangaFromSlug(slug)
    }

    private suspend fun fetchMangaFromSlug(slug: String): SManga? {
        val url = buildApiUrl("slug=$slug", "_fields=$LIST_FIELDS")
        val response = client.get(url)
        if (!response.isSuccessful) return null
        val posts = response.parseAs<List<WPPostDto>>()
        return posts.firstOrNull()?.let(::toSManga)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = extractSlug(manga.url)
        val url = buildApiUrl("slug=$slug", "_fields=$DETAIL_FIELDS")
        val response = client.get(url)
        if (!response.isSuccessful) throw Exception("Album not found")

        val posts = response.parseAs<List<WPPostDto>>()
        val post = posts.firstOrNull() ?: throw Exception("Album not found")

        val html = post.content?.rendered.orEmpty()
        val imageCount = imageRegex.findAll(html).count()

        val sm = SManga.create().apply {
            this.url = manga.url
            this.title = Jsoup.parse(post.title.rendered).text().ifBlank { post.slug }
            this.thumbnail_url = post.yoast?.ogImage?.firstOrNull()?.url.orEmpty()
            this.genre = post.categories
                ?.mapNotNull { CATEGORY_NAMES[it] }
                ?.joinToString(", ")
                .orEmpty()
            this.description = when {
                imageCount <= 0 -> ""
                imageCount == 1 -> "Album with 1 photo."
                else -> "Album with $imageCount photos."
            }
            this.status = SManga.COMPLETED
            this.initialized = true
        }

        val chapterList = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    this.url = manga.url
                    // ✂️ Removido o contador "(37)" daqui
                    this.name = if (imageCount == 1) "Photo" else "Photos"
                    this.date_upload = parseDate(post.date)
                },
            )
        } else {
            emptyList()
        }

        return SMangaUpdate(sm, chapterList)
    }

    // -----------------------------------------------------------
    // Páginas (imagens)
    // -----------------------------------------------------------

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val slug = extractSlug(chapter.url)
        val url = buildApiUrl("slug=$slug", "_fields=content")
        val response = client.get(url)
        if (!response.isSuccessful) return emptyList()

        val html = response.parseAs<List<WPPostDto>>()
            .firstOrNull()?.content?.rendered
            ?: return emptyList()

        val doc = Jsoup.parse(html, baseUrl)
        val images = doc.select("img[src]").mapNotNull { img ->
            val src = img.absUrl("src").ifBlank { img.attr("src") }
            when {
                src.isBlank() || !src.startsWith("http") -> null
                src.contains("/wp-content/", ignoreCase = true) -> null
                else -> src
            }
        }.distinct()

        return images.mapIndexed { i, src -> Page(i, url = chapter.url, imageUrl = src) }
    }

    // -----------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------

    private fun buildApiUrl(vararg params: String): String = "$baseUrl/wp-json/wp/v2/posts?" + params.joinToString("&")

    private fun extractSlug(url: String): String = url.trim('/').substringAfterLast('/').substringBefore('?')

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return runCatching { dateFormat.parse(dateStr)?.time ?: 0L }.getOrDefault(0L)
    }

    private companion object {
        const val PER_PAGE = 30
        const val LIST_FIELDS =
            "id,slug,date,title,link,yoast_head_json.og_image,categories"
        const val DETAIL_FIELDS =
            "id,slug,date,title,link,content,yoast_head_json.og_image,categories,tags"

        val CATEGORY_NAMES = mapOf(
            112 to "Sexy Girls",
            10 to "Cosplay",
            4426 to "Eu Girls",
            24 to "AI Lookbook",
            74 to "Anime",
            119 to "Almost Real",
            209 to "Toon Girls",
            97 to "Hot Games",
            1 to "Uncategorized",
        )
    }
}
