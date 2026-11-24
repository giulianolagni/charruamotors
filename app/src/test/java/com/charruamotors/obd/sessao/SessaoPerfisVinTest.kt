package com.charruamotors.obd.sessao

import com.charruamotors.direcao.hud.TelemetriaCompartilhada
import com.charruamotors.domain.calculo.CambioA750f
import com.charruamotors.domain.faixas.NivelPerfil
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.ModoObd
import com.charruamotors.obd.ObdClientFake
import com.charruamotors.obd.dtc.CatalogoDtc
import com.charruamotors.obd.log.DestinoMemoria
import com.charruamotors.obd.pid.CatalogoTeste
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random

/** v2.2: VIN (`0902`) decodificado no cabeçalho do relatório, sugestão de perfil e marcha pelo perfil de câmbio. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessaoPerfisVinTest {

    @get:Rule
    val pasta = TemporaryFolder()

    private val inicio = LocalDateTime.of(2026, 11, 23, 9, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val scanner = DispositivoObd("OBDII", "00:1D:A5:68:98:8B")
    private val destino = DestinoMemoria()
    private val fake = ObdClientFake(random = Random(7), latenciaMs = 50, catalogoEstendido = CatalogoTeste.estendido)
    private val escopos = mutableListOf<CoroutineScope>()

    @After
    fun cancelarEscopos() = escopos.forEach { it.cancel() }

    private fun veiculo(marca: String, modelo: String, ano: Int, combustivel: TipoCombustivel) = VeiculoSessao.de(
        Veiculo(id = 3, marca = marca, modelo = modelo, ano = ano, placa = "ABC1D23", kmAtual = 0, tipoCombustivel = combustivel),
    )

    private fun TestScope.escopo() = CoroutineScope(StandardTestDispatcher(testScheduler)).also { escopos += it }

    private fun TestScope.novaSessao(v: VeiculoSessao) = SessaoObd(
        clientePorModo = { fake },
        modoInicial = ModoObd.SIMULADO,
        catalogoPids = CatalogoTeste.padrao,
        catalogoDtc = CatalogoDtc.VAZIO,
        config = { ConfigAutoConexao(veiculoId = v.id) },
        buscarVeiculo = { id -> v.takeIf { id == it.id } },
        destinoLogs = destino,
        pastaTemporaria = pasta.newFolder(),
        escopo = escopo(),
        io = StandardTestDispatcher(testScheduler),
        telemetria = TelemetriaCompartilhada(),
        relogio = { inicio + testScheduler.currentTime },
        zona = ZoneOffset.UTC,
    )


    private fun TestScope.rodar(v: VeiculoSessao, ms: Long = 8_000): Pair<String, List<String>> {
        val sessao = novaSessao(v)
        val mensagens = mutableListOf<String>()
        escopo().launch { sessao.eventos.collect { (it as? EventoSessao.Mensagem)?.let { m -> mensagens += m.texto } } }
        sessao.conectar(scanner, veiculoId = v.id, origem = OrigemSessao.MANUAL)
        advanceTimeBy(ms)
        runCurrent()
        sessao.desconectar()
        advanceUntilIdle()
        return destino.terminadoEm(".txt").conteudo to mensagens
    }

    private fun campo(txt: String, rotulo: String): String? =
        txt.lines().firstOrNull { it.trimStart().startsWith(rotulo) }?.substringAfter(": ")

    @Test
    fun `cadastro generico da GM recebe a sugestao do Cruze pelo VIN e o relatorio registra o VIN`() = runTest {
        val spin = veiculo("Chevrolet", "Spin 1.8", 2018, TipoCombustivel.FLEX)
        assertEquals(NivelPerfil.GENERICO, spin.resolucao!!.nivel)
        val (txt, mensagens) = rodar(spin)

        assertEquals(ObdClientFake.VIN_CRUZE, campo(txt, "VIN (0902)"))
        assertEquals("Chevrolet (WMI 8AG, Argentina)", campo(txt, "Montadora (VIN)"))
        assertEquals("2018", campo(txt, "Ano-modelo (VIN)"))
        assertEquals("Genérico · Flex Aspirado (limites do cadastro)", campo(txt, "Perfil de faixas"))
        val sugestao = campo(txt, "Sugestão de perfil")!!
        assertTrue(sugestao, "Chevrolet Cruze 1.4 Turbo Flex 2018 (LE2)" in sugestao)
        assertTrue(mensagens.toString(), mensagens.any { it.startsWith("VIN indica Chevrolet 2018") })
    }

    @Test
    fun `perfil exato nao recebe sugestao e a SW4 simulada responde o VIN da Toyota`() = runTest {
        val sw4 = veiculo("Toyota", "Hilux SW4 3.0 D-4D", 2013, TipoCombustivel.DIESEL)
        assertEquals(NivelPerfil.EXATO, sw4.resolucao!!.nivel)
        val (txt, mensagens) = rodar(sw4)

        assertEquals(ObdClientFake.VIN_TOYOTA, campo(txt, "VIN (0902)"))
        assertEquals("Toyota (WMI 8AJ, Argentina)", campo(txt, "Montadora (VIN)"))
        assertEquals("2013", campo(txt, "Ano-modelo (VIN)"))
        assertEquals("Toyota Hilux SW4 3.0 D-4D 2013 (1KD-FTV)", campo(txt, "Perfil de faixas"))
        assertEquals(null, campo(txt, "Sugestão de perfil"))
        assertFalse(mensagens.any { it.startsWith("VIN indica") })
    }

    @Test
    fun `VW TSI simulado estima a marcha pelas razoes do Aisin de 6 marchas`() = runTest {
        val polo = veiculo("Volkswagen", "Polo 1.0 TSI", 2022, TipoCombustivel.FLEX)
        assertEquals("aisin_6m", polo.cambio!!.id)
        val sessao = novaSessao(polo)
        sessao.conectar(scanner, veiculoId = polo.id, origem = OrigemSessao.MANUAL)
        val marchas = mutableSetOf<Double>()
        repeat(40) {
            advanceTimeBy(1_000)
            runCurrent()
            sessao.dados.value?.valores?.get(CambioA750f.CHAVE_MARCHA)?.let { marchas += it }
        }
        sessao.desconectar()
        advanceUntilIdle()
        assertTrue("marcha estimada no instantâneo: $marchas", marchas.isNotEmpty())
        assertTrue(marchas.all { it in 1.0..6.0 || it == CambioA750f.MARCHA_PARADO.toDouble() })
        // A coluna da marcha estimada entra no CSV (o VW não tem PID de marcha).
        assertTrue("[TOYOTA_MARCHA]" in destino.terminadoEm(".csv").conteudo.lineSequence().first())
    }
}
