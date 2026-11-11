import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hentai Milf"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6" // ← única mudança

    source {
        lang = "pt-BR"
        baseUrl = "https://hentaimilf.net"
    }
}
