import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NHentai BR"
    versionCode = 3
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "pt-BR"
        baseUrl = "https://nhentaibr.com"
        id = 2025000006L
    }

    deeplink {
        host("nhentaibr.com")
        path("/..*")
    }
}
