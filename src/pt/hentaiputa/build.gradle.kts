import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hentai Puta"
    versionCode = 2
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"
    source {
        lang = "pt-BR"
        baseUrl = "https://www.hentaiputa.com"
    }
    deeplink {
        host("www.hentaiputa.com")
        path("/..*")
    }
}
