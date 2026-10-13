import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Mangas Hentais"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"
    source {
        lang = "pt-BR"
        baseUrl = "https://mangashentais.com"
    }
    deeplink {
        host("mangashentais.com")
        path("/.*")
    }
}
