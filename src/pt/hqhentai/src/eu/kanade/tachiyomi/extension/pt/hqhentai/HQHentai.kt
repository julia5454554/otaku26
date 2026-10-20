package eu.kanade.tachiyomi.extension.pt.hqhentai

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
class HQHentai(
    override val lang: String = "pt-BR",
    override val id: Long = 2025000007L,
) : HttpSource() {

    override val name = "HQ Hentai"
    override val baseUrl = "https://www.hqhentai.com.br"
    override val supportsLatest = true

    // ========== POPULAR (categoria 151 - ⚡Exclusivo, excluindo vídeos e contos) ==========
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/wp-json/wp/v2/posts?_embed&per_page=20&page=$page&categories=151&categories_exclude=75,1219&orderby=date&order=desc", headers)
    override fun popularMangaParse(response: Response) = parseList(response)

    // ========== RECENTES (geral, sem vídeos nem contos) ==========
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/wp-json/wp/v2/posts?_embed&per_page=20&page=$page&categories_exclude=75,1219&orderby=date&order=desc", headers)
    override fun latestUpdatesParse(response: Response) = parseList(response)

    // ========== BUSCA (também sem vídeos nem contos) ==========
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/wp-json/wp/v2/posts?_embed&per_page=20&page=$page&search=$q&categories_exclude=75,1219&orderby=date&order=desc", headers)
    }
    override fun searchMangaParse(response: Response) = parseList(response)

    // ========== LISTA (compartilhado) ==========
    private fun parseList(response: Response): MangasPage {
        if (!response.isSuccessful) return MangasPage(emptyList(), false)
        val body = response.body.string()
        if (body.isBlank()) return MangasPage(emptyList(), false)
        val arr = try {
            JSONArray(body)
        } catch (_: Exception) {
            return MangasPage(emptyList(), false)
        }
        val mangas = mutableListOf<SManga>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            parseListItem(item)?.let { mangas.add(it) }
        }
        val hasNext = response.headers["Link"]?.contains("rel=\"next\"") == true
        return MangasPage(mangas, hasNext)
    }

    private fun parseListItem(json: JSONObject): SManga? {
        val postId = json.optInt("id", 0)
        if (postId == 0) return null
        val titleRaw = json.optJSONObject("title")?.optString("rendered") ?: return null
        val t = Jsoup.parse(titleRaw).text()
        if (t.isBlank()) return null
        val c = getCoverUrl(json)
        return SManga.create().apply {
            this.title = t
            this.url = postId.toString()
            this.thumbnail_url = c
        }
    }

    private fun getCoverUrl(json: JSONObject): String? {
        val embedded = json.optJSONObject("_embedded") ?: return null
        val media = embedded.optJSONArray("wp:featuredmedia") ?: return null
        if (media.length() == 0) return null
        return media.optJSONObject(0)?.optString("source_url")?.takeIf { it.isNotBlank() }
    }

    // ========== DETALHES ==========
    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl/wp-json/wp/v2/posts/${manga.url}?_embed", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val json = JSONObject(response.body.string())
        val postId = json.getInt("id")
        val titleRaw = json.getJSONObject("title").getString("rendered")
        val t = Jsoup.parse(titleRaw).text()
        val d = Jsoup.parse(json.optJSONObject("excerpt")?.optString("rendered").orEmpty()).text()
        val c = getCoverUrl(json)
        val g = extractGenres(json)
        return SManga.create().apply {
            this.title = t
            this.url = postId.toString()
            this.thumbnail_url = c
            this.description = d
            this.genre = g
            this.status = SManga.COMPLETED
        }
    }

    private fun extractGenres(json: JSONObject): String {
        val categories = json.optJSONArray("categories") ?: return ""
        val map = getCategoryMap()
        val out = mutableListOf<String>()
        for (i in 0 until categories.length()) {
            val cid = categories.optInt(i, 0)
            if (cid > 0) map[cid]?.let { out.add(it) }
        }
        return out.joinToString(", ")
    }

    @Volatile private var categoryCache: Map<Int, String>? = null

    private fun getCategoryMap(): Map<Int, String> {
        categoryCache?.let { return it }
        return try {
            val response = client.newCall(GET("$baseUrl/wp-json/wp/v2/categories?per_page=100", headers)).execute()
            val arr = JSONArray(response.body.string())
            val map = mutableMapOf<Int, String>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                map[obj.getInt("id")] = Jsoup.parse(obj.getString("name")).text()
            }
            categoryCache = map
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ========== CAPÍTULOS (1 único por post) ==========
    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl/wp-json/wp/v2/posts/${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val json = JSONObject(response.body.string())
        val postId = json.getInt("id")
        val dateStr = json.optString("date")
        val dateMs = parseDate(dateStr)
        return listOf(
            SChapter.create().apply {
                this.name = "Capítulo Único"
                this.url = postId.toString()
                this.date_upload = dateMs
                this.chapter_number = 1f
            },
        )
    }

    private fun parseDate(s: String): Long {
        if (s.isBlank()) return 0L
        val formats = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )
        for (fmt in formats) {
            try {
                return SimpleDateFormat(fmt, Locale.US).parse(s)?.time ?: continue
            } catch (_: Exception) {}
        }
        return 0L
    }

    // ========== PÁGINAS (via /media?parent= com ORDEM CORRETA) ==========
    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl/wp-json/wp/v2/media?parent=${chapter.url}&per_page=100&orderby=id&order=asc", headers)

    override fun pageListParse(response: Response): List<Page> {
        if (!response.isSuccessful) return emptyList()
        val body = response.body.string()
        if (body.isBlank()) return emptyList()
        val arr = try {
            JSONArray(body)
        } catch (_: Exception) {
            return emptyList()
        }
        val pages = mutableListOf<Page>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("media_type") != "image") continue
            val src = item.optString("source_url")
            if (src.isBlank()) continue
            pages.add(Page(pages.size, "", src))
        }
        return pages
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}
