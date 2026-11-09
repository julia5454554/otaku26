package eu.kanade.tachiyomi.extension.pt.hentaigratis

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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

@Source
abstract class HentaiGratis : KeiSource() {

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
    override suspend fun getPopularManga(page: Int): MangasPage {
        // cat=-1831 exclui a categoria "filmes-hentai" (posts falsos)
        val url = if (page == 1) {
            "$baseUrl/?cat=-1831"
        } else {
            "$baseUrl/page/$page/?cat=-1831"
        }
        return parseListing(client.get(url).asJsoup(), requireHostedThumb = true)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    private fun parseListing(
        document: Document,
        requireHostedThumb: Boolean,
    ): MangasPage {
        val mangas = mutableListOf<SManga>()

        document.select("article").forEach { article: Element ->
            val titleLink = article.selectFirst("h2.entry-title a") ?: return@forEach
            val title = titleLink.text().trim()
            val href = titleLink.attr("href")
            val img = article.selectFirst("div.entry-content img, div.entry-summary img")
            val thumb = img?.attr("src") ?: ""

            if (requireHostedThumb) {
                val isHostedOnSite = thumb.startsWith("https://hentaigratis.biz") ||
                    thumb.startsWith("http://hentaigratis.biz")
                if (title.isBlank() || href.isBlank() || thumb.isBlank() || !isHostedOnSite) {
                    return@forEach
                }
            } else {
                if (title.isBlank() || href.isBlank()) return@forEach
            }

            SManga.create().apply {
                this.title = title
                this.thumbnail_url = thumb
                this.url = href.removePrefix(baseUrl)
            }.let { mangas.add(it) }
        }

        val hasNextPage = document.selectFirst("a.next") != null
        return MangasPage(mangas.distinctBy { it.url }, hasNextPage)
    }

    // ==================== BUSCA ====================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        // cat=-1831 também na busca: esconde posts da categoria "filmes-hentai"
        val url = if (page == 1) {
            "$baseUrl/?s=$query&cat=-1831"
        } else {
            "$baseUrl/page/$page/?s=$query&cat=-1831"
        }
        return parseListing(client.get(url).asJsoup(), requireHostedThumb = false)
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

        val rawTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("h1.entry-title")?.text()
            ?: "Sem título"
        val title = rawTitle.removeSuffix(" - Hentai Grátis").removeSuffix(" - Hentai Grátis").trim()

        val cover = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.entry-content img")?.attr("src") ?: ""

        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst("div.entry-content p")?.text()?.trim() ?: ""

        val genres = mutableListOf<String>()
        document.select("a[rel='tag']").forEach { genres.add(it.text().trim()) }

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

    // ==================== PÁGINAS ====================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val document = client.get(chapterUrl).asJsoup()
        val images = document.select("div.entry-content img")
        val pages = mutableListOf<Page>()
        var index = 0

        val mangaSlug = chapterUrl.substringAfter("hentaigratis.biz/")
            .substringBefore("?")
            .trimEnd('/')
            .split("/")
            .lastOrNull { it.isNotBlank() }
            ?: ""

        val normalizedSlug = normalize(mangaSlug)

        images.forEach { img: Element ->
            val src = img.attr("src").trim()
            if (src.isBlank() || src.startsWith("data:image")) return@forEach

            val fileName = src.substringAfterLast("/").substringBefore("?")
            val normalizedFileName = normalize(fileName)

            if (normalizedSlug.isNotEmpty() && normalizedFileName.contains(normalizedSlug)) {
                pages.add(Page(index++, url = baseUrl, imageUrl = src))
            }
        }

        return pages
    }

    private fun normalize(input: String): String = input.lowercase()
        .replace("-", "")
        .replace("_", "")
        .replace(" ", "")
        .replace(".jpg", "")
        .replace(".png", "")
        .replace(".webp", "")

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
