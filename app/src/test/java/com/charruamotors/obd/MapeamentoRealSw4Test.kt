package com.charruamotors.obd

import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.pid.RequisicaoEstendida
import com.charruamotors.obd.transporte.ObdTransport
import com.charruamotors.oficina.bicos.AvaliacaoBicos
import com.charruamotors.oficina.bicos.SituacaoBico
import com.charruamotors.sistema.unidades.ConversorUnidades
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Payloads reais capturados na Toyota SW4 (1KD-FTV + câmbio A750F): `7E1 · 21D9` (alavanca + ATF),
 * `7E1 · 2130` (marcha), `7E0 · 21AD` (4 bicos), `7E0 · 2149`, `2142` e `2133`.
 */
class MapeamentoRealSw4Test {

    private val catalogo = CatalogoTeste.estendido
    private val conversor = ConversorUnidades.PADRAO

    private fun valor(chave: String, bruto: String): Double =
        (ObdParser.parsePid(bruto, catalogo[chave]!!) as RespostaObd.Ok).valor

    /** `21D9` do câmbio: 11 bytes de dados em multi-frame ISO-TP (como o ELM327 com ATH0 devolve). */
    private fun d9(vararg b: Int): String {
        require(b.size == 11)
        val hex = b.joinToString("") { "%02X".format(it) }
        return "00D\r0:61D9${hex.take(8)}\r1:${hex.drop(8)}\r\r"
    }

    // ---------------------------------------------------------------- 7E1 · 21D9: alavanca + ATF

    @Test
    fun `21D9 no 7E1 decodifica a alavanca pelos bytes C e D em P-N R e D`() {
        val pn = d9(0x00, 0x00, 0x00, 0x00, 0x42, 0xE0, 0, 0, 0, 0, 0)
        val r = d9(0x00, 0x00, 0x04, 0x00, 0x42, 0xE0, 0, 0, 0, 0, 0)
        val d = d9(0x00, 0x00, 0x00, 0x04, 0x42, 0xE0, 0, 0, 0, 0, 0)
        val alavanca = catalogo["TOYOTA_ALAVANCA"]!!
        assertEquals("P/N", alavanca.rotulo(valor("TOYOTA_ALAVANCA", pn)))
        assertEquals("R", alavanca.rotulo(valor("TOYOTA_ALAVANCA", r)))
        assertEquals("D", alavanca.rotulo(valor("TOYOTA_ALAVANCA", d)))
        // Sem espaços no ELM327 com ATS0 ou com espaços: o mesmo resultado.
        assertEquals("D", alavanca.rotulo(valor("TOYOTA_ALAVANCA", "61 D9 00 00 00 04 42 E0 00 00 00 00 00\r\r")))
        // O byte A não decide a alavanca (C/D 00 00 segue P/N com A = 0x10).
        assertEquals("P/N", alavanca.rotulo(valor("TOYOTA_ALAVANCA", d9(0x10, 0, 0, 0, 0x42, 0xE0, 0, 0, 0, 0, 0))))
        // Combinação não mapeada (C e D ao mesmo tempo) não vira rótulo chutado.
        assertTrue(ObdParser.parsePid(d9(0, 0, 0x04, 0x04, 0x42, 0xE0, 0, 0, 0, 0, 0), alavanca) is RespostaObd.Invalido)
    }

    @Test
    fun `21D9 no 7E1 decodifica a temperatura do ATF com os valores reais capturados`() {
        fun atf(hi: Int, lo: Int) = valor("TOYOTA_ATF_CAMBIO", d9(0x10, 0, 0, 0, hi, lo, 0, 0, 0, 0, 0))
        assertEquals(26.0, atf(0x42, 0xE0), 0.01)
        assertEquals(28.0, atf(0x44, 0xC0), 0.01)
        assertEquals(32.0, atf(0x48, 0x80), 0.01)
        // Exposto direto no sensor Temp. Câmbio (ATF).
        assertEquals("Temp. Câmbio (ATF)", SensorPainel.TEMP_CAMBIO.titulo)
        assertEquals("TOYOTA_ATF_CAMBIO", SensorPainel.TEMP_CAMBIO.comando)
        assertEquals("7E1 · 21D9", catalogo["TOYOTA_ATF_CAMBIO"]!!.rotuloComando)
    }

    @Test
    fun `fallback da alavanca pelo 7E0 21D9 de 3 bytes 00 41 A0 e P`() {
        val ecm = catalogo["TOYOTA_ALAVANCA_ECM"]!!
        assertEquals("7E0 · 21D9", ecm.rotuloComando)
        assertEquals("P", ecm.rotulo(valor("TOYOTA_ALAVANCA_ECM", "6 1D90041A0".replace(" ", "") + "\r\r")))
    }

    // ---------------------------------------------------------------- 7E1 · 2130: marcha

    @Test
    fun `2130 devolve FF parado e 1 a 5 em movimento`() {
        val marcha = catalogo["TOYOTA_MARCHA"]!!
        assertEquals("7E1 · 2130", marcha.rotuloComando)
        assertEquals("Parado", marcha.rotulo(valor("TOYOTA_MARCHA", "6130FF\r\r")))
        listOf("1ª", "2ª", "3ª", "4ª", "5ª").forEachIndexed { i, nome ->
            assertEquals(nome, marcha.rotulo(valor("TOYOTA_MARCHA", "6130%02X\r\r".format(i + 1))))
        }
        // No Painel o 0xFF aparece como "Parado", não como valor inválido.
        assertEquals("Parado", SensorPainel.MARCHA_ATUAL.texto(255.0, conversor))
        assertEquals("3ª", SensorPainel.MARCHA_ATUAL.texto(3.0, conversor))
        assertEquals(marcha.rotulos, SensorPainel.MARCHA_ATUAL.rotulos)
    }

    // ---------------------------------------------------------------- 7E0 · 21AD: 4 bicos injetores

    @Test
    fun `21AD decodifica os 4 bicos como signed short dividido por 512`() {
        // +0,45 / +0,44 / +0,66 / 0,00 mm³/st (0x00E6, 0x00E1, 0x0152, 0x0000).
        val bruto = "00D\r0:61AD00E600E1\r1:01520000000000\r\r"
        assertEquals(230 / 512.0, valor("TOYOTA_INJ1", bruto), 1e-9)
        assertEquals(225 / 512.0, valor("TOYOTA_INJ2", bruto), 1e-9)
        assertEquals(338 / 512.0, valor("TOYOTA_INJ3", bruto), 1e-9)
        assertEquals(0.0, valor("TOYOTA_INJ4", bruto), 1e-9)
        assertEquals("7E0 · 21AD", catalogo["TOYOTA_INJ1"]!!.rotuloComando)
        // Sinal: 0xFE00 = −512 → −1,00 mm³/st.
        assertEquals(-1.0, valor("TOYOTA_INJ2", "61ADFE00FE00FE00FE00\r\r"), 1e-9)
        // Resposta curta (só 2 bicos) invalida os bicos 3 e 4.
        assertTrue(ObdParser.parsePid("61AD00E600E1\r\r", catalogo["TOYOTA_INJ3"]!!) is RespostaObd.Invalido)
    }

    @Test
    fun `mostradores dos bicos com sinal e a faixa saudavel de 3 mm3 por stroke`() {
        assertEquals(
            listOf("TOYOTA_INJ1", "TOYOTA_INJ2", "TOYOTA_INJ3", "TOYOTA_INJ4"),
            listOf(SensorPainel.BICO_1, SensorPainel.BICO_2, SensorPainel.BICO_3, SensorPainel.BICO_4).map { it.comando },
        )
        assertTrue(Regex("\\+0[.,]45").matches(SensorPainel.BICO_1.texto(230 / 512.0, conversor)))
        assertTrue(Regex("-0[.,]39").matches(SensorPainel.BICO_1.texto(-200 / 512.0, conversor)))
        assertTrue(Regex("0[.,]00").matches(SensorPainel.BICO_4.texto(0.0, conversor)))

        assertEquals(SituacaoBico.OK, AvaliacaoBicos.avaliar(0.66))
        assertEquals(SituacaoBico.OK, AvaliacaoBicos.avaliar(-3.0))
        assertEquals(SituacaoBico.OK, AvaliacaoBicos.avaliar(3.0))
        assertEquals(SituacaoBico.ATENCAO, AvaliacaoBicos.avaliar(3.5))
        assertEquals(SituacaoBico.FORA, AvaliacaoBicos.avaliar(-4.2))
        assertEquals(SituacaoBico.SEM_LEITURA, AvaliacaoBicos.avaliar(null))
        assertEquals(SituacaoBico.SEM_LEITURA, AvaliacaoBicos.avaliar(Double.NaN))
    }

    // ---------------------------------------------------------------- 7E0: pedal, tensão da ECU e barômetro

    @Test
    fun `7E0 pedal 2149 tensao da ECU 2142 e barometro 2133`() {
        assertEquals(0x29 * 100.0 / 255, valor("TOYOTA_PEDAL_D", "614929\r\r"), 1e-9) // 16,1 %
        assertEquals(16.1, valor("TOYOTA_PEDAL_D", "614929\r\r"), 0.05)
        assertEquals(14.179, valor("TOYOTA_TENSAO_ECU", "61423763\r\r"), 1e-9) // 14,18 V
        assertEquals(92.0, valor("TOYOTA_BARO", "61335C\r\r"), 1e-9)
        assertEquals("7E0 · 2142", catalogo["TOYOTA_TENSAO_ECU"]!!.rotuloComando)
        assertEquals("7E0 · 2133", catalogo["TOYOTA_BARO"]!!.rotuloComando)
        // Pedal prioriza o 2149 (D) e cai no 214A (E).
        assertEquals(listOf("0149", "TOYOTA_PEDAL_D", "TOYOTA_PEDAL_E"), SensorPainel.ACELERADOR.comandos)
        assertTrue(Regex("14[.,]18").matches(SensorPainel.TENSAO_ECU.texto(14.179, conversor)))
        assertEquals(SensorPainel.PRESSAO_BARO, SensorPainel.doComando("TOYOTA_BARO"))
    }

    // ---------------------------------------------------------------- sem sufixo "1" nos PIDs multi-frame

    @Test
    fun `21D9 e 21AD sao multi-frame e nunca levam o sufixo 1`() {
        assertTrue(RequisicaoEstendida("7E1", "21", "D9").multiFrame)
        assertTrue(RequisicaoEstendida("7E0", "21", "D9").multiFrame)
        assertTrue(RequisicaoEstendida("7E0", "21", "AD").multiFrame)
        assertFalse(RequisicaoEstendida("7E0", "21", "49").multiFrame)
        assertFalse(RequisicaoEstendida("7E0", "22", "11BD").multiFrame)
    }

    private class Roteirizado(val respostas: Map<String, String>) : ObdTransport {
        val enviados = mutableListOf<String>()
        private var aberto = false
        override suspend fun conectar() { aberto = true }
        override suspend fun enviarComando(comando: String, timeoutMs: Long): String {
            if (!aberto) throw IOException("fechado")
            enviados += comando
            return respostas[comando] ?: if (comando.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"
        }
        override fun fechar() { aberto = false }
    }

    private fun TestScope.cliente(t: ObdTransport) = Elm327BluetoothClient(
        adaptador = null,
        catalogo = CatalogoTeste.padrao,
        criarTransporte = { t },
        dispatcher = StandardTestDispatcher(testScheduler),
        catalogoEstendido = CatalogoTeste.estendido,
    )

    @Test
    fun `polling continuo da SW4 nao anexa o sufixo 1 em 21D9 e 21AD e deixa 7B0 e 7C0 de fora`() = runTest {
        val t = Roteirizado(
            mapOf(
                "ATDPN" to "A6\r\r",
                "ATRV" to "13.8V\r\r",
                "0100" to "41 00 BE 1F A8 13\r\r", // o adaptador aceita o sufixo de resposta única
                "01001" to "41 00 BE 1F A8 13\r\r",
                "21AD" to "00D\r0:61AD00E600E1\r1:01520000000000\r\r",
                "21AD1" to "61AD00E6\r\r", // se o sufixo fosse enviado, a resposta viria truncada
                "21D9" to d9(0x10, 0, 0, 0, 0x42, 0xE0, 0, 0, 0, 0, 0),
                "21D91" to "NO DATA\r\r",
                "2149" to "614929\r\r",
                "21491" to "614929\r\r",
                "2142" to "61423763\r\r",
                "21421" to "61423763\r\r",
                "2133" to "61335C\r\r",
                "21331" to "61335C\r\r",
            ),
        )
        val obd = cliente(t)
        obd.conectar(DispositivoObd("ELM327", "00:11:22:33:44:55"))
        val perfil = obd.ativarPerfilFabricante("Toyota SW4") as ResultadoPerfil.Ativo
        val ativos = perfil.pids.map { it.comando }.toSet()
        assertTrue(ativos.containsAll(listOf("TOYOTA_INJ1", "TOYOTA_INJ4", "TOYOTA_ALAVANCA", "TOYOTA_ATF_CAMBIO", "TOYOTA_PEDAL_D")))
        // 7B0 / 7C0 não responderam na sondagem: ficam fora do loop de telemetria.
        assertFalse("TOYOTA_VSC" in ativos || "TOYOTA_4WD" in ativos)
        t.enviados.clear()

        val dados = obd.lerTelemetria(250, lentosPorVez = 50, ciclosPorLento = 1).take(12).toList().last()

        assertTrue(t.enviados.any { it == "21AD" } && t.enviados.any { it == "21D9" })
        assertFalse(t.enviados.any { it == "21AD1" || it == "21D91" })
        assertFalse(t.enviados.any { it == "ATSH7B0" || it == "ATSH7C0" })
        assertEquals(230 / 512.0, dados["TOYOTA_INJ1"]!!, 1e-9)
        assertEquals(26.0, dados["TOYOTA_ATF_CAMBIO"]!!, 0.01)
        // C/D 00 00 = P/N no cliente; a sessão (EstimadorCambioA750f) decide P ou N pela velocidade.
        assertEquals("P/N", SensorPainel.MARCHA_ALAVANCA.texto(SensorPainel.MARCHA_ALAVANCA.ler(dados), conversor))
        assertEquals("N/D", SensorPainel.TRACAO_4X4.texto(SensorPainel.TRACAO_4X4.ler(dados), conversor))
        assertNull(dados["TOYOTA_VSC"])
    }
}
