import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "HQ Brasa"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        baseUrl = "https://hqbrasa.com"
        lang = "pt-BR"
    }

    deeplink {
        path("/hq/..*")
    }
}
