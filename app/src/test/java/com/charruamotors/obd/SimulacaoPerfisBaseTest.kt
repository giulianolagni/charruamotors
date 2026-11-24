package com.charruamotors.obd

import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.pid.Pids
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * v2.2: o Modo Simulado com os perfis base do catálogo. VW TSI ativa os PIDs UDS do motor e descarta
 * em silêncio o `7E1 · 222109` (DSG) que não responde; Fiat e Honda não têm PIDs de montadora e
 * seguem só no OBD2 modo 01; SW4 e Cruze continuam como na v2.1.5.
 */
class SimulacaoPerfisBaseTest {

    private val dispositivo = DispositivoObd("Teste", "00:00:00:00:00:01")

    private suspend fun conectado(marca: String?) =
        ObdClientFake(random = Random(5), latenciaMs = 0, latenciaComandoMs = 0, catalogoEstendido = CatalogoTeste.estendido)
            .also { assertTrue(it.conectar(dispositivo, marca).isSuccess) }

    @Test
    fun `VW TSI ativa o perfil UDS e descarta o TCM que nao responde`() = runTest {
        val vw = conectado("Volkswagen")
        assertEquals(PerfilSimulacao.PADRAO, vw.perfilSimulacao)
        val perfil = vw.ativarPerfilFabricante("Volkswagen") as ResultadoPerfil.Ativo
        val ativos = perfil.pids.map { it.comando }.toSet()
        assertEquals(setOf("VW_TEMP_OLEO", "VW_TURBO_REAL", "VW_TURBO_ALVO"), ativos)
        assertFalse("VW_TEMP_DSG" in vw.pidsSuportados.value) // 7E1 calado: fora do polling
        val leitura = vw.lerDadosEmTempoReal(10, null).take(2).toList().last()
        assertTrue(leitura[Pids.RPM] != null && leitura["VW_TURBO_REAL"] != null)
        assertEquals(EstadoConexaoObd.Conectado, vw.estado.value)
        assertEquals(ObdClientFake.VIN_PADRAO, vw.lerVin().getOrThrow())
    }

    @Test
    fun `Fiat e Honda sem PIDs de montadora seguem no OBD2 modo 01`() = runTest {
        for (marca in listOf("Fiat", "Honda")) {
            val cli = conectado(marca)
            assertEquals(ResultadoPerfil.SemPerfil, cli.ativarPerfilFabricante(marca))
            val leitura = cli.lerDadosEmTempoReal(10, null).take(2).toList().last()
            assertTrue(marca, leitura[Pids.RPM] != null && leitura[Pids.VELOCIDADE] != null)
            assertTrue(marca, cli.pidsSuportados.value.none { it.startsWith("VW_") || it.startsWith("TOYOTA_") || it.startsWith("GM_") })
            assertEquals(EstadoConexaoObd.Conectado, cli.estado.value)
        }
    }

    @Test
    fun `SW4 e Cruze simulados continuam com os PIDs e o VIN do proprio carro`() = runTest {
        val sw4 = conectado("Toyota")
        assertTrue(sw4.ativarPerfilFabricante("Toyota") is ResultadoPerfil.Ativo)
        assertEquals(ObdClientFake.VIN_TOYOTA, sw4.lerVin().getOrThrow())
        val cruze = conectado("Chevrolet")
        assertEquals(PerfilSimulacao.CRUZE_14T_FLEX, cruze.perfilSimulacao)
        val gm = cruze.ativarPerfilFabricante("Chevrolet") as ResultadoPerfil.Ativo
        // GM_ATF no 7E1 responde; o GM_ATF_7E2 alternativo fica de fora sem derrubar a sessão.
        assertEquals(listOf("GM_ATF"), gm.pids.map { it.comando })
        assertEquals(ObdClientFake.VIN_CRUZE, cruze.lerVin().getOrThrow())
        assertEquals(EstadoConexaoObd.Conectado, cruze.estado.value)
    }
}
