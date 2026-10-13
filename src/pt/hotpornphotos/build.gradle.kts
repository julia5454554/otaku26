import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hot Porn Photos"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        lang = "pt-BR"
        baseUrl = "https://www.hotpornphotos.com"
    }

    deeplink {
        host("www.hotpornphotos.com")
        path("/..*")
    }
}
