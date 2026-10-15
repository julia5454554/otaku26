package eu.kanade.tachiyomi.extension.pt.hentaidatia

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
class HentaiDaTia(
    override val lang: String = "pt-BR",
    override val id: Long = 2025000005L, // ⚠️ ID único — não colidir com SuperHQ (3) / HentaisBlog (4)
) : HttpSource() {

    override val name = "Hentai da Tia"
    override val baseUrl = "https://hentaidatia.com"
    override val supportsLatest = true

    private val postsUrl = "$baseUrl/wp-json/wp/v2/posts"
    private val mediaUrl = "$baseUrl/wp-json/wp/v2/media"
    private val perPage = 20
    private val includeChunk = 100 // WP limita ?include= a 100 IDs por request

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Injeta Referer em toda requisição. E o cookie do age gate (tia_idade_confirmada=1)
     * por segurança — hoje o gate é só CSS/JS, mas se mudarem pra server-side,
     * já estará coberto.
     */
    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()
            if (original.header("Referer") == null) {
                builder.header("Referer", "$baseUrl/")
            }
            if (original.header("Cookie") == null) {
                builder.header("Cookie", "tia_idade_confirmada=1")
            }
            chain.proceed(builder.build())
        }
        .build()

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

    /**
     * Melhor URL de uma mídia:
     * 1. media_details.sizes.full.source_url (full-res) se existir
     * 2. source_url de topo (fallback)
     */
    private fun WpMedia.bestUrl(): String? = mediaDetails?.sizes?.get("full")?.sourceUrl?.takeIf { it.isNotBlank() }
        ?: sourceUrl.takeIf { it.isNotBlank() }

    /**
     * Usa Jsoup pra decodificar entidades HTML (&atilde; → ã, etc) e remover tags.
     */
    private fun cleanHtmlText(html: String): String = Jsoup.parse(html).text().trim()

    private fun extractSlug(url: String): String = url.trimEnd('/').substringAfterLast("/")

    private fun WpPost.toSMangaOrNull(): SManga? {
        val href = link
        if (href.isBlank() || !href.startsWith(baseUrl)) return null

        // Capa: featured_media (via _embed). Se não tiver, post fica sem capa mas não é pulado.
        val cover = embedded?.featuredMedia?.firstOrNull()?.bestUrl()

        val cleanTitle = cleanHtmlText(title.rendered).ifBlank { slug }
        if (cleanTitle.isBlank()) return null

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

    /**
     * Busca mídias por IDs, dividindo em lotes de 100 (limite do WP).
     * Ordena pela ordem dos IDs originais (importante — a ordem das páginas
     * do capítulo vem da ordem do vdw_gallery_id).
     */
    private fun fetchMediaByIds(ids: List<Long>): List<WpMedia> {
        if (ids.isEmpty()) return emptyList()

        val all = mutableMapOf<Long, WpMedia>()
        for (chunk in ids.chunked(includeChunk)) {
            val includeParam = chunk.joinToString(",")
            val req = GET("$mediaUrl?include=$includeParam&per_page=$includeChunk", headers)
            val resp = client.newCall(req).execute()
            val bodyText = resp.body.string()
            resp.close()
            val medias: List<WpMedia> = json.decodeFromString(bodyText)
            medias.forEach { all[it.id] = it }
        }

        // Preserva a ordem do vdw_gallery_id original
        return ids.mapNotNull { all[it] }
    }

    // ============================================================
    // POPULAR
    // ============================================================

    override fun popularMangaRequest(page: Int): Request = GET("$postsUrl?_embed&per_page=$perPage&page=$page", headers)

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
        return GET("$postsUrl?_embed&search=$q&per_page=$perPage&page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseListing(response)

    // ============================================================
    // DETAILS
    // ============================================================

    override fun mangaDetailsParse(response: Response): SManga {
        val slug = extractSlug(response.request.url.toString())

        val apiResponse = client.newCall(
            GET("$postsUrl?_embed&slug=$slug", headers),
        ).execute()
        val bodyText = apiResponse.body.string()
        apiResponse.close()

        val posts: List<WpPost> = json.decodeFromString(bodyText)
        val post = posts.firstOrNull()
            ?: throw Exception("HentaiDaTia: post não encontrado para slug=$slug")

        // Gêneros: extrai de class_list (tag-*, category-*)
        val genres = post.classList
            .filter { it.startsWith("tag-") || it.startsWith("category-") }
            .map { it.removePrefix("tag-").removePrefix("category-").replace("-", " ") }
            .distinct()

        val cover = post.embedded?.featuredMedia?.firstOrNull()?.bestUrl()

        val cleanTitle = cleanHtmlText(post.title.rendered).ifBlank { post.slug }

        // Descrição: excerpt > meta.paginas + meta.formato como fallback
        val cleanDescription = cleanHtmlText(post.excerpt.rendered)
            .takeIf { it.isNotBlank() }
            ?: buildString {
                val paginas = post.meta.paginas
                if (paginas.isNotBlank()) append("Páginas: $paginas")
            }.takeIf { it.isNotBlank() }

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
    // CHAPTERS  (leitor direto = 1 capítulo único)
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
    // PAGES  (busca post via API, pega vdw_gallery_id, busca mídias)
    // ============================================================

    override fun pageListParse(response: Response): List<Page> {
        val url = response.request.url.toString()
        val slug = extractSlug(url)

        // 1. Busca o post pra pegar a lista de IDs da galeria
        val postResp = client.newCall(
            GET("$postsUrl?slug=$slug&per_page=1", headers),
        ).execute()
        val postBody = postResp.body.string()
        postResp.close()

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

        return urls.mapIndexed { i, u -> Page(i, "", u) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)
}
