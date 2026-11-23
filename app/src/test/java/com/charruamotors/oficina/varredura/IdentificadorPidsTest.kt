package com.charruamotors.oficina.varredura

import com.charruamotors.obd.RespostaBruta
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.sistema.unidades.SistemaUnidades
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Varredura amigável: cruza as respostas cruas com os catálogos reais do app. */
class IdentificadorPidsTest {

    private val identificador = IdentificadorPids.de(CatalogoTeste.padrao, CatalogoTeste.estendido, CatalogoTeste.cascata)

    private fun resultado(comando: String, bruto: String) = VarreduraPids.classificar(comando, RespostaBruta(comando, bruto, 40))

    @Test
    fun `210C no motor e identificado como RPM com o valor decodificado`() {
        val id = identificador.identificar(resultado("210C", "610C0D48\r\r>"), "7E0")
        assertEquals(StatusIdentificacao.IDENTIFICADO, id.status)
        assertEquals("Identificado: Rotação do motor (PID 210C)", id.descricao)
        assertEquals("850 rpm", id.valorTexto) // (0x0D*256 + 0x48)/4 = 850
        assertEquals("Modo 21 (M-OBD / KWP)", id.conhecido!!.origem)
    }

    @Test
    fun `2123 e a pressao do common rail`() {
        val id = identificador.identificar(resultado("2123", "61230FA0\r>"), "7E0")
        assertEquals(StatusIdentificacao.IDENTIFICADO, id.status)
        assertTrue(id.descricao, id.descricao.startsWith("Identificado: Pressão do rail"))
        assertTrue(id.descricao.endsWith("(PID 2123)"))
    }

    @Test
    fun `identificador fora do catalogo aparece como desconhecido com a resposta`() {
        val id = identificador.identificar(resultado("21D4", "61D4A1B2C3D4E5\r>"), "7E0")
        assertEquals(StatusIdentificacao.DESCONHECIDO, id.status)
        assertEquals("Desconhecido (PID 21D4 - resposta: 61D4A1B2C3D4...)", id.descricao)
        assertNull(id.conhecido)
        assertEquals("", id.valorTexto)
    }

    @Test
    fun `NO DATA e negativa ficam como ignorados`() {
        val semDados = identificador.identificar(resultado("2155", "NO DATA\r>"), "7E0")
        assertEquals(StatusIdentificacao.IGNORADO, semDados.status)
        assertEquals("Sem resposta (PID 2155 - NO DATA)", semDados.descricao)
        val negativa = identificador.identificar(resultado("2199", "7F2131\r>"), "7E0")
        assertEquals(StatusIdentificacao.IGNORADO, negativa.status)
        assertEquals("Sem resposta (PID 2199 - 7F 21 31)", negativa.descricao)
    }

    @Test
    fun `perfil Toyota identifica o ATF no cambio e os 4 injetores no 2113`() {
        val atf = identificador.conhecido("7E1", "2182")!!
        assertEquals("Toyota", atf.origem)
        assertTrue(atf.nome.contains("ATF"))
        val injetores = identificador.identificar(resultado("2113", "611381A47ECA873A7F88\r>"), "7E0")
        assertEquals(StatusIdentificacao.IDENTIFICADO, injetores.status)
        assertTrue(injetores.descricao, injetores.descricao.endsWith("+3 sensores"))
    }

    @Test
    fun `modo 01 padrao e identificado em qualquer cabecalho e converte para imperial`() {
        val r = resultado("010D", "410D64\r>") // 100 km/h
        val metrico = identificador.identificar(r, "7DF")
        assertEquals("Identificado: Velocidade do veículo (PID 010D)", metrico.descricao)
        assertEquals("100 km/h", metrico.valorTexto)
        val imperial = identificador.identificar(r, "7E0", ConversorUnidades(SistemaUnidades.IMPERIAL))
        assertEquals("62 mph", imperial.valorTexto)
    }

    @Test
    fun `cabecalho exato tem prioridade e sem ele vale o primeiro conhecido`() {
        assertEquals("Modo 21 (M-OBD / KWP)", identificador.conhecido("7E0", "210C")!!.origem)
        assertEquals("Modo 21 (M-OBD / KWP)", identificador.conhecido("7E1", "210C")!!.origem)
        assertNull(identificador.conhecido("7E0", "21D4"))
    }

    @Test
    fun `valor fora da faixa teorica nao e exibido`() {
        // Temperatura do motor (2105) com A = 0xFF → 215 °C está no limite; resposta curta demais não decodifica.
        val curta = identificador.identificar(resultado("210C", "610C0D\r>"), "7E0")
        assertEquals(StatusIdentificacao.IDENTIFICADO, curta.status)
        assertNull(curta.valor)
    }

    @Test
    fun `metricas contam encontrados identificados e ignorados`() {
        val lista = identificador.identificarTodos(
            listOf(
                resultado("210C", "610C0D48\r>"),
                resultado("21D4", "61D4A1\r>"),
                resultado("2155", "NO DATA\r>"),
                resultado("2199", "7F2131\r>"),
            ),
            "7E0",
        )
        val m = MetricasVarredura.de(lista, total = 256)
        assertEquals(1, m.identificados)
        assertEquals(1, m.desconhecidos)
        assertEquals(2, m.encontrados)
        assertEquals(2, m.ignorados)
        assertEquals(4, m.feitos)
        assertEquals(256, m.total)
    }

    @Test
    fun `relatorio da varredura traz o nome e o valor dos identificados`() {
        val config = ConfigVarredura("7E0", "21", 0x0C, 0x0E)
        val resultados = listOf(resultado("210C", "610C0D48\r>"), resultado("210D", "NO DATA\r>"), resultado("210E", "610E01\r>"))
        val texto = RelatorioVarredura.gerar(
            ResultadoVarredura(config, resultados, 3),
            java.time.LocalDateTime.of(2026, 11, 23, 9, 0),
            identificacoes = identificador.identificarTodos(resultados, "7E0"),
        )
        assertTrue(texto, texto.contains("210C → 61 0C 0D 48  [2 bytes de dados]  = Rotação do motor: 850 rpm"))
        assertTrue(texto, texto.contains("210E → 61 0E 01  [1 byte de dados]\n"))
        assertTrue(texto, texto.contains("Identificados no catálogo: 1 · desconhecidos (candidatos a mapear): 1"))
    }

    @Test
    fun `abreviar corta respostas longas`() {
        assertEquals("610C0D48", IdentificadorPids.abreviar("61 0C 0D 48"))
        assertEquals("61D4A1B2C3D4...", IdentificadorPids.abreviar("61 D4 A1 B2 C3 D4 E5 F6"))
    }
}
