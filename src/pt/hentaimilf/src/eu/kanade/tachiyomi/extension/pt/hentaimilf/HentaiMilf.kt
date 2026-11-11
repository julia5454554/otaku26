package eu.kanade.tachiyomi.extension.pt.hentaimilf

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
abstract class HentaiMilf : KeiSource() {

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

        document.select("li").forEach { item: Element ->
            val titleLink = item.selectFirst("a.titulo") ?: return@forEach
            val title = titleLink.text().trim()
            val href = titleLink.attr("href")

            // BLOQUEIO: anúncio externo ou vídeo/spam interno
            if (isAdOrVideoSlug(href)) return@forEach

            val img = item.selectFirst("div.thumb-conteudo a img")
            val thumb = img?.attr("src") ?: ""

            if (requireHostedThumb) {
                val isHostedOnSite = thumb.startsWith("https://hentaimilf.net") ||
                    thumb.startsWith("http://hentaimilf.net")
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

        val hasNextPage = document.selectFirst("link[rel='next']") != null
        return MangasPage(mangas.distinctBy { it.url }, hasNextPage)
    }

    // ==================== BUSCA ====================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = if (page == 1) {
            "$baseUrl/?s=$query&cat=-1831"
        } else {
            "$baseUrl/page/$page/?s=$query&cat=-1831"
        }
        return parseListing(client.get(url).asJsoup(), requireHostedThumb = false)
    }

    // ==================== FILTROS DE ANÚNCIO / VÍDEO ====================

    /**
     * Um link é considerado anúncio/vídeo se:
     *  - Aponta para outro domínio (ex: pornojapa.com, my.loboclick.com)
     *  - OU tem slug típico de vídeo legendado (porno-legendado, jav-legendado, etc.)
     */
    private fun isAdOrVideoSlug(href: String): Boolean {
        if (href.isBlank()) return true

        // 1) Domínio externo = anúncio
        val isInternal = href.startsWith("/") ||
            href.startsWith("$baseUrl/") ||
            href.startsWith("http://hentaimilf.net/") ||
            href.startsWith("https://www.hentaimilf.net/")

        if (!isInternal) return true

        // 2) Slug de vídeo/anúncio no mesmo site
        val path = href.lowercase()
        return path.contains("porno-legendado") ||
            path.contains("jav-legendado") ||
            path.contains("assistir-") ||
            path.endsWith("-legendado/") ||
            path.contains("-filme-legendado") ||
            path.contains("-serie-legendada")
    }

    private fun hasRealMangaContent(document: Document): Boolean {
        // Multi-cap: <details class="chapter-item">
        if (document.selectFirst("details.chapter-item") != null) return true

        // One-shot com imagens reais (alt preenchido, não é thumb resized)
        return document.select("img.responsive-img").any { img ->
            val alt = img.attr("alt").trim()
            val src = img.attr("src")
            alt.isNotBlank() &&
                src.isNotBlank() &&
                src.contains("/wp-content/uploads/", ignoreCase = true) &&
                !src.contains(Regex("""-\d+x\d+\.(webp|jpe?g|png)$""", RegexOption.IGNORE_CASE))
        }
    }

    // ==================== DETALHES + CAPÍTULOS ====================
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.none { it.isNotBlank() }) return null
        if (isAdOrVideoSlug(url.encodedPath)) return null

        // Pré-check: vídeos/anúncios não têm capítulos nem imagens de página
        val pageUrl = baseUrl + url.encodedPath
        val document = client.get(pageUrl).asJsoup()
        if (!hasRealMangaContent(document)) return null

        return fetchMangaUpdate(
            SManga.create().apply { this.url = url.encodedPath },
            emptyList(),
            true,
            false,
        ).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        val rawTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("h1.entry-title")?.text()
            ?: document.selectFirst("h1")?.text()
            ?: "Sem título"
        val title = rawTitle.removeSuffix(" - Hentai Milf").trim()

        val cover = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.entry-content img")?.attr("src").orEmpty()

        val description = extractDescription(document)

        val genres = document.select("a[rel='tag']").map { it.text().trim() }.filter { it.isNotEmpty() }

        val updated = SManga.create().apply {
            this.url = manga.url
            this.title = title
            this.thumbnail_url = cover
            this.description = description
            this.genre = genres.joinToString(", ")
            this.status = SManga.COMPLETED
            this.update_strategy = UpdateStrategy.ALWAYS_UPDATE
        }

        val chapterList = if (fetchChapters) parseChapters(document, manga.url) else emptyList()
        return SMangaUpdate(updated, chapterList)
    }

    // ==================== DESCRIÇÃO — SOMENTE "Nome Alternativo" ====================
    private fun extractDescription(document: Document): String = document.select("h1, h2, h3, h4, h5, h6, p, li")
        .firstOrNull { el ->
            el.text().trim().startsWith("Nome Alternativo", ignoreCase = true)
        }
        ?.text()
        ?.trim()
        ?.takeIf { it.length in 5..500 }
        .orEmpty()

    // ==================== CAPÍTULOS ====================
    private fun parseChapters(document: Document, mangaUrl: String): List<SChapter> {
        // Multi-cap: <details class="chapter-item">
        val details = document.select("details.chapter-item")
        if (details.isNotEmpty()) {
            return details.mapIndexed { idx, det ->
                val numText = det.selectFirst("span.chapter-number")?.text()?.trim()
                    ?: "Capítulo ${idx + 1}"
                val capNum = Regex("""(?i)cap[íi]tulo\s*(\d+)""")
                    .find(numText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (idx + 1)

                SChapter.create().apply {
                    this.url = "$mangaUrl#cap=$capNum"
                    this.name = numText
                    this.chapter_number = capNum.toFloat()
                }
            }.sortedBy { it.chapter_number }
        }

        // Fallback one-shot: imagens na própria página do mangá
        return listOf(
            SChapter.create().apply {
                this.url = mangaUrl
                this.name = "Capítulo Único"
                this.chapter_number = 1f
            },
        )
    }

    // ==================== PÁGINAS ====================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // --- Multi-cap (#cap=N): imagens no HTML da página do mangá ---
        if (chapter.url.contains("#cap=")) {
            val mangaUrl = baseUrl + chapter.url.substringBefore("#")
            val capNum = chapter.url.substringAfter("#cap=").toIntOrNull() ?: return emptyList()
            val document = client.get(mangaUrl).asJsoup()

            val html = document.outerHtml()
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/")

            val pattern = Regex(
                """https?://[^"'<>\s]+?[-_]cap$capNum[-_](\d+)\.(webp|jpe?g|png)""",
                RegexOption.IGNORE_CASE,
            )

            val urls = pattern.findAll(html)
                .map { it.value }
                .distinct()
                .sortedBy { url ->
                    Regex("""[-_](\d+)\.(webp|jpe?g|png)$""", RegexOption.IGNORE_CASE)
                        .find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }
                .toList()

            if (urls.isNotEmpty()) {
                return urls.mapIndexed { i, u -> Page(i, url = mangaUrl, imageUrl = u) }
            }
            return emptyList()
        }

        // --- One-shot: capítulo aponta pra página do mangá ---
        val chapterUrl = getChapterUrl(chapter)
        val document = client.get(chapterUrl).asJsoup()

        val urls = document.select(
            "div.entry-content img, article img, .manga-page img, img.responsive-img",
        )
            .mapNotNull { extractImageUrl(it) }
            .distinct()

        return urls.mapIndexed { i, u -> Page(i, url = chapterUrl, imageUrl = u) }
    }

    private fun extractImageUrl(img: Element): String? {
        val raw = img.attr("data-src").ifBlank { img.attr("src") }.trim()
        if (raw.isBlank() || raw.startsWith("data:image")) return null
        val abs = if (raw.startsWith("http")) raw else baseUrl + raw

        // Só mídia do WordPress
        if (!abs.contains("/wp-content/uploads/", ignoreCase = true)) return null

        // Thumb resized do WP (capa de relacionadas): [Nome]-248x320.webp
        if (abs.contains(Regex("""-\d+x\d+\.(webp|jpe?g|png)$""", RegexOption.IGNORE_CASE))) return null

        val cls = img.attr("class")
        if (cls.contains("wp-post-image", ignoreCase = true)) return null
        if (cls.contains("thumb", ignoreCase = true)) return null

        // Banner/botão: alt vazio + aspect paisagem
        val alt = img.attr("alt").trim()
        val w = img.attr("width").toIntOrNull() ?: 0
        val h = img.attr("height").toIntOrNull() ?: 0
        if (alt.isBlank() && w > 0 && h > 0 && w > h) return null

        return abs
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
