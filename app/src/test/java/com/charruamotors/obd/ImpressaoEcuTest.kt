package com.charruamotors.obd

import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.transporte.ObdTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** v2.2.2: parsers do `0151`/`0904`, a sondagem do cliente ELM327 (TCM, restauração do cabeçalho) e a do simulado. */
class ImpressaoEcuTest {

    private fun <T> RespostaObd<T>.valor(): T = (this as RespostaObd.Ok<T>).valor

    private fun hex(texto: String) = texto.map { "%02X".format(it.code) }

    // ---------------------------------------------------------------- 0151

    @Test
    fun `0151 devolve o byte do combustivel`() {
        assertEquals(0x01, ObdParser.parseCombustivelEcu("0151\r41 51 01\r\r>").valor())
        assertEquals(0x04, ObdParser.parseCombustivelEcu("SEARCHING...\r41 51 04\r\r>").valor())
        assertEquals(0x09, ObdParser.parseCombustivelEcu("415109").valor())
        assertEquals(0x7E, ObdParser.parseCombustivelEcu("41 51 7E").valor()) // desconhecido: decodifica depois
    }

    @Test
    fun `0151 com NO DATA 7F ou lixo vira erro tipado e nunca lanca`() {
        assertEquals(RespostaObd.SemDados, ObdParser.parseCombustivelEcu("NO DATA\r\r>"))
        assertTrue(ObdParser.parseCombustivelEcu("7F 01 12\r\r>") is RespostaObd.Invalido) // subfunção não suportada
        assertTrue(ObdParser.parseCombustivelEcu("7F 01 31") is RespostaObd.Invalido)
        assertTrue(ObdParser.parseCombustivelEcu("41 51") is RespostaObd.Invalido) // truncada
        assertEquals(RespostaObd.SemConexao, ObdParser.parseCombustivelEcu("UNABLE TO CONNECT"))
        assertEquals(RespostaObd.ComandoInvalido, ObdParser.parseCombustivelEcu("?"))
        assertTrue(ObdParser.parseCombustivelEcu("") is RespostaObd.Invalido)
    }

    // ---------------------------------------------------------------- 0904

    @Test
    fun `0904 CAN multi-frame ISO-TP junta os quadros e limpa o texto`() {
        val b = hex("GM6T35LE2CAL018A")
        val bruto = "0904\r013\r0: 49 04 01 ${b.take(3).joinToString(" ")}\r" +
            "1: ${b.subList(3, 10).joinToString(" ")}\r2: ${b.subList(10, 16).joinToString(" ")}\r>"
        assertEquals("GM6T35LE2CAL018A", ObdParser.parseCalid(bruto).valor())
    }

    @Test
    fun `0904 descarta bytes nulos de preenchimento e espacos`() {
        // "ABC123" + 10 bytes 00, sem espaços (ATS0) e minúsculas.
        val bytes = hex("ABC123") + List(10) { "00" }
        val quadros = (listOf("49", "04", "01") + bytes)
        val bruto = "013\r0:" + quadros.take(6).joinToString("") + "\r1:" + quadros.subList(6, 13).joinToString("") +
            "\r2:" + quadros.drop(13).joinToString("") + "\r>"
        assertEquals("ABC123", ObdParser.parseCalid(bruto.lowercase()).valor())
    }

    @Test
    fun `0904 com dois CALIDs devolve os dois separados`() {
        val dados = hex("CALIDUM0000000A1") + hex("CALIDDOIS000002B")
        val bytes = (listOf("49", "04", "02") + dados)
        val bruto = "023\r0: ${bytes.take(6).joinToString(" ")}\r" +
            bytes.drop(6).chunked(7).withIndex().joinToString("\r") { (i, q) -> "${i + 1}: ${q.joinToString(" ")}" } + "\r>"
        val calid = ObdParser.parseCalid(bruto).valor()
        assertEquals(2, calid.split(" / ").size)
        assertTrue(calid, calid.startsWith("CALIDUM") && calid.contains("CALIDDOIS"))
    }

    @Test
    fun `0904 em KWP com uma linha por trecho e fora de ordem`() {
        val b = hex("TOYOTACALID12345")
        val bruto = "49 04 02 ${b.subList(4, 8).joinToString(" ")}\r49 04 01 ${b.take(4).joinToString(" ")}\r" +
            "49 04 04 ${b.subList(12, 16).joinToString(" ")}\r49 04 03 ${b.subList(8, 12).joinToString(" ")}\r>"
        assertEquals("TOYOTACALID12345", ObdParser.parseCalid(bruto).valor())
    }

    @Test
    fun `0904 com NO DATA 7F ou so zeros e erro tipado`() {
        assertEquals(RespostaObd.SemDados, ObdParser.parseCalid("NO DATA\r\r>"))
        assertTrue(ObdParser.parseCalid("7F 09 11") is RespostaObd.Invalido)
        assertTrue(ObdParser.parseCalid("49 04 01 00 00 00 00") is RespostaObd.Invalido)
        assertEquals(RespostaObd.SemConexao, ObdParser.parseCalid("CAN ERROR"))
    }

    // ---------------------------------------------------------------- cliente ELM327 (transporte roteirizado)

    private class Transporte(respostasIniciais: Map<String, String>) : ObdTransport {
        val respostas = respostasIniciais.toMutableMap()
        val enviados = mutableListOf<String>()
        private var aberto = false

        override suspend fun conectar() {
            aberto = true
        }

        override suspend fun enviarComando(comando: String, timeoutMs: Long): String {
            enviados += comando
            return respostas[comando] ?: if (comando.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"
        }

        override fun fechar() {
            aberto = false
        }
    }

    private val dispositivo = DispositivoObd("ELM327", "00:11:22:33:44:55")

    private fun ecu(extra: Map<String, String> = emptyMap()) = mutableMapOf(
        "ATZ" to "ELM327 v1.5\r\r",
        "ATRV" to "12.6V\r\r",
        "0100" to "SEARCHING...\r41 00 BE 1F A8 13\r\r",
        "0120" to "41 20 80 02 20 01\r\r",
        "0140" to "41 40 44 00 00 10\r\r",
        "01001" to "41 00 BE 1F A8 13\r\r",
        "010C1" to "41 0C 1A F8\r\r",
    ) + extra

    private fun TestScope.cliente(t: ObdTransport) = Elm327BluetoothClient(
        adaptador = null,
        catalogo = CatalogoTeste.padrao,
        criarTransporte = { t },
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private val calid = hex("GM6T35LE2CAL018A").let { b ->
        "013\r0: 49 04 01 ${b.take(3).joinToString(" ")}\r1: ${b.subList(3, 10).joinToString(" ")}\r2: ${b.subList(10, 16).joinToString(" ")}\r\r"
    }

    @Test
    fun `sondagem real le 0151 e 0904 e detecta o TCM em 7E1 restaurando o cabecalho`() = runTest {
        val t = Transporte(
            ecu(
                mapOf(
                    "ATDPN" to "A6\r\r",
                    "0151" to "41 51 09\r\r",
                    "0904" to calid,
                    // Só o TCM em 7E1 responde ao 0100 (a ECU do motor não é endereçada).
                ),
            ),
        )
        val obd = cliente(t)
        assertTrue(obd.conectar(dispositivo).isSuccess)
        val antes = t.enviados.size
        // O ELM327 de teste devolve a mesma resposta ao 0100 em qualquer cabeçalho: o TCM "responde" em 7E1.
        val imp = obd.sondarEcu(sondarTcm = true)

        assertEquals(0x09, imp.codigoCombustivel)
        assertEquals("GM6T35LE2CAL018A", imp.calid)
        assertEquals(true, imp.tcmDetectado)
        assertEquals("7E1", imp.tcmCabecalho)
        val novos = t.enviados.drop(antes)
        // O 0904 é de prazo longo (ATST32/ATST19) e multi-frame (no CAN, refixa protocolo, cabeçalho e filtro).
        assertEquals(listOf("0151", "ATST32", "0904", "ATSP6", "ATSH7DF", "ATCRA", "ATST19", "ATSH7E1", "0100", "ATSH7DF"), novos)
        assertEquals(EstadoConexaoObd.Conectado, obd.estado.value)
    }

    @Test
    fun `NO DATA em tudo deixa a impressao vazia, tenta 7E1 e 7E2, restaura o cabecalho e mantem a sessao`() = runTest {
        val t = Transporte(ecu(mapOf("ATDPN" to "A6\r\r")))
        val obd = cliente(t)
        assertTrue(obd.conectar(dispositivo).isSuccess)
        t.respostas["0100"] = "NO DATA\r\r"
        t.respostas["0151"] = "NO DATA\r\r"
        t.respostas["0904"] = "7F 09 11\r\r"
        val antes = t.enviados.size

        val imp = obd.sondarEcu(sondarTcm = true)

        assertNull(imp.codigoCombustivel)
        assertNull(imp.calid)
        assertEquals(false, imp.tcmDetectado)
        assertNull(imp.tcmCabecalho)
        assertEquals(
            listOf("0151", "ATST32", "0904", "ATSP6", "ATSH7DF", "ATCRA", "ATST19", "ATSH7E1", "0100", "ATSH7E2", "0100", "ATSH7DF"),
            t.enviados.drop(antes),
        )
        // A sessão segue: um PID normal ainda é lido depois da sondagem.
        assertEquals(EstadoConexaoObd.Conectado, obd.estado.value)
        assertEquals(1726.0, obd.lerDadosEmTempoReal(10, listOf("010C")).first()["010C"]!!, 0.5)
    }

    @Test
    fun `sem pedir o TCM a sondagem so le 0151 e 0904 e nao troca o cabecalho`() = runTest {
        val t = Transporte(ecu(mapOf("0151" to "41 51 04\r\r")))
        val obd = cliente(t)
        obd.conectar(dispositivo)
        val antes = t.enviados.size
        val imp = obd.sondarEcu(sondarTcm = false)
        assertEquals(0x04, imp.codigoCombustivel)
        assertNull(imp.calid)
        assertNull(imp.tcmDetectado)
        assertEquals(listOf("0151", "ATST32", "0904"), t.enviados.drop(antes))
    }

    @Test
    fun `desconectado a sondagem devolve vazio sem enviar nada`() = runTest {
        val t = Transporte(ecu())
        val imp = cliente(t).sondarEcu(sondarTcm = true)
        assertTrue(imp.vazia)
        assertTrue(t.enviados.isEmpty())
    }

    @Test
    fun `em KWP (ATDPN A5) a sondagem nao toca no cabecalho do TCM e nomeia o protocolo`() = runTest {
        val t = Transporte(ecu(mapOf("ATDP" to "AUTO, \r\r", "ATDPN" to "A5\r\r", "0151" to "41 51 01\r\r")))
        val obd = cliente(t)
        assertTrue(obd.conectar(dispositivo).isSuccess)
        assertEquals("AUTO, ISO 14230-4 (KWP Fast Init)", obd.infoConexao.value.protocolo)

        val imp = obd.sondarEcu(sondarTcm = true)

        assertEquals(0x01, imp.codigoCombustivel)
        assertNull(imp.tcmDetectado)
        assertNull(imp.tcmCabecalho)
        assertFalse(t.enviados.any { it in listOf("ATSH7E1", "ATSH7E2", "ATSH7DF") })
    }

    // ---------------------------------------------------------------- simulado

    private suspend fun simulado(vin: String? = null, marca: String? = null, mudo: Boolean = false): ObdClientFake =
        ObdClientFake(random = Random(1), latenciaMs = 0, latenciaComandoMs = 0, vin = vin, ecuSemImpressao = mudo)
            .also { assertTrue(it.conectar(dispositivo, marca).isSuccess) }

    @Test
    fun `simulado responde combustivel e CALID coerentes com cada carro`() = runTest {
        val sw4 = simulado(marca = "Toyota").sondarEcu(true)
        assertEquals(0x04, sw4.codigoCombustivel)
        assertEquals("TY1KDFTV16A750F1", sw4.calid)
        assertEquals(true, sw4.tcmDetectado)

        val cruze = simulado(marca = "Chevrolet").sondarEcu(true)
        assertEquals(0x09, cruze.codigoCombustivel)
        assertEquals("GM6T35LE2CAL018A", cruze.calid)
        assertEquals("7E1", cruze.tcmCabecalho)

        val vw = simulado(marca = "Volkswagen")
        assertEquals(ObdClientFake.VIN_VW_TSI, vw.lerVin().getOrThrow())
        val tsi = vw.sondarEcu(true)
        assertEquals(0x09, tsi.codigoCombustivel)
        assertEquals("VWEA211TSI0019AB", tsi.calid)
        assertTrue("0123" in vw.pidsSuportados.value)

        val generico = simulado(marca = "Fiat")
        val asp = generico.sondarEcu(true)
        assertEquals(0x01, asp.codigoCombustivel)
        assertEquals(false, asp.tcmDetectado)
        assertFalse("o aspirado não tem rail absoluto", "0123" in generico.pidsSuportados.value)
    }

    @Test
    fun `simulado sem impressao responde NO DATA e a conexao continua`() = runTest {
        val obd = simulado(marca = "Toyota", mudo = true)
        val imp = obd.sondarEcu(true)
        assertNull(imp.codigoCombustivel)
        assertNull(imp.calid)
        assertEquals(false, imp.tcmDetectado)
        assertEquals(EstadoConexaoObd.Conectado, obd.estado.value)
        assertTrue(obd.registroComunicacao().any { "NO DATA" in it.toString() })
        // Alternando o cenário em tempo de execução, a próxima sondagem volta a responder.
        obd.ecuSemImpressao = false
        assertEquals(0x04, obd.sondarEcu(false).codigoCombustivel)
    }
}
