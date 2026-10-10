import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NHentaiNetBr"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"   // <-- Versão correta

    source {
        lang = "pt-BR"
        baseUrl = "https://nhentai.net.br"
    }
}
