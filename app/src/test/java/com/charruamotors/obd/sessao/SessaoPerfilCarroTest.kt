package com.charruamotors.obd.sessao

import com.charruamotors.carros.CamadaPreferida
import com.charruamotors.carros.PerfilCarro
import com.charruamotors.direcao.hud.TelemetriaCompartilhada
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.ModoObd
import com.charruamotors.obd.ObdClient
import com.charruamotors.obd.ObdClientFake
import com.charruamotors.obd.ResultadoPerfil
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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/**
 * v2.3.0 — a sessão usa o perfil isolado do carro: no OBD2 Genérico a marca não vai para a cascata
 * nem para os PIDs da montadora; a camada Modo 21 chega ao cliente; o id do veículo fica no estado
 * (dono dos recordes).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessaoPerfilCarroTest {

    @get:Rule
    val pasta = TemporaryFolder()

    private val scanner = DispositivoObd("OBDII", "00:1D:A5:68:98:8B")
    private val escopos = mutableListOf<CoroutineScope>()

    /** Cliente simulado que anota a marca e o serviço que a sessão pediu. */
    private class Gravador(private val base: ObdClientFake) : ObdClient by base {
        val marcasConexao = mutableListOf<String?>()
        val marcasPerfil = mutableListOf<String?>()
        val servicos = mutableListOf<String?>()

        override suspend fun conectar(dispositivo: DispositivoObd, marcaVeiculo: String?): Result<Unit> {
            marcasConexao += marcaVeiculo
            return base.conectar(dispositivo, marcaVeiculo)
        }

        override suspend fun ativarPerfilFabricante(marca: String?): ResultadoPerfil {
            marcasPerfil += marca
            return base.ativarPerfilFabricante(marca)
        }

        override fun definirServicoPreferido(servico: String?) {
            servicos += servico
        }
    }

    @After
    fun cancelarEscopos() = escopos.forEach { it.cancel() }

    private fun TestScope.sessaoCom(cliente: ObdClient, veiculo: VeiculoSessao) = SessaoObd(
        clientePorModo = { cliente },
        modoInicial = ModoObd.SIMULADO,
        catalogoPids = CatalogoTeste.padrao,
        catalogoDtc = CatalogoDtc.VAZIO,
        config = { ConfigAutoConexao(veiculoId = veiculo.id, gravarLogsAutomaticos = false) },
        buscarVeiculo = { id -> veiculo.takeIf { id == it.id } },
        destinoLogs = DestinoMemoria(),
        pastaTemporaria = pasta.newFolder(),
        escopo = CoroutineScope(StandardTestDispatcher(testScheduler)).also { escopos += it },
        io = StandardTestDispatcher(testScheduler),
        telemetria = TelemetriaCompartilhada(),
    )

    private fun TestScope.avancar(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `Toyota no OBD2 Generico conecta sem marca e sem PIDs da montadora`() = runTest {
        val corolla = Veiculo(id = 13, marca = "Toyota", modelo = "Corolla", ano = 2020, placa = "COR0L20", kmAtual = 1, tipoCombustivel = TipoCombustivel.FLEX)
        val perfil = PerfilCarro.paraCarroNovo(corolla.id, PerfilCarro(corolla.id).resolver(corolla))
        val cliente = Gravador(ObdClientFake(random = Random(1), latenciaMs = 10))
        val sessao = sessaoCom(cliente, VeiculoSessao.de(corolla, perfil))

        sessao.conectar(scanner, corolla.id, OrigemSessao.MANUAL)
        avancar(5_000)

        assertEquals(listOf<String?>(null), cliente.marcasConexao)
        assertEquals(listOf<String?>(null), cliente.marcasPerfil)
        assertEquals(listOf<String?>(null), cliente.servicos)
        assertEquals(ResultadoPerfil.SemPerfil, sessao.perfil.value)
        assertNull("sem câmbio herdado", sessao.cambioAtivo.value)
        assertEquals(corolla.id, sessao.estadoSessao.value.veiculoId)
        sessao.desconectar()
        advanceUntilIdle()
    }

    @Test
    fun `SW4 no automatico leva a marca e a camada Modo 21 chega ao cliente`() = runTest {
        val sw4 = Veiculo(id = 7, marca = "Toyota", modelo = "SW4", ano = 2013, placa = "ABC1D23", kmAtual = 1, tipoCombustivel = TipoCombustivel.DIESEL)
        val cliente = Gravador(ObdClientFake(random = Random(2), latenciaMs = 10))
        val sessao = sessaoCom(cliente, VeiculoSessao.de(sw4, PerfilCarro(sw4.id, camada = CamadaPreferida.MODO_21)))

        sessao.conectar(scanner, sw4.id, OrigemSessao.MANUAL)
        avancar(5_000)

        assertEquals(listOf<String?>("Toyota"), cliente.marcasConexao)
        assertEquals(listOf<String?>("Toyota"), cliente.marcasPerfil)
        assertEquals(listOf<String?>("21"), cliente.servicos)
        assertEquals("toyota_a750f", sessao.cambioAtivo.value?.id)
        assertEquals(sw4.id, sessao.estadoSessao.value.veiculoId)
        sessao.desconectar()
        advanceUntilIdle()
        assertEquals(0L, sessao.estadoSessao.value.veiculoId)
    }
}
