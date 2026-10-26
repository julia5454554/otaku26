package eu.kanade.tachiyomi.extension.pt.megahq

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
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLEncoder
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Megahq : KeiSource() {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(2, 1.seconds)
    }

    // ------------------------------------------------------------------
    // ORDEM CUSTOMIZADA (Dennix Milftoon)
    // ------------------------------------------------------------------
    private val customOrders = mapOf(
        "dennix-milftoon" to listOf(
            "07-69", "01-77", "02-77", "13-54", "03-77", "14-51",
            "04-77", "05-74", "06-72", "08-66", "09-62", "10-62",
            "11-61", "12-59",
        ),
    )

    // ------------------------------------------------------------------
    // Listagem (via HTML)
    // ------------------------------------------------------------------

    private fun Element.toSManga(): SManga? {
        val titleLink = selectFirst("div.title a[href]") ?: return null
        val href = titleLink.absUrl("href").takeIf { it.isNotBlank() } ?: return null
        if (!href.startsWith(baseUrl)) return null

        val title = selectFirst("div.title h2")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: titleLink.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        val cover = selectFirst("div.thumb img")
            ?.let { it.absUrl("data-src").ifBlank { it.absUrl("src") } }
            ?.takeIf { it.startsWith("http") }
            .orEmpty()

        return SManga.create().apply {
            this.title = title
            this.url = href.removePrefix(baseUrl)
            this.thumbnail_url = cover
        }
    }

    private fun parseListing(document: Document): MangasPage {
        val mangas = document.select("article.box").mapNotNull { it.toSManga() }
        val hasNext = document.selectFirst(
            "a.nextpostslink[href], a.next.page-numbers[href], link[rel=next], a[rel=next][href]",
        ) != null
        return MangasPage(mangas, hasNext)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        return parseListing(client.get(url).asJsoup())
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page == 1) "$baseUrl/doujins/" else "$baseUrl/doujins/page/$page/"
        return parseListing(client.get(url).asJsoup())
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getLatestUpdates(page)
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page == 1) {
            "$baseUrl/?s=$encoded"
        } else {
            "$baseUrl/page/$page/?s=$encoded"
        }
        return parseListing(client.get(url).asJsoup())
    }

    // ------------------------------------------------------------------
    // Detalhes (via API wp-json)
    // ------------------------------------------------------------------

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.none { it.isNotBlank() }) return null
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
        val slug = manga.url.trim('/').substringAfterLast('/')
        val apiUrl = "$baseUrl/wp-json/wp/v2/posts?slug=$slug"

        val response = client.get(apiUrl)
        val posts = response.parseAs<List<JsonObject>>()
        val post = posts.firstOrNull() ?: return SMangaUpdate(manga, emptyList())

        val title = post["title"]?.jsonObject?.get("rendered")?.jsonPrimitive?.contentOrNull
            ?.let { Parser.unescapeEntities(it, false) }
            ?: manga.title

        val contentHtml = post["content"]?.jsonObject?.get("rendered")?.jsonPrimitive?.contentOrNull.orEmpty()
        val contentDoc = Jsoup.parse(contentHtml, baseUrl)

        val cover = contentDoc.selectFirst("img[src*='/wp-content/uploads/']")
            ?.absUrl("src")
            ?.takeIf { it.startsWith("http") }
            ?: manga.thumbnail_url

        val description = contentDoc.selectFirst("p")
            ?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "Obra única do site MegaHQ."

        val updated = SManga.create().apply {
            this.url = manga.url
            this.title = title
            this.thumbnail_url = cover
            this.description = description
            this.status = SManga.COMPLETED
            this.update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }

        val chapterList = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    url = manga.url
                    name = "Capítulo Único"
                    chapter_number = 1f
                },
            )
        } else {
            emptyList()
        }

        return SMangaUpdate(updated, chapterList)
    }

    // ------------------------------------------------------------------
    // Páginas (via API wp-json com ordenação híbrida)
    // ------------------------------------------------------------------

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val slug = chapter.url.trim('/').substringAfterLast('/')
        val apiUrl = "$baseUrl/wp-json/wp/v2/posts?slug=$slug"

        val response = client.get(apiUrl)
        val posts = response.parseAs<List<JsonObject>>()
        val post = posts.firstOrNull()

        val contentHtml = post?.get("content")?.jsonObject?.get("rendered")
            ?.jsonPrimitive?.contentOrNull.orEmpty()

        val document = Jsoup.parse(contentHtml, baseUrl)

        // Extrai as imagens e remove os sufixos de tamanho (-768x1004, etc)
        val images = document.select("img[src*='/wp-content/uploads/']")
            .mapNotNull { img ->
                var src = img.absUrl("src").ifBlank { img.absUrl("data-src") }
                src = src.replace(Regex("-\\d+x\\d+(?=\\.(webp|jpg|jpeg|png))"), "")
                src.takeIf { it.startsWith("http") }
            }
            .distinct()

        // ---------------------------------------------------------
        // LÓGICA DE ORDENAÇÃO
        // ---------------------------------------------------------
        val customOrder = customOrders[slug]

        val sortedImages = if (customOrder != null) {
            // ORDEM CUSTOMIZADA: Usa a lista exata que você definiu
            images.sortedBy { url ->
                val filename = url.substringAfterLast('/').substringBefore('.')
                val index = customOrder.indexOf(filename)
                // Se por algum motivo aparecer uma imagem que não está na lista, manda pro final
                if (index >= 0) index else Int.MAX_VALUE
            }
        } else {
            // ORDEM PADRÃO: Numérica ascendente (1, 2, 3...)
            images.sortedBy { url ->
                val filename = url.substringAfterLast('/')
                Regex("""(\d+)""").find(filename)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
        }

        val referer = getChapterUrl(chapter)

        return sortedImages.mapIndexed { i, url -> Page(i, url = referer, imageUrl = url) }
    }

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .header("Referer", page.url)
        .header(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        )
        .build()
}
