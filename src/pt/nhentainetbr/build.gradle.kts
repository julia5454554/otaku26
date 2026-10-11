import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NHentai Net BR"
    versionCode = 3          // bump de 2 → 3
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"       // migrado de 1.4 → 1.6
    source {
        lang = "pt-BR"
        baseUrl = "https://nhentai.net.br"
    }
    deeplink {
        host("nhentai.net.br")
        path("/.*")
    }
}
