package eu.kanade.tachiyomi.extension.pt.nhentaibr

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

@Source
class NHentaiBR(
    override val lang: String = "pt-BR",
    override val id: Long = 2025000006L,
) : HttpSource() {

    override val name = "NHentai BR"
    override val baseUrl = "https://nhentaibr.com"
    override val supportsLatest = true

    private val apiUrl = "$baseUrl/wp-json/wp/v2"
    private val vipCategoryId = 8348
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)

    // ───── Popular = categoria VIP ─────

    override fun popularMangaRequest(page: Int): Request = GET("$apiUrl/posts?categories=$vipCategoryId&per_page=20&page=$page&_embed", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response)

    // ───── Recentes = todos os posts (página principal) ─────

    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/posts?per_page=20&page=$page&_embed", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response)

    // ───── Busca ─────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$apiUrl/posts?search=$encoded&per_page=20&page=$page&_embed", headers)
    }

    override fun searchMangaParse(response: Response) = parseMangaList(response)

    // ───── Parser compartilhado de lista ─────

    private fun parseMangaList(response: Response): MangasPage {
        val posts = JSONArray(response.body!!.string())

        val mangas = mutableListOf<SManga>()
        for (i in 0 until posts.length()) {
            val post = posts.getJSONObject(i)
            val title = post.getJSONObject("title").getString("rendered")
            val link = post.getString("link")

            // Filtro 1: link externo (sponsored)
            if (!link.startsWith(baseUrl)) continue

            // Filtro 2: sem capa = ad/teaser
            val cover = extractCover(post) ?: continue

            mangas.add(
                SManga.create().apply {
                    this.title = Jsoup.parse(title).text()
                    url = link
                    thumbnail_url = cover
                },
            )
        }

        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val totalPages = response.headers["X-WP-TotalPages"]?.toIntOrNull() ?: 1
        return MangasPage(mangas, currentPage < totalPages)
    }

    // ───── Detalhes ─────

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url.trimEnd('/').substringAfterLast("/")
        return GET("$apiUrl/posts?slug=$slug&_embed", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val post = JSONArray(response.body!!.string()).getJSONObject(0)

        val title = post.getJSONObject("title").getString("rendered")
        val excerpt = post.getJSONObject("excerpt").getString("rendered")
        val cover = extractCover(post)

        val genres = post.optJSONArray("class_list")?.let { arr ->
            (0 until arr.length())
                .map { arr.getString(it) }
                .filter { it.startsWith("category-") || it.startsWith("tag-") }
                .map { it.removePrefix("category-").removePrefix("tag-") }
                .distinct()
                .joinToString(", ")
        }

        val localTitle = Jsoup.parse(title).text()
        val localDesc = Jsoup.parse(excerpt).text().takeIf { it.isNotBlank() }

        return SManga.create().apply {
            this.title = localTitle
            thumbnail_url = cover
            description = localDesc
            genre = genres
            initialized = true
        }
    }

    // ───── Capítulos ─────

    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.trimEnd('/').substringAfterLast("/")
        return GET("$apiUrl/posts?slug=$slug", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val post = JSONArray(response.body!!.string()).getJSONObject(0)
        val postId = post.getLong("id")
        val dateStr = post.getString("date")

        return listOf(
            SChapter.create().apply {
                name = "Capítulo Único"
                url = postId.toString()
                date_upload = dateFormat.tryParse(dateStr)
            },
        )
    }

    // ───── Páginas ─────

    override fun pageListRequest(chapter: SChapter): Request = GET("$apiUrl/media?parent=${chapter.url}&per_page=100", headers)

    override fun pageListParse(response: Response): List<Page> {
        val medias = JSONArray(response.body!!.string())

        val urls = mutableListOf<String>()
        for (i in 0 until medias.length()) {
            val obj = medias.getJSONObject(i)
            val url = obj.optString("source_url").takeIf { it.isNotBlank() } ?: continue
            if (Regex("-\\d+x\\d+\\.[a-zA-Z]+$").containsMatchIn(url)) continue
            urls.add(url)
        }

        val groups = mutableMapOf<String, MutableList<String>>()
        for (url in urls) {
            val prefix = extractNumericPrefix(url) ?: continue
            groups.getOrPut(prefix) { mutableListOf() }.add(url)
        }

        // Ordena por NÚMERO (1, 2, 3, ..., 10, 11, ...)
        val pages = mutableListOf<Page>()
        var index = 0
        for (key in groups.keys.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }) {
            val variants = groups.getValue(key)
            val chosen = variants.firstOrNull { it.contains("-scaled.") } ?: variants.first()
            pages.add(Page(index++, "", chosen))
        }
        return pages
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ───── Helpers ─────

    private fun extractCover(post: JSONObject): String? {
        val embedded = post.optJSONObject("_embedded") ?: return null
        val media = embedded.optJSONArray("wp:featuredmedia") ?: return null
        if (media.length() == 0) return null
        return media.getJSONObject(0).optString("source_url").takeIf { it.isNotBlank() }
    }

    private fun extractNumericPrefix(url: String): String? {
        val filename = url.substringAfterLast("/")
        return Regex("^(\\d+)").find(filename)?.groupValues?.get(1)
    }

    private fun SimpleDateFormat.tryParse(value: String): Long = try {
        parse(value)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }
}
