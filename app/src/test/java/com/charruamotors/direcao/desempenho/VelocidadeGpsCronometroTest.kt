package com.charruamotors.direcao.desempenho

import com.charruamotors.direcao.hud.TelemetriaCompartilhada
import com.charruamotors.direcao.painel.HistoricoSensores
import com.charruamotors.direcao.painel.GrupoSensor
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.log.GeradorCsv
import com.charruamotors.obd.pid.DefinicaoPid
import com.charruamotors.obd.pid.Pids
import com.charruamotors.sistema.unidades.ConversorUnidades
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Velocidade GPS (v2.0): escolha da fonte do cronômetro, sensor do Painel/HUD e coluna do CSV. */
class VelocidadeGpsCronometroTest {

    private fun dados(ecu: Double?, gps: Double?) = DadosObd(
        buildMap {
            ecu?.let { put(Pids.VELOCIDADE, it) }
            gps?.let { put(Pids.VELOCIDADE_GPS, it) }
        },
    )

    // ---------------------------------------------------------------- seletor

    @Test
    fun `fonte GPS com fix usa o GPS e sem fix volta para a ECU`() {
        assertEquals(VelocidadeEscolhida(52.0, FonteVelocidade.GPS), SeletorVelocidade.escolher(dados(50.0, 52.0), FonteVelocidade.GPS))
        assertEquals(VelocidadeEscolhida(50.0, FonteVelocidade.OBD), SeletorVelocidade.escolher(dados(50.0, null), FonteVelocidade.GPS))
        assertNull(SeletorVelocidade.escolher(dados(null, null), FonteVelocidade.GPS))
        assertNull(SeletorVelocidade.escolher(null, FonteVelocidade.GPS))
    }

    @Test
    fun `fonte ECU ignora o GPS mesmo com fix`() {
        assertEquals(VelocidadeEscolhida(50.0, FonteVelocidade.OBD), SeletorVelocidade.escolher(dados(50.0, 52.0), FonteVelocidade.OBD))
        assertNull(SeletorVelocidade.escolher(dados(null, 52.0), FonteVelocidade.OBD))
    }

    @Test
    fun `troca de fonte so conta entre duas leituras`() {
        assertTrue(SeletorVelocidade.mudouDeFonte(FonteVelocidade.GPS, FonteVelocidade.OBD))
        assertFalse(SeletorVelocidade.mudouDeFonte(null, FonteVelocidade.GPS))
        assertFalse(SeletorVelocidade.mudouDeFonte(FonteVelocidade.GPS, null))
        assertFalse(SeletorVelocidade.mudouDeFonte(FonteVelocidade.GPS, FonteVelocidade.GPS))
        assertEquals(FonteVelocidade.GPS, FonteVelocidade.deCodigo(" gps "))
        assertEquals(FonteVelocidade.OBD, FonteVelocidade.deCodigo("lixo"))
    }

    // ---------------------------------------------------------------- cronômetro do Cluster/Oficina

    private class Relogio(var ms: Long = 0)

    @Test
    fun `cronometro do cluster mede o 0-100 pelo GPS quando escolhido`() {
        val relogio = Relogio()
        val telemetria = TelemetriaCompartilhada(relogioMs = { relogio.ms }, fonteCronometro = { FonteVelocidade.GPS })
        fun amostra(ms: Long, ecu: Double, gps: Double) {
            relogio.ms = ms
            telemetria.publicar(dados(ecu, gps), conectado = true)
        }
        // A ECU (010D) atrasa/arredonda; o GPS é o que vale.
        amostra(0, 0.0, 0.0)
        amostra(1_000, 5.0, 10.0)
        assertEquals(FaseDesempenho.MEDINDO, telemetria.cronometro.estado.value.fase)
        amostra(5_000, 55.0, 60.0)
        amostra(9_000, 95.0, 100.0)

        val e = telemetria.cronometro.estado.value
        assertEquals(FaseDesempenho.CONCLUIDO, e.fase)
        assertEquals(4.0, e.tempo060!!, 1e-9)
        assertEquals(8.0, e.tempo0100!!, 1e-9)
        assertEquals(FonteVelocidade.GPS, telemetria.fonteCronometroAtiva.value)
    }

    @Test
    fun `GPS que perde o sinal no meio do teste recomeca em vez de misturar com a ECU`() {
        val relogio = Relogio()
        val telemetria = TelemetriaCompartilhada(relogioMs = { relogio.ms }, fonteCronometro = { FonteVelocidade.GPS })
        relogio.ms = 0
        telemetria.publicar(dados(0.0, 0.0), conectado = true)
        relogio.ms = 1_000
        telemetria.publicar(dados(8.0, 10.0), conectado = true)
        assertEquals(FaseDesempenho.MEDINDO, telemetria.cronometro.estado.value.fase)

        relogio.ms = 2_000
        telemetria.publicar(dados(30.0, null), conectado = true) // túnel: sem fix

        assertEquals(FaseDesempenho.AGUARDANDO_PARADA, telemetria.cronometro.estado.value.fase)
        assertEquals(FonteVelocidade.OBD, telemetria.fonteCronometroAtiva.value)
    }

    @Test
    fun `fonte ECU padrao mantem o comportamento anterior`() {
        val relogio = Relogio()
        val telemetria = TelemetriaCompartilhada(relogioMs = { relogio.ms })
        relogio.ms = 0
        telemetria.publicar(dados(0.0, 0.0), conectado = true)
        relogio.ms = 1_000
        telemetria.publicar(dados(0.0, 30.0), conectado = true) // GPS andando, ECU parada: segue pronto
        assertEquals(FaseDesempenho.PRONTO, telemetria.cronometro.estado.value.fase)
        assertEquals(FonteVelocidade.OBD, telemetria.fonteCronometroAtiva.value)
    }

    // ---------------------------------------------------------------- sensor e CSV

    @Test
    fun `sensor Velocidade GPS le a chave do celular e mostra traco sem fix`() {
        val s = SensorPainel.VELOCIDADE_GPS
        assertEquals(GrupoSensor.PRINCIPAL, s.grupo)
        assertEquals(63.0, s.ler(dados(60.0, 63.0))!!, 0.0)
        assertNull(s.ler(dados(60.0, null)))
        assertEquals(SensorPainel.SEM_LEITURA, s.texto(null, ConversorUnidades.PADRAO))
        assertEquals(s, SensorPainel.deCodigo("VELOCIDADE_GPS"))
        assertTrue(s.foraDaEcu)
    }

    @Test
    fun `GPS sem sinal nunca vai para nao suportados neste veiculo`() {
        HistoricoSensores.limpar()
        repeat(HistoricoSensores.CICLOS_ATE_DESISTIR + 2) { HistoricoSensores.registrar(dados(50.0, null)) }
        assertFalse(SensorPainel.VELOCIDADE_GPS in HistoricoSensores.naoSuportados())
        HistoricoSensores.limpar()
    }

    @Test
    fun `coluna GPS do CSV tem nome, unidade e uma casa decimal`() {
        val def = DefinicaoPid.VELOCIDADE_GPS
        assertTrue(def.doAparelho)
        assertFalse(def.ehModo01 || def.ehComandoAt || def.ehEstendido)
        assertNull(def.pid)
        assertTrue(GeradorCsv.cabecalho(listOf(def)).endsWith("Velocidade GPS (km/h) [GPS_VEL]"))
        val linha = GeradorCsv.linha(1_000, 0, listOf(def), mapOf(Pids.VELOCIDADE_GPS to 87.26), java.time.ZoneOffset.UTC)
        assertTrue(linha, linha.endsWith(",87.3"))
    }
}
