package com.charruamotors.direcao.waze

import java.net.URLEncoder

/** Atalhos de navegação do card de direção (deep links `waze://`). Lógica pura, testável na JVM. */
enum class AcaoWaze(val uri: String) {
    /** Abre (ou traz para a frente) o Waze. */
    ABRIR(AtalhosWaze.URI_WAZE),

    /** Busca o posto de combustível mais próximo e já inicia a rota. */
    POSTO(AtalhosWaze.uriBusca("Posto de combustivel")),

    /** Busca oficina mecânica próxima e já inicia a rota. */
    OFICINA(AtalhosWaze.uriBusca("Oficina mecanica")),
}

object AtalhosWaze {
    const val PACOTE = "com.waze"
    const val URI_WAZE = "waze://"
    const val URI_LOJA = "market://details?id=$PACOTE"
    const val URI_LOJA_WEB = "https://play.google.com/store/apps/details?id=$PACOTE"

    /**
     * `waze://?q=<termo>&navigate=yes`. O termo vai codificado com `%20` nos espaços (o
     * `URLEncoder` usa `+`, que o Waze trataria como parte do texto).
     */
    fun uriBusca(termo: String, navegar: Boolean = true): String {
        val consulta = URLEncoder.encode(termo.trim(), Charsets.UTF_8.name()).replace("+", "%20")
        return "$URI_WAZE?q=$consulta" + if (navegar) "&navigate=yes" else ""
    }

    /**
     * URIs a tentar, em ordem, até uma abrir: o deep link do Waze; sem o Waze instalado, a
     * página dele na Play Store (app) e, sem a Play Store, no navegador.
     */
    fun tentativas(acao: AcaoWaze): List<String> = listOf(acao.uri, URI_LOJA, URI_LOJA_WEB)
}
