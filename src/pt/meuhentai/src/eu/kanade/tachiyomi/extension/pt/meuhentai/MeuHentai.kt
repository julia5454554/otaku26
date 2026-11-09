package eu.kanade.tachiyomi.extension.pt.meuhentai

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MeuHentai : KeiSource() {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2, 1.seconds)
        .addInterceptor { chain ->
            val req = chain.request()
            val builder = req.newBuilder()
            if (req.header("User-Agent") == null) builder.header("User-Agent", UA)
            if (req.header("Referer") == null) builder.header("Referer", "$baseUrl/")
            chain.proceed(builder.build())
        }

    // =========================
    // Populares (Mais vistos)
    // =========================
    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page == 1) {
            "$baseUrl/?agt_sort=views".toHttpUrl()
        } else {
            "$baseUrl/page/$page/?agt_sort=views".toHttpUrl()
        }
        return parseMangaList(client.get(url.toString()).asJsoup())
    }

    // =========================
    // Recentes (Latest)
    // =========================
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (page == 1) {
            "$baseUrl/".toHttpUrl()
        } else {
            "$baseUrl/page/$page/".toHttpUrl()
        }
        return parseMangaList(client.get(url.toString()).asJsoup())
    }

    // =========================
    // Busca
    // =========================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val urlBuilder = if (page == 1) {
            "$baseUrl/".toHttpUrl().newBuilder()
        } else {
            "$baseUrl/page/$page/".toHttpUrl().newBuilder()
        }
        urlBuilder.addQueryParameter("s", query)
        return parseMangaList(client.get(urlBuilder.build().toString()).asJsoup())
    }

    // =========================
    // Parser comum da listagem (só HQ, sem anúncios)
    // =========================
    private fun parseMangaList(document: Document): MangasPage {
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
                    this.url = href.removePrefix(baseUrl)
                }
            }

        val hasNextPage = document.selectFirst("a.next.page-numbers") != null ||
            document.selectFirst("link[rel=next]") != null

        return MangasPage(mangas, hasNextPage)
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

    // =========================
    // Detalhes + Capítulos (cada post = 1 capítulo)
    // =========================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

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

        val updated = SManga.create().apply {
            this.url = manga.url
            this.title = title
            this.thumbnail_url = thumbnail
            this.description = description
            this.genre = genre
        }

        val chapterList = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    name = "Capítulo Único"
                    this.url = manga.url
                },
            )
        } else {
            emptyList()
        }

        return SMangaUpdate(updated, chapterList)
    }

    // =========================
    // Páginas (imagens do conteúdo)
    // =========================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val document = client.get(chapterUrl).asJsoup()

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
                Page(index, url = chapterUrl, imageUrl = url)
            }
    }

    // =========================
    // imageRequest com Referer correto
    // =========================
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