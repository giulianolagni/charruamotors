package com.charruamotors.obd.aprendizado

import com.charruamotors.obd.log.ItemLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.StringReader
import kotlin.math.sin

/** v2.2.5: apagar logs no Histórico tira o vínculo do índice e refaz o aprendizado do veículo. */
class ExclusaoLogsTest {

    @get:Rule
    val pasta = TemporaryFolder()

    private lateinit var indice: IndiceLogsReais
    private lateinit var aprendiz: AprendizLogsVeiculo
    private lateinit var pastaCache: File

    /** "Armazenamento": uri → conteúdo; apagar um arquivo o remove daqui. */
    private val arquivos = mutableMapOf<String, String>()

    private val sw4 = 1L
    private val cruze = 2L

    private data class Item(override val nome: String, override val modificadoMs: Long = 0) : ItemLog

    @Before
    fun montar() {
        pastaCache = pasta.newFolder("aprendizado")
        indice = IndiceLogsReais(File(pastaCache, "indice.json"))
        aprendiz = AprendizLogsVeiculo(pastaCache, indice) { e -> arquivos[e.uri]?.let(::StringReader) }
    }

    private fun csv(linhas: Int = 120, amplitude: Double = 50.0): String = buildString {
        append("﻿timestamp_ms,data_hora,tempo_s,RPM do motor (rpm) [010C],Velocidade (km/h) [010D],Carga do motor (%) [0104]\r\n")
        for (i in 0 until linhas) {
            val v = (60 + amplitude * sin(i / 10.0)).coerceAtLeast(0.0)
            append("${1_700_000_000_000 + i * 1000L},2026-11-24T10:00:00.000,$i,${(800 + v * 30).toInt()},${v.toInt()},${(20 + v * 0.5).toInt()}\r\n")
        }
    }

    private fun registrar(veiculoId: Long, nome: String) {
        arquivos["content://logs/$nome"] = csv()
        assertTrue(indice.registrar(veiculoId, nome, "content://logs/$nome", simulado = false))
    }

    private fun apagarDoArmazenamento(item: Item): Boolean = arquivos.keys.removeIf { it.endsWith("/${item.nome}") }

    @Test
    fun `apagar um csv remove o vinculo do indice e refaz o cache sem ele`() {
        registrar(sw4, "CharruaOBD_20261120_100000_Toyota_SW4.csv")
        registrar(sw4, "CharruaOBD_20261121_100000_Toyota_SW4.csv")
        assertEquals(2, aprendiz.atualizar(sw4)!!.quantidadeLogs)

        val r = ExclusaoLogs.executar(listOf(Item("CharruaOBD_20261120_100000_Toyota_SW4.csv")), aprendiz, ::apagarDoArmazenamento)

        assertEquals(listOf("CharruaOBD_20261120_100000_Toyota_SW4.csv"), r.apagados)
        assertEquals(listOf("CharruaOBD_20261121_100000_Toyota_SW4.csv"), indice.entradas(sw4).map { it.nome })
        val cache = aprendiz.carregar(sw4)!!
        assertEquals("o cache só tem o log que sobrou", listOf("CharruaOBD_20261121_100000_Toyota_SW4.csv"), cache.logs.map { it.nome })
    }

    @Test
    fun `apagar o ultimo log do veiculo remove o cache e volta ao sintetico`() {
        registrar(sw4, "CharruaOBD_20261120_100000_Toyota_SW4.csv")
        aprendiz.atualizar(sw4)
        assertTrue(aprendiz.arquivoCache(sw4).isFile)

        ExclusaoLogs.executar(listOf(Item("CharruaOBD_20261120_100000_Toyota_SW4.csv")), aprendiz, ::apagarDoArmazenamento)

        assertTrue(indice.entradas(sw4).isEmpty())
        assertFalse(aprendiz.arquivoCache(sw4).exists())
        assertNull(aprendiz.atualizar(sw4))
    }

    @Test
    fun `apagar log de um veiculo nao mexe no indice nem no cache do outro`() {
        registrar(sw4, "CharruaOBD_20261120_100000_Toyota_SW4.csv")
        registrar(cruze, "CharruaOBD_20261120_110000_Chevrolet_Cruze.csv")
        aprendiz.atualizar(sw4)
        aprendiz.atualizar(cruze)
        val cacheCruze = aprendiz.arquivoCache(cruze).readText()

        ExclusaoLogs.executar(listOf(Item("CharruaOBD_20261120_100000_Toyota_SW4.csv")), aprendiz, ::apagarDoArmazenamento)

        assertEquals(1, indice.entradas(cruze).size)
        assertEquals(cacheCruze, aprendiz.arquivoCache(cruze).readText())
        assertEquals(1, aprendiz.carregar(cruze)!!.quantidadeLogs)
    }

    @Test
    fun `arquivo que nao foi possivel apagar continua no indice e no aprendizado`() {
        registrar(sw4, "CharruaOBD_20261120_100000_Toyota_SW4.csv")
        aprendiz.atualizar(sw4)

        val r = ExclusaoLogs.executar(listOf(Item("CharruaOBD_20261120_100000_Toyota_SW4.csv")), aprendiz) { false }

        assertEquals(listOf("CharruaOBD_20261120_100000_Toyota_SW4.csv"), r.falhas)
        assertTrue(r.apagados.isEmpty())
        assertEquals(1, indice.entradas(sw4).size)
        assertEquals(1, aprendiz.carregar(sw4)!!.quantidadeLogs)
    }

    @Test
    fun `excecao ao apagar vira falha e nao interrompe os demais`() {
        registrar(sw4, "CharruaOBD_20261120_100000_Toyota_SW4.csv")
        registrar(sw4, "CharruaOBD_20261121_100000_Toyota_SW4.csv")
        aprendiz.atualizar(sw4)

        val r = ExclusaoLogs.executar(
            listOf(Item("CharruaOBD_20261120_100000_Toyota_SW4.csv"), Item("CharruaOBD_20261121_100000_Toyota_SW4.csv")),
            aprendiz,
        ) { item -> if (item.nome.contains("1120")) throw SecurityException("sem acesso") else apagarDoArmazenamento(item) }

        assertEquals(1, r.falhas.size)
        assertEquals(listOf("CharruaOBD_20261121_100000_Toyota_SW4.csv"), r.apagados)
        assertEquals(listOf("CharruaOBD_20261120_100000_Toyota_SW4.csv"), aprendiz.carregar(sw4)!!.logs.map { it.nome })
    }

    @Test
    fun `cache que cita o arquivo e refeito mesmo com o indice perdido`() {
        registrar(sw4, "CharruaOBD_20261120_100000_Toyota_SW4.csv")
        registrar(sw4, "CharruaOBD_20261121_100000_Toyota_SW4.csv")
        aprendiz.atualizar(sw4)
        // Índice recriado só com o log que sobra: o cache ainda cita o apagado.
        File(pastaCache, "indice.json").delete()
        indice.registrar(sw4, "CharruaOBD_20261121_100000_Toyota_SW4.csv", "content://logs/CharruaOBD_20261121_100000_Toyota_SW4.csv", false)

        val refeitos = aprendiz.logsApagados(listOf("CharruaOBD_20261120_100000_Toyota_SW4.csv"))

        assertEquals(setOf(sw4), refeitos)
        assertEquals(listOf("CharruaOBD_20261121_100000_Toyota_SW4.csv"), aprendiz.carregar(sw4)!!.logs.map { it.nome })
    }

    @Test
    fun `apagar so o txt nao mexe no aprendizado do csv`() {
        registrar(sw4, "CharruaOBD_20261120_100000_Toyota_SW4.csv")
        aprendiz.atualizar(sw4)

        ExclusaoLogs.executar(listOf(Item("CharruaOBD_20261120_100000_Toyota_SW4.txt")), aprendiz) { true }

        assertEquals(1, indice.entradas(sw4).size)
        assertNotNull(aprendiz.carregar(sw4))
        assertEquals(1, aprendiz.carregar(sw4)!!.quantidadeLogs)
    }

    // ------------------------------------------------------------------ "Limpar logs": do veículo atual x todos

    @Test
    fun `limpar do veiculo pega csv indexado, txt da mesma sessao e logs antigos pelo nome`() {
        val todos = listOf(
            "CharruaOBD_20261120_100000_Toyota_SW4.csv", // indexado
            "CharruaOBD_20261120_100000_Toyota_SW4.txt", // relatório da mesma sessão
            "CharruaOBD_20261001_090000_Toyota_SW4.csv", // anterior ao índice, mesmo veículo pelo nome
            "CharruaOBD_SIMULADO_20261002_090000_Toyota_SW4.txt", // simulado antigo do mesmo veículo
            "CharruaOBD_20261120_110000_Chevrolet_Cruze.csv", // outro veículo
            "CharruaOBD_20261120_100000_Toyota_SW4_Limited.csv", // nome parecido, outro carro
            "charrua_obd_2026-11-20_10-00-00.csv", // Data Logger manual
        ).map(::Item)

        val doVeiculo = ExclusaoLogs.doVeiculo(todos, listOf("CharruaOBD_20261120_100000_Toyota_SW4.csv"), "Toyota_SW4")

        assertEquals(
            listOf(
                "CharruaOBD_20261120_100000_Toyota_SW4.csv",
                "CharruaOBD_20261120_100000_Toyota_SW4.txt",
                "CharruaOBD_20261001_090000_Toyota_SW4.csv",
                "CharruaOBD_SIMULADO_20261002_090000_Toyota_SW4.txt",
            ),
            doVeiculo.map { it.nome },
        )
    }

    @Test
    fun `limpar todos zera indice e caches de todos os veiculos`() {
        registrar(sw4, "CharruaOBD_20261120_100000_Toyota_SW4.csv")
        registrar(cruze, "CharruaOBD_20261120_110000_Chevrolet_Cruze.csv")
        aprendiz.atualizar(sw4)
        aprendiz.atualizar(cruze)

        val todos = arquivos.keys.map { Item(it.substringAfterLast('/')) }
        val r = ExclusaoLogs.executar(todos, aprendiz, ::apagarDoArmazenamento)

        assertEquals(2, r.apagados.size)
        assertTrue(indice.todas().isEmpty())
        assertFalse(aprendiz.arquivoCache(sw4).exists())
        assertFalse(aprendiz.arquivoCache(cruze).exists())
    }

    @Test
    fun `sessao simulada continua fora do indice`() {
        assertFalse(indice.registrar(sw4, "CharruaOBD_SIMULADO_20261120_100000_Toyota_SW4.csv", "content://x", simulado = true))
        assertFalse(indice.registrar(sw4, "CharruaOBD_SIMULADO_20261120_100000_Toyota_SW4.csv", "content://x", simulado = false))
        assertTrue(indice.todas().isEmpty())
    }
}
