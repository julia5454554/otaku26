package eu.kanade.tachiyomi.extension.pt.nhentaibr

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
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.stringOrNull
import keiyoushi.utils.tryParseDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

@Source
abstract class NHentaiBR : KeiSource() {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3, 1.seconds)

    private val apiUrl: String get() = "$baseUrl/wp-json/wp/v2"
    private val vipCategoryId = 8348
    private val perPage = 20
    private val mediaPerPage = 100
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    private val resizedImageRegex = Regex("-\\d+x\\d+\\.[a-zA-Z]+$")
    private val galleryPagesRegex = Regex("""var\s+pages\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)

    override suspend fun getPopularManga(page: Int): MangasPage = fetchListing(postsUrl(page, categoryId = vipCategoryId), page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchListing(postsUrl(page), page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = fetchListing(postsUrl(page, query = query.trim()), page)

    private fun postsUrl(page: Int, categoryId: Int? = null, query: String? = null): HttpUrl = "$apiUrl/posts".toHttpUrl().newBuilder()
        .addQueryParameter("per_page", perPage.toString())
        .addQueryParameter("page", page.toString())
        .addQueryParameter("_embed", "true")
        .apply {
            if (categoryId != null) addQueryParameter("categories", categoryId.toString())
            if (!query.isNullOrBlank()) addQueryParameter("search", query)
        }
        .build()

    private fun postUrl(slug: String): HttpUrl = "$apiUrl/posts".toHttpUrl().newBuilder()
        .addQueryParameter("slug", slug)
        .addQueryParameter("_embed", "true")
        .build()

    private suspend fun fetchListing(url: HttpUrl, page: Int): MangasPage {
        val response = client.get(url)
        val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: page
        val posts = response.parseAs<List<WpPost>>()
        return MangasPage(posts.mapNotNull { it.toSManga() }, page < totalPages)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val slug = url.pathSegments.lastOrNull { it.isNotBlank() } ?: return null
        val post = client.get(postUrl(slug)).parseAs<List<WpPost>>().firstOrNull()
        return post?.toSManga()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.url.trimEnd('/').substringAfterLast('/')
        val post = client.get(postUrl(slug)).parseAs<List<WpPost>>().firstOrNull()
            ?: return SMangaUpdate(manga, emptyList())

        val mangaTitle = post.title.rendered.toPlainText().ifBlank { manga.title }
        val coverUrl = post.cover().orEmpty()
        val description = post.excerpt.rendered.toPlainText()
        val genres = post.classList
            .filter { it.startsWith("category-") || it.startsWith("tag-") }
            .map { it.removePrefix("category-").removePrefix("tag-") }
            .distinct()

        val updatedManga = SManga.create().apply {
            url = manga.url
            title = mangaTitle
            thumbnail_url = coverUrl
            this.description = description
            genre = genres.joinToString()
            status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }

        val chapter = SChapter.create().apply {
            name = "Capítulo Único"
            url = post.id.toString()
            chapter_number = 1f
            date_upload = dateFormatter.tryParseDateTime(post.date)
            memo = buildJsonObject { put("url", post.link) }
        }

        return SMangaUpdate(updatedManga, listOf(chapter))
    }

    override fun getMangaUrl(manga: SManga): String = manga.url

    override fun getChapterUrl(chapter: SChapter): String = chapter.memo["url"]?.stringOrNull ?: baseUrl

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val imageUrls = fetchGalleryPages(chapter.url) ?: fetchMediaImages(chapter.url)
        return imageUrls.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    /**
     * The gallery viewer holds the exact image list of the post in the order shown by the site.
     * It is also the only source for VIP posts, whose post page hides the gallery.
     */
    private suspend fun fetchGalleryPages(postId: String): List<String>? {
        val document = client.get(
            "$baseUrl/galeria".toHttpUrl().newBuilder()
                .addQueryParameter("id", postId)
                .build(),
        ).asJsoup()

        val script = document.select("script").map { it.data() }.firstOrNull { galleryPagesRegex.containsMatchIn(it) }
            ?: return null
        val pagesJson = galleryPagesRegex.find(script)?.groupValues?.get(1) ?: return null
        return pagesJson.parseAs<List<String>>().takeIf { it.isNotEmpty() }
    }

    private suspend fun fetchMediaImages(postId: String): List<String> {
        val medias = mutableListOf<WpMedia>()
        var page = 1
        var totalPages = 1

        while (page <= totalPages) {
            val response = client.get(
                "$apiUrl/media".toHttpUrl().newBuilder()
                    .addQueryParameter("parent", postId)
                    .addQueryParameter("per_page", mediaPerPage.toString())
                    .addQueryParameter("page", page.toString())
                    .build(),
            )
            totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 1
            medias += response.parseAs<List<WpMedia>>()
            page++
        }

        return medias.sortedBy { it.id }
            .map { it.sourceUrl }
            .filter { it.isNotBlank() && !resizedImageRegex.containsMatchIn(it) }
            .distinct()
    }

    private fun WpPost.toSManga(): SManga? {
        if (!link.startsWith(baseUrl)) return null
        val coverUrl = cover() ?: return null
        val mangaTitle = title.rendered.toPlainText()
        if (mangaTitle.isBlank()) return null

        return SManga.create().apply {
            title = mangaTitle
            url = link
            thumbnail_url = coverUrl
        }
    }

    private fun WpPost.cover(): String? = embedded?.featuredMedia?.firstOrNull()?.sourceUrl?.takeIf { it.isNotBlank() }

    private fun String.toPlainText(): String = Jsoup.parse(this).text()
}

@Serializable
private class WpPost(
    val id: Long,
    val link: String,
    val date: String,
    val title: WpRendered,
    val excerpt: WpRendered,
    @SerialName("class_list") val classList: List<String> = emptyList(),
    @SerialName("_embedded") val embedded: WpEmbedded? = null,
)

@Serializable
private class WpRendered(val rendered: String)

@Serializable
private class WpEmbedded(
    @SerialName("wp:featuredmedia") val featuredMedia: List<WpMedia> = emptyList(),
)

@Serializable
private class WpMedia(
    val id: Long,
    @SerialName("source_url") val sourceUrl: String,
)
