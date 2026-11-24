package com.charruamotors.obd.log

import com.charruamotors.domain.calculo.CentralAlertas
import com.charruamotors.domain.calculo.LimitesProtecao
import com.charruamotors.domain.calculo.TipoAlertaProtecao
import com.charruamotors.obd.DadosObd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.8.0: log completo de tráfego, alertas no log e ATF no resumo. */
class LogCompletoTrafegoTest {
    @Test
    fun `polling so chega ao ouvinte com o log completo ligado e fora do buffer`() {
        val r = RegistroComunicacao()
        val recebidas = mutableListOf<EntradaRegistro>()
        r.ouvinte = { recebidas += it }
        r.comandoSomenteLog("010C", "41 0C 1A F8", 40)
        assertTrue(recebidas.isEmpty())
        r.logCompleto = true
        r.comandoSomenteLog("21D9", "61 D9 00", 35)
        r.decodificado("010C RPM = 1726.0 rpm")
        assertEquals(2, recebidas.size)
        assertTrue(r.entradas().isEmpty())
        assertEquals(5_000, RegistroComunicacao.CAPACIDADE_PADRAO)
    }

    @Test
    fun `alerta entra uma vez no log e rearma ao sair`() {
        val central = CentralAlertas(som = {}, relogio = { 0L })
        central.limites = LimitesProtecao(aguaMaxC = 100.0)
        val tipos = mutableListOf<TipoAlertaProtecao>()
        central.aoEntrarEmAlerta = { tipos += it.tipo }
        val quente = DadosObd(mapOf("0105" to 105.0))
        central.processar(quente); central.processar(quente)
        central.processar(DadosObd(mapOf("0105" to 90.0)))
        central.processar(quente)
        assertEquals(listOf(TipoAlertaProtecao.AGUA, TipoAlertaProtecao.AGUA), tipos)
    }

    @Test
    fun `estatisticas guardam a temperatura maxima do ATF`() {
        val e = EstatisticasViagem()
        e.registrar(DadosObd(mapOf("TOYOTA_ATF_CAMBIO" to 70.0)))
        e.registrar(DadosObd(mapOf("TOYOTA_ATF_CAMBIO" to 96.0)))
        e.registrar(DadosObd(mapOf("TOYOTA_ATF_CAMBIO" to 88.0)))
        assertEquals(96.0, e.temperaturaAtf.maximo!!, 0.0)
    }
}
