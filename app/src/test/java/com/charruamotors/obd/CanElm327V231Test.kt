package com.charruamotors.obd

import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.transporte.ObdTransport
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * v2.3.1 — log real do GM Onix 2013 com ELM327 v2.1: `ATDP` devolvendo só `AUTO, `, CAN reconhecido
 * pelo `ATDPN`, reset de protocolo/cabeçalho/filtro depois do modo 09 multi-frame e `ATST19`.
 */
class CanElm327V231Test {

    private class Ecu(respostas: Map<String, String>) : ObdTransport {
        val respostas = respostas.toMutableMap()
        val enviados = mutableListOf<String>()
        private var aberto = false

        override suspend fun conectar() {
            aberto = true
        }

        override suspend fun enviarComando(comando: String, timeoutMs: Long): String {
            if (!aberto) throw IOException("fechado")
            enviados += comando
            return respostas[comando] ?: if (comando.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"
        }

        override fun fechar() {
            aberto = false
        }
    }

    private val dispositivo = DispositivoObd("OBDII", "00:11:22:33:44:55")

    private fun TestScope.cliente(t: Ecu) = Elm327BluetoothClient(
        adaptador = null,
        catalogo = CatalogoTeste.padrao,
        criarTransporte = { t },
        dispatcher = StandardTestDispatcher(testScheduler),
        catalogoEstendido = CatalogoTeste.estendido,
        catalogoCascata = CatalogoTeste.cascata,
    )

    private fun onix(atdpn: String = "A6") = Ecu(
        mapOf(
            "ATZ" to "\r\rELM327 v2.1\r\r",
            "0100" to "SEARCHING...\r41001E3F8000\r\r",
            "01001" to "41001E3F8000\r\r",
            "ATDP" to "AUTO, \r\r",
            "ATDPN" to "$atdpn\r\r",
            "ATRV" to "14.1V\r\r",
            "010C0D0B" to "008\r0:410C0FA00D32\r1:0B650000000000\r\r",
            "0902" to "014\r0:490201394247\r1:4B533438423044\r2:47313233343536\r\r",
        ),
    )

    private fun Ecu.depoisDe(comando: String, quantos: Int = 3): List<String> {
        val i = enviados.indexOf(comando)
        assertTrue("$comando não foi enviado: $enviados", i >= 0)
        return enviados.subList(i + 1, i + 1 + quantos)
    }

    @Test
    fun `ATDPN A6 e CAN mesmo com o ATDP vazio e liga o lote`() = runTest {
        val t = onix()
        val obd = cliente(t)
        obd.conectar(dispositivo, "Chevrolet Onix").getOrThrow()

        assertTrue(obd.usaLoteMultiPid)
        assertEquals("AUTO, ISO 15765-4 (CAN 11/500)", obd.infoConexao.value.protocolo)
    }

    @Test
    fun `ATDPN sem a marca de automatico tambem e CAN`() = runTest {
        val obd = cliente(onix("6"))
        obd.conectar(dispositivo, "Chevrolet Onix").getOrThrow()

        assertTrue(obd.usaLoteMultiPid)
        assertEquals("ISO 15765-4 (CAN 11/500)", obd.infoConexao.value.protocolo)
    }

    @Test
    fun `numeros CAN do ATDPN, comando de fixacao e cabecalho funcional`() {
        val esperado = mapOf(
            "6" to Triple("CAN 11/500", "ATSP6", "7DF"),
            "A7" to Triple("CAN 29/500", "ATSP7", "DB33F1"),
            "A8" to Triple("CAN 11/250", "ATSP8", "7DF"),
            "9" to Triple("CAN 29/250", "ATSP9", "DB33F1"),
        )
        for ((bruto, e) in esperado) {
            val p = ObdParser.parseProtocoloAtivo("$bruto\r\r")!!
            assertTrue(p.ehCan)
            assertTrue(e.first in p.descrever("AUTO, ")!!)
            assertEquals(e.second, p.comandoFixar)
            assertEquals(e.third, p.cabecalhoFuncional)
        }
        val linhaK = ObdParser.parseProtocoloAtivo("A3\r\r")!!
        assertFalse(linhaK.ehCan)
        assertEquals("AUTO, ISO 9141-2", linhaK.descrever("AUTO, ISO 9141-2"))
    }

    @Test
    fun `CAN usa ATST19 e nunca ATST0C no modo 01`() = runTest {
        val t = onix()
        val obd = cliente(t)
        obd.conectar(dispositivo, "Chevrolet Onix").getOrThrow()

        assertTrue("ATST19" in t.enviados)
        assertFalse("ATST0C" in t.enviados)
        assertTrue(obd.usaTimeoutCurto)
    }

    @Test
    fun `depois do 0902 fixa o ATSP6 e devolve ATSH 7DF e ATCRA`() = runTest {
        val t = onix()
        val obd = cliente(t)
        obd.conectar(dispositivo, "Chevrolet Onix").getOrThrow()
        obd.lerVin()

        assertEquals(listOf("ATSP6", "ATSH7DF", "ATCRA"), t.depoisDe("0902"))
    }

    @Test
    fun `0904 e 0906 pelas ferramentas manuais tambem resetam o filtro`() = runTest {
        val t = onix("A8")
        val obd = cliente(t)
        obd.conectar(dispositivo, "Chevrolet Onix").getOrThrow()
        obd.iniciarSessaoManual().getOrThrow()
        for (comando in listOf("0904", "0906")) {
            t.enviados.clear()
            obd.enviarComandoManual(comando)
            assertEquals(listOf(comando, "ATSP8", "ATSH7DF", "ATCRA"), t.enviados)
        }
    }

    @Test
    fun `fora do CAN o 0902 nao muda protocolo nem cabecalho`() = runTest {
        val t = onix("A3").apply { respostas["ATDP"] = "AUTO, ISO 9141-2\r\r" }
        val obd = cliente(t)
        obd.conectar(dispositivo, "Chevrolet Onix").getOrThrow()
        t.enviados.clear()
        obd.lerVin()

        assertTrue(t.enviados.none { it.startsWith("ATSP") || it.startsWith("ATSH") || it.startsWith("ATCRA") })
    }
}
