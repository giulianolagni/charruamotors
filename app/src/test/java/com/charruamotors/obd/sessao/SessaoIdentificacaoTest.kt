package com.charruamotors.obd.sessao

import com.charruamotors.direcao.hud.TelemetriaCompartilhada
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.domain.perfis.OrigemPerfil
import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.ModoObd
import com.charruamotors.obd.ObdClientFake
import com.charruamotors.obd.dtc.CatalogoDtc
import com.charruamotors.obd.log.DestinoMemoria
import com.charruamotors.obd.pid.CatalogoTeste
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * v2.2.2: cascata completa na sessão — `Match VDS → Sondagem ECU (0151 + 0123/MAP + TCM) → Fallback
 * genérico` — com o relatório `.txt` e a garantia de que `NO DATA` nunca derruba a sessão.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessaoIdentificacaoTest {

    @get:Rule
    val pasta = TemporaryFolder()

    private val inicio = LocalDateTime.of(2026, 11, 23, 9, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val scanner = DispositivoObd("OBDII", "00:1D:A5:68:98:8B")
    private val destino = DestinoMemoria()
    private val escopos = mutableListOf<CoroutineScope>()

    @After
    fun cancelarEscopos() = escopos.forEach { it.cancel() }

    private fun veiculo(marca: String, modelo: String, ano: Int, combustivel: TipoCombustivel) = VeiculoSessao.de(
        Veiculo(id = 3, marca = marca, modelo = modelo, ano = ano, placa = "ABC1D23", kmAtual = 0, tipoCombustivel = combustivel),
    )

    private fun TestScope.escopo() = CoroutineScope(StandardTestDispatcher(testScheduler)).also { escopos += it }

    private fun TestScope.novaSessao(v: VeiculoSessao, fake: ObdClientFake) = SessaoObd(
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

    private fun fake(vin: String? = null, mudo: Boolean = false) =
        ObdClientFake(random = Random(7), latenciaMs = 50, catalogoEstendido = CatalogoTeste.estendido, vin = vin, ecuSemImpressao = mudo)

    private class Resultado(val sessao: SessaoObd, val txt: String, val estadoNoMeio: EstadoConexaoObd, val temDadosNoMeio: Boolean)

    private fun TestScope.rodar(v: VeiculoSessao, fake: ObdClientFake, aoMeio: (SessaoObd) -> Unit = {}): Resultado {
        val sessao = novaSessao(v, fake)
        sessao.conectar(scanner, veiculoId = v.id, origem = OrigemSessao.MANUAL)
        advanceTimeBy(8_000)
        runCurrent()
        aoMeio(sessao)
        val estado = sessao.estado.value
        val temDados = sessao.dados.value != null
        sessao.desconectar()
        advanceUntilIdle()
        return Resultado(sessao, destino.terminadoEm(".txt").conteudo, estado, temDados)
    }

    private fun campo(txt: String, rotulo: String): String? =
        txt.lines().firstOrNull { it.trimStart().startsWith(rotulo) }?.substringAfter(": ")?.trim()

    @Test
    fun `match por VDS aplica o perfil exato, avisa e registra a origem no relatorio`() = runTest {
        // Cadastro genérico da GM (Spin) + chassi de Cruze 1.4T 2018 (8AGBN69S0).
        val spin = veiculo("Chevrolet", "Spin 1.8", 2018, TipoCombustivel.FLEX)
        var aviso: String? = null
        val r = rodar(spin, fake()) { aviso = it.perfilAutomatico.value?.preset?.titulo }

        assertEquals("Chevrolet Cruze 1.4 Turbo Flex 2018 (LE2)", aviso)
        val origem = campo(r.txt, "Origem do perfil")!!
        assertTrue(origem, origem.startsWith("VDS do VIN — Chevrolet Cruze 1.4 Turbo Flex 2018 (LE2)"))
        assertEquals("Flex (gasolina/etanol) (09)", campo(r.txt, "Combustível (0151)"))
        assertEquals("GM6T35LE2CAL018A", campo(r.txt, "CALID (0904)"))
        // Perfil já exato pelo chassi: o TCM não precisa ser sondado.
        assertEquals("não sondado", campo(r.txt, "TCM detectado"))
        // As faixas do relatório passam a ser as do Cruze.
        assertEquals("Chevrolet Cruze 1.4 Turbo Flex 2018 (LE2)", campo(r.txt, "Perfil de faixas"))
        assertNull("sugestão do VIN é dispensada quando o perfil já foi aplicado", campo(r.txt, "Sugestão de perfil"))
    }

    @Test
    fun `sem VDS a sondagem aplica o generico aspirado e detecta que nao ha TCM`() = runTest {
        // Cadastro genérico flex + chassi do Gol de teste (9BWZZZ, aspirado, sem 0123).
        val gol = veiculo("Marca X", "Hatch 1.0", 2010, TipoCombustivel.FLEX)
        val r = rodar(gol, fake())

        val origem = campo(r.txt, "Origem do perfil")!!
        assertTrue(origem, origem.startsWith("Sondagem ECU — Genérico · Flex Aspirado"))
        assertEquals("Gasolina (01)", campo(r.txt, "Combustível (0151)"))
        assertEquals("GENASP0000000001", campo(r.txt, "CALID (0904)"))
        assertTrue(campo(r.txt, "TCM detectado")!!.startsWith("não (sem resposta"))
    }

    @Test
    fun `sem VDS o 0151 diesel aplica o generico diesel e o TCM e detectado em 7E1`() = runTest {
        // Chassi Toyota que não casa com nenhum padrão (8AJKB3CD0): só a sondagem identifica o diesel.
        val x = veiculo("Marca X", "Picape 3.0", 2012, TipoCombustivel.FLEX)
        val r = rodar(x, fake(vin = "8AJKB3CD0C6012345"))

        val origem = campo(r.txt, "Origem do perfil")!!
        assertTrue(origem, origem.startsWith("Sondagem ECU — Genérico · Diesel Common Rail"))
        assertEquals("Diesel (04)", campo(r.txt, "Combustível (0151)"))
        assertEquals("sim (7E1)", campo(r.txt, "TCM detectado"))
        assertEquals("Genérico · Diesel Common Rail (limites do cadastro)", campo(r.txt, "Perfil de faixas"))
    }

    @Test
    fun `NO DATA em 0151 0904 e TCM mantem o cadastro e a sessao segue viva`() = runTest {
        val gol = veiculo("Marca X", "Hatch 1.0", 2010, TipoCombustivel.FLEX)
        val r = rodar(gol, fake(mudo = true))

        assertEquals(EstadoConexaoObd.Conectado, r.estadoNoMeio)
        assertTrue("a telemetria continua", r.temDadosNoMeio)
        assertEquals("sem resposta (NO DATA)", campo(r.txt, "Combustível (0151)"))
        assertEquals("sem resposta (NO DATA)", campo(r.txt, "CALID (0904)"))
        assertTrue(campo(r.txt, "TCM detectado")!!.startsWith("não"))
        assertEquals("Manual", campo(r.txt, "Origem do perfil"))
        assertEquals("Genérico · Flex Aspirado (limites do cadastro)", campo(r.txt, "Perfil de faixas"))
        assertNull(r.sessao.perfilAutomatico.value)
    }

    @Test
    fun `perfil exato do cadastro nao e trocado nem sondado no TCM`() = runTest {
        val sw4 = veiculo("Toyota", "Hilux SW4 3.0 D-4D", 2013, TipoCombustivel.DIESEL)
        var aviso: Any? = "nunca definido"
        val r = rodar(sw4, fake()) { aviso = it.perfilAutomatico.value }

        assertNull(aviso)
        assertEquals("Manual", campo(r.txt, "Origem do perfil"))
        assertEquals("Diesel (04)", campo(r.txt, "Combustível (0151)"))
        assertEquals("TY1KDFTV16A750F1", campo(r.txt, "CALID (0904)"))
        assertEquals("não sondado", campo(r.txt, "TCM detectado"))
        assertEquals("Toyota Hilux SW4 3.0 D-4D 2013 (1KD-FTV)", campo(r.txt, "Perfil de faixas"))
    }

    @Test
    fun `ignorar o aviso volta ao perfil do cadastro e o relatorio registra Manual`() = runTest {
        val spin = veiculo("Chevrolet", "Spin 1.8", 2018, TipoCombustivel.FLEX)
        val r = rodar(spin, fake()) { it.ignorarPerfilAutomatico() }

        assertNull(r.sessao.perfilAutomatico.value)
        assertEquals("Manual", campo(r.txt, "Origem do perfil"))
        assertEquals("Genérico · Flex Aspirado (limites do cadastro)", campo(r.txt, "Perfil de faixas"))
        assertTrue(r.txt.contains("Perfil automático ignorado pelo usuário"))
    }

    @Test
    fun `manter o aviso o dispensa e o perfil continua valendo`() = runTest {
        val spin = veiculo("Chevrolet", "Spin 1.8", 2018, TipoCombustivel.FLEX)
        var antes: Any? = null
        val r = rodar(spin, fake()) {
            antes = it.perfilAutomatico.value
            it.manterPerfilAutomatico()
        }

        assertNotNull(antes)
        assertNull(r.sessao.perfilAutomatico.value)
        assertTrue(campo(r.txt, "Origem do perfil")!!.startsWith("VDS do VIN"))
        assertTrue(r.txt.contains("Perfil automático mantido pelo usuário"))
    }

    @Test
    fun `conexao seguinte comeca sem aviso nem impressao da anterior`() = runTest {
        val spin = veiculo("Chevrolet", "Spin 1.8", 2018, TipoCombustivel.FLEX)
        val r = rodar(spin, fake())
        assertNull(r.sessao.perfilAutomatico.value)
        assertNull(r.sessao.impressaoEcu.value)
    }
}
