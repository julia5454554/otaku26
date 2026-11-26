package eu.kanade.tachiyomi.extension.pt.hqbrasa

import eu.kanade.tachiyomi.source.model.Filter

internal open class SelectFilter(
    name: String,
    private val options: List<Pair<String, String>>,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    fun selected(): String = options.getOrNull(state)?.second.orEmpty()
}

internal class CategoryFilter :
    SelectFilter(
        name = "Categoria",
        options = listOf(
            "Todas" to "",
            "Doujinshi" to "doujinshi",
            "Quadrinhos Eróticos" to "quadrinhos-eroticos",
            "Mangá Hentai" to "manga-hentai",
            "Sem Censura" to "sem-censura",
            "Colorido" to "colorido",
            "Comics" to "comics",
            "Gay" to "gay",
            "NTR" to "ntr",
            "Milf" to "milf",
            "Romance" to "romance",
            "Yuri" to "yuri",
            "Ahegao" to "ahegao",
            "Comédia" to "comedia",
        ),
    )

internal class OrderFilter :
    SelectFilter(
        name = "Ordenação",
        options = listOf(
            "Mais recentes" to "latest",
            "Mais vistos" to "popular",
            "A-Z" to "name",
        ),
    )
