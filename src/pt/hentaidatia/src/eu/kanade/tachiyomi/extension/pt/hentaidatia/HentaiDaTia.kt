package eu.kanade.tachiyomi.extension.pt.hentaidatia

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
abstract class HentaiDaTia : KeiSource() {

    override val supportsLatest = true

    private val postsUrl = "$baseUrl/wp-json/wp/v2/posts"
    private val mediaUrl = "$baseUrl/wp-json/wp/v2/media"
    private val perPage = 20
    private val includeChunk = 100

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Injeta UA, Referer e Cookie do age gate em toda requisição (só se ausentes,
     * preservando o Referer específico de imageRequest caso alguém o defina).
     */
    override fun OkHttpClient.Builder.configureClient() = addInterceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()
        if (original.header("User-Agent") == null) builder.header("User-Agent", UA)
        if (original.header("Referer") == null) builder.header("Referer", "$baseUrl/")
        if (original.header("Cookie") == null) builder.header("Cookie", "tia_idade_confirmada=1")
        chain.proceed(builder.build())
    }

    // ============================================================
    // MODELOS JSON
    // ============================================================

    @Serializable
    private data class WpRendered(val rendered: String = "")

    @Serializable
    private data class MediaSize(
        val file: String = "",
        @SerialName("source_url") val sourceUrl: String = "",
    )

    @Serializable
    private data class MediaDetails(
        val file: String = "",
        val sizes: Map<String, MediaSize> = emptyMap(),
    )

    @Serializable
    private data class WpMedia(
        val id: Long = 0,
        @SerialName("source_url") val sourceUrl: String = "",
        @SerialName("media_details") val mediaDetails: MediaDetails? = null,
    )

    @Serializable
    private data class WpEmbedded(
        @SerialName("wp:featuredmedia") val featuredMedia: List<WpMedia>? = null,
    )

    @Serializable
    private data class WpMeta(
        @SerialName("vdw_gallery_id") val galleryId: List<Long> = emptyList(),
        val paginas: String = "",
        val formato: String = "",
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
        val meta: WpMeta = WpMeta(),
    )

    // ============================================================
    // HELPERS
    // ============================================================

    private fun WpMedia.bestUrl(): String? = mediaDetails?.sizes?.get("full")?.sourceUrl?.takeIf { it.isNotBlank() }
        ?: sourceUrl.takeIf { it.isNotBlank() }

    private fun cleanHtmlText(html: String): String = Jsoup.parse(html).text().trim()

    private fun extractSlug(url: String): String = url.trimEnd('/').substringAfterLast("/")

    private fun WpPost.toSMangaOrNull(): SManga? {
        val href = link
        if (href.isBlank() || !href.startsWith(baseUrl)) return null

        val cover = embedded?.featuredMedia?.firstOrNull()?.bestUrl()

        val cleanTitle = cleanHtmlText(title.rendered).ifBlank { slug }
        if (cleanTitle.isBlank()) return null

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

    /**
     * Busca mídias por IDs em lotes de 100 (limite WP), preservando a ordem
     * original de vdw_gallery_id.
     */
    private suspend fun fetchMediaByIds(ids: List<Long>): List<WpMedia> {
        if (ids.isEmpty()) return emptyList()

        val all = mutableMapOf<Long, WpMedia>()
        for (chunk in ids.chunked(includeChunk)) {
            val includeParam = chunk.joinToString(",")
            val bodyText = client.get("$mediaUrl?include=$includeParam&per_page=$includeChunk").body.string()
            val medias: List<WpMedia> = json.decodeFromString(bodyText)
            medias.forEach { all[it.id] = it }
        }

        return ids.mapNotNull { all[it] }
    }

    // ============================================================
    // POPULAR / LATEST / SEARCH
    // ============================================================

    override suspend fun getPopularManga(page: Int): MangasPage = parseListing(client.get("$postsUrl?_embed&per_page=$perPage&page=$page"))

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val q = URLEncoder.encode(query, "UTF-8")
        return parseListing(client.get("$postsUrl?_embed&search=$q&per_page=$perPage&page=$page"))
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
    // DETALHES + CAPÍTULOS (1 fetch só — pula o front-end)
    // ============================================================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = extractSlug(manga.url)
        val bodyText = client.get("$postsUrl?_embed&slug=$slug").body.string()
        val posts: List<WpPost> = json.decodeFromString(bodyText)
        val post = posts.firstOrNull()
            ?: throw Exception("HentaiDaTia: post não encontrado para slug=$slug")

        val genres = post.classList
            .filter { it.startsWith("tag-") || it.startsWith("category-") }
            .map { it.removePrefix("tag-").removePrefix("category-").replace("-", " ") }
            .distinct()

        val cover = post.embedded?.featuredMedia?.firstOrNull()?.bestUrl()
        val cleanTitle = cleanHtmlText(post.title.rendered).ifBlank { post.slug }

        val cleanDescription = cleanHtmlText(post.excerpt.rendered)
            .takeIf { it.isNotBlank() }
            ?: buildString {
                if (post.meta.paginas.isNotBlank()) append("Páginas: ${post.meta.paginas}")
            }.takeIf { it.isNotBlank() }

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

    // ============================================================
    // PÁGINAS
    // ============================================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val slug = extractSlug(chapterUrl)

        // 1. Busca o post pra pegar a lista de IDs da galeria
        val postBody = client.get("$postsUrl?slug=$slug&per_page=1").body.string()
        val posts: List<WpPost> = json.decodeFromString(postBody)
        val post = posts.firstOrNull()
            ?: throw Exception("HentaiDaTia: nenhum post para slug=$slug")

        val galleryIds = post.meta.galleryId
        if (galleryIds.isEmpty()) {
            throw Exception("HentaiDaTia: post sem vdw_gallery_id (slug=$slug)")
        }

        // 2. Busca as mídias em lotes de 100
        val medias = fetchMediaByIds(galleryIds)
        val urls = medias.mapNotNull { it.bestUrl() }.distinct()

        if (urls.isEmpty()) {
            throw Exception("HentaiDaTia: nenhuma imagem retornada para ${galleryIds.size} IDs")
        }

        return urls.mapIndexed { i, u -> Page(i, chapterUrl, u) }
    }

    // ============================================================
    // IMAGEM
    // ============================================================

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .build()

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    }
}
