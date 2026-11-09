package eu.kanade.tachiyomi.extension.pt.hqhentai

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class HQHentai : KeiSource() {

    override val supportsLatest = true

    // Interceptor para garantir UA/Referer em chamadas que não usam o default do KeiSource
    override fun OkHttpClient.Builder.configureClient() = addInterceptor { chain ->
        val req = chain.request()
        val builder = req.newBuilder()
        if (req.header("User-Agent") == null) builder.header("User-Agent", UA)
        if (req.header("Referer") == null) builder.header("Referer", "$baseUrl/")
        chain.proceed(builder.build())
    }

    // ========== POPULAR (categoria 151 - ⚡Exclusivo, excluindo vídeos e contos) ==========
    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/wp-json/wp/v2/posts?_embed&per_page=20&page=$page&categories=151&categories_exclude=75,1219&orderby=date&order=desc"
        return parseList(client.get(url))
    }

    // ========== RECENTES (geral, sem vídeos nem contos) ==========
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/wp-json/wp/v2/posts?_embed&per_page=20&page=$page&categories_exclude=75,1219&orderby=date&order=desc"
        return parseList(client.get(url))
    }

    // ========== BUSCA (também sem vídeos nem contos) ==========
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/wp-json/wp/v2/posts?_embed&per_page=20&page=$page&search=$q&categories_exclude=75,1219&orderby=date&order=desc"
        return parseList(client.get(url))
    }

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

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.none { it.isNotBlank() }) return null
        return fetchMangaUpdate(
            SManga.create().apply { this.url = url.encodedPath },
            emptyList(),
            true,
            false,
        ).manga
    }

    // ========== DETALHES + CAPÍTULOS ==========
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val url = "$baseUrl/wp-json/wp/v2/posts/${manga.url}?_embed"
        val response = client.get(url)
        val json = JSONObject(response.body.string())
        val postId = json.getInt("id")
        val titleRaw = json.getJSONObject("title").getString("rendered")
        val t = Jsoup.parse(titleRaw).text()
        val d = Jsoup.parse(json.optJSONObject("excerpt")?.optString("rendered").orEmpty()).text()
        val c = getCoverUrl(json)
        val g = extractGenres(json)

        val updated = SManga.create().apply {
            this.title = t
            this.url = postId.toString()
            this.thumbnail_url = c
            this.description = d
            this.genre = g
            this.status = SManga.COMPLETED
        }

        val chapterList = if (fetchChapters) {
            val dateStr = json.optString("date")
            val dateMs = parseDate(dateStr)
            listOf(
                SChapter.create().apply {
                    this.name = "Capítulo Único"
                    this.url = postId.toString()
                    this.date_upload = dateMs
                    this.chapter_number = 1f
                },
            )
        } else {
            emptyList()
        }

        return SMangaUpdate(updated, chapterList)
    }

    private suspend fun extractGenres(json: JSONObject): String {
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

    private suspend fun getCategoryMap(): Map<Int, String> {
        categoryCache?.let { return it }
        return try {
            val response = client.get("$baseUrl/wp-json/wp/v2/categories?per_page=100")
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
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = "$baseUrl/wp-json/wp/v2/media?parent=${chapter.url}&per_page=100&orderby=id&order=asc"
        val response = client.get(url)

        // ---------- 1) CAMINHO PREMIUM (INTOCADO) ----------
        if (response.isSuccessful) {
            val body = response.body.string()
            if (body.isNotBlank()) {
                val arr = try {
                    JSONArray(body)
                } catch (_: Exception) {
                    null
                }
                if (arr != null) {
                    val pages = mutableListOf<Page>()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        if (item.optString("media_type") != "image") continue
                        val src = item.optString("source_url")
                        if (src.isBlank()) continue
                        pages.add(Page(pages.size, chapter.url, src))
                    }
                    if (pages.isNotEmpty()) return pages // Premium carregou, encerra aqui.
                }
            }
        }

        // ---------- 2) CAMINHO FREE (só roda se o Premium voltou vazio) ----------
        val postId = chapter.url.toIntOrNull() ?: return emptyList()
        return fetchFreeGallery(postId)
    }

    private suspend fun fetchFreeGallery(postId: Int): List<Page> {
        val ids = try {
            val resp = client.get("$baseUrl/wp-json/wp/v2/posts/$postId?_fields=id,meta")
            if (!resp.isSuccessful) return emptyList()

            val json = JSONObject(resp.body.string())
            val meta = json.optJSONObject("meta") ?: return emptyList()
            val arr = meta.optJSONArray("vdw_gallery_id") ?: return emptyList()

            val out = mutableListOf<Int>()
            for (i in 0 until arr.length()) {
                val id = arr.optInt(i, 0)
                if (id > 0) out.add(id)
            }
            out
        } catch (_: Exception) {
            return emptyList()
        }

        if (ids.isEmpty()) return emptyList()
        return fetchMediaByIds(ids)
    }

    private suspend fun fetchMediaByIds(ids: List<Int>): List<Page> {
        val map = mutableMapOf<Int, String>()
        ids.chunked(100).forEach { chunk ->
            try {
                val resp = client.get("$baseUrl/wp-json/wp/v2/media?include=${chunk.joinToString(",")}&per_page=100")
                if (!resp.isSuccessful) return@forEach
                val arr = JSONArray(resp.body.string())
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    if (item.optString("media_type") != "image") continue
                    val src = item.optString("source_url")
                    if (src.isNotBlank()) map[item.optInt("id")] = src
                }
            } catch (_: Exception) {}
        }

        // Preserva a ordem exata do array vdw_gallery_id
        val pages = mutableListOf<Page>()
        for (id in ids) {
            val url = map[id] ?: continue
            pages.add(Page(pages.size, "", url))
        }
        return pages
    }

    // ========== IMAGEM ==========
    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .header("Referer", "$baseUrl/")
        .header("User-Agent", UA)
        .build()

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    }
}
