package eu.kanade.tachiyomi.extension.pt.pornojapa

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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import kotlin.time.Duration.Companion.seconds

@Source
abstract class PornoJapa : KeiSource() {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2, 1.seconds)
        .addInterceptor { chain ->
            val req = chain.request()
            val builder = req.newBuilder()
            if (req.header("User-Agent") == null) builder.header("User-Agent", UA)
            if (req.header("Referer") == null) builder.header("Referer", "$baseUrl/")
            chain.proceed(builder.build())
        }

    // ==================== LISTAGEM (HTML) ====================
    private fun categoryUrl(slug: String, page: Int): String = if (page == 1) {
        "$baseUrl/categoria/$slug/"
    } else {
        "$baseUrl/categoria/$slug/page/$page/"
    }

    // Popular = Fotos Hentai
    override suspend fun getPopularManga(page: Int): MangasPage = parseListing(client.get(categoryUrl("fotos-hentai", page)).asJsoup())

    // Recentes = Mangás Hentais (igual a antes)
    override suspend fun getLatestUpdates(page: Int): MangasPage = parseListing(client.get(categoryUrl("mangas-hentais", page)).asJsoup())

    private fun parseListing(document: Document): MangasPage {
        val mangas = mutableListOf<SManga>()
        val seenUrls = mutableSetOf<String>()

        // O site usa uma lista <ul> com <li><a href="...">Titulo</a></li>
        document.select("li a[href]").forEach { a ->
            val href = a.attr("href")
            val title = a.text().trim()

            // Filtra menus, páginas de sistema e links externos
            if (!href.startsWith("$baseUrl/") ||
                href.contains("/categoria/") ||
                href.contains("/assinar") ||
                href.contains("/acesso") ||
                href.contains("/contato") ||
                href.contains("/duvidas") ||
                href.contains("/termos") ||
                href.contains("/privacidade") ||
                href.contains("/dmca") ||
                href == "$baseUrl/" ||
                title.isBlank() ||
                title.contains("ASSINAR", ignoreCase = true) ||
                title.contains("VER MAIS", ignoreCase = true)
            ) {
                return@forEach
            }

            if (seenUrls.add(href)) {
                SManga.create().apply {
                    this.title = title
                    this.url = href.removePrefix(baseUrl)
                    this.thumbnail_url = a.selectFirst("img")?.attr("src").orEmpty()
                }.let { mangas.add(it) }
            }
        }

        val hasNext = document.selectFirst("link[rel='next']") != null ||
            document.select("a").any { it.text().contains("Próximo", ignoreCase = true) }

        return MangasPage(mangas, hasNext)
    }

    // ==================== BUSCA (HTML) ====================
    // Campo vazio ou "." = categoria Fotos. Com outro texto = busca normal no site.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val q = query.trim()
        if (q.isEmpty() || q == ".") {
            return parseListing(client.get(categoryUrl("fotos", page)).asJsoup())
        }
        val encoded = URLEncoder.encode(q, "UTF-8")
        val url = if (page == 1) {
            "$baseUrl/?s=$encoded"
        } else {
            "$baseUrl/page/$page/?s=$encoded"
        }
        return parseListing(client.get(url).asJsoup())
    }

    // ==================== DETALHES (HTML) ====================
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.none { it.isNotBlank() }) return null
        return fetchMangaUpdate(
            SManga.create().apply { this.url = url.encodedPath },
            emptyList(),
            true,
            true,
        ).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val cover = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim().orEmpty()

        val updated = SManga.create().apply {
            this.url = manga.url
            this.title = title.ifBlank { manga.title }
            this.thumbnail_url = cover
            this.description = description
            this.status = SManga.COMPLETED
            this.update_strategy = UpdateStrategy.ALWAYS_UPDATE
        }

        // É one-shot. Criamos um capítulo que aponta para o próprio post.
        val chapterList = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    this.url = manga.url
                    this.name = "Capítulo Único"
                    this.chapter_number = 1f
                },
            )
        } else {
            emptyList()
        }

        return SMangaUpdate(updated, chapterList)
    }

    // ==================== PÁGINAS (JSON API) ====================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)

        // Pega o slug (última parte da URL)
        val slug = chapter.url.trim('/').substringAfterLast('/')

        val apiUrl = "$baseUrl/wp-json/wp/v2/posts?slug=$slug"

        // 1) Tenta pegar as imagens via JSON REST API
        val htmlContent = try {
            val responseBody = client.get(apiUrl).body.string()
            val jsonArray = JSONArray(responseBody)
            if (jsonArray.length() > 0) {
                jsonArray.getJSONObject(0).getJSONObject("content").getString("rendered")
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }

        // 2) Fallback: HTML normal do post
        val finalHtml = if (htmlContent.isNotBlank()) {
            htmlContent
        } else {
            val doc = client.get(chapterUrl).asJsoup()
            doc.selectFirst("div.post-conteudo")?.html() ?: ""
        }

        if (finalHtml.isBlank()) return emptyList()

        // 3) Jsoup no HTML obtido
        val doc = Jsoup.parse(finalHtml)

        val urls = doc.select("img")
            .mapNotNull { img ->
                val src = img.attr("data-src").ifBlank { img.attr("src") }.trim()
                if (src.isBlank() || src.startsWith("data:image")) return@mapNotNull null

                val abs = if (src.startsWith("http")) src else baseUrl + src

                // Limpeza de lixo (banners, ads, imagens de sistema)
                if (abs.contains("histats.com") ||
                    abs.contains("search.png") ||
                    abs.contains("logo") ||
                    abs.contains("banner") ||
                    abs.contains("imgur.com") ||
                    !abs.contains("/wp-content/uploads/")
                ) {
                    return@mapNotNull null
                }

                abs
            }
            .distinct()

        return urls.mapIndexed { i, u -> Page(i, url = chapterUrl, imageUrl = u) }
    }

    // ==================== IMAGEM ====================
    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .header("Referer", page.url) // Necessário para não dar 403 Forbidden
        .header("User-Agent", UA)
        .build()

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    }
}
