package eu.kanade.tachiyomi.extension.pt.hentaiputa

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.util.Collections

@Source
abstract class HentaiPuta : KeiSource() {

    override val supportsLatest = true

    // Memória de bloqueio dinâmica: guarda posts ruins que você tentou abrir
    private val blockedUrls = Collections.synchronizedSet(mutableSetOf<String>())

    // Adicione aqui os posts que você já sabe que são lixo (Tufos/Teasers)
    private val blacklistedSlugs = setOf(
        "/game-polemico/",
        "/game-polemico",
        "/quero-nem-saber-o-seu-nome/",
        "/quero-nem-saber-o-seu-nome",
    )

    override fun OkHttpClient.Builder.configureClient() = addInterceptor { chain ->
        val req = chain.request()
        val builder = req.newBuilder()
        if (req.header("User-Agent") == null) builder.header("User-Agent", UA)
        chain.proceed(builder.build())
    }

    // ============================================================
    // POPULAR / LATEST
    // ============================================================

    override suspend fun getPopularManga(page: Int): MangasPage = parseListing(client.get(pageUrl(page)))

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseListing(client.get(pageUrl(page)))

    // ============================================================
    // BUSCA
    // ============================================================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("paged", page.toString())
            .build()
        return parseListing(client.get(url.toString()))
    }

    // ============================================================
    // DETALHES + CAPÍTULOS
    // ============================================================

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
        val response = client.get(getMangaUrl(manga))
        val doc = response.asJsoup()

        // ---- FILTRO CIRÚRGICO (Anti-Tufos e Anti-Teasers) ----
        val htmlLower = doc.outerHtml().lowercase()

        // 1. Busca APENAS links de conteúdo específicos do Tufos (ignora o menu/rodapé)
        val isTufosPost = htmlLower.contains("tufos.com.br/quadrinhos/") ||
            htmlLower.contains("tufos.com.br/games/")

        // 2. Busca o banner de publicidade "Veja Completo"
        val hasBannerAd = doc.select("img").any { img ->
            val src = img.attr("src").lowercase()
            src.contains("site-oficial") || src.contains("publicidade")
        }

        if (isTufosPost || hasBannerAd) {
            blockedUrls.add(manga.url)
            throw Exception("HentaiPuta: post externo (Tufos/Teaser) — ignorado")
        }

        // 3. Filtro de segurança: se o post tiver menos de 2 imagens próprias no conteúdo
        val contentImgs = doc.select("img").count { img ->
            val src = img.attr("src").ifBlank { img.attr("data-src") }
            src.contains("hentaiputa.com/wp-content/uploads") || src.contains("img.hentaiputa.com")
        }
        if (contentImgs < 2) {
            blockedUrls.add(manga.url)
            throw Exception("HentaiPuta: post com poucas imagens — ignorado")
        }

        // ---- Restante da lógica de detalhes ----
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

        // Pega a capa em alta resolução também nos detalhes
        val coverSrc = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            ?: doc.selectFirst("meta[name=\"twitter:image\"]")?.attr("content")
        val highResCover = coverSrc?.replace(Regex("-\\d+x\\d+(?=\\.(webp|jpg|jpeg|png|gif))"), "")

        val updated = SManga.create().apply {
            this.url = manga.url
            this.title = doc.selectFirst("h1")?.text()?.trim().orEmpty()

            this.thumbnail_url = highResCover

            this.description = doc.selectFirst("meta[property=\"og:description\"]")?.attr("content")?.trim()
                ?: doc.selectFirst("meta[name=\"description\"]")?.attr("content")?.trim()

            this.genre = (categorias + tags).distinct().joinToString(", ")
            this.status = SManga.COMPLETED
        }

        val chapterList = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    this.url = manga.url
                    this.name = "Capítulo Único"
                    this.chapter_number = 1f
                },
            )
        } else {
            emptyList()
        }

        return SMangaUpdate(updated, chapterList)
    }

    // ============================================================
    // PÁGINAS
    // ============================================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val doc = client.get(getChapterUrl(chapter)).asJsoup()

        return doc.select("img.attachment-full.size-full")
            .filter { it.absUrl("src").contains("img.hentaiputa.com/wp-content/uploads/") }
            .filter {
                val src = it.absUrl("src").lowercase()
                !src.contains("completo") && !src.contains("publicidade") && !src.contains("site-oficial")
            }
            .distinctBy { it.absUrl("src") }
            .mapIndexed { i, img -> Page(i, imageUrl = img.absUrl("src")) }
    }

    // ============================================================
    // IMAGEM
    // ============================================================

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .header("Referer", "$baseUrl/")
        .header("User-Agent", UA)
        .build()

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

            val titleLower = title.lowercase()
            val hrefLower = href.lowercase()
            if (titleLower.contains("ver completo") ||
                titleLower.contains("tufos") ||
                hrefLower.contains("tufos")
            ) {
                return@mapNotNull null
            }

            val path = href.removePrefix("$baseUrl/").trimEnd('/')
            if (path.isEmpty() || path.contains("/")) return@mapNotNull null

            // ---- Filtra os posts da Blacklist (Manual e Dinâmica) ----
            val cleanPath = "/" + path.trim('/')
            val cleanPathWithSlash = "$cleanPath/"
            if (blacklistedSlugs.contains(cleanPath) ||
                blacklistedSlugs.contains(cleanPathWithSlash) ||
                blockedUrls.contains(cleanPath) ||
                blockedUrls.contains(cleanPathWithSlash)
            ) {
                return@mapNotNull null
            }

            if (isAd(a)) return@mapNotNull null

            // ---- MELHORIA DE QUALIDADE DA CAPA (HD) ----
            var thumb = img.absUrl("src").ifBlank { img.absUrl("data-src") }
            // Remove o sufixo de redimensionamento do WordPress (ex: -300x200, -768x1024)
            thumb = thumb.replace(Regex("-\\d+x\\d+(?=\\.(webp|jpg|jpeg|png|gif))"), "")

            if (!thumb.contains("hentaiputa.com")) return@mapNotNull null

            SManga.create().apply {
                this.url = href.removePrefix(baseUrl)
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

    private fun isAd(a: Element): Boolean {
        val rel = a.attr("rel").lowercase()
        if (rel.contains("sponsored")) return true
        if (a.attr("target") == "_blank") return true

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

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    }
}
