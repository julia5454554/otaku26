package eu.kanade.tachiyomi.extension.pt.hotpornphotos

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
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.time.Duration.Companion.seconds

@Source
abstract class HotPornPhotos : KeiSource() {

    override val supportsLatest = false

    override fun OkHttpClient.Builder.configureClient() = rateLimit(3, 1.seconds)

    // ---------- POPULAR (usa a API AJAX do site) ----------
    override suspend fun getPopularManga(page: Int): MangasPage {
        val start = (page - 1) * PAGE_SIZE
        val url = "$baseUrl/categories/get_images.php?categories=popular&start=$start&limit=$PAGE_SIZE"

        val data = client.get(url).parseAs<ApiResponse>()

        val mangas = data.thumbs.map { item ->
            SManga.create().apply {
                title = item.descriptionPt.ifBlank { item.namePt }.ifBlank { "Sem título" }
                thumbnail_url = item.thumbUrl
                // gallery_url começa com /gallery/... → prefixa /pt
                this.url = "/pt${item.galleryUrl}"
            }
        }
        val hasNextPage = data.thumbs.size >= PAGE_SIZE
        return MangasPage(mangas, hasNextPage)
    }

    // ---------- LATEST ----------
    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException("Latest updates not supported")

    // ---------- SEARCH (Corrigida com Headers e Seletor) ----------
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        // O formulário do site usa POST para /search/index.php
        val body = FormBody.Builder()
            .add("searchQuery", query)
            .build()

        val request = Request.Builder()
            .url("$baseUrl/search/index.php")
            .post(body)
            // Headers essenciais para o site não bloquear a busca
            .header("Referer", "$baseUrl/")
            .header("Origin", baseUrl)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        val document = client.newCall(request).execute().use { it.asJsoup() }

        // O HTML retorna <div class='grid-item'> <a href='/gallery/...'>
        val mangas = document.select("div.grid-item > a[href*=/gallery/]").mapNotNull { anchor ->
            val href = anchor.attr("href")
            if (href.isBlank()) return@mapNotNull null

            val img = anchor.selectFirst("img")

            SManga.create().apply {
                title = anchor.attr("title").ifBlank {
                    img?.attr("alt").orEmpty()
                }.trim()

                // A imagem da capa fica no data-src devido ao lazyload
                thumbnail_url = img?.attr("data-src")?.ifBlank { img.attr("src") }

                // Adiciona o /pt na URL para o detalhe abrir no idioma correto
                var cleanUrl = href
                if (!cleanUrl.startsWith("/pt/")) {
                    cleanUrl = "/pt$cleanUrl"
                }
                this.url = cleanUrl
            }
        }.distinctBy { it.url }

        // A busca via POST parece não ter paginação direta na URL, retornamos falso.
        // Se o site tiver paginação via POST, precisaríamos enviar um parâmetro extra (ex: page=2) no FormBody.
        val hasNextPage = false

        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.none { it.isNotBlank() }) return null
        return SManga.create().apply { this.url = url.encodedPath }
    }

    // ---------- DETAILS + CHAPTERS ----------
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        val updated = SManga.create().apply {
            this.url = manga.url
            this.title = document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.selectFirst("h1")?.text().orEmpty()
            this.thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            this.description = document.selectFirst("meta[property=og:description]")?.attr("content")
                ?: document.selectFirst("meta[name=description]")?.attr("content")
        }

        // Galeria única: 1 capítulo virtual sem fetch extra
        val chapterList = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    this.name = "Galeria"
                    this.url = manga.url
                },
            )
        } else {
            emptyList()
        }

        return SMangaUpdate(updated, chapterList)
    }

    // ---------- PAGES ----------
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val document = client.get(chapterUrl).asJsoup()

        // Tenta pegar links da galeria (a.rel-link é o padrão deste site)
        val urls = document.select("#gallery-container a.rel-link, a[href*=.jpg], a[href*=.png], a[href*=.webp]")
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

        return urls.mapIndexed { index, url -> Page(index, url = chapterUrl, imageUrl = url) }
    }

    // ---------- IMAGE ----------
    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        // Adicionado Referer para evitar bloqueio do CDN (cdnth.hotpornphotos.com)
        .header("Referer", "$baseUrl/")
        .build()

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
