import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Quadrinhos de Sexo"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"
    source {
        lang = "pt-BR"
        baseUrl = "https://www.quadrinhosdesexo.com"
    }
    deeplink {
        host("www.quadrinhosdesexo.com")
        host("quadrinhosdesexo.com")
        path("/.*")
    }
}
