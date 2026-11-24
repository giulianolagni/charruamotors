package com.charruamotors.obd

import com.charruamotors.domain.calculo.Cronometro0a100
import com.charruamotors.domain.calculo.EstadoCronometro
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.pid.Pids
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Telemetria em duas filas, arrancada 0–100 e perfil de montadora no scanner simulado. */
class ObdClientFakeTelemetriaTest {

    private val dispositivo = DispositivoObd("Teste", "00:00:00:00:00:01")

    private suspend fun conectado(semente: Int = 7) =
        ObdClientFake(random = Random(semente), latenciaMs = 0, catalogoEstendido = CatalogoTeste.estendido)
            .also { it.conectar(dispositivo) }

    @Test
    fun `primeiro instantaneo tem os 4 rapidos e 1 lento e uma volta cobre todos os PIDs`() = runTest {
        val obd = conectado()
        val leituras = obd.lerTelemetria(250).take(79).toList()

        assertEquals(
            setOf(Pids.RPM, Pids.VELOCIDADE, Pids.PRESSAO_COLETOR, Pids.MAF, Pids.CARGA_MOTOR),
            leituras.first().valores.keys,
        )
        // 30 suportados − 4 rápidos = 26 lentos, 1 a cada 3 ciclos → 78 ciclos por volta.
        assertFalse(ObdClientFake.COMANDOS_SIMULADOS == leituras[74].valores.keys)
        assertEquals(ObdClientFake.COMANDOS_SIMULADOS, leituras[75].valores.keys)
        leituras.last().valores.forEach { (cmd, v) ->
            assertTrue("$cmd = $v", CatalogoTeste.pid(cmd).dentroDaFaixa(v))
        }
    }

    @Test
    fun `simulacao avanca pelo tempo do ciclo`() = runTest {
        val obd = conectado()
        // 40 ciclos de 250 ms = 10 s de motor ligado (011F é lido na fila lenta e retido).
        val ultima = obd.lerTelemetria(250).take(40).toList().last()
        assertTrue("motor ligado ${ultima["011F"]}", ultima["011F"]!! in 6.0..10.0) // retido desde a última vez na fila lenta
    }

    @Test
    fun `arrancada simulada vai de 0 a 100 km por h em tempo realista`() = runTest {
        val obd = conectado()
        val cronometro = Cronometro0a100()
        obd.simularArrancada()
        assertTrue(obd.arrancadaEmAndamento)
        cronometro.armar(null)

        // Instantes virtuais: 250 ms por ciclo. Para o carro, espera 2 s e acelera com tudo.
        obd.lerTelemetria(250).withIndex()
            .takeWhile { (i, _) -> i < 400 && cronometro.estado !is EstadoCronometro.Concluido }
            .collect { (i, dados) -> cronometro.registrar(dados[Pids.VELOCIDADE], i * 250L) }

        val fim = cronometro.estado
        assertTrue("estado final $fim", fim is EstadoCronometro.Concluido)
        val segundos = (fim as EstadoCronometro.Concluido).tempoMs / 1000.0
        assertTrue("0–100 em $segundos s", segundos in 10.0..14.0)
    }

    @Test
    fun `depois da arrancada o transito volta ao normal`() = runTest {
        val obd = conectado()
        obd.simularArrancada()
        val velocidades = obd.lerTelemetria(250).take(200).toList().map { it.velocidade!! }
        assertTrue(velocidades.max() >= 100)
        assertTrue(velocidades.contains(0)) // parou antes de arrancar
        assertFalse(obd.arrancadaEmAndamento)
        assertTrue("voltou a desacelerar", velocidades.last() < velocidades.max())
    }

    @Test
    fun `perfil VW ativa so os PIDs que a ECU simulada responde`() = runTest {
        val obd = conectado()
        val r = obd.ativarPerfilFabricante("VW Gol") as ResultadoPerfil.Ativo
        assertEquals("Volkswagen", r.fabricante)
        assertEquals(listOf("VW_TEMP_OLEO", "VW_TURBO_REAL", "VW_TURBO_ALVO"), r.pids.map { it.comando })
        assertFalse("VW_TEMP_DSG" in obd.pidsSuportados.value) // Gol sem câmbio DSG
        assertTrue(r.observacao.isNotBlank())

        val volta = obd.lerTelemetria(250, lentosPorVez = 50, ciclosPorLento = 1).take(1).toList() // tudo num ciclo
        r.pids.forEach { d ->
            val v = volta.last()[d.comando]
            assertTrue("${d.comando} = $v", v != null && d.dentroDaFaixa(v))
        }
    }

    @Test
    fun `perfil Toyota simula ATF e correcao dos injetores`() = runTest {
        val obd = conectado()
        val r = obd.ativarPerfilFabricante("Toyota Hilux") as ResultadoPerfil.Ativo
        assertEquals(11, r.pids.size) // ATF (21D9), 4 bicos, pedal D/E, tensão da ECU, barômetro, marcha e alavanca (sem VSC, 4x4 e _ECM)
        val dados = obd.lerTelemetria(250, lentosPorVez = 50, ciclosPorLento = 1).take(1).toList().last()
        assertEquals(0.66, dados["TOYOTA_INJ3"]!!, 0.05) // bicos como na captura real da SW4
        assertTrue(dados["TOYOTA_ATF_CAMBIO"]!! in 20.0..120.0)
        assertEquals(92.0, dados["TOYOTA_BARO"]!!, 0.0)
        assertNull(dados["TOYOTA_VSC"])
    }

    @Test
    fun `marca sem perfil e desconectado nao ativam`() = runTest {
        val obd = conectado()
        assertEquals(ResultadoPerfil.SemPerfil, obd.ativarPerfilFabricante("Fiat"))
        obd.desconectar()
        assertTrue(obd.ativarPerfilFabricante("VW") is ResultadoPerfil.Indisponivel)
        assertTrue(obd.lerTelemetria(250).toList().isEmpty())
    }
}
