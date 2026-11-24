package com.charruamotors.ui.diagnostico.obd

import com.charruamotors.obd.dtc.InfoDtc
import com.charruamotors.obd.dtc.TipoDtc
import org.junit.Assert.assertEquals
import org.junit.Test

class ResultadoDiagnosticoTest {

    private fun dtc(tipo: TipoDtc, codigo: String) = DtcExibicao(tipo, InfoDtc(codigo, "Título $codigo", null))

    @Test
    fun `itens separam as falhas por categoria na ordem 03 07 0A`() {
        val r = ResultadoDiagnostico(
            confirmados = listOf(dtc(TipoDtc.CONFIRMADO, "P0300"), dtc(TipoDtc.CONFIRMADO, "P0171")),
            pendentes = emptyList(),
            permanentes = null, // modo 0A não suportado
            freezeFrame = null,
        )
        assertEquals(
            listOf(
                ItemDiagnostico.Cabecalho(TipoDtc.CONFIRMADO, 2),
                ItemDiagnostico.Codigo(dtc(TipoDtc.CONFIRMADO, "P0300")),
                ItemDiagnostico.Codigo(dtc(TipoDtc.CONFIRMADO, "P0171")),
                ItemDiagnostico.Cabecalho(TipoDtc.PENDENTE, 0),
                ItemDiagnostico.Vazio(TipoDtc.PENDENTE, suportado = true),
                ItemDiagnostico.Cabecalho(TipoDtc.PERMANENTE, null),
                ItemDiagnostico.Vazio(TipoDtc.PERMANENTE, suportado = false),
            ),
            r.itens(),
        )
        assertEquals(2, r.total)
    }

    @Test
    fun `mesmo codigo em categorias diferentes tem chaves distintas`() {
        val r = ResultadoDiagnostico(
            confirmados = listOf(dtc(TipoDtc.CONFIRMADO, "P0420")),
            pendentes = emptyList(),
            permanentes = listOf(dtc(TipoDtc.PERMANENTE, "P0420")),
            freezeFrame = null,
        )
        val chaves = r.itens().map { it.chave }
        assertEquals(chaves.size, chaves.toSet().size)
        assertEquals(2, r.total)
    }
}
