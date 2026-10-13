package eu.kanade.tachiyomi.extension.pt.mangashentais

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
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
import org.jsoup.select.Elements
import java.net.URLEncoder
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MangasHentais : KeiSource() {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient() = rateLimit(5, 1.seconds)

    // ─── Filtros de URL ───────────────────────────────────────────────────

    private val adHostRegex = Regex("(?i)pornojapa\\.com")

    private val videoSlugRegex = Regex(
        "(?i)-(hentai-live-action|live-action|jav-legendado|jav|" +
            "porno-legendado|porno-japa|hentai-gif|anime-hentai|hentai-exclusivo)/",
    )

    private val nonContentPaths = setOf(
        "acesso", "cadastro", "contato", "entrar", "registrar", "login",
        "category", "categoria", "tag", "tags", "author", "wp-json",
        "feed", "page", "serie",
    )

    private fun isAdUrl(href: String): Boolean = adHostRegex.containsMatchIn(href)

    private fun isVideoUrl(href: String): Boolean = videoSlugRegex.containsMatchIn(href)

    private fun isNonContentUrl(href: String): Boolean {
        if (isVideoUrl(href)) return true
        val path = href.removePrefix(baseUrl).trim('/').lowercase()
        if (path.isEmpty()) return true
        return path in nonContentPaths ||
            path.split("/").any { it in nonContentPaths }
    }

    // ─── Busca ────────────────────────────────────────────────────────────

    private fun buildSearchQuery(rawQuery: String): String = URLEncoder.encode(rawQuery.trim(), "UTF-8")

    // ─── Listagem ─────────────────────────────────────────────────────────

    private fun Element.toSManga(): SManga? {
        val a = select("a[href]").firstOrNull {
            val h = it.absUrl("href")
            h.startsWith(baseUrl) &&
                !isAdUrl(h) &&
                !isNonContentUrl(h) &&
                it.selectFirst("img") != null
        } ?: return null

        val href = a.absUrl("href")
        if (href.isEmpty() || isAdUrl(href) || isNonContentUrl(href)) return null

        val title = selectFirst("a.titulo h2")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: a.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        val thumb = a.selectFirst("img.wp-post-image, img.thumb, img")
            ?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }
            .orEmpty()

        return SManga.create().apply {
            this.title = title
            this.thumbnail_url = thumb
            this.url = href.removePrefix(baseUrl)
        }
    }

    private fun parseListPage(document: Document): MangasPage {
        val mangas = document.select("div.video-conteudo")
            .mapNotNull { it.toSManga() }
            .distinctBy { it.url }

        val hasNext = document.selectFirst(
            "a.next.page-numbers[href], " +
                "li.next a[href], " +
                "link[rel=next], " +
                "a[rel=next][href]",
        ) != null

        return MangasPage(mangas, hasNext)
    }

    override suspend fun getPopularManga(page: Int) = parseListPage(client.get("$baseUrl/page/$page/").asJsoup())

    override suspend fun getLatestUpdates(page: Int) = getPopularManga(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)
        val encoded = buildSearchQuery(query)
        val url = if (page == 1) {
            "$baseUrl/?s=$encoded"
        } else {
            "$baseUrl/page/$page/?s=$encoded"
        }
        return parseListPage(client.get(url).asJsoup())
    }

    // ─── Detalhes do mangá ────────────────────────────────────────────────

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.count { it.isNotBlank() } < 1) return null
        val fullUrl = url.toString()
        if (isAdUrl(fullUrl) || isNonContentUrl(fullUrl)) return null
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

        val sections = document.select("div.chapter-section[id^=chapter-]")
        val hasLooseImages = document.select("div.foto img").isNotEmpty()

        // Doujin (cap único): sem chapter-section mas com imagens soltas
        val isSingleChapter = sections.isEmpty() && hasLooseImages

        // Sem capítulos E sem imagens → vídeo/anime/não-mangá
        if (sections.isEmpty() && !hasLooseImages) {
            return SMangaUpdate(
                SManga.create().apply {
                    url = manga.url
                    title = extractTitle(document).ifBlank { "Sem título" }
                    description = "⚠️ Este conteúdo não parece ser mangá."
                    status = SManga.CANCELLED
                },
                emptyList(),
            )
        }

        val tags = document.select("a[rel=tag]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val description = document.selectFirst("meta[property=og:description]")
            ?.attr("content")?.trim().orEmpty()
            .ifBlank {
                document.selectFirst("meta[name=description]")?.attr("content").orEmpty()
            }

        val cover = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()

        return SMangaUpdate(
            SManga.create().apply {
                url = manga.url
                title = extractTitle(document)
                thumbnail_url = cover
                this.description = description
                genre = tags.joinToString(", ")
            },
            parseChapters(sections, manga.url, isSingleChapter),
        )
    }

    private fun extractTitle(document: Document): String {
        document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        val og = document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        if (og.isNotEmpty()) {
            return og.substringBefore(" ⋆").substringBefore(" - Mangas").trim()
        }
        return document.title().substringBefore(" ⋆").trim()
    }

    private fun parseChapters(
        sections: Elements,
        mangaUrl: String,
        singleChapter: Boolean,
    ): List<SChapter> {
        if (singleChapter) {
            return listOf(
                SChapter.create().apply {
                    name = "Capítulo Único"
                    url = mangaUrl
                    date_upload = 0L
                },
            )
        }

        return sections.mapNotNull { sec ->
            val id = sec.id().takeIf { it.startsWith("chapter-") } ?: return@mapNotNull null
            val num = id.removePrefix("chapter-")
            val name = sec.selectFirst("h3")?.text()?.trim().orEmpty()
                .ifBlank { "Capítulo $num" }

            SChapter.create().apply {
                this.name = name
                url = "$mangaUrl#$id"
                date_upload = 0L
            }
        }
    }

    // ─── Páginas do capítulo ──────────────────────────────────────────────

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val rawUrl = chapter.url.substringBefore("#")
        val anchor = chapter.url.substringAfter("#", "")
        val sectionId = anchor.takeIf { it.startsWith("chapter-") }

        val pageUrl = if (rawUrl.startsWith("http")) rawUrl else "$baseUrl$rawUrl"
        val document = client.get(pageUrl).asJsoup()

        val scope: Element = if (sectionId != null) {
            document.selectFirst("div#$sectionId") ?: document
        } else {
            document
        }

        val images = scope.select("div.foto img").mapNotNull { img ->
            val src = img.absUrl("src").ifBlank { img.absUrl("data-src") }
            if (src.isBlank()) return@mapNotNull null
            if (!src.contains("/wp-content/uploads/")) return@mapNotNull null

            val alt = img.attr("alt")
            val cls = img.className()

            // Lixo: créditos, doação, banners
            if (alt.contains("Créditos", ignoreCase = true)) return@mapNotNull null
            if (src.contains("QR-CODE", ignoreCase = true)) return@mapNotNull null
            if (src.contains("DOACAO", ignoreCase = true)) return@mapNotNull null
            if (src.contains("TELEGRAM", ignoreCase = true)) return@mapNotNull null

            // Banners WordPress com alt vazio
            if (alt.isBlank() && cls.contains("wp-image-")) return@mapNotNull null

            src
        }.distinct()

        return images.mapIndexed { i, url ->
            Page(i, url = pageUrl, imageUrl = url)
        }
    }

    // ─── Requests de imagem ───────────────────────────────────────────────

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .header("Referer", page.url)
        .header(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        )
        .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
        .header("Sec-Fetch-Dest", "image")
        .header("Sec-Fetch-Mode", "no-cors")
        .header("Sec-Fetch-Site", "same-origin")
        .build()
}
