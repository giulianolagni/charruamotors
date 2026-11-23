package com.charruamotors.sistema

import com.charruamotors.obd.InfoConexao
import com.charruamotors.obd.ModoComunicacaoEcu
import com.charruamotors.obd.pid.Formula
import com.charruamotors.obd.pid.ChaveSemantica
import com.charruamotors.obd.pid.DefinicaoPid
import com.charruamotors.obd.pid.RequisicaoEstendida
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Barra compacta do Painel conectado: `Conectado · OBDII · 7E0`. */
class StatusConexaoPainelTest {

    private fun espelho(cabecalho: String) = DefinicaoPid(
        comando = "010C",
        nome = "RPM ($cabecalho · 210C)",
        unidade = "rpm",
        bytes = 2,
        formula = Formula.compilar("(A*256+B)/4"),
        min = 0.0,
        max = 8000.0,
        estendida = RequisicaoEstendida(cabecalho, "21", "0C"),
        chave = ChaveSemantica.RPM,
    )

    @Test
    fun `modo 21 mostra o cabecalho fisico da ECU`() {
        val info = InfoConexao(protocolo = "ISO 15765-4 (CAN 11/500)", modo = ModoComunicacaoEcu.entries.first { it.servico == "21" }, nomeModo = "Modo 21", espelhos = listOf(espelho("7E0")))
        assertEquals("Conectado · OBDII · 7E0", StatusConexaoPainel.resumo("OBDII", info))
    }

    @Test
    fun `obd2 padrao mostra o protocolo curto`() {
        val info = InfoConexao(protocolo = "AUTO, ISO 15765-4 (CAN 11/500)")
        assertEquals("Conectado · OBDII · CAN 11/500", StatusConexaoPainel.resumo("OBDII", info))
        assertEquals("ISO 9141-2", StatusConexaoPainel.ecu(InfoConexao(protocolo = "AUTO, ISO 9141-2")))
    }

    @Test
    fun `partes desconhecidas somem`() {
        assertNull(StatusConexaoPainel.ecu(InfoConexao()))
        assertEquals("Conectado", StatusConexaoPainel.resumo(null, InfoConexao()))
        assertEquals("Conectado · OBDII Simulado", StatusConexaoPainel.resumo(" OBDII Simulado (ELM327) ", InfoConexao(protocolo = " ")))
    }
}
