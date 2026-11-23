package com.charruamotors.obd

import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.pid.RequisicaoEstendida
import com.charruamotors.sistema.unidades.ConversorUnidades
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Pedal (2149/214A), marcha/alavanca, ATF, 4x4 e VSC da Toyota SW4: catálogo, decodificação e Painel. */
class SensoresSw4Test {

    private val catalogo = CatalogoTeste.estendido
    private val pedalD = catalogo["TOYOTA_PEDAL_D"]!!
    private val pedalE = catalogo["TOYOTA_PEDAL_E"]!!

    // ---------------------------------------------------------------- pedal do acelerador

    @Test
    fun `pedal APP D e E sao sondados no motor 7E0 pelo modo 21 2149 e 214A`() {
        assertEquals(RequisicaoEstendida("7E0", "21", "49"), pedalD.estendida)
        assertEquals(RequisicaoEstendida("7E0", "21", "4A"), pedalE.estendida)
        assertEquals("7E0 · 2149", pedalD.rotuloComando)
        assertEquals("%", pedalD.unidade)
        assertEquals(0.0, pedalD.min, 0.0)
        assertEquals(100.0, pedalD.max, 0.0)
        // Nunca confundido com a borboleta (2111 / 0111).
        assertTrue(pedalD.nome.contains("Pedal"))
        assertTrue(pedalD.comando != CatalogoTeste.pid("0111").comando)
        assertTrue(pedalD.estendida!!.comando != "2111")
    }

    @Test
    fun `decodifica 2149 e 214A em porcentagem de 0 a 100`() {
        // A = 0x26 (38) → 14,9 % (pedal solto); A = 0xCC (204) → 80 % (pé embaixo).
        assertEquals(38 * 100.0 / 255, (ObdParser.parsePid("614926\r\r", pedalD) as RespostaObd.Ok).valor, 1e-9)
        assertEquals(80.0, (ObdParser.parsePid("614ACC\r\r", pedalE) as RespostaObd.Ok).valor, 1e-9)
        assertEquals(100.0, (ObdParser.parsePid("6149FF\r\r", pedalD) as RespostaObd.Ok).valor, 1e-9)
        assertEquals(0.0, (ObdParser.parsePid("614900\r\r", pedalD) as RespostaObd.Ok).valor, 1e-9)
        // Com espaços e eco, como num clone sem ATS0.
        assertEquals(80.0, (ObdParser.parsePid("2149\r61 49 CC\r\r", pedalD, "2149") as RespostaObd.Ok).valor, 1e-9)
    }

    @Test
    fun `pedal sem resposta, negativa ou de outro identificador nao vira leitura`() {
        assertEquals(RespostaObd.SemDados, ObdParser.parsePid("7F2131\r\r", pedalD))
        assertTrue(ObdParser.parsePid("NO DATA\r\r", pedalD) !is RespostaObd.Ok)
        assertTrue(ObdParser.parsePid("614ACC\r\r", pedalD) !is RespostaObd.Ok) // resposta do 214A não serve ao 2149
    }

    // ---------------------------------------------------------------- marcha, alavanca, ATF, 4x4 e VSC

    @Test
    fun `marcha e alavanca respondem no 7E1 e no 7E0 com rotulos P R N D 3 2 L`() {
        val alavanca = catalogo["TOYOTA_ALAVANCA"]!!
        assertEquals("7E1 · 21D9", alavanca.rotuloComando)
        assertEquals("7E0 · 21D9", catalogo["TOYOTA_ALAVANCA_ECM"]!!.rotuloComando)
        assertEquals("7E1 · 2130", catalogo["TOYOTA_MARCHA"]!!.rotuloComando)
        assertNull(catalogo["TOYOTA_MARCHA_ECM"]) // 7E0 2130 é contador de aquecimentos, não marcha
        assertEquals("7E0 · 2182", catalogo["TOYOTA_ATF_ECM"]!!.rotuloComando)
        assertEquals(listOf("P", "R", "N", "D", "3", "2", "L"), (0..6).map { alavanca.rotulo(it.toDouble()) })
        // Fallback pelo motor (7E0 · 21D9, 3 bytes): 00 41 A0 = P.
        val ecm = catalogo["TOYOTA_ALAVANCA_ECM"]!!
        assertEquals("P", ecm.rotulo((ObdParser.parsePid("61D90041A0\r\r", ecm) as RespostaObd.Ok).valor))
        assertEquals("4ª", catalogo["TOYOTA_MARCHA"]!!.rotulo((ObdParser.parsePid("6130040000\r\r", catalogo["TOYOTA_MARCHA"]!!) as RespostaObd.Ok).valor))
        // Fora da faixa (byte desconhecido): não entra no painel.
        assertTrue(ObdParser.parsePid("61D9FF\r\r", ecm) is RespostaObd.Invalido)
        assertTrue(ObdParser.parsePid("61D9FF\r\r", alavanca) !is RespostaObd.Ok) // curto demais para o A750F
        assertNull(alavanca.rotulo(2.5))
    }

    @Test
    fun `4x4 no painel 7C0 e VSC no ABS 7B0`() {
        val tracao = catalogo["TOYOTA_4WD"]!!
        val vsc = catalogo["TOYOTA_VSC"]!!
        assertEquals("7C0", tracao.estendida!!.cabecalho)
        assertEquals("7B0", vsc.estendida!!.cabecalho)
        assertEquals("4x4 (H4)", tracao.rotulo(1.0))
        assertEquals("Reduzida (L4)", tracao.rotulo(3.0))
        assertEquals("VSC OFF", vsc.rotulo((ObdParser.parsePid("610302000002\r\r", vsc) as RespostaObd.Ok).valor))
    }

    @Test
    fun `filtro de recepcao ATCRA so para modulos fora de 7E0 a 7E7`() {
        assertEquals("7B8", RequisicaoEstendida.filtroRecepcao("7B0"))
        assertEquals("7C8", RequisicaoEstendida.filtroRecepcao("7c0"))
        assertNull(RequisicaoEstendida.filtroRecepcao("7E0"))
        assertNull(RequisicaoEstendida.filtroRecepcao("7E1"))
        assertNull(RequisicaoEstendida.filtroRecepcao("7DF"))
        assertNull(RequisicaoEstendida.filtroRecepcao(null))
        assertNull(RequisicaoEstendida.filtroRecepcao("18DA10F1")) // 29 bits
    }

    // ---------------------------------------------------------------- Painel / Lista Rápida

    @Test
    fun `mostradores novos leem o PID principal ou o alternativo`() {
        val c = ConversorUnidades.PADRAO
        assertEquals("Pedal Acelerador", SensorPainel.ACELERADOR.titulo)
        // Sem o 0149 do modo 01, o pedal vem do 2149 Toyota.
        assertEquals(42.0, SensorPainel.ACELERADOR.ler(DadosObd(mapOf("TOYOTA_PEDAL_D" to 42.0)))!!, 0.0)
        assertEquals(18.0, SensorPainel.ACELERADOR.ler(DadosObd(mapOf("0149" to 18.0, "TOYOTA_PEDAL_D" to 42.0)))!!, 0.0)
        // Alavanca pelo câmbio ou, sem ele, pelo motor; com rótulo no lugar do número.
        assertEquals("D", SensorPainel.MARCHA_ALAVANCA.texto(SensorPainel.MARCHA_ALAVANCA.ler(DadosObd(mapOf("TOYOTA_ALAVANCA_ECM" to 3.0))), c))
        assertEquals("R", SensorPainel.MARCHA_ALAVANCA.texto(1.0, c))
        assertEquals("H4 bloqueado", SensorPainel.TRACAO_4X4.texto(2.0, c))
        assertEquals("Atuando", SensorPainel.VSC.texto(1.0, c))
        assertEquals("78", SensorPainel.TEMP_CAMBIO.texto(SensorPainel.TEMP_CAMBIO.ler(DadosObd(mapOf("TOYOTA_ATF_ECM" to 78.0))), c))
        assertEquals("Temp. Câmbio (ATF)", SensorPainel.TEMP_CAMBIO.titulo)
        assertEquals(32.0, SensorPainel.TEMP_CAMBIO.ler(DadosObd(mapOf("TOYOTA_ATF_CAMBIO" to 32.0, "TOYOTA_ATF_ECM" to 78.0)))!!, 0.0)
        // Pedal: 2149 (D) antes do 214A (E).
        assertEquals(16.1, SensorPainel.ACELERADOR.ler(DadosObd(mapOf("TOYOTA_PEDAL_E" to 30.0, "TOYOTA_PEDAL_D" to 16.1)))!!, 0.0)
        assertEquals(30.0, SensorPainel.ACELERADOR.ler(DadosObd(mapOf("TOYOTA_PEDAL_E" to 30.0)))!!, 0.0)
        // Módulo/PID que não responde: "N/D" no 4x4/VSC (módulos ausentes na SW4) e "—" nos demais.
        assertNull(SensorPainel.TRACAO_4X4.ler(DadosObd(mapOf("010C" to 800.0))))
        assertEquals("N/D", SensorPainel.TRACAO_4X4.texto(null, c))
        assertEquals("N/D", SensorPainel.VSC.texto(null, c))
        assertEquals("—", SensorPainel.MARCHA_ALAVANCA.texto(null, c))
        assertEquals("ALAVANCA", SensorPainel.MARCHA_ALAVANCA.rotuloHud(c))
        assertEquals(SensorPainel.TEMP_CAMBIO, SensorPainel.doComando("TOYOTA_ATF_ECM"))
    }

    @Test
    fun `rotulos dos mostradores batem com os do catalogo`() {
        for (s in SensorPainel.entries.filter { it.ehEstado }) {
            for (comando in s.comandos) {
                val def = catalogo[comando] ?: error("$comando ausente do catálogo estendido")
                assertEquals("${s.name} × $comando", def.rotulos, s.rotulos)
            }
        }
        // Todos os comandos dos mostradores Toyota existem no catálogo.
        SensorPainel.entries.flatMap { it.comandos }.filter { it.startsWith("TOYOTA_") }.forEach {
            assertTrue(it, catalogo[it] != null)
        }
    }

    // ---------------------------------------------------------------- simulador

    @Test
    fun `simulador entrega pedal, marcha, alavanca e ATF da SW4 e deixa VSC e 4x4 sem leitura`() = runTest {
        val obd = ObdClientFake(random = Random(3), latenciaMs = 0, catalogoEstendido = catalogo)
        obd.conectar(DispositivoObd("Teste", "00:00:00:00:00:01"))
        val perfil = obd.ativarPerfilFabricante("Toyota SW4") as ResultadoPerfil.Ativo
        val ativos = perfil.pids.map { it.comando }.toSet()
        assertTrue(ativos.containsAll(listOf("TOYOTA_PEDAL_D", "TOYOTA_PEDAL_E", "TOYOTA_MARCHA", "TOYOTA_ALAVANCA", "TOYOTA_ATF_CAMBIO")))
        assertTrue("TOYOTA_4WD" !in ativos && "TOYOTA_VSC" !in ativos)

        val dados = obd.lerTelemetria(250, lentosPorVez = 50, ciclosPorLento = 1).take(3).toList().last()
        val pedal = dados["TOYOTA_PEDAL_D"]!!
        assertTrue(pedal in 0.0..100.0)
        assertEquals(minOf(100.0, pedal + EcuSimulada.DESVIO_APP_E), dados["TOYOTA_PEDAL_E"]!!, 0.11)
        // A velocidade publicada é arredondada: perto da troca, a marcha pode ser a vizinha.
        val marchaEsperada = EcuSimulada.marchaSimulada(dados[com.charruamotors.obd.pid.Pids.VELOCIDADE]!!).toDouble()
        assertTrue(dados["TOYOTA_MARCHA"]!! == marchaEsperada || dados["TOYOTA_MARCHA"]!! in 1.0..5.0 || dados["TOYOTA_MARCHA"]!! == 255.0)
        assertEquals("D", SensorPainel.MARCHA_ALAVANCA.texto(SensorPainel.MARCHA_ALAVANCA.ler(dados), ConversorUnidades.PADRAO))
        assertNull(SensorPainel.VSC.ler(dados))
        assertNull(SensorPainel.TRACAO_4X4.ler(dados))
    }

    @Test
    fun `terminal simulado responde 2149 no motor e o 7B0 so com o ATCRA`() = runTest {
        val obd = ObdClientFake(random = Random(3), latenciaMs = 0, latenciaComandoMs = 0)
        obd.conectar(DispositivoObd("Teste", "00:00:00:00:00:01"))
        obd.iniciarSessaoManual()
        suspend fun cmd(c: String) = obd.enviarComandoManual(c, registrar = false).getOrThrow().resposta
        cmd("ATSH7E0")
        assertTrue(cmd("2149").startsWith("6149"))
        assertTrue(cmd("214A").startsWith("614A"))
        cmd("ATSH7E1")
        assertTrue(cmd("21D9").startsWith("00D\r0:61D9000000"))
        cmd("ATSH7B0")
        assertEquals("NO DATA\r\r", cmd("2103")) // resposta em 7B8 filtrada pelo ELM327
        assertEquals("OK\r\r", cmd("ATCRA7B8"))
        assertEquals("6103${"%02X".format(EcuSimulada.VSC_NORMAL)}000002\r\r", cmd("2103"))
        assertEquals("7F2131\r\r", cmd("21FE"))
        cmd("ATCRA")
        cmd("ATSH7C0")
        assertEquals("NO DATA\r\r", cmd("2101"))
    }
}
