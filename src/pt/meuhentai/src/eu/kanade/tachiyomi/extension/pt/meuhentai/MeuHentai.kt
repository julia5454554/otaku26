package eu.kanade.tachiyomi.extension.pt.meuhentai

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.time.Duration.Companion.seconds

@Source
class MeuHentai(
    override val lang: String = "pt-BR",
    override val id: Long = 2026000001L,
) : HttpSource() {

    override val name = "MeuHentai"
    override val baseUrl = "https://meuhentai.com"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // =========================
    // Populares (Mais vistos)
    // =========================

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/?agt_sort=views".toHttpUrl()
        } else {
            "$baseUrl/page/$page/?agt_sort=views".toHttpUrl()
        }
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response)

    // =========================
    // Recentes (Latest)
    // =========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/".toHttpUrl()
        } else {
            "$baseUrl/page/$page/".toHttpUrl()
        }
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response)

    // =========================
    // Busca
    // =========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val urlBuilder = if (page == 1) {
            "$baseUrl/".toHttpUrl().newBuilder()
        } else {
            "$baseUrl/page/$page/".toHttpUrl().newBuilder()
        }
        urlBuilder.addQueryParameter("s", query)
        return GET(urlBuilder.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaList(response)

    // =========================
    // Parser comum da listagem (só HQ, sem anúncios)
    // =========================

    private fun parseMangaList(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document
            .select("article.agt-card--hq")
            .filterNot { element ->
                // Defesa extra: ignora cards com badge de anúncio
                element.selectFirst("span.agt-badge--ad") != null
            }
            .mapNotNull { element ->
                val link = element.selectFirst("a.agt-card-link") ?: return@mapNotNull null
                val href = link.attr("abs:href").ifBlank { return@mapNotNull null }
                val title = element.selectFirst("h3.agt-card-title")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                SManga.create().apply {
                    this.title = title
                    this.thumbnail_url = element.selectFirst("img.agt-card-img")
                        ?.attr("abs:src")
                        ?.takeIf { it.isNotBlank() }
                    setUrlWithoutDomain(href)
                }
            }

        val hasNextPage = document.selectFirst("a.next.page-numbers") != null ||
            document.selectFirst("link[rel=next]") != null

        return MangasPage(mangas, hasNextPage)
    }

    // =========================
    // Detalhes
    // =========================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        val title = document.selectFirst("h1.agt-single-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: ""

        val thumbnail = document.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }

        val description = document.selectFirst("div.agt-single-content > p")
            ?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:description]")
                ?.attr("content")?.trim()

        val genre = document.select("a.agt-chip--cat")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")
            .takeIf { it.isNotBlank() }

        return SManga.create().apply {
            this.title = title
            this.thumbnail_url = thumbnail
            this.description = description
            this.genre = genre
        }
    }

    // =========================
    // Capítulos (cada post = 1 capítulo)
    // =========================

    override fun chapterListParse(response: Response): List<SChapter> = listOf(
        SChapter.create().apply {
            name = "Capítulo Único"
            setUrlWithoutDomain(response.request.url.toString())
        },
    )

    // =========================
    // Páginas (imagens do conteúdo)
    // =========================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        val images = document.select("div.agt-single-content figure.wp-block-image img")
            .ifEmpty {
                // Fallback: se o tema mudar e tirar o <figure>, tenta direto no container
                document.select("div.agt-single-content img")
            }

        return images
            .filterNot { img ->
                // Remove possíveis anúncios injetados dentro de divs com text-align
                img.parents().any { parent ->
                    parent.tagName() == "div" &&
                        parent.attr("style").contains("text-align", ignoreCase = true)
                }
            }
            .mapNotNull { img ->
                val src = img.attr("abs:src").takeIf { it.isNotBlank() }
                    ?: img.attr("abs:data-src").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                src
            }
            .distinct()
            .mapIndexed { index, url ->
                Page(index, imageUrl = url)
            }
    }

    // =========================
    // imageUrlParse (obrigatório na libVersion 1.4 — nunca chamado,
    // pois pageListParse é totalmente sobrescrito)
    // =========================

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    // =========================
    // imageRequest com Referer correto
    // =========================

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Referer", page.url)
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }
}
