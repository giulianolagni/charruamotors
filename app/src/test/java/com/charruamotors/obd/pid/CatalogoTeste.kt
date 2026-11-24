package com.charruamotors.obd.pid

import java.io.File

/** Catálogos reais de `src/main/assets`, lidos do disco nos testes JVM (sem Context/AssetManager). */
object CatalogoTeste {
    val padrao: CatalogoPids by lazy { CatalogoPids.deJson(asset(CatalogoPids.ARQUIVO_PADRAO)) }

    val estendido: CatalogoEstendido by lazy { CatalogoEstendido.deJson(asset(CatalogoEstendido.ARQUIVO_PADRAO)) }

    val cascata: CatalogoCascata by lazy { CatalogoCascata.deJson(asset(CatalogoCascata.ARQUIVO_PADRAO), padrao) }

    fun pid(comando: String): DefinicaoPid = padrao[comando] ?: error("PID $comando ausente do catálogo")

    private fun asset(nome: String): String {
        val arquivo = listOf("src/main/assets", "app/src/main/assets")
            .map { File(it, nome) }
            .firstOrNull { it.isFile }
            ?: error("$nome não encontrado a partir de ${File(".").absolutePath}")
        return arquivo.readText(Charsets.UTF_8)
    }
}
