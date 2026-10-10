package eu.kanade.tachiyomi.extension.pt.nhentainetbr

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Instant

@Source
class NHentaiNetBr(
    override val lang: String = "pt-BR",
    override val id: Long = 0L,
) : KeiSource() {

    override val name = "nhentai.net.br"
    override val baseUrl = "https://nhentai.net.br"
    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(2) { !it.encodedPath.startsWith("/wp-content/uploads/") }
    }

    // ===== Listagem =====
    override suspend fun getPopularManga(page: Int): MangasPage = fetchListing("$baseUrl/popular/", page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchListing("$baseUrl/ultimos/", page)

    private suspend fun fetchListing(listingUrl: String, page: Int): MangasPage {
        val url = if (page > 1) {
            "${listingUrl}page/$page/"
        } else {
            listingUrl
        }
        
        val response = client.get(url)
        val doc = response.asJsoup()
        val mangas = parseMangaList(doc)
        
        return MangasPage(mangas, hasNextPage(doc))
    }

    private fun parseMangaList(document: Document): List<SManga> {
        return document.select("div.thumb-conteudo").mapNotNull { element ->
            val linkElement = element.selectFirst("a[href*='$baseUrl/']:not(.thumbParodiaNome)") ?: return@mapNotNull null
            val title = element.selectFirst(".thumb-titulo")?.text()?.trim() ?: return@mapNotNull null
            val url = linkElement.absUrl("href").toHttpUrl().encodedPath
            val thumb = element.selectFirst("img")?.let { img ->
                img.absUrl("data-src").ifEmpty { img.absUrl("src") }
            } ?: ""

            if (title.isNotEmpty() && url.startsWith("/")) {
                SManga.create().apply {
                    this.url = url
                    this.title = title
                    this.thumbnail_url = thumb
                }
            } else null
        }
    }

    private fun hasNextPage(document: Document): Boolean {
        return document.selectFirst("ul.paginacao li.next a[href]") != null
    }

    // ===== Busca =====
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val urlBuilder = baseUrl.toHttpUrl().newBuilder()
        
        if (page > 1) {
            urlBuilder.addPathSegment("page")
            urlBuilder.addPathSegment(page.toString())
            urlBuilder.addPathSegment("") // Garante a barra no final (trailing slash)
        }
        
        val url = urlBuilder
            .addQueryParameter("s", query.trim())
            .build()
            .toString()
            
        val response = client.get(url)
        val doc = response.asJsoup()
        return MangasPage(parseMangaList(doc), hasNextPage(doc))
    }

    // ===== Detalhes e capítulos via fetchMangaUpdate =====
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val slug = url.pathSegments.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val manga = SManga.create().apply { this.url = "/$slug/" }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false)
            .manga
            .apply { initialized = true }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()

        val galleries = document.select("div.galeriaTabItem")
        val date = document.selectFirst("meta[property=article:published_time]")
            ?.attr("content")
            .toTimestamp()

        val updatedManga = SManga.create().apply {
            url = manga.url
            title = document.selectFirst("h1.post-titulo")?.text()?.trim()
                ?: manga.title
            thumbnail_url = document.selectFirst("div.post-capa img")?.let { img ->
                img.absUrl("src").ifEmpty { img.absUrl("data-src") }
            } ?: manga.thumbnail_url

            description = document.selectFirst("meta[property='og:description']")?.attr("content")
                ?: document.select("ul.post-itens li").joinToString("\n") { it.text().trim() }

            genre = document.select(
                "a[href*='/category/'], a[href*='/tag/'], a[href*='/parodia/'], a[href*='/cor/']",
            ).eachText().distinct().joinToString(", ")

            status = SManga.COMPLETED
            update_strategy = if (galleries.isEmpty()) UpdateStrategy.ONLY_FETCH_ONCE else UpdateStrategy.ALWAYS_UPDATE
        }

        val newChapters = when {
            galleries.isEmpty() -> listOf(
                SChapter.create().apply {
                    url = manga.url
                    name = "Capítulo Único"
                    chapter_number = 1F
                    date_upload = date
                },
            )
            else -> galleries.mapNotNull { it.toSChapter(manga.url, date) }.reversed()
        }

        return SMangaUpdate(updatedManga, newChapters)
    }

    private fun Element.toSChapter(mangaUrl: String, date: Long): SChapter? {
        val galleryId = selectFirst("div.galeriaConteudo[id~=^galeria-\\d+$]")?.id() ?: return null
        val label = selectFirst(".galeriaTabCapitulo")?.text().orEmpty()
        val chapterTitle = selectFirst(".galeriaTabTitulo")?.text().orEmpty()

        return SChapter.create().apply {
            url = "$mangaUrl#$galleryId"
            name = when {
                label.isEmpty() -> chapterTitle.ifEmpty { "Capítulo" }
                chapterTitle.isEmpty() -> label
                else -> "$label: $chapterTitle"
            }
            chapter_number = CHAPTER_NUMBER_REGEX.find(label)?.groupValues?.get(1)?.toFloatOrNull() ?: -1F
            date_upload = date
        }
    }

    // ===== Páginas =====
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val galleryId = chapter.url.substringAfter('#', "")
        val document = client.get(baseUrl + chapter.url.substringBefore('#')).asJsoup()

        val images = when {
            galleryId.isEmpty() -> document.select("ul.post-fotos img")
            else -> document.select("div.galeriaConteudo#$galleryId img")
        }

        return images.mapIndexed { index, img ->
            val imageUrl = img.absUrl("data-src").ifEmpty { img.absUrl("src") }
            Page(index, imageUrl = imageUrl)
        }
    }

    private fun String?.toTimestamp(): Long = this?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() } ?: 0L

    companion object {
        private val CHAPTER_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")
    }
}
