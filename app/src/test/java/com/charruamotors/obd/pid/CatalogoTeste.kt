package com.charruamotors.obd.pid

import java.io.File

/** Catálogo real de `src/main/assets`, lido do disco nos testes JVM (sem Context/AssetManager). */
object CatalogoTeste {
    val padrao: CatalogoPids by lazy {
        val arquivo = listOf("src/main/assets", "app/src/main/assets")
            .map { File(it, CatalogoPids.ARQUIVO_PADRAO) }
            .firstOrNull { it.isFile }
            ?: error("${CatalogoPids.ARQUIVO_PADRAO} não encontrado a partir de ${File(".").absolutePath}")
        CatalogoPids.deJson(arquivo.readText(Charsets.UTF_8))
    }

    fun pid(comando: String): DefinicaoPid = padrao[comando] ?: error("PID $comando ausente do catálogo")
}
