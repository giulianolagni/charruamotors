package com.charruamotors.obd

import com.charruamotors.domain.calculo.ClasseCombustivel
import com.charruamotors.domain.calculo.EstequiometriaFlex
import com.charruamotors.domain.faixas.ParametroFaixa
import com.charruamotors.domain.faixas.PresetVeiculo
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.pid.Pids
import com.charruamotors.obd.sessao.SessaoObd
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** v2.1: perfil Chevrolet/GM e o Modo Simulado do Cruze 1.4 Turbo Flex 2018 (LE2). */
class SimulacaoCruzeTest {

    private val dispositivo = DispositivoObd("Teste", "00:00:00:00:00:01")

    private suspend fun cruze(semente: Int = 3) =
        ObdClientFake(random = Random(semente), latenciaMs = 0, latenciaComandoMs = 0, catalogoEstendido = CatalogoTeste.estendido)
            .also { it.conectar(dispositivo, "Chevrolet") }

    @Test
    fun `preset do Cruze traz a rede CAN 500 kbps com ECU 7E0 e TCM 7E1`() {
        val p = PresetVeiculo.CRUZE_2018
        assertEquals("ATSP6", p.protocolo)
        assertEquals("7E0", p.ecuMotor)
        assertEquals("7E1", p.ecuCambio)
        assertEquals("LE2", p.motor)
        assertEquals(TipoCombustivel.FLEX, p.combustivel)
        assertEquals(PresetVeiculo.CRUZE_2018, PresetVeiculo.detectar("Chevrolet", "Cruze 1.4 Turbo Flex", 2018))
        assertEquals(PresetVeiculo.CRUZE_2018, PresetVeiculo.detectar("GM", "Cruze LTZ", 2019))
        assertNull(PresetVeiculo.detectar("Chevrolet", "Onix 1.0", 2018))
        assertNull(PresetVeiculo.detectar("Chevrolet", "Cruze 1.8", 2013)) // geração anterior, aspirado
        assertTrue(p.faixas[ParametroFaixa.STFT] != null)
    }

    @Test
    fun `perfil GM do catalogo le o ATF por UDS 22 1940 no 7E1 com alternativa no 7E2`() {
        val gm = CatalogoTeste.estendido.perfilPara("Chevrolet")!!
        assertEquals("Chevrolet / GM", gm.nome)
        val atf = CatalogoTeste.estendido["GM_ATF"]!!
        assertEquals("7E1", atf.estendida!!.cabecalho)
        assertEquals("221940", atf.estendida!!.comando)
        assertEquals("7E2", CatalogoTeste.estendido["GM_ATF_7E2"]!!.estendida!!.cabecalho)
        assertEquals(90.0, atf.converter(listOf(130)), 0.0) // A − 40
        assertTrue(SensorPainel.TEMP_CAMBIO.comandos.containsAll(listOf("GM_ATF", "GM_ATF_7E2")))
        assertTrue(SensorPainel.TENSAO_ECU.comandos.contains(Pids.TENSAO_ECU))
    }

    @Test
    fun `marca Chevrolet liga a simulacao do Cruze com os PIDs de um LE2`() = runTest {
        val obd = cruze()
        assertEquals(PerfilSimulacao.CRUZE_14T_FLEX, obd.perfilSimulacao)
        assertEquals(SimuladorCruze.COMANDOS, obd.pidsSuportados.value)
        listOf("0123", "010E", "0124", "0152", "0106", "0107", "0142", "0110", "010B").forEach {
            assertTrue("$it suportado", it in obd.pidsSuportados.value)
        }
        assertFalse("015E" in obd.pidsSuportados.value)
        // Outras marcas seguem no simulador genérico.
        val toyota = ObdClientFake(random = Random(1), latenciaMs = 0).also { it.conectar(dispositivo, "Toyota") }
        assertEquals(PerfilSimulacao.PADRAO, toyota.perfilSimulacao)
    }

    @Test
    fun `leituras do Cruze ficam dentro da faixa do catalogo`() = runTest {
        val obd = cruze()
        val leituras = obd.lerTelemetria(250, lentosPorVez = 50, ciclosPorLento = 1).take(120).toList()
        leituras.forEach { d ->
            d.valores.forEach { (cmd, v) -> assertTrue("$cmd = $v", CatalogoTeste.pid(cmd).dentroDaFaixa(v)) }
        }
        val ultima = leituras.last()
        assertEquals(SimuladorCruze.ETANOL_INICIAL, ultima[Pids.PERCENTUAL_ETANOL]!!, 0.0)
        assertEquals(ClasseCombustivel.MISTURA_FLEX, EstequiometriaFlex.classificar(ultima[Pids.PERCENTUAL_ETANOL]!!))
        // Injeção direta: rail entre ~30 e 200 bar (kPa no catálogo).
        assertTrue(ultima[Pids.PRESSAO_RAIL]!! in 3_000.0..20_000.0)
        // A/F real calculada pela sessão para a mistura E48 (λ ≈ 1 → ≈ 12:1).
        val af = SessaoObd.comCalculados(ultima, TipoCombustivel.FLEX)[Pids.RELACAO_AF_REAL]
        if (ultima[Pids.LAMBDA_BANDA_LARGA]!! < 1.6) assertTrue("A/F $af", af!! in 9.5..14.0)
    }

    @Test
    fun `arrancada mostra o turbo e as 6 marchas`() = runTest {
        val obd = cruze()
        obd.simularArrancada()
        var mapMax = 0.0
        val marchas = mutableSetOf<Int>()
        val cruzeSim = SimuladorCruze(Random(9))
        obd.lerTelemetria(100, lentosPorVez = 50, ciclosPorLento = 1).take(600).toList().forEach { d ->
            mapMax = maxOf(mapMax, d[Pids.PRESSAO_COLETOR] ?: 0.0)
        }
        // Câmbio de 6 marchas pela velocidade (a 120 km/h em cruzeiro, 6ª).
        for (v in listOf(0.0, 10.0, 25.0, 45.0, 60.0, 85.0, 120.0)) {
            cruzeSim.leitura(0.1, v, 0.0, 90.0, 95.0, 10.0, 50.0, 0.0, 0.0)
            marchas += cruzeSim.marcha
        }
        assertTrue("MAP máx. $mapMax", mapMax > 140.0) // pressão positiva do turbo
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 6), marchas)
    }

    @Test
    fun `perfil GM no simulado ativa o ATF do TCM 7E1 e deixa o 7E2 de fora`() = runTest {
        val obd = cruze()
        val r = obd.ativarPerfilFabricante("Chevrolet") as ResultadoPerfil.Ativo
        assertEquals(listOf("GM_ATF"), r.pids.map { it.comando })
        val d = obd.lerTelemetria(250, lentosPorVez = 50, ciclosPorLento = 1).take(2).toList().last()
        assertTrue(d["GM_ATF"]!! in 20.0..120.0)
        assertNull(d["GM_ATF_7E2"])
        assertTrue(SensorPainel.TEMP_CAMBIO.ler(d) != null)
    }

    @Test
    fun `ECU simulada do Cruze responde ao UDS 22 1940 no TCM`() = runTest {
        val obd = cruze()
        obd.ativarPerfilFabricante("Chevrolet")
        obd.lerTelemetria(250, lentosPorVez = 50, ciclosPorLento = 1).take(1).toList()
        obd.iniciarSessaoManual()
        obd.enviarComandoManual("ATSH7E1")
        val atf = obd.enviarComandoManual("221940").getOrThrow().resposta
        assertTrue(atf, atf.startsWith("621940"))
        assertTrue(obd.enviarComandoManual("22F40C").getOrThrow().resposta.startsWith("7F2231"))
        obd.enviarComandoManual("ATSH7E2")
        assertTrue(obd.enviarComandoManual("221940").getOrThrow().resposta.startsWith("NO DATA"))
        obd.encerrarSessaoManual()
    }

    @Test
    fun `etanol do tanque simulado muda a classificacao`() = runTest {
        val obd = cruze()
        obd.simularEtanol(95.0)
        val d = obd.lerTelemetria(250, lentosPorVez = 50, ciclosPorLento = 1).take(1).toList().last()
        assertEquals(95.0, d[Pids.PERCENTUAL_ETANOL]!!, 0.0)
        assertEquals(ClasseCombustivel.ETANOL_E100, EstequiometriaFlex.avaliar(TipoCombustivel.FLEX, d[Pids.PERCENTUAL_ETANOL]).classe)
    }
}
