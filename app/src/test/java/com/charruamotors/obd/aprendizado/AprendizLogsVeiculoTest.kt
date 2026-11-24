package com.charruamotors.obd.aprendizado

import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.ObdClientFake
import com.charruamotors.obd.SimuladorCruze
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.pid.Pids
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** v2.2.4: aprendizado por veículo dos CSVs reais — isolamento por `veiculoId`, cache incremental e fallback seguro. */
class AprendizLogsVeiculoTest {

    @get:Rule
    val pasta = TemporaryFolder()

    private lateinit var indice: IndiceLogsReais
    private lateinit var aprendiz: AprendizLogsVeiculo
    private lateinit var pastaCache: File

    /** "Armazenamento": uri → conteúdo do CSV; [leituras] conta quantas vezes cada um foi aberto. */
    private val arquivos = mutableMapOf<String, String>()
    private val leituras = mutableMapOf<String, Int>()

    private val sw4 = 1L
    private val cruze = 2L
    private val scanner = DispositivoObd("T", "00:00:00:00:00:01")

    @Before
    fun montar() {
        pastaCache = pasta.newFolder("aprendizado")
        indice = IndiceLogsReais(File(pastaCache, "indice.json"))
        aprendiz = novoAprendiz()
    }

    private fun novoAprendiz() = AprendizLogsVeiculo(pastaCache, indice) { e ->
        leituras.merge(e.uri, 1, Int::plus)
        arquivos[e.uri]?.let(::StringReader)
    }

    private fun fake(semente: Int = 1) =
        ObdClientFake(random = Random(semente), latenciaMs = 0, latenciaComandoMs = 0, catalogoEstendido = CatalogoTeste.estendido)

    private fun csv(linhas: Int = 120, amplitude: Double = 50.0, extra: String? = null, inicio: Long = 1_700_000_000_000): String = buildString {
        append("﻿timestamp_ms,data_hora,tempo_s,RPM do motor (rpm) [010C],Velocidade (km/h) [010D],Carga do motor (%) [0104],Pressão do coletor (kPa) [010B]")
        if (extra != null) append(",$extra (u) [$extra]")
        append("\r\n")
        for (i in 0 until linhas) {
            val v = (60 + amplitude * sin(i / 10.0)).coerceAtLeast(0.0)
            append("${inicio + i * 1000L},2026-11-24T10:00:00.000,$i,${(800 + v * 30).toInt()},${v.toInt()},${(20 + v * 0.5).toInt()},${(30 + v).toInt()}")
            if (extra != null) append(",${80 + i % 5}")
            append("\r\n")
        }
    }

    private fun registrar(veiculoId: Long, nome: String, conteudo: String, simulado: Boolean = false): Boolean {
        val uri = "content://logs/$nome"
        arquivos[uri] = conteudo
        return indice.registrar(veiculoId, nome, uri, simulado)
    }

    // ------------------------------------------------------------------ isolamento

    @Test
    fun `SW4 com logs reais e Cruze sem logs - Cruze segue sintetico e nao puxa nada da SW4`() = runTest {
        assertTrue(registrar(sw4, "CharruaOBD_20261120_100000_Toyota_SW4.csv", csv(extra = "TOYOTA_ATF_CAMBIO")))
        val modeloSw4 = aprendiz.atualizar(sw4)!!
        assertEquals(1, modeloSw4.quantidadeLogs)
        assertTrue(modeloSw4.utilizavel)

        // Cruze: nenhum CSV real registrado para ele.
        assertNull(aprendiz.atualizar(cruze))
        assertFalse(aprendiz.arquivoCache(cruze).exists())
        assertTrue(aprendiz.arquivoCache(sw4).exists())

        // No Modo Simulado, sem modelo, o Cruze segue no simulador sintético dele.
        val fake = fake(3)
        fake.definirModeloAprendido(aprendiz.atualizar(cruze))
        fake.conectar(scanner, "Chevrolet")
        assertEquals(FonteSimulacao.Sintetica, fake.fonteSimulacao.value)
        assertEquals(SimuladorCruze.COMANDOS, fake.pidsSuportados.value)
    }

    @Test
    fun `cada veiculo aprende so dos proprios logs`() {
        registrar(sw4, "sw4.csv", csv(extra = "TOYOTA_ATF_CAMBIO"))
        registrar(cruze, "cruze.csv", csv(amplitude = 20.0, extra = "GM_ATF"))
        val a = aprendiz.atualizar(sw4)!!
        val b = aprendiz.atualizar(cruze)!!
        assertEquals(listOf("sw4.csv"), a.logs.map { it.nome })
        assertEquals(listOf("cruze.csv"), b.logs.map { it.nome })
        assertTrue(a.trechos.all { "GM_ATF" !in it.comandos } && a.faixas.values.all { "GM_ATF" !in it.keys })
        assertTrue(b.trechos.all { "TOYOTA_ATF_CAMBIO" !in it.comandos } && b.faixas.values.all { "TOYOTA_ATF_CAMBIO" !in it.keys })
        assertEquals(1, leituras["content://logs/cruze.csv"])
        assertEquals(1, leituras["content://logs/sw4.csv"])
        // Cada modelo no seu arquivo.
        assertEquals(sw4, ModeloAprendido.deJson(aprendiz.arquivoCache(sw4).readText())!!.veiculoId)
        assertEquals(cruze, ModeloAprendido.deJson(aprendiz.arquivoCache(cruze).readText())!!.veiculoId)
    }

    @Test
    fun `cache de outro veiculo copiado no arquivo errado e rejeitado`() {
        registrar(sw4, "sw4.csv", csv())
        registrar(cruze, "cruze.csv", csv())
        aprendiz.atualizar(sw4)
        aprendiz.arquivoCache(sw4).copyTo(aprendiz.arquivoCache(cruze))
        // O cache do Cruze diz veiculoId=1: é descartado e refeito só com os CSVs do Cruze.
        assertNull(aprendiz.carregar(cruze))
        assertEquals(listOf("cruze.csv"), aprendiz.atualizar(cruze)!!.logs.map { it.nome })
    }

    // ------------------------------------------------------------------ sessões simuladas

    @Test
    fun `sessoes simuladas nunca entram no indice nem no aprendizado`() {
        assertFalse(registrar(sw4, "CharruaOBD_20261120_100000_Toyota_SW4.csv", csv(), simulado = true))
        assertFalse(registrar(sw4, "CharruaOBD_SIMULADO_20261120_100000_Toyota_SW4.csv", csv()))
        assertFalse(registrar(sw4, "charrua_obd_SIMULADO_2026-11-20_10-00-00.csv", csv()))
        assertFalse(registrar(0, "sem_veiculo.csv", csv()))
        assertTrue(indice.entradas(sw4).isEmpty())
        assertNull(aprendiz.atualizar(sw4))
        // Real + simulada: só a real conta.
        registrar(sw4, "CharruaOBD_20261121_100000_Toyota_SW4.csv", csv())
        registrar(sw4, "CharruaOBD_SIMULADO_20261121_110000_Toyota_SW4.csv", csv())
        assertEquals(1, aprendiz.atualizar(sw4)!!.quantidadeLogs)
        assertNull(leituras["content://logs/CharruaOBD_SIMULADO_20261121_110000_Toyota_SW4.csv"])
    }

    // ------------------------------------------------------------------ incremental

    @Test
    fun `cache e incremental - so le o CSV novo e soma as faixas`() {
        registrar(sw4, "a.csv", csv(120))
        val m1 = aprendiz.atualizar(sw4)!!
        assertEquals(120, m1.faixas.values.sumOf { it[Pids.RPM]?.n ?: 0 })

        // Sem novidade: nada é relido.
        aprendiz.atualizar(sw4)
        assertEquals(1, leituras["content://logs/a.csv"])

        registrar(sw4, "b.csv", csv(60, inicio = 1_700_100_000_000))
        val m2 = aprendiz.atualizar(sw4)!!
        assertEquals(listOf("a.csv", "b.csv"), m2.logs.map { it.nome })
        assertEquals(1, leituras["content://logs/a.csv"])
        assertEquals(1, leituras["content://logs/b.csv"])
        assertEquals(180, m2.faixas.values.sumOf { it[Pids.RPM]?.n ?: 0 })
        assertEquals(2, m2.trechos.size)

        // Persistido: um novo aprendiz (processo reiniciado) enxerga o mesmo cache sem reler nada.
        leituras.clear()
        assertEquals(2, novoAprendiz().atualizar(sw4)!!.quantidadeLogs)
        assertTrue(leituras.isEmpty())
    }

    @Test
    fun `faixas separam lenta, cruzeiro e carga por RPM e velocidade`() {
        registrar(sw4, "a.csv", csv(200, amplitude = 70.0))
        val m = aprendiz.atualizar(sw4)!!
        val lenta = m.faixa(RegimeConducao.LENTA, Pids.RPM)
        val cruzeiro = m.faixa(RegimeConducao.CRUZEIRO, Pids.VELOCIDADE)
        assertNotNull(lenta)
        assertNotNull(cruzeiro)
        assertTrue(lenta!!.maximo <= 1200.0)
        assertTrue(cruzeiro!!.media > 3.0)
        assertTrue(m.faixa(RegimeConducao.CARGA, Pids.RPM)!!.minimo >= 1200.0)
    }

    @Test
    fun `CSV que sumiu do armazenamento e pulado sem marcar como ignorado`() {
        indice.registrar(sw4, "fantasma.csv", "content://logs/fantasma.csv", simulado = false)
        registrar(sw4, "real.csv", csv())
        val m = aprendiz.atualizar(sw4)!!
        assertEquals(listOf("real.csv"), m.logs.map { it.nome })
        assertTrue(m.ignorados.isEmpty())
        // Voltou a existir: entra na próxima.
        arquivos["content://logs/fantasma.csv"] = csv(inicio = 1_700_200_000_000)
        assertEquals(2, aprendiz.atualizar(sw4)!!.quantidadeLogs)
    }

    // ------------------------------------------------------------------ fallback seguro

    @Test
    fun `CSV corrompido e ignorado e o resto do aprendizado segue`() {
        registrar(sw4, "ruim1.csv", "isto nao e csv")
        registrar(sw4, "ruim2.csv", "")
        registrar(sw4, "ruim3.csv", "timestamp_ms,tempo_s\r\n1,0\r\n")
        registrar(sw4, "ruim4.csv", "timestamp_ms,RPM (rpm) [010C]\r\n" + "x,y\r\n".repeat(30))
        registrar(sw4, "bom.csv", csv())
        val m = aprendiz.atualizar(sw4)!!
        assertEquals(listOf("bom.csv"), m.logs.map { it.nome })
        assertEquals(4, m.ignorados.size)
        assertTrue(m.utilizavel)
        // Não tenta de novo.
        aprendiz.atualizar(sw4)
        assertEquals(1, leituras["content://logs/ruim1.csv"])
    }

    @Test
    fun `so CSVs corrompidos - sem modelo utilizavel e o Modo Simulado cai no sintetico sem erro`() = runTest {
        registrar(sw4, "ruim.csv", "lixo\u0000\u0001\r\n,,,\r\n")
        val m = aprendiz.atualizar(sw4)!!
        assertEquals(0, m.quantidadeLogs)
        assertFalse(m.utilizavel)
        val fake = fake()
        fake.definirModeloAprendido(m)
        assertTrue(fake.conectar(scanner, "Toyota").isSuccess)
        assertEquals(FonteSimulacao.Sintetica, fake.fonteSimulacao.value)
        assertEquals(ObdClientFake.COMANDOS_SIMULADOS, fake.pidsSuportados.value)
    }

    @Test
    fun `cache em disco corrompido e refeito a partir dos CSVs`() {
        registrar(sw4, "a.csv", csv())
        aprendiz.atualizar(sw4)
        aprendiz.arquivoCache(sw4).writeText("{ quebrado ]")
        assertNull(aprendiz.carregar(sw4))
        val refeito = aprendiz.atualizar(sw4)!!
        assertEquals(1, refeito.quantidadeLogs)
        assertNotNull(aprendiz.carregar(sw4))
    }

    @Test
    fun `indice corrompido e tratado como vazio`() {
        File(pastaCache, "indice.json").writeText("[[[")
        assertTrue(indice.entradas(sw4).isEmpty())
        assertNull(aprendiz.atualizar(sw4))
        assertTrue(registrar(sw4, "a.csv", csv()))
        assertEquals(1, aprendiz.atualizar(sw4)!!.quantidadeLogs)
    }

    @Test
    fun `JSON do modelo preserva tudo, inclusive lacunas NaN`() {
        registrar(sw4, "a.csv", csv(extra = "TOYOTA_ATF_CAMBIO"))
        val m = aprendiz.atualizar(sw4)!!
        val t = TrechoReal(listOf(Pids.RPM, "X"), listOf(doubleArrayOf(900.0, Double.NaN), doubleArrayOf(1000.5, 3.0)))
        val volta = ModeloAprendido.deJson(m.copy(trechos = m.trechos + t).paraJson())!!
        assertEquals(m.logs, volta.logs)
        assertEquals(m.faixas, volta.faixas)
        assertEquals(m.trechos.size + 1, volta.trechos.size)
        assertTrue(volta.trechos.last().linhas[0][1].isNaN())
        assertEquals(1000.5, volta.trechos.last().linhas[1][0], 0.0)
        assertNull(ModeloAprendido.deJson("nada"))
    }

    // ------------------------------------------------------------------ reprodução

    @Test
    fun `reprodutor faz loop suave sem saltos nas emendas`() {
        registrar(sw4, "a.csv", csv(120, amplitude = 50.0))
        val modelo = aprendiz.atualizar(sw4)!!
        val r = ReprodutorTrechos(modelo.trechos)
        var anterior = r.proxima(0.1)[Pids.VELOCIDADE]!!
        var maiorSalto = 0.0
        repeat(5_000) { // ~500 s: várias voltas no ciclo de ~90 s
            val v = r.proxima(0.1)[Pids.VELOCIDADE]!!
            maiorSalto = maxOf(maiorSalto, abs(v - anterior))
            anterior = v
        }
        assertTrue("salto de $maiorSalto km/h em 100 ms", maiorSalto < 5.0)
    }

    @Test
    fun `Modo Simulado com modelo anima o Painel com os sensores do carro real`() = runTest {
        registrar(sw4, "a.csv", csv(120, extra = "TOYOTA_ATF_CAMBIO"))
        val modelo = aprendiz.atualizar(sw4)!!
        val fake = fake()
        fake.definirModeloAprendido(modelo)
        fake.conectar(scanner, "Toyota")

        assertEquals(FonteSimulacao.LogsReais(1), fake.fonteSimulacao.value)
        assertEquals(setOf(Pids.RPM, Pids.VELOCIDADE, Pids.CARGA_MOTOR, Pids.PRESSAO_COLETOR), fake.pidsSuportados.value)
        val amostras = fake.lerTelemetria(intervaloMinimoMs = 100).take(40).toList()
        val rpms = amostras.mapNotNull { it[Pids.RPM] }
        assertTrue(rpms.isNotEmpty())
        // Todos os valores caem dentro da faixa observada no CSV real (800 + 30·v, v em ~10..110).
        assertTrue(rpms.all { it in 800.0..4200.0 })
        assertTrue(amostras.all { a -> a.valores.keys.all { k -> k in fake.pidsSuportados.value } })
        assertTrue(rpms.distinct().size > 3)
    }

    @Test
    fun `modelo sem RPM nao e utilizavel e mantem o sintetico`() = runTest {
        val m = ModeloAprendido(sw4, logs = listOf(LogIncorporado("a.csv", 50)), trechos = listOf(TrechoReal(listOf(Pids.VELOCIDADE), listOf(doubleArrayOf(10.0)))))
        assertFalse(m.utilizavel)
        val fake = fake()
        fake.definirModeloAprendido(m)
        fake.conectar(scanner, "Toyota")
        assertEquals(FonteSimulacao.Sintetica, fake.fonteSimulacao.value)
    }
}
