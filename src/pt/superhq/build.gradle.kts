import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "SuperHQ"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        lang = "pt-BR"
        baseUrl = "https://www.superhq.net"
    }
    deeplink {
        host("superhq.net")
        path("/..*")
    }
}
