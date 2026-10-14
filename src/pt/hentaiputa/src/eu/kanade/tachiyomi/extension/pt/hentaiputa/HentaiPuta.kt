package eu.kanade.tachiyomi.extension.pt.hentaiputa

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

@Source
abstract class HentaiPuta : HttpSource() {

    override val supportsLatest = true

    private val imageHeaders = headers.newBuilder()
        .add("Referer", "$baseUrl/")
        .build()

    // ============================================================
    // POPULAR / LATEST
    // ============================================================

    override fun popularMangaRequest(page: Int): Request = GET(pageUrl(page), headers)
    override fun popularMangaParse(response: Response): MangasPage = parseListing(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(pageUrl(page), headers)
    override fun latestUpdatesParse(response: Response): MangasPage = parseListing(response)

    // ============================================================
    // BUSCA (WP padrão: /?s=QUERY&paged=N)
    // ============================================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("paged", page.toString())
            .build()
        return GET(url, headers)
    }
    override fun searchMangaParse(response: Response): MangasPage = parseListing(response)

    // ============================================================
    // DETALHES
    // ============================================================

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()

            thumbnail_url = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
                ?: doc.selectFirst("meta[name=\"twitter:image\"]")?.attr("content")

            description = doc.selectFirst("meta[property=\"og:description\"]")?.attr("content")?.trim()
                ?: doc.selectFirst("meta[name=\"description\"]")?.attr("content")?.trim()

            val dropdown = doc.select("ul#catsDropdownMenu a")
                .map { it.text().trim() }
                .toSet()

            val obraPath = response.request.url.encodedPath.trimEnd('/')

            fun isValid(el: Element): Boolean {
                val texto = el.text().trim()
                if (texto.isEmpty()) return false
                if (texto in dropdown) return false
                if (texto.equals("Categorias", true)) return false
                if (texto.equals("Tags", true)) return false
                if (texto.startsWith("Mostrar vídeos", true)) return false
                if (texto.startsWith("Mostrar videos", true)) return false
                if (el.attr("title").equals("Mostrar mais vídeos relacionados", true)) return false
                if (el.absUrl("href").trimEnd('/').endsWith(obraPath)) return false
                return true
            }

            val categorias = doc.select("a[href*=\"/categoria/\"]")
                .filter { isValid(it) }
                .map { it.text().trim() }
                .distinct()

            val tags = doc.select("a[href*=\"/tag/\"]")
                .filter { isValid(it) }
                .map { it.text().trim() }
                .distinct()

            genre = (categorias + tags).distinct().joinToString(", ")
            status = SManga.COMPLETED
        }
    }

    // ============================================================
    // CAPÍTULOS (1 único por obra)
    // ============================================================

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> = listOf(
        SChapter.create().apply {
            url = response.request.url.encodedPath
            name = "Capítulo Único"
            chapter_number = 1f
        },
    )

    // ============================================================
    // PÁGINAS (imagens da galeria)
    // ============================================================

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asJsoup()
        return doc.select("img.attachment-full.size-full")
            .filter { it.absUrl("src").contains("img.hentaiputa.com/wp-content/uploads/") }
            .distinctBy { it.absUrl("src") }
            .mapIndexed { i, img -> Page(i, imageUrl = img.absUrl("src")) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================================================
    // HELPERS
    // ============================================================

    private fun pageUrl(page: Int): String = if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"

    private fun parseListing(response: Response): MangasPage {
        val doc = response.asJsoup()

        val mangas = doc.select("a[title]").mapNotNull { a ->
            val img = a.selectFirst("img") ?: return@mapNotNull null
            val href = a.absUrl("href")
            val title = a.attr("title").trim()

            if (title.isEmpty()) return@mapNotNull null
            if (!href.startsWith("$baseUrl/")) return@mapNotNull null

            val path = href.removePrefix("$baseUrl/").trimEnd('/')
            if (path.isEmpty() || path.contains("/")) return@mapNotNull null

            if (isAd(a)) return@mapNotNull null

            val thumb = img.absUrl("src")
            if (!thumb.contains("hentaiputa.com")) return@mapNotNull null

            SManga.create().apply {
                setUrlWithoutDomain(href)
                this.title = title
                thumbnail_url = thumb
            }
        }.distinctBy { it.url }

        val hasNext = doc.select("a").any {
            val t = it.text().trim()
            t.equals("Próximo", true) ||
                t.equals("Next", true) ||
                t == "›" || t == "»" ||
                it.attr("rel") == "next"
        }

        return MangasPage(mangas, hasNext)
    }

    // Detecta anúncios disfarçados de card
    private fun isAd(a: Element): Boolean {
        // 1. rel="sponsored" (AdSense / afiliado)
        val rel = a.attr("rel").lowercase()
        if (rel.contains("sponsored")) return true

        // 2. target="_blank" — link externo, quase sempre anúncio
        if (a.attr("target") == "_blank") return true

        // 3. Container com classe/id de anúncio
        val dentroDeAd = a.parents().any { p ->
            val cls = p.className().lowercase()
            val id = p.id().lowercase()
            p.tagName() == "ins" ||
                cls.contains("anuncio") ||
                cls.contains("ad-") ||
                cls.contains("-ad") ||
                cls.startsWith("ad ") ||
                cls == "ad" ||
                cls.contains("adsbygoogle") ||
                cls.contains("banner") ||
                cls.contains("tufos") ||
                cls.contains("sponsor") ||
                cls.contains("publicid") ||
                id.contains("adsense") ||
                id.contains("google_ads") ||
                id.contains("tufos")
        }
        if (dentroDeAd) return true

        // 4. Título com palavra-chave de anúncio
        val t = a.attr("title").lowercase()
        if (t.startsWith("ad ") ||
            t.contains("patrocinado") ||
            t.contains("publicidade") ||
            t.contains("sponsored")
        ) {
            return true
        }

        return false
    }
}
