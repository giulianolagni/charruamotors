package com.charruamotors.domain.perfis

import java.io.InputStream

/**
 * De onde vêm os arquivos de `perfis/` ([caminho] relativo a ela: `veiculos.json`,
 * `motores/toyota_1kd_ftv.json`). Null = arquivo ausente. No app, os assets; nos testes JVM,
 * o classpath (os assets entram como recursos de teste) ou o disco.
 */
fun interface FontePerfis {
    fun ler(caminho: String): String?

    companion object {
        /** Adapta um "abridor" de streams (ex.: `AssetManager.open`) que lança quando o arquivo falta. */
        fun deStreams(abrir: (String) -> InputStream): FontePerfis = FontePerfis { caminho ->
            runCatching { abrir("${CatalogoPerfis.PASTA}/$caminho").bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()
        }

        /** Recursos `perfis/...` do [classLoader] (testes JVM, sem Context do Android). */
        fun doClasspath(classLoader: ClassLoader? = CarregadorPerfis::class.java.classLoader): FontePerfis = FontePerfis { caminho ->
            classLoader?.getResourceAsStream("${CatalogoPerfis.PASTA}/$caminho")?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }
    }
}

/**
 * Catálogo de perfis em uso pelo app, carregado sob demanda uma única vez. O `Application` instala
 * a fonte dos assets em [instalar]; sem instalação (testes JVM), lê os recursos do classpath. Se nada
 * carregar, o catálogo fica vazio e o app cai nas faixas do OBD2 padrão ([com.charruamotors.domain.faixas.FaixasVeiculo.genericas]).
 */
object CarregadorPerfis {

    @Volatile private var fonte: FontePerfis? = null
    @Volatile private var cache: CatalogoPerfis? = null

    /** Troca a fonte dos arquivos (e descarta o catálogo já carregado). */
    fun instalar(nova: FontePerfis) {
        synchronized(this) {
            fonte = nova
            cache = null
        }
    }

    /** Usa um catálogo pronto (testes). */
    fun usar(catalogo: CatalogoPerfis) {
        synchronized(this) { cache = catalogo }
    }

    val catalogo: CatalogoPerfis
        get() = cache ?: synchronized(this) {
            cache ?: runCatching { CatalogoPerfis.carregar(fonte ?: FontePerfis.doClasspath()) }
                .getOrElse { CatalogoPerfis(emptyList(), emptyMap(), emptyMap(), listOf("falha ao carregar: ${it.message}")) }
                .also { cache = it }
        }
}
