package eu.kanade.tachiyomi.extension.pt.revistasequadrinhos

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element
import kotlin.time.Instant

@Source
abstract class RevistasEQuadrinhos : KeiSource() {

    // ============================== Popular ==============================
    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("category")
            addPathSegment("popular-comics")
            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }
        }.build()

        return fetchMangaList(url)
    }

    private suspend fun fetchMangaList(url: HttpUrl): MangasPage {
        val document = client.get(url).asJsoup()

        val mangas = document.select("ul.videos > li").map { element ->
            val a = element.selectFirst("a.titulo") ?: throw Exception("Manga URL is mandatory")
            val img = element.selectFirst("div.thumb-conteudo img")

            SManga.create().apply {
                title = a.text()
                setUrlWithoutDomain(a.attr("href"))
                thumbnail_url = img?.attr("abs:src")
            }
        }

        val hasNextPage = document.selectFirst(".paginacao li.next") != null
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }
        }.build()

        return fetchMangaList(url)
    }

    // ============================== Search ===============================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotEmpty()) {
            val url = baseUrl.toHttpUrl().newBuilder().apply {
                if (page > 1) {
                    addPathSegment("page")
                    addPathSegment(page.toString())
                }
                addQueryParameter("s", query)
            }.build()

            return fetchMangaList(url)
        }

        val categoryFilter = filters.firstInstanceOrNull<CategoryFilter>()
        val tagFilter = filters.firstInstanceOrNull<TagFilter>()

        var path = ""
        if (categoryFilter != null && categoryFilter.toUriPart().isNotEmpty()) {
            path = "category/${categoryFilter.toUriPart()}"
        } else if (tagFilter != null && tagFilter.toUriPart().isNotEmpty()) {
            path = "tag/${tagFilter.toUriPart()}"
        }

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (path.isNotEmpty()) {
                addPathSegments(path)
            }
            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }
        }.build()

        return fetchMangaList(url)
    }

    // ============================== Details ==============================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get(getMangaUrl(manga))
        val chapterUrl = response.request.url.encodedPath
        val document = response.asJsoup()

        val details = SManga.create().apply {
            url = manga.url
            title = document.selectFirst(".post-conteudo h1")?.text() ?: throw Exception("Manga title is mandatory")
            description = document.select(".post-texto p").joinToString("\n") { it.text() }
            genre = document.select(".post-tags a").joinToString { it.text() }
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")

            // Site treats each post/comic as a single entity, usually completed once uploaded.
            status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }

        val chapter = SChapter.create().apply {
            name = "Capítulo Único"
            url = chapterUrl
            date_upload = Instant.tryParse(document.selectFirst("meta[property=article:published_time]")?.attr("content"))
        }

        return SMangaUpdate(details, listOf(chapter))
    }

    // =============================== Pages ===============================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()

        val gallery = document.select("div.dgwt-jg-gallery figure.dgwt-jg-item a")
        if (gallery.isNotEmpty()) {
            return gallery.mapIndexed { index, a ->
                Page(index, imageUrl = a.absUrl("href"))
            }
        }

        return document.select(".post-texto img").mapIndexedNotNull { index, img ->
            val url = bestImageUrl(img) ?: return@mapIndexedNotNull null
            Page(index, imageUrl = url)
        }
    }

    /**
     * Retorna a URL de maior qualidade possível para um <img> do conteúdo.
     *
     * Ordem de preferência:
     *  1. <a href> que envolve o <img> (WordPress aponta pro arquivo original)
     *  2. maior entrada do srcset (por largura em "w")
     *  3. data-lazy-src / src como fallback
     */
    private fun bestImageUrl(img: Element): String? {
        // 1) <a href> envolvendo a imagem
        img.parent()
            ?.takeIf { it.tagName() == "a" }
            ?.absUrl("href")
            ?.takeIf(::looksLikeImage)
            ?.let { return it }

        // 2) srcset — pega a maior largura
        val srcset = img.attr("srcset").ifBlank { img.attr("data-srcset") }
        if (srcset.isNotBlank()) {
            srcset.split(",")
                .mapNotNull { part ->
                    val toks = part.trim().split(Regex("\\s+"), limit = 2)
                    val u = toks.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val w = toks.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: 0
                    u to w
                }
                .maxByOrNull { it.second }
                ?.first
                ?.let { raw -> return resolveUrl(img, raw) }
        }

        // 3) fallback
        return img.absUrl("data-lazy-src").takeIf { it.isNotBlank() }
            ?: img.absUrl("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
    }

    private fun resolveUrl(el: Element, url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return runCatching { java.net.URL(java.net.URL(el.baseUri()), url).toString() }
            .getOrDefault(url)
    }

    private fun looksLikeImage(url: String): Boolean {
        val p = url.substringBefore('?').lowercase()
        return p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".png") ||
            p.endsWith(".webp") || p.endsWith(".gif")
    }

    // ============================== Filters ==============================
    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header("Nota: Ignorado se usar pesquisa de texto."),
        Filter.Separator(),
        CategoryFilter(),
        Filter.Separator(),
        Filter.Header("Nota: Ignorado se escolher uma categoria."),
        TagFilter(),
    )
}