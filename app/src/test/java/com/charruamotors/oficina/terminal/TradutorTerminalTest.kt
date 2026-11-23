package com.charruamotors.oficina.terminal

import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.oficina.varredura.IdentificadorPids
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.sistema.unidades.SistemaUnidades
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Terminal didático: descrição dos comandos, tradução das respostas e o laudo. */
class TradutorTerminalTest {

    private val tradutor = TradutorTerminal(
        CatalogoTeste.padrao,
        IdentificadorPids.de(CatalogoTeste.padrao, CatalogoTeste.estendido, CatalogoTeste.cascata),
    )

    private fun entrada(comando: String, resposta: String?, latencia: Long? = 40, semPrompt: Boolean = false, erro: String? = null) =
        EntradaTerminal(1_700_000_000_000, comando, resposta, latencia, semPrompt, erro)

    // ---------------------------------------------------------------- descrições e atalhos

    @Test
    fun `atalhos cobrem os comandos pedidos com descricao`() {
        val comandos = TradutorTerminal.ATALHOS.map { it.comando }
        for (c in listOf("ATRV", "ATDP", "ATSH 7E0", "ATSH 7E1", "210C", "0902")) assertTrue(c, c in comandos)
        TradutorTerminal.ATALHOS.forEach { assertTrue(it.descricao.isNotBlank() && it.titulo.isNotBlank()) }
        assertTrue(TradutorTerminal.DICAS.any { "ATRV" in it })
        assertTrue(TradutorTerminal.DICAS.any { "ATSH 7E0" in it })
        assertTrue(TradutorTerminal.DICAS.any { "210C" in it })
    }

    @Test
    fun `descreve comandos AT de servico e PIDs`() {
        assertEquals("Lê a tensão da bateria", tradutor.descrever("atrv"))
        assertEquals("Muda o foco para o motor (ECU 7E0)", tradutor.descrever("ATSH 7E0"))
        assertEquals("Muda o foco para o câmbio (TCM 7E1)", tradutor.descrever("ATSH7E1"))
        assertEquals("Lê o número do chassi (VIN)", tradutor.descrever("09 02"))
        assertEquals("Lê: Rotação do motor (OBD2 padrão)", tradutor.descrever("010C"))
        assertEquals("Lê: Rotação do motor pelo modo 21 (KWP/Toyota)", tradutor.descrever("210C"))
        assertEquals("Lê o identificador D4 pelo modo 21 (KWP/Toyota)", tradutor.descrever("21D4"))
        assertNull(tradutor.descrever("XYZ"))
    }

    // ---------------------------------------------------------------- traduções

    @Test
    fun `traduz a tensao da bateria com diagnostico`() {
        assertEquals("Bateria: 12,6 V (boa, motor desligado)", tradutor.traduzir(entrada("ATRV", "12.6V\r\r>")))
        assertEquals("Bateria: 14,2 V (alternador carregando)", tradutor.traduzir(entrada("ATRV", "14.2V\r>")))
        assertEquals("Bateria: 11,5 V (baixa: bateria descarregada ou fraca)", tradutor.traduzir(entrada("ATRV", "11.5V\r>")))
    }

    @Test
    fun `traduz protocolo foco VIN e falhas`() {
        assertEquals("Protocolo em uso: AUTO, ISO 15765-4 (CAN 11/500)", tradutor.traduzir(entrada("ATDP", "AUTO, ISO 15765-4 (CAN 11/500)\r\r>")))
        assertEquals("Foco alterado: agora os comandos vão para o motor (ECU 7E0)", tradutor.traduzir(entrada("ATSH 7E0", "OK\r\r>")))
        val vin = tradutor.traduzir(entrada("0902", "014\r0: 49 02 01 39 42 57\r1: 5A 5A 5A 33 37 37 56\r2: 54 30 30 34 32 35 31\r\r>"))
        assertEquals("Chassi (VIN): 9BWZZZ377VT004251", vin)
        assertEquals("Nenhuma falha registrada", tradutor.traduzir(entrada("03", "43 00\r\r>")))
    }

    @Test
    fun `traduz PID padrao e modo 21 com o cabecalho ativo e unidades imperiais`() {
        assertEquals("Rotação do motor: 851 rpm", tradutor.traduzir(entrada("010C", "41 0C 0D 4C\r\r>")))
        assertEquals("Velocidade do veículo: 62 mph", tradutor.traduzir(entrada("010D", "41 0D 64\r>"), conversor = ConversorUnidades(SistemaUnidades.IMPERIAL)))
        assertEquals("Rotação do motor: 850 rpm", tradutor.traduzir(entrada("210C", "61 0C 0D 48\r>"), cabecalho = "7E0"))
        assertEquals(
            "Resposta positiva com 3 byte(s) de dados (sensor ainda não mapeado)",
            tradutor.traduzir(entrada("21D4", "61 D4 01 02 03\r>"), cabecalho = "7E0"),
        )
    }

    @Test
    fun `traduz erros do ELM327 e respostas negativas`() {
        assertEquals("Sem dados: o módulo não tem esta informação", tradutor.traduzir(entrada("2155", "NO DATA\r\r>")))
        assertTrue(tradutor.traduzir(entrada("2199", "7F 21 12\r>"))!!.startsWith("Recusado pela ECU: "))
        assertEquals("Comando não reconhecido pelo ELM327", tradutor.traduzir(entrada("ATXX", "?\r>")))
        assertEquals("Sem resposta no prazo: o adaptador ou o módulo não respondeu", tradutor.traduzir(entrada("0100", "", semPrompt = true)))
        assertEquals("Não enviado: desconectado", tradutor.traduzir(entrada("ATRV", null, null, erro = "desconectado")))
    }

    @Test
    fun `cabecalho ativo e o ultimo ATSH aceito`() {
        val log = listOf(
            entrada("ATSH 7E0", "OK\r>"),
            entrada("210C", "61 0C 0D 48\r>"),
            entrada("ATSH 7E1", "OK\r>"),
            entrada("ATSH 7ZZ", "?\r>"),
        )
        assertEquals("7E1", tradutor.cabecalhoAtivo(log))
        assertEquals("7E0", tradutor.cabecalhoAtivo(log.take(2)))
        assertNull(tradutor.cabecalhoAtivo(emptyList()))
    }

    // ---------------------------------------------------------------- laudo

    @Test
    fun `laudo traz resumo leituras traduzidas e o log bruto`() {
        val log = listOf(
            entrada("ATRV", "12.4V\r>", 30),
            entrada("ATSH 7E0", "OK\r>", 20),
            entrada("210C", "61 0C 0D 48\r>", 70),
            entrada("2155", "", null, semPrompt = true),
        )
        val laudo = LaudoTerminal.gerar(
            log, tradutor, LocalDateTime.of(2026, 11, 23, 14, 30),
            veiculo = "Toyota SW4 (ABC-1D23)", protocolo = "ISO 15765-4 (CAN 11/500)",
        )
        assertTrue(laudo, laudo.startsWith("LAUDO DO TERMINAL ELM327 — Charrua OBD Pro\n23/11/2026 14:30 · Toyota SW4 (ABC-1D23)"))
        assertTrue(laudo.contains("Protocolo: ISO 15765-4 (CAN 11/500)"))
        assertTrue(laudo.contains("Comandos: 4 · respondidos: 3 · sem resposta/erro: 1 · latência média: 40 ms"))
        assertTrue(laudo.contains("> ATRV — Lê a tensão da bateria\n  bruto: 12.4V\n  → Bateria: 12,4 V (boa, motor desligado)"))
        // O 210C é traduzido no contexto do ATSH 7E0 enviado antes.
        assertTrue(laudo.contains("  → Rotação do motor: 850 rpm"))
        assertTrue(laudo.contains("  → Sem resposta no prazo"))
        assertTrue(laudo.contains("Log bruto:"))
    }

    @Test
    fun `laudo vazio avisa que nada foi enviado`() {
        val laudo = LaudoTerminal.gerar(emptyList(), tradutor, LocalDateTime.of(2026, 1, 2, 3, 4))
        assertTrue(laudo.endsWith("Nenhum comando enviado."))
        assertTrue(laudo.contains("Comandos: 0"))
    }

    @Test
    fun `linhas do laudo usam o cabecalho anterior a cada comando`() {
        val log = listOf(entrada("210C", "61 0C 0D 48\r>"), entrada("ATSH 7E1", "OK\r>"))
        val linhas = LaudoTerminal.linhas(log, tradutor)
        assertEquals("Rotação do motor: 850 rpm", linhas[0].traducao)
        assertEquals("61 0C 0D 48", linhas[0].bruto)
        assertEquals("Muda o foco para o câmbio (TCM 7E1)", linhas[1].descricao)
    }
}
