package eu.kanade.tachiyomi.extension.pt.nhentainetbr

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
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class NhentaiNetBr : KeiSource() {

    override val supportsLatest = true

    // Rate limit: 1 req / 2 s (evita bloqueio de IP)
    override fun OkHttpClient.Builder.configureClient() = rateLimit(1, 2.seconds)

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)

    // ============================================================
    // LISTAGEM
    // ============================================================
    private fun Element.toSManga(): SManga? {
        // Pula publicidade
        val ad = selectFirst("a.thumbParodiaNome")
        if (ad != null && ad.text().contains("Publicidade", ignoreCase = true)) return null

        val a = select("a").firstOrNull { it.hasAttr("href") && it.selectFirst("img") != null }
            ?: selectFirst("a[href]:not(.thumbParodiaNome)")
            ?: return null

        val href = a.absUrl("href").takeIf { it.isNotEmpty() && it.startsWith(baseUrl) } ?: return null

        val title = selectFirst("span.thumb-titulo")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        // Thumb: img.wp-post-image (tira sufixo -241x334 pra pegar full)
        val thumb = a.selectFirst("img.wp-post-image, img")
            ?.let {
                val s = it.absUrl("src").ifBlank { it.absUrl("data-src") }
                s.replace(Regex("-\\d+x\\d+(?=\\.(webp|jpg|png))"), "")
            }
            .orEmpty()

        return SManga.create().apply {
            this.title = title
            this.thumbnail_url = thumb
            this.url = href.removePrefix(baseUrl)
        }
    }

    private fun parseListPage(document: Document): MangasPage {
        val mangas = document.select("div.thumb-conteudo").mapNotNull { it.toSManga() }
        val hasNext = document.selectFirst(
            "a.next.page-numbers[href], " +
                "nav.pagination a.next[href], " +
                "link[rel=next], " +
                "a[rel=next][href]",
        ) != null
        return MangasPage(mangas, hasNext)
    }

    override suspend fun getPopularManga(page: Int) =
        parseListPage(client.get("$baseUrl/ultimos/page/$page/").asJsoup())

    override suspend fun getLatestUpdates(page: Int) = getPopularManga(page)

    // ============================================================
    // BUSCA
    // ============================================================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val encoded = query.replace(" ", "+")
        val url = if (page == 1) "$baseUrl/?s=$encoded" else "$baseUrl/page/$page/?s=$encoded"
        return parseListPage(client.get(url).asJsoup())
    }

    // ============================================================
    // DETALHES — SÓ TAGS
    // ============================================================
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.count { it.isNotBlank() } < 1) return null
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

        val genres = extractLabeledTags(document).take(15)

        val description = document.select(
            "div.summary, div.description, div.sinopse, meta[property=og:description]",
        ).joinToString(" ") { it.text().trim() }
            .ifBlank {
                document.selectFirst("meta[name=description]")?.attr("content").orEmpty()
            }

        val dateText = document.selectFirst("meta[property=article:published_time]")?.attr("content")
        val date = dateText?.take(19)?.let {
            runCatching { dateFormat.parse(it)?.time }.getOrNull()
        } ?: 0L

        return SMangaUpdate(
            SManga.create().apply {
                url = manga.url
                title = document.selectFirst("h1")?.text()?.trim().orEmpty()
                thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
                this.description = description
                genre = genres.joinToString(", ")
            },
            listOf(
                SChapter.create().apply {
                    name = "Capítulo 1"
                    url = manga.url  // aponta pra página do mangá, não pra galeria
                    date_upload = date
                },
            ),
        )
    }

    /**
     * Extrai SÓ o bloco "Tags:" (sem Categorias/Paródia/Personagens).
     * Estrutura: <li><strong>Tags:</strong> <a>Ahegao</a> <a>Creampie</a> ...</li>
     * Obs: Jsoup do Mihon NÃO suporta :scope > — usar selectFirst separado.
     */
    private fun extractLabeledTags(document: Document): List<String> {
        val result = mutableListOf<String>()
        val labels = listOf("Tags")

        document.select("li").forEach { li ->
            val strong = li.selectFirst("strong") ?: li.selectFirst("b") ?: return@forEach
            val labelText = strong.text().trim().removeSuffix(":").trim()
            if (labels.none { labelText.equals(it, ignoreCase = true) }) return@forEach

            li.select("a").forEach { a ->
                val t = a.text().trim()
                if (t.isNotEmpty() &&
                    !t.equals("Login", true) &&
                    !t.equals("Registre-se", true) &&
                    !t.contains("Publicidade", true)
                ) {
                    result.add(t)
                }
            }
        }

        return result.distinct()
    }

    // ============================================================
    // PÁGINAS — img[style*=height] (marcador ouro da galeria real)
    // ============================================================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pageUrl = getChapterUrl(chapter)
        val document = client.get(pageUrl).asJsoup()

        val images = document.select("img[style*=height]").mapNotNull { img ->
            val src = img.absUrl("src").ifBlank { img.absUrl("data-src") }
            if (src.isBlank()) return@mapNotNull null
            if (!src.contains("/wp-content/uploads/")) return@mapNotNull null
            if (src.contains("Logo", ignoreCase = true)) return@mapNotNull null
            if (src.contains("mundohentaioficial", ignoreCase = true)) return@mapNotNull null
            src
        }

        return images.mapIndexed { i, url -> Page(i, url = pageUrl, imageUrl = url) }
    }

    // ============================================================
    // IMAGE REQUEST — precisa do Referer pra não quebrar img
    // Usa okhttp puro (Request.Builder) porque KeiSource 1.6 não expõe GET()
    // ============================================================
    override fun imageRequest(page: Page): Request {
        return Request.Builder()
            .url(page.imageUrl!!)
            .header("Referer", page.url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            .header("Sec-Fetch-Dest", "image")
            .header("Sec-Fetch-Mode", "no-cors")
            .header("Sec-Fetch-Site", "same-origin")
            .build()
    }
}
