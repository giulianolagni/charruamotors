package com.charruamotors.obd.sessao

import com.charruamotors.direcao.hud.TelemetriaCompartilhada
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.ModoObd
import com.charruamotors.obd.ObdClientFake
import com.charruamotors.obd.aprendizado.FonteSimulacao
import com.charruamotors.obd.aprendizado.ModeloAprendido
import com.charruamotors.obd.aprendizado.TrechoReal
import com.charruamotors.obd.aprendizado.LogIncorporado
import com.charruamotors.obd.dtc.CatalogoDtc
import com.charruamotors.obd.log.DestinoMemoria
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.pid.Pids
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random

/** v2.2.4: a sessão só entrega ao Modo Simulado o modelo do veículo da sessão e só alimenta o aprendizado com sessão real. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessaoAprendizadoTest {

    @get:Rule
    val pasta = TemporaryFolder()

    private val inicio = LocalDateTime.of(2026, 11, 24, 9, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val scanner = DispositivoObd("OBDII", "00:1D:A5:68:98:8B")
    private val escopos = mutableListOf<CoroutineScope>()

    @After
    fun cancelarEscopos() = escopos.forEach { it.cancel() }

    private fun veiculo(id: Long, marca: String, modelo: String) = VeiculoSessao.de(
        Veiculo(id = id, marca = marca, modelo = modelo, ano = 2018, placa = "ABC1D23", kmAtual = 0, tipoCombustivel = TipoCombustivel.FLEX),
    )

    private val veiculos = listOf(veiculo(1, "Toyota", "SW4"), veiculo(2, "Chevrolet", "Cruze"))

    /** Só o SW4 (id 1) tem logs reais aprendidos. */
    private val modeloSw4 = ModeloAprendido(
        veiculoId = 1,
        logs = listOf(LogIncorporado("a.csv", 100), LogIncorporado("b.csv", 100)),
        trechos = listOf(TrechoReal(listOf(Pids.RPM, Pids.VELOCIDADE), List(20) { doubleArrayOf(1500.0 + it * 10, 40.0 + it) })),
    )

    private val consultas = mutableListOf<Long>()
    private val logsReaisSalvos = mutableListOf<Pair<Long, String>>()

    private fun TestScope.sessao(modo: ModoObd, fake: ObdClientFake, destino: DestinoMemoria) = SessaoObd(
        clientePorModo = { fake },
        modoInicial = modo,
        catalogoPids = CatalogoTeste.padrao,
        catalogoDtc = CatalogoDtc.VAZIO,
        config = { ConfigAutoConexao() },
        buscarVeiculo = { id -> veiculos.firstOrNull { it.id == id } },
        destinoLogs = destino,
        pastaTemporaria = pasta.newFolder(),
        escopo = CoroutineScope(StandardTestDispatcher(testScheduler)).also { escopos += it },
        io = StandardTestDispatcher(testScheduler),
        telemetria = TelemetriaCompartilhada(),
        relogio = { inicio + testScheduler.currentTime },
        zona = ZoneOffset.UTC,
        modeloAprendidoDe = { id ->
            consultas += id
            modeloSw4.takeIf { it.veiculoId == id }
        },
        aoSalvarLogReal = { id, csv -> logsReaisSalvos += id to csv.nome },
    )

    private fun fake() = ObdClientFake(random = Random(7), latenciaMs = 50, catalogoEstendido = CatalogoTeste.estendido)

    /** Conecta, espera a sessão estabilizar, devolve a fonte do Modo Simulado e encerra. */
    private fun TestScope.rodar(modo: ModoObd, veiculoId: Long): FonteSimulacao? {
        val s = sessao(modo, fake(), DestinoMemoria())
        s.conectar(scanner, veiculoId, OrigemSessao.MANUAL)
        advanceTimeBy(8_000)
        runCurrent()
        val fonte = s.fonteSimulacao.value
        s.desconectar()
        advanceUntilIdle()
        assertNull(s.fonteSimulacao.value) // limpa ao encerrar
        return fonte
    }

    @Test
    fun `Modo Simulado do SW4 usa os logs do SW4 e o Cruze continua sintetico`() = runTest {
        assertEquals(FonteSimulacao.LogsReais(2), rodar(ModoObd.SIMULADO, veiculoId = 1))
        assertEquals(FonteSimulacao.Sintetica, rodar(ModoObd.SIMULADO, veiculoId = 2))
        assertEquals(listOf(1L, 2L), consultas)
    }

    @Test
    fun `sessao simulada nunca alimenta o aprendizado`() = runTest {
        rodar(ModoObd.SIMULADO, veiculoId = 1)
        assertTrue(logsReaisSalvos.isEmpty())
    }

    @Test
    fun `sessao real entrega o CSV ao aprendizado com o veiculoId certo e sem consultar o modelo`() = runTest {
        assertNull(rodar(ModoObd.BLUETOOTH, veiculoId = 2))
        assertEquals(1, logsReaisSalvos.size)
        assertEquals(2L, logsReaisSalvos.single().first)
        assertTrue(logsReaisSalvos.single().second.endsWith("_Chevrolet_Cruze.csv"))
        assertTrue(consultas.isEmpty())
    }
}
