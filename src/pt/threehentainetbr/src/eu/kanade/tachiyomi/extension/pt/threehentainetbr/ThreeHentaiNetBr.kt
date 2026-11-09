package eu.kanade.tachiyomi.extension.pt.threehentainetbr

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
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

@Source
abstract class ThreeHentaiNetBr : KeiSource() {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2, 1.seconds)
        .addInterceptor { chain ->
            val req = chain.request()
            val builder = req.newBuilder()
            if (req.header("User-Agent") == null) builder.header("User-Agent", UA)
            if (req.header("Referer") == null) builder.header("Referer", "$baseUrl/")
            chain.proceed(builder.build())
        }

    // ==================== LISTAGEM ====================
    private fun parseListing(document: Document): MangasPage {
        val mangas = mutableListOf<SManga>()

        document.select("div.lista li").forEach { li: Element ->
            val link = li.selectFirst("a[href*='3hentai.net.br']:has(img)") ?: return@forEach
            val title = link.attr("title").ifBlank {
                link.selectFirst("span.tituloConteudo")?.text()?.trim() ?: ""
            }
            val thumb = link.selectFirst("img")?.attr("abs:src") ?: ""

            if (title.isNotBlank() && thumb.isNotBlank()) {
                SManga.create().apply {
                    this.title = title
                    this.thumbnail_url = thumb
                    this.url = link.attr("href").removePrefix(baseUrl)
                }.let { mangas.add(it) }
            }
        }

        val hasNextPage = document.selectFirst("ul.paginacao li.next a") != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page == 1) baseUrl else "$baseUrl/page/$page/"
        return parseListing(client.get(url).asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    // ==================== BUSCA ====================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = if (page == 1) {
            "$baseUrl/?s=$query"
        } else {
            "$baseUrl/page/$page/?s=$query"
        }
        return parseListing(client.get(url).asJsoup())
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

    // ==================== DETALHES + CAPÍTULOS ====================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val title = document.selectFirst("h1.post-titulo")?.text()?.trim() ?: "Sem título"
        val cover = document.selectFirst("div.post-capa img")?.attr("abs:src") ?: ""
        val description = ""

        val genres = mutableListOf<String>()
        document.select("ul.post-itens a[rel='tag'], ul.post-itens a[href*='/category/'], ul.post-itens a[href*='/tag/']").forEach { element: Element ->
            val text = element.text().trim()
            if (text.isNotBlank()) genres.add(text)
        }

        val updated = SManga.create().apply {
            this.url = manga.url
            this.title = title
            this.thumbnail_url = cover
            this.description = description
            this.genre = genres.joinToString(", ")
            this.status = SManga.COMPLETED
            this.author = ""
            this.update_strategy = UpdateStrategy.ALWAYS_UPDATE
        }

        val chapterList = if (fetchChapters) {
            val postId = document.selectFirst("a[data-id]")?.attr("data-id")?.toLongOrNull()
            val chapterUrl = if (postId != null) "/?p=$postId" else manga.url

            listOf(
                SChapter.create().apply {
                    name = "Capítulo Único"
                    chapter_number = 1f
                    this.url = chapterUrl
                },
            )
        } else {
            emptyList()
        }

        return SMangaUpdate(updated, chapterList)
    }

    // ==================== PÁGINAS ====================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val response = client.get(chapterUrl)
        val bodyStr = response.body.string()
        val document = Jsoup.parse(bodyStr, chapterUrl)

        val pages = mutableListOf<Page>()
        var index = 0

        // 1ª tentativa: lista estática ul.post-fotos
        val staticImages = document.select("ul.post-fotos img")
        if (staticImages.isNotEmpty()) {
            staticImages.forEach { img: Element ->
                val src = extractImageUrl(img)
                if (src.isNotBlank()) {
                    pages.add(Page(index++, url = chapterUrl, imageUrl = src))
                }
            }
            return pages
        }

        // 2ª tentativa: outras áreas estáticas
        val otherImages = document.select("div.galeriaConteudo img, div.galeriaHtml img, div.post-conteudo img")
        if (otherImages.isNotEmpty()) {
            otherImages.forEach { img: Element ->
                val src = extractImageUrl(img)
                if (src.isNotBlank()) {
                    pages.add(Page(index++, url = chapterUrl, imageUrl = src))
                }
            }
            if (pages.isNotEmpty()) {
                return pages
            }
        }

        // 3ª tentativa: extrair postId do HTML e consultar API de mídia
        val postId = document.selectFirst("a[data-id]")?.attr("data-id")?.toLongOrNull()
        if (postId != null) {
            val mediaPages = fetchPagesFromMediaApi(postId, chapterUrl)
            if (mediaPages.isNotEmpty()) {
                return mediaPages
            }
        }

        // 4ª tentativa: se a resposta for JSON da API do post (fallback)
        if (bodyStr.trim().startsWith("{")) {
            try {
                val json = JSONObject(bodyStr)
                val contentHtml = json.optString("content.rendered", "")
                if (contentHtml.isNotBlank()) {
                    val doc = Jsoup.parse(contentHtml)
                    val images = doc.select("img")
                    val startIdx = if (images.size > 1) 1 else 0
                    for (i in startIdx until images.size) {
                        val src = extractImageUrl(images[i])
                        if (src.isNotBlank()) {
                            pages.add(Page(index++, url = chapterUrl, imageUrl = src))
                        }
                    }
                }
            } catch (_: Exception) {
                // ignora
            }
        }

        return pages
    }

    // Função para buscar imagens via API de mídia do WordPress
    private suspend fun fetchPagesFromMediaApi(postId: Long, chapterUrl: String): List<Page> {
        val url = "$baseUrl/wp-json/wp/v2/media?parent=$postId&per_page=100"
        return try {
            val resp = client.get(url)
            if (resp.isSuccessful) {
                val jsonArray = JSONArray(resp.body.string())
                val pages = mutableListOf<Page>()
                var index = 0
                // Se houver mais de uma imagem, a primeira é a capa e deve ser ignorada
                val startIndex = if (jsonArray.length() > 1) 1 else 0
                for (i in startIndex until jsonArray.length()) {
                    val media = jsonArray.getJSONObject(i)
                    val imageUrl = media.optString("source_url")
                    if (imageUrl.isNotBlank()) {
                        pages.add(Page(index++, url = chapterUrl, imageUrl = imageUrl))
                    }
                }
                pages
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Função auxiliar para extrair URL de imagem considerando lazy loading
    private fun extractImageUrl(img: Element): String {
        val raw = img.attr("data-lazy-src")
            .ifBlank { img.attr("data-src") }
            .ifBlank { img.attr("abs:src") }
            .ifBlank { img.attr("src") }
        if (raw.isEmpty()) return ""
        return if (raw.startsWith("http://") || raw.startsWith("https://")) {
            raw
        } else {
            baseUrl + raw.removePrefix("/")
        }
    }

    // ==================== IMAGEM ====================
    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .header("Referer", page.url)
        .header("User-Agent", UA)
        .build()

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    }
}
