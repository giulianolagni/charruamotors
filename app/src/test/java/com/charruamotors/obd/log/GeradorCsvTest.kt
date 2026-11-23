package com.charruamotors.obd.log

import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.pid.CatalogoTeste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class GeradorCsvTest {

    private val zona: ZoneId = ZoneOffset.ofHours(-3) // horário de Brasília, sem horário de verão
    private val inicio = LocalDateTime.of(2026, 11, 20, 14, 30, 5).atZone(zona).toInstant().toEpochMilli()

    private val rpm = CatalogoTeste.pid("010C") // 0 casas
    private val maf = CatalogoTeste.pid("0110") // 2 casas
    private val stft = CatalogoTeste.pid("0106") // 1 casa, pode ser negativo
    private val sonda = CatalogoTeste.pid("0114") // nome com vírgula: "banco 1, sensor 1"
    private val colunas = listOf(rpm, maf, stft, sonda)

    private fun sessao(vararg amostras: AmostraLog) = SessaoLog(inicio, colunas, amostras.toList())

    // ---------------------------------------------------------------- cabeçalho

    @Test
    fun `cabecalho tem colunas de tempo e nome unidade e comando de cada PID`() {
        val campos = GeradorCsv.cabecalho(listOf(rpm, maf)).split(',')
        assertEquals(
            listOf("timestamp_ms", "data_hora", "tempo_s", "Rotação do motor (rpm) [010C]", "Fluxo de massa de ar (MAF) (g/s) [0110]"),
            campos,
        )
    }

    @Test
    fun `campo do cabecalho com virgula vai entre aspas`() {
        val cabecalho = GeradorCsv.cabecalho(listOf(sonda))
        assertTrue(cabecalho, cabecalho.endsWith(",\"Tensão da sonda lambda — banco 1, sensor 1 (V) [0114]\""))
    }

    // ---------------------------------------------------------------- linhas

    @Test
    fun `linha com timestamp data hora tempo relativo e valores pelas casas do catalogo`() {
        val amostra = AmostraLog(inicio + 1_250, doubleArrayOf(1726.0, 4.356, -3.125, 0.8))
        assertEquals(
            "${inicio + 1_250},2026-11-20T14:30:06.250,1.250,1726,4.36,-3.1,0.800",
            GeradorCsv.linha(amostra, sessao(amostra), zona),
        )
    }

    @Test
    fun `PID sem resposta vira campo vazio e menos zero vira zero`() {
        val amostra = AmostraLog(inicio, doubleArrayOf(Double.NaN, 0.0, -0.01, Double.POSITIVE_INFINITY))
        val campos = GeradorCsv.linha(amostra, sessao(amostra), zona).split(',')
        assertEquals(listOf("", "0.00", "0.0", ""), campos.drop(3))
        assertEquals("0.000", campos[2]) // tempo_s da primeira amostra
    }

    @Test
    fun `numeros usam ponto decimal sem separador de milhar nem notacao cientifica`() {
        assertEquals("655350", GeradorCsv.formatarNumero(655_350.0, 0))
        assertEquals("1234567.89", GeradorCsv.formatarNumero(1_234_567.891, 2))
        assertEquals("0.000", GeradorCsv.formatarNumero(1e-7, 3))
        assertEquals("2.5", GeradorCsv.formatarNumero(2.45, 1)) // HALF_UP
    }

    @Test
    fun `amostra com quantidade de valores diferente das colunas e erro`() {
        val torta = AmostraLog(inicio, doubleArrayOf(1.0))
        assertThrows(IllegalArgumentException::class.java) { GeradorCsv.linha(torta, sessao(), zona) }
    }

    @Test
    fun `escapar segue a RFC 4180`() {
        assertEquals("simples", GeradorCsv.escapar("simples"))
        assertEquals("\"a,b\"", GeradorCsv.escapar("a,b"))
        assertEquals("\"diz \"\"oi\"\"\"", GeradorCsv.escapar("diz \"oi\""))
        assertEquals("\"duas\nlinhas\"", GeradorCsv.escapar("duas\nlinhas"))
    }

    // ---------------------------------------------------------------- arquivo completo

    @Test
    fun `arquivo completo com BOM cabecalho e uma linha por amostra em CRLF`() {
        val a1 = AmostraLog(inicio, doubleArrayOf(800.0, 2.5, 0.0, 0.1))
        val a2 = AmostraLog(inicio + 1_000, doubleArrayOf(2000.0, Double.NaN, 1.6, 0.75))
        val csv = GeradorCsv.gerar(sessao(a1, a2), zona)

        assertEquals(GeradorCsv.BOM, csv.first())
        assertTrue(csv.endsWith("\r\n"))
        val linhas = csv.drop(1).removeSuffix("\r\n").split("\r\n")
        assertEquals(3, linhas.size)
        assertTrue(linhas[0].startsWith("timestamp_ms,"))
        assertEquals("$inicio,2026-11-20T14:30:05.000,0.000,800,2.50,0.0,0.100", linhas[1])
        assertEquals("${inicio + 1_000},2026-11-20T14:30:06.000,1.000,2000,,1.6,0.750", linhas[2])
    }

    @Test
    fun `sessao sem amostras gera so o cabecalho`() {
        val csv = GeradorCsv.gerar(sessao(), zona)
        assertEquals(GeradorCsv.BOM + GeradorCsv.cabecalho(colunas) + "\r\n", csv)
    }

    @Test
    fun `nome do arquivo usa data e hora sem dois pontos`() {
        assertEquals("charrua_obd_2026-11-20_14-30-05.csv", GeradorCsv.nomeArquivo(inicio, zona))
        assertEquals("charrua_obd_SIMULADO_2026-11-20_14-30-05.csv", GeradorCsv.nomeArquivo(inicio, zona, simulado = true))
    }

    // ---------------------------------------------------------------- integração com o DataLogger

    @Test
    fun `logger grava leituras nas colunas fixadas no inicio`() {
        val logger = DataLogger()
        logger.iniciar(listOf(rpm, maf), inicio)
        assertTrue(logger.gravando)
        assertTrue(logger.registrar(DadosObd(mapOf("010C" to 900.0, "0110" to 3.1, "0105" to 90.0)), inicio + 500))
        assertTrue(logger.registrar(DadosObd(mapOf("010C" to 950.0)), inicio + 1_500)) // MAF sem resposta
        assertEquals(2, logger.quantidade)

        val s = logger.parar()!!
        assertFalse(logger.gravando)
        assertEquals(listOf("010C", "0110"), s.colunas.map { it.comando })
        val linhas = GeradorCsv.gerar(s, zona).drop(1).split("\r\n")
        assertTrue(linhas[1].endsWith(",0.500,900,3.10")) // 0105 não é coluna: ignorado
        assertTrue(linhas[2].endsWith(",1.500,950,"))
    }

    @Test
    fun `logger parado nao registra e parar sem gravar devolve null`() {
        val logger = DataLogger()
        assertFalse(logger.registrar(DadosObd(mapOf("010C" to 900.0)), inicio))
        assertNull(logger.parar())
    }

    @Test
    fun `logger respeita o limite de amostras`() {
        val logger = DataLogger(limiteAmostras = 2)
        logger.iniciar(listOf(rpm), inicio)
        assertTrue(logger.registrar(DadosObd(mapOf("010C" to 1.0)), inicio))
        assertTrue(logger.registrar(DadosObd(mapOf("010C" to 2.0)), inicio + 1))
        assertTrue(logger.cheio)
        assertFalse(logger.registrar(DadosObd(mapOf("010C" to 3.0)), inicio + 2))
        assertEquals(2, logger.parar()!!.amostras.size)
    }

    @Test
    fun `nova gravacao descarta a anterior e colunas invalidas sao rejeitadas`() {
        val logger = DataLogger()
        logger.iniciar(listOf(rpm), inicio)
        logger.registrar(DadosObd(mapOf("010C" to 1.0)), inicio)
        logger.iniciar(listOf(maf), inicio + 10)
        assertEquals(0, logger.quantidade)
        assertThrows(IllegalArgumentException::class.java) { logger.iniciar(emptyList(), inicio) }
        assertThrows(IllegalArgumentException::class.java) { logger.iniciar(listOf(rpm, rpm), inicio) }
    }
}
