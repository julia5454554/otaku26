package eu.kanade.tachiyomi.extension.pt.hotpornphotos

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable

@Source
abstract class HotPornPhotos : HttpSource() {

    override val supportsLatest = false

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(3)
        .build()

    // ---------- POPULAR (usa a API AJAX do site) ----------
    override fun popularMangaRequest(page: Int): Request {
        val start = (page - 1) * PAGE_SIZE
        return GET(
            "$baseUrl/categories/get_images.php?categories=popular&start=$start&limit=$PAGE_SIZE",
            headers,
        )
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<ApiResponse>()
        val mangas = data.thumbs.map { item ->
            SManga.create().apply {
                title = item.descriptionPt.ifBlank { item.namePt }.ifBlank { "Sem título" }
                thumbnail_url = item.thumbUrl
                // gallery_url começa com /gallery/... → prefixa /pt
                setUrlWithoutDomain("/pt${item.galleryUrl}")
            }
        }
        val hasNextPage = data.thumbs.size >= PAGE_SIZE
        return MangasPage(mangas, hasNextPage)
    }

    // ---------- LATEST ----------
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // ---------- SEARCH ----------
    // A busca do site é POST em /search/index.php e devolve HTML.
    // Reaproveita o parser HTML simples.
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val body = FormBody.Builder()
            .add("searchQuery", query)
            .build()
        return POST("$baseUrl/search/index.php", headers, body)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("#gallery-container .grid-item").mapNotNull { element ->
            val anchor = element.selectFirst("a[href*=/gallery/]") ?: return@mapNotNull null
            val href = anchor.absUrl("href")
            if (href.isBlank()) return@mapNotNull null
            SManga.create().apply {
                title = anchor.attr("title").ifBlank {
                    anchor.selectFirst("img")?.attr("alt").orEmpty()
                }
                thumbnail_url = anchor.selectFirst("img")?.let { img ->
                    img.absUrl("src").ifBlank { img.absUrl("data-src") }
                }
                setUrlWithoutDomain(href)
            }
        }
        return MangasPage(mangas, false)
    }

    // ---------- DETAILS ----------
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.selectFirst("h1")?.text().orEmpty()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            description = document.selectFirst("meta[property=og:description]")?.attr("content")
                ?: document.selectFirst("meta[name=description]")?.attr("content")
        }
    }

    // ---------- CHAPTERS ----------
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.just(
        listOf(
            SChapter.create().apply {
                name = "Galeria"
                url = manga.url
            },
        ),
    )

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    // ---------- PAGES ----------
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val urls = document.select("#gallery-container a.rel-link")
            .mapNotNull { a ->
                val href = a.absUrl("href").ifBlank { a.attr("data-src") }
                if (href.isBlank()) return@mapNotNull null
                val lower = href.lowercase()
                if (!lower.contains(".jpg") && !lower.contains(".jpeg") &&
                    !lower.contains(".png") && !lower.contains(".webp")
                ) {
                    return@mapNotNull null
                }
                href
            }
            .distinct()

        return urls.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList(): FilterList = FilterList()

    // ---------- DTOs da API ----------
    @Serializable
    private data class ApiResponse(val thumbs: List<Thumb> = emptyList())

    @Serializable
    private data class Thumb(
        @SerialName("gallery_url") val galleryUrl: String,
        @SerialName("thumb_url") val thumbUrl: String,
        @SerialName("description_pt") val descriptionPt: String = "",
        @SerialName("name_pt") val namePt: String = "",
        val cid: String = "",
    )

    companion object {
        private const val PAGE_SIZE = 20
    }
}
