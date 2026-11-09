package eu.kanade.tachiyomi.extension.pt.piratahq

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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

@Source
abstract class PirataHq : KeiSource() {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2, 1.seconds)
        .addInterceptor { chain ->
            val req = chain.request()
            val builder = req.newBuilder()
            if (req.header("User-Agent") == null) builder.header("User-Agent", UA)
            if (req.header("Referer") == null) builder.header("Referer", "$baseUrl/")
            chain.proceed(builder.build())
        }

    // ==================== EXTRAÇÃO DE URL DE IMAGEM ====================
    private fun extractImageUrl(element: Element): String {
        val raw = element.attr("data-lazy-src")
            .ifEmpty { element.attr("data-src") }
            .ifEmpty { element.attr("abs:src") }
            .ifEmpty { element.attr("src") }

        if (raw.isEmpty()) return ""

        return if (raw.startsWith("http://") || raw.startsWith("https://")) {
            raw
        } else {
            baseUrl + raw.removePrefix("/")
        }
    }

    // ==================== FILTRO DE TEASERS ====================
    private fun isTeaserPost(contentHtml: String): Boolean {
        val doc = Jsoup.parse(contentHtml)
        val textContent = doc.text().lowercase()

        // 1. Palavras-chave típicas de posts que redirecionam
        val teaserKeywords = listOf(
            "ver completo",
            "veja completo",
            "site oficial",
            "continuar lendo",
            "leia o resto",
            "leia mais no site",
            "acesse o site",
            "publicidade",
        )
        if (teaserKeywords.any { textContent.contains(it) }) {
            return true
        }

        // 2. Links externos para sites conhecidos de redirecionamento (Confirmado via Termux)
        val teaserDomains = listOf(
            "tufos.com.br",
            "pervcomix.com",
            "hentaiputa.com",
            "meuhentai.com",
            "nudelas.com",
            "linkrosa",
            "bit.ly",
        )
        val hasExternalLink = doc.select("a[href]").any { a ->
            val href = a.absUrl("href").lowercase()
            teaserDomains.any { domain -> href.contains(domain) }
        }
        if (hasExternalLink) {
            return true
        }

        return false
    }

    // ==================== LISTAGEM (API REST) ====================
    private fun parseListing(response: Response): MangasPage {
        val jsonArray = JSONArray(response.body.string())
        val mangas = mutableListOf<SManga>()

        val adKeywords = listOf(
            "download", "grátis", "acesse", "clique", "patrocinado",
            "publicidade", "site", "anúncio", "ads", "vazou", "torrent",
        )

        for (i in 0 until jsonArray.length()) {
            val post = jsonArray.getJSONObject(i)
            val id = post.getInt("id")
            val title = Jsoup.parse(post.getJSONObject("title").getString("rendered")).text()
            val link = post.getString("link")

            if (!link.contains("piratahq.com.br")) continue

            val lowerTitle = title.lowercase()
            if (adKeywords.any { lowerTitle.contains(it) }) continue

            val contentHtml = post.getJSONObject("content").getString("rendered")
            val doc = Jsoup.parse(contentHtml)
            val images = doc.select("img")

            // FILTRO 1: Menos de 2 imagens
            if (images.size < 2) continue

            // FILTRO 2: É um post "isca" (teaser)?
            if (isTeaserPost(contentHtml)) continue

            val apiUrl = "/wp-json/wp/v2/posts/$id"
            val thumb = post.optString("jetpack_featured_media_url", "").ifEmpty {
                images.firstOrNull()?.let { extractImageUrl(it) } ?: ""
            }

            SManga.create().apply {
                this.title = title
                this.thumbnail_url = thumb
                this.url = apiUrl
            }.let { mangas.add(it) }
        }

        val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 1
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = currentPage < totalPages

        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/wp-json/wp/v2/posts".toHttpUrl().newBuilder()
            .addQueryParameter("per_page", "20")
            .addQueryParameter("page", page.toString())
            .build()
        return parseListing(client.get(url.toString()))
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    // ==================== BUSCA (API REST) ====================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/wp-json/wp/v2/posts".toHttpUrl().newBuilder()
            .addQueryParameter("search", query.trim())
            .addQueryParameter("per_page", "20")
            .addQueryParameter("page", page.toString())
            .build()
        return parseListing(client.get(url.toString()))
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

    // ==================== DETALHES + CAPÍTULOS (API REST) ====================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get(getMangaUrl(manga))
        val post = JSONObject(response.body.string())

        val contentHtml = post.getJSONObject("content").getString("rendered")

        // Trava extra: Se tentar abrir direto o post, também bloqueia.
        if (isTeaserPost(contentHtml)) {
            throw Exception("PirataHQ: Post com redirecionamento externo (Teaser) — ignorado")
        }

        val title = Jsoup.parse(post.getJSONObject("title").getString("rendered")).text()
        val thumb = post.optString("jetpack_featured_media_url", "").ifEmpty {
            val doc = Jsoup.parse(contentHtml)
            doc.selectFirst("img")?.let { extractImageUrl(it) } ?: ""
        }
        val description = Jsoup.parse(post.getJSONObject("excerpt").getString("rendered")).text().trim()

        val updated = SManga.create().apply {
            this.url = manga.url
            this.title = title
            this.thumbnail_url = thumb
            this.description = description
            this.status = SManga.COMPLETED
            this.genre = ""
            this.author = ""
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

    // ==================== PÁGINAS (API REST) ====================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(getChapterUrl(chapter))
        val post = JSONObject(response.body.string())
        val contentHtml = post.getJSONObject("content").getString("rendered")
        val doc = Jsoup.parse(contentHtml)

        // Pega todas as imagens
        val images = doc.select("img")
        val pages = mutableListOf<Page>()
        var index = 0

        images.forEach { img ->
            val src = extractImageUrl(img)

            // Filtro extra: Ignora a imagem de banner (se o nome do arquivo ou o alt indicar redirecionamento)
            val alt = img.attr("alt").lowercase()
            val isBanner = src.lowercase().contains("completo") || src.lowercase().contains("publicidade") ||
                src.lowercase().contains("site-oficial") || src.lowercase().contains("banner") ||
                alt.contains("completo") || alt.contains("site oficial")

            if (src.isNotEmpty() && !src.startsWith("data:image") && !isBanner) {
                pages.add(Page(index++, url = baseUrl, imageUrl = src))
            }
        }

        return pages
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
