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

    // ID é auto-gerado pelo @Source (na 1.4 era id: Long = 0L no construtor)

    override val supportsLatest = true

    // Rate limit: 1 req / 2 s (evita bloqueio de IP)
    override fun OkHttpClient.Builder.configureClient() = rateLimit(1, 2.seconds)

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)

    /**
     * Filtro de vídeo/live-action confirmado via curl:
     * URLs de vídeo terminam em `-live-action` (ex:
     * /a-bela-e-a-fera-2-a-gyaru-e-o-otaku-live-action/).
     * Também pega por garantia: qualquer "/live-action" ou "/jav".
     */
    private val videoUrlRegex = Regex(
        "(?i)(-live-action/?$|/live-action/|/jav/)",
    )

    /**
     * Páginas que NÃO são conteúdo (navegação, cadastro, etc.).
     * Aparecem no grep mas nunca viram cards de mangá — belt and suspenders.
     */
    private val nonContentPaths = setOf(
        "cadastro", "contato", "entrar", "registrar", "login",
        "photography", "category", "parodia", "parody", "tag", "tags",
    )

    private fun isVideoUrl(href: String): Boolean = videoUrlRegex.containsMatchIn(href)

    private fun isNonContentUrl(href: String): Boolean {
        val path = href.removePrefix(baseUrl).trim('/').lowercase()
        return path in nonContentPaths || path.split("/").any { it in nonContentPaths }
    }

    // ============================================================
    // LISTAGEM
    // ============================================================
    private fun Element.toSManga(): SManga? {
        // Pula cards que são só propaganda cruzada (todos links externos)
        val internalLinks = select("a[href]").filter {
            val h = it.absUrl("href")
            h.isNotEmpty() && h.startsWith(baseUrl)
        }
        if (internalLinks.isEmpty()) return null

        // Pula publicidade
        val ad = selectFirst("a.thumbParodiaNome")
        if (ad != null && ad.text().contains("Publicidade", ignoreCase = true)) {
            if (internalLinks.none { it.selectFirst("img") != null }) return null
        }

        // Pega o link interno (mesmo domínio) que envolve a imagem
        val a = internalLinks.firstOrNull { it.selectFirst("img") != null }
            ?: internalLinks.firstOrNull()
            ?: return null

        val href = a.absUrl("href")
        if (href.isEmpty()) return null

        // === FILTRO DE VÍDEO / LIVE-ACTION ===
        if (isVideoUrl(href)) return null

        // === FILTRO DE PÁGINAS DE NAVEGAÇÃO ===
        if (isNonContentUrl(href)) return null

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

    override suspend fun getPopularManga(page: Int) = parseListPage(client.get("$baseUrl/ultimos/page/$page/").asJsoup())

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
    // DETALHES + CAPÍTULOS
    // ============================================================
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.count { it.isNotBlank() } < 1) return null
        val fullUrl = url.toString()
        if (isVideoUrl(fullUrl) || isNonContentUrl(fullUrl)) return null
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

        // === CHECAGEM VÍDEO: se tem "Áudio:" ou "Legenda:" → live action
        val bodyText = document.body()?.text().orEmpty()
        val isVideo = Regex("(?im)^(Áudio|Audio|Legenda)\\s*:", RegexOption.MULTILINE)
            .containsMatchIn(bodyText) ||
            document.select("li, p, div, span, strong").any {
                val t = it.text().trim()
                t.startsWith("Áudio:", true) || t.startsWith("Audio:", true) ||
                    t.startsWith("Legenda:", true)
            }

        if (isVideo) {
            return SMangaUpdate(
                SManga.create().apply {
                    url = manga.url
                    title = "[VÍDEO] " + (document.selectFirst("h1")?.text()?.trim() ?: "Sem título")
                    description = "⚠️ Este conteúdo é vídeo/live action, não mangá."
                    status = SManga.CANCELLED
                },
                emptyList(),
            )
        }

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
            parseChapters(document, manga.url, date),
        )
    }

    /**
     * Estrutura confirmada via curl:
     *   <div class="galeriaTabItem">
     *     <div class="galeriaTab" data-id="1">...</div>
     *     <div class="galeriaConteudo" id="galeria-1">...</div>
     *   </div>
     */
    private fun parseChapters(document: Document, mangaUrl: String, fallbackDate: Long): List<SChapter> {
        val items = document.select("div.galeriaTabItem")

        if (items.isEmpty()) {
            return listOf(
                SChapter.create().apply {
                    name = "Capítulo 1"
                    url = mangaUrl
                    date_upload = fallbackDate
                },
            )
        }

        return items.mapNotNull { item ->
            val tab = item.selectFirst("div.galeriaTab[data-id]") ?: return@mapNotNull null
            val dataId = tab.attr("data-id").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null

            val capTitle = item.selectFirst("div.galeriaTabCapitulo")?.text()?.trim().orEmpty()
            val subTitle = item.selectFirst("div.galeriaTabTitulo")?.text()?.trim().orEmpty()

            val name = buildString {
                append(capTitle.ifBlank { "Capítulo $dataId" })
                if (subTitle.isNotEmpty()) {
                    append(" - ")
                    append(subTitle)
                }
            }

            SChapter.create().apply {
                this.name = name
                url = "$mangaUrl#cap$dataId"
                date_upload = fallbackDate
            }
        }.reversed()
    }

    /**
     * Extrai SÓ o bloco "Tags:" (sem Categorias/Paródia/Personagens).
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
    // PÁGINAS — img[style*=height] dentro do painel #galeria-N
    // ============================================================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val rawUrl = chapter.url.substringBefore("#")
        val anchor = chapter.url.substringAfter("#", "")
        val dataId = anchor.removePrefix("cap").takeIf { it.isNotEmpty() }

        val pageUrl = if (rawUrl.startsWith("http")) rawUrl else "$baseUrl$rawUrl"
        val document = client.get(pageUrl).asJsoup()

        val scope: Element = if (dataId != null) {
            document.selectFirst("div.galeriaConteudo#galeria-$dataId")
                ?: document.selectFirst("div[id=galeria-$dataId]")
                ?: document
        } else {
            document
        }

        val images = scope.select("img[style*=height]").mapNotNull { img ->
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
    // IMAGE REQUEST — usa okhttp puro (KeiSource 1.6 não expõe GET())
    // ============================================================
    override fun imageRequest(page: Page): Request = Request.Builder()
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
