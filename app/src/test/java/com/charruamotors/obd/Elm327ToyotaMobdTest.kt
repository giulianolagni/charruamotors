package com.charruamotors.obd

import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.obd.log.EntradaRegistro
import com.charruamotors.obd.pid.CatalogoEstendido
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
import java.io.IOException

/**
 * Cenário real (Toyota Hilux SW4 3.0 D-4D 2013, central Denso, adaptador ELM327 v2.1): a ECU
 * responde `7F 01 11` ao `0100` e só fala o Toyota M-OBD (serviço 21 com `ATSH 7E0`).
 */
class Elm327ToyotaMobdTest {

    /**
     * ELM327 roteirizado que acompanha o cabeçalho (`ATSH`): pedidos fora do modo AT são
     * procurados primeiro como `"<cabecalho>:<comando>"` e depois só pelo comando.
     */
    private class TransporteToyota(respostas: Map<String, String>) : ObdTransport {
        val respostas = respostas.toMutableMap()
        val enviados = mutableListOf<String>()
        var cabecalho = "7DF"
        private var aberto = false

        override suspend fun conectar() {
            aberto = true
        }

        override suspend fun enviarComando(comando: String, timeoutMs: Long): String {
            if (!aberto) throw IOException("fechado")
            enviados += comando
            val roteirizada = respostas[comando] ?: respostas["$cabecalho:$comando"]
            if (comando.startsWith("ATSH")) {
                val r = roteirizada ?: "OK\r\r"
                if ("OK" in r) cabecalho = comando.removePrefix("ATSH").trim()
                return r
            }
            return roteirizada ?: if (comando.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"
        }

        override fun fechar() {
            aberto = false
        }
    }

    private val dispositivo = DispositivoObd("OBDII", "00:11:22:33:44:55")

    /** Exatamente o que o Log de comunicação mostrou no carro. */
    private fun hilux() = mutableMapOf(
        "ATZ" to "\r\rELM327 v2.1\r\r",
        "0100" to "SEARCHING...\r7F0111\r7F0111\r\r",
        "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)\r\r",
        "ATRV" to "13.8V\r\r",
        // Funcional (7DF): modo 01 recusado; no 7E0 a central responde ao serviço 21.
        "7E0:2101" to "02B\r0:6101800064A0\r1:7B1A2C3D4E5F60\r\r",
        "7E0:2104" to "7F2112\r\r",
        "7E0:2113" to "6113807F806480008000\r\r", // injetores 1–4 (perfil do catálogo): +0,127 +0,100 0 0
        "7E0:03" to "4300\r\r",
        "7E0:13" to "7F1311\r\r",
        "7E0:1800FF00" to "5801012160\r\r",
    )

    private fun TestScope.cliente(t: ObdTransport, estendido: CatalogoEstendido = CatalogoTeste.estendido) =
        Elm327BluetoothClient(
            adaptador = null,
            catalogo = CatalogoTeste.padrao,
            criarTransporte = { t },
            dispatcher = StandardTestDispatcher(testScheduler),
            catalogoEstendido = estendido,
        )

    private fun Elm327BluetoothClient.comandos() =
        registroComunicacao().filterIsInstance<EntradaRegistro.Comando>()

    private fun Elm327BluetoothClient.eventos() =
        registroComunicacao().filterIsInstance<EntradaRegistro.Evento>().map { it.texto }

    // ---------------------------------------------------------------- parser

    @Test
    fun `7F0111 e reconhecido como resposta negativa ao modo 01`() {
        val bruto = "SEARCHING...\r7F0111\r7F0111\r\r"
        assertEquals("11", ObdParser.respostaNegativa(bruto, "01", "0100"))
        assertEquals("serviceNotSupported", ObdParser.descricaoNrc("11"))
        assertNull(ObdParser.respostaNegativa("41 00 BE 1F A8 13\r", "01"))
        assertNull("responsePending não é recusa", ObdParser.respostaNegativa("7F 01 78\r", "01"))
        assertEquals("31", ObdParser.respostaNegativa("7F 01 31\r", "01"))
    }

    @Test
    fun `resposta positiva do servico 21 confere o identificador`() {
        assertTrue(ObdParser.respostaPositiva("02B\r0:6101800064A0\r1:7B1A2C3D4E5F60\r\r", "2101"))
        assertFalse(ObdParser.respostaPositiva("6104AABB\r", "2101"))
        assertFalse(ObdParser.respostaPositiva("7F2112\r", "2104"))
        assertTrue(ObdParser.respostaPositiva("4300\r", "03"))
    }

    @Test
    fun `DTCs KWP 18 00 FF 00 sao decodificados`() {
        assertEquals(listOf("P0121"), (ObdParser.parseDtcsKwp("5801012160\r") as RespostaObd.Ok).valor)
        assertEquals(emptyList<String>(), (ObdParser.parseDtcsKwp("5800\r") as RespostaObd.Ok).valor)
        assertEquals(RespostaObd.SemDados, ObdParser.parseDtcsKwp("7F1811\r"))
    }

    @Test
    fun `descricao do protocolo vem do ATDP`() {
        assertEquals("AUTO, ISO 15765-4 (CAN 11/500)", ObdParser.parseDescricaoProtocolo("AUTO, ISO 15765-4 (CAN 11/500)\r\r"))
        assertNull(ObdParser.parseDescricaoProtocolo("?\r\r"))
    }

    // ---------------------------------------------------------------- fallback Toyota M-OBD

    @Test
    fun `Hilux com 7F0111 conecta em Toyota M-OBD com ATSH 7E0 e 2101`() = runTest {
        val t = TransporteToyota(hilux())
        val obd = cliente(t)

        val r = obd.conectar(dispositivo, "Toyota")

        assertTrue(r.exceptionOrNull()?.message, r.isSuccess)
        assertEquals(EstadoConexaoObd.Conectado, obd.estado.value)
        // ATDP e ATRV logo depois do 0100, antes do fallback.
        val i0100 = t.enviados.indexOf("0100")
        assertEquals(listOf("ATDP", "ATRV", "ATSH7E0"), t.enviados.subList(i0100 + 1, i0100 + 4))
        Elm327BluetoothClient.BLOCOS_TOYOTA_MOBD.forEach { assertTrue("$it sondado", it in t.enviados) }
        Elm327BluetoothClient.COMANDOS_DTC_TOYOTA.forEach { assertTrue("$it sondado", it in t.enviados) }
        assertFalse("nada de 0120/0140/01001 sem modo 01", listOf("0120", "0140", "01001").any { it in t.enviados })

        val info = obd.infoConexao.value
        assertEquals(ModoComunicacaoEcu.TOYOTA_MOBD, info.modo)
        assertEquals("AUTO, ISO 15765-4 (CAN 11/500)", info.protocolo)
        assertEquals(listOf("2101", "2113"), info.comandosModo21)
        assertEquals(setOf("ATRV", "TOYOTA_INJ1", "TOYOTA_INJ2", "TOYOTA_INJ3", "TOYOTA_INJ4"), obd.pidsSuportados.value)

        // Log de campo: respostas exatas de 0100, ATDP, ATSH 7E0 e 21xx, e o motivo.
        val trocas = obd.comandos().associateBy { it.comando }
        assertEquals("SEARCHING...\r7F0111\r7F0111\r\r", trocas["0100"]!!.resposta)
        assertEquals("AUTO, ISO 15765-4 (CAN 11/500)\r\r", trocas["ATDP"]!!.resposta)
        assertEquals("OK\r\r", trocas["ATSH7E0"]!!.resposta)
        assertEquals("02B\r0:6101800064A0\r1:7B1A2C3D4E5F60\r\r", trocas["2101"]!!.resposta)
        assertEquals("7F2112\r\r", trocas["2104"]!!.resposta)
        assertTrue(obd.eventos().any { "7F 01 11" in it && "não suporta OBD2 padrão (Modo 01)" in it })
        assertFalse(obd.eventos().any { "não respondeu como um adaptador ELM327" in it })
    }

    @Test
    fun `polling em M-OBD usa o modo 21 e nunca volta ao 0100 ou 010C`() = runTest {
        val t = TransporteToyota(hilux())
        val obd = cliente(t)
        obd.conectar(dispositivo, "Toyota Hilux SW4")
        assertTrue(obd.ativarPerfilFabricante("Toyota") is ResultadoPerfil.Ativo)
        t.enviados.clear()

        val dados = obd.lerTelemetria(intervaloMinimoMs = 250).first()

        assertTrue("2113" in t.enviados)
        assertFalse(t.enviados.any { it.startsWith("01") })
        assertEquals(0.127, dados["TOYOTA_INJ1"]!!, 1e-9) // (0x807F − 32768) / 1000
        assertEquals(0.1, dados["TOYOTA_INJ2"]!!, 1e-9)
    }

    @Test
    fun `falhas em M-OBD caem para o KWP 18 00 FF 00 quando o 03 nao serve`() = runTest {
        val ecu = hilux().apply { put("7E0:03", "7F0311\r\r") }
        val obd = cliente(TransporteToyota(ecu))
        obd.conectar(dispositivo, "Toyota")

        val dtcs = obd.lerCodigosFalha()

        assertEquals(listOf("P0121"), dtcs.getOrThrow().map { it.codigo })
    }

    @Test
    fun `adaptador v2_1 que recusa ATSH com interrogacao encerra com mensagem clara`() = runTest {
        val t = TransporteToyota(hilux().apply { put("ATSH7E0", "?\r\r") })
        val obd = cliente(t)

        val r = obd.conectar(dispositivo, "Toyota")

        assertTrue(r.isFailure)
        val msg = r.exceptionOrNull()!!.message!!
        assertTrue(msg, "ELM327 v2.1" in msg && "cabeçalhos customizados" in msg && "ATSH 7E0" in msg)
        assertFalse(msg, "não respondeu como um adaptador ELM327" in msg)
        assertTrue(obd.estado.value is EstadoConexaoObd.Erro)
        assertFalse("sem ATSH não adianta sondar o modo 21", "2101" in t.enviados)
        assertEquals("?\r\r", obd.comandos().last { it.comando == "ATSH7E0" }.resposta)
        assertTrue("ATDP fica no log mesmo na falha", obd.comandos().any { it.comando == "ATDP" })
    }

    @Test
    fun `ATSH com ERROR tambem e recusa do adaptador`() = runTest {
        val r = cliente(TransporteToyota(hilux().apply { put("ATSH7E0", "ERROR\r\r") })).conectar(dispositivo, "Toyota")
        assertTrue(r.exceptionOrNull()!!.message!!.contains("cabeçalhos customizados"))
    }

    @Test
    fun `nenhum 21xx positivo falha explicando o M-OBD`() = runTest {
        val ecu = hilux().apply {
            remove("7E0:2101")
            remove("7E0:2113")
        }
        val r = cliente(TransporteToyota(ecu)).conectar(dispositivo, "Toyota")
        val msg = r.exceptionOrNull()!!.message!!
        assertTrue(msg, "Toyota M-OBD" in msg && "Log de comunicação" in msg)
    }

    @Test
    fun `marca nao Toyota com 7F0111 explica a recusa do modo 01 sem tentar o M-OBD`() = runTest {
        val t = TransporteToyota(hilux())
        val r = cliente(t).conectar(dispositivo, "Volkswagen")

        val msg = r.exceptionOrNull()!!.message!!
        assertTrue(msg, "não suporta OBD2 padrão (Modo 01)" in msg && "7F 01 11" in msg)
        assertFalse("ATSH7E0" in t.enviados)
        assertTrue("ATDP" in t.enviados && "ATRV" in t.enviados)
    }

    @Test
    fun `NO DATA no 0100 de um Toyota tambem entra no M-OBD`() = runTest {
        val ecu = hilux().apply { put("0100", "SEARCHING...\rNO DATA\r\r") }
        val obd = cliente(TransporteToyota(ecu))

        assertTrue(obd.conectar(dispositivo, "Toyota").isSuccess)
        assertEquals(ModoComunicacaoEcu.TOYOTA_MOBD, obd.infoConexao.value.modo)
    }

    @Test
    fun `desconectar volta o modo para OBD2 padrao`() = runTest {
        val obd = cliente(TransporteToyota(hilux()))
        obd.conectar(dispositivo, "Toyota")
        obd.desconectar()
        assertEquals(InfoConexao(), obd.infoConexao.value)
    }
}
