import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hentai Puta"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
    source {
        lang = "pt-BR"
        baseUrl = "https://www.hentaiputa.com"
    }
    deeplink {
        host("www.hentaiputa.com")
        path("/..*")
    }
}
