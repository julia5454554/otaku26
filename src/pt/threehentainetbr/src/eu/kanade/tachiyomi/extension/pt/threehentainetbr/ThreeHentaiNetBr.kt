package eu.kanade.tachiyomi.extension.pt.threehentainetbr

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

@Source
class ThreeHentaiNetBr(
    override val lang: String = "pt-BR",
    override val id: Long = 2024060001L, // Troque por um ID único
) : HttpSource() {

    override val name = "3Hentai.net.br"
    override val baseUrl = "https://3hentai.net.br"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ==================== LISTAGEM ====================

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) baseUrl else "$baseUrl/page/$page/"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
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
                    setUrlWithoutDomain(link.attr("href"))
                }.let { mangas.add(it) }
            }
        }

        val hasNextPage = document.selectFirst("ul.paginacao li.next a") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== BUSCA ====================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (page == 1) {
            "$baseUrl/?s=$query"
        } else {
            "$baseUrl/page/$page/?s=$query"
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== DETALHES ====================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val title = document.selectFirst("h1.post-titulo")?.text()?.trim() ?: "Sem título"
        val cover = document.selectFirst("div.post-capa img")?.attr("abs:src") ?: ""
        val description = ""

        val genres = mutableListOf<String>()
        document.select("ul.post-itens a[rel='tag'], ul.post-itens a[href*='/category/'], ul.post-itens a[href*='/tag/']").forEach { element: Element ->
            val text = element.text().trim()
            if (text.isNotBlank()) genres.add(text)
        }

        return SManga.create().apply {
            this.title = title
            this.thumbnail_url = cover
            this.description = description
            this.genre = genres.joinToString(", ")
            this.status = SManga.COMPLETED
            this.author = ""
            update_strategy = UpdateStrategy.ALWAYS_UPDATE
        }
    }

    // ==================== CAPÍTULOS ====================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        // Extrai o ID do post do botão de favorito (atributo data-id)
        val postId = document.selectFirst("a[data-id]")?.attr("data-id")?.toLongOrNull()

        val chapterUrl = if (postId != null) {
            "$baseUrl/?p=$postId" // sempre aponta para a página HTML do mangá
        } else {
            response.request.url.toString()
        }

        return listOf(
            SChapter.create().apply {
                name = "Capítulo Único"
                chapter_number = 1f
                setUrlWithoutDomain(chapterUrl)
            },
        )
    }

    // ==================== PÁGINAS ====================

    override fun pageListParse(response: Response): List<Page> {
        val pages = mutableListOf<Page>()
        var index = 0

        val document = response.asJsoup()

        // 1ª tentativa: lista estática ul.post-fotos
        val staticImages = document.select("ul.post-fotos img")
        if (staticImages.isNotEmpty()) {
            staticImages.forEach { img: Element ->
                val src = extractImageUrl(img)
                if (src.isNotBlank()) {
                    pages.add(Page(index++, url = baseUrl, imageUrl = src))
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
                    pages.add(Page(index++, url = baseUrl, imageUrl = src))
                }
            }
            if (pages.isNotEmpty()) {
                return pages
            }
        }

        // 3ª tentativa: extrair postId do HTML e consultar API de mídia
        val postId = document.selectFirst("a[data-id]")?.attr("data-id")?.toLongOrNull()
        if (postId != null) {
            val mediaPages = fetchPagesFromMediaApi(postId)
            if (mediaPages.isNotEmpty()) {
                return mediaPages
            }
        }

        // 4ª tentativa: se a resposta for JSON da API do post (fallback)
        val body = response.body.string()
        if (body.trim().startsWith("{")) {
            try {
                val json = JSONObject(body)
                val contentHtml = json.optString("content.rendered", "")
                if (contentHtml.isNotBlank()) {
                    val doc = Jsoup.parse(contentHtml)
                    val images = doc.select("img")
                    val startIdx = if (images.size > 1) 1 else 0
                    for (i in startIdx until images.size) {
                        val src = extractImageUrl(images[i])
                        if (src.isNotBlank()) {
                            pages.add(Page(index++, url = baseUrl, imageUrl = src))
                        }
                    }
                }
            } catch (e: Exception) {
                // ignora
            }
        }

        return pages
    }

    // Função para buscar imagens via API de mídia do WordPress
    private fun fetchPagesFromMediaApi(postId: Long): List<Page> {
        val url = "$baseUrl/wp-json/wp/v2/media?parent=$postId&per_page=100"
        val request = GET(url, headersBuilder().set("Referer", "$baseUrl/?p=$postId").build())
        return try {
            val response = client.newCall(request).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    val jsonArray = JSONArray(resp.body!!.string())
                    val pages = mutableListOf<Page>()
                    var index = 0
                    // Se houver mais de uma imagem, a primeira é a capa e deve ser ignorada
                    val startIndex = if (jsonArray.length() > 1) 1 else 0
                    for (i in startIndex until jsonArray.length()) {
                        val media = jsonArray.getJSONObject(i)
                        val imageUrl = media.optString("source_url")
                        if (imageUrl.isNotBlank()) {
                            pages.add(Page(index++, url = baseUrl, imageUrl = imageUrl))
                        }
                    }
                    pages
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
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

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Referer", page.url)
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }
}
