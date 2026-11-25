package com.charruamotors.obd

import com.charruamotors.obd.log.EntradaRegistro
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.pid.Pids
import com.charruamotors.obd.transporte.ObdTransport
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
 * v2.2.9 — Chevrolet Cruze no HS-CAN (ISO 15765-4) com ELM327 v2.1: Multi-PID em lote no modo 01 (com
 * fallback para 1 PID por vez), ATF do TCM `7E1` (`22 1940`) e falhas do câmbio, sempre com o cabeçalho
 * e o `ATCRA` restaurados. A SW4 (modo 21) nunca recebe lote nem a leitura do TCM.
 */
class CruzeCanV229Test {

    /** ELM327 roteirizado: resposta por `"<cabecalho>:<comando>"` (acompanha o `ATSH`/`ATCRA`) ou só `comando`. */
    private class Ecu(respostas: Map<String, String>) : ObdTransport {
        val respostas = respostas.toMutableMap()
        val enviados = mutableListOf<String>()
        var cabecalho = "7DF"
        var filtro: String? = null
        private var aberto = false

        override suspend fun conectar() {
            aberto = true
        }

        override suspend fun enviarComando(comando: String, timeoutMs: Long): String {
            if (!aberto) throw IOException("fechado")
            enviados += comando
            val r = respostas["$cabecalho:$comando"] ?: respostas[comando]
                ?: if (comando.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"
            if (comando.startsWith("ATSH") && "OK" in r) cabecalho = comando.removePrefix("ATSH")
            if (comando.startsWith("ATCRA") && "OK" in r) filtro = comando.removePrefix("ATCRA").ifEmpty { null }
            return r
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

    private fun Elm327BluetoothClient.eventos() =
        registroComunicacao().filterIsInstance<EntradaRegistro.Evento>().map { it.texto }

    /**
     * Cruze LE2: bitmask com 04–07, 0B–10 e 11 (sem 0120); RPM 1.000, 50 km/h, MAP 101 kPa, MAF 5 g/s.
     * O lote de teste e o da fila rápida voltam em 2 quadros CAN (`0:`/`1:`), como num ELM327 v2.1 real.
     */
    private fun cruze() = mutableMapOf(
        "ATZ" to "\r\rELM327 v2.1\r\r",
        "0100" to "SEARCHING...\r41001E3F8000\r\r",
        "01001" to "41001E3F8000\r\r",
        "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)\r\r",
        "ATDPN" to "A6\r\r",
        "ATRV" to "14.1V\r\r",
        "010C0D0B" to "008\r0:410C0FA00D32\r1:0B650000000000\r\r",
        "010C0B0D10" to "00B\r0:410C0FA00B65\r1:0D321001F4000000\r\r",
        "010C1" to "410C0FA0\r\r",
        "010B1" to "410B65\r\r",
        "010D1" to "410D32\r\r",
        "01101" to "411001F4\r\r",
        "01051" to "41057E\r\r",
        "7E1:221940" to "62194082\r\r", // ATF 90 °C (sondagem, sem sufixo)
        "7E1:2219401" to "62194082\r\r", // no polling, com o sufixo de resposta única
    )

    /** SW4 real: o OBD2 padrão é recusado (`7F 01 11`) e o motor responde ao modo 21 no `7E0`. */
    private fun sw4() = mutableMapOf(
        "ATZ" to "\r\rELM327 v2.1\r\r",
        "0100" to "SEARCHING...\r7F0111\r\r",
        "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)\r\r",
        "ATRV" to "13.8V\r\r",
        "7E0:210C" to "610C0C3B\r\r",
        "7E0:2105" to "61055F\r\r",
        "7E0:2104" to "61042D\r\r",
        "7E0:210B" to "610B4A\r\r",
        "7E0:13" to "5300\r\r",
        "7E1:21D9" to "61D90000000442E000000000\r\r",
    )

    // ---------------------------------------------------------------- Multi-PID em lote

    @Test
    fun `handshake no CAN testa 010C0D0B e ativa o lote quando vem todos os PIDs`() = runTest {
        val t = Ecu(cruze())
        val obd = cliente(t)

        assertTrue(obd.conectar(dispositivo, "Chevrolet Cruze").isSuccess)

        assertEquals(1, t.enviados.count { it == "010C0D0B" })
        assertTrue(obd.usaLoteMultiPid)
        assertTrue(obd.eventos().any { "Multi-PID em lote ativo: 010C0D0B" in it })
        assertTrue(obd.eventos().any { "Multi-PID em lote: sim" in it })
    }

    @Test
    fun `polling le os PIDs rapidos num so comando e decodifica cada um`() = runTest {
        val t = Ecu(cruze())
        val obd = cliente(t)
        obd.conectar(dispositivo, "Chevrolet Cruze")
        t.enviados.clear()

        val leituras = obd.lerTelemetria(intervaloMinimoMs = 1).take(3).toList()

        assertEquals(3, t.enviados.count { it == "010C0B0D10" })
        // Nenhum dos PIDs do lote saiu sozinho.
        assertTrue(t.enviados.none { it in setOf("010C1", "010B1", "010D1", "01101") })
        leituras.forEach { l ->
            assertEquals(1000.0, l[Pids.RPM]!!, 1e-9)
            assertEquals(50.0, l[Pids.VELOCIDADE]!!, 1e-9)
            assertEquals(101.0, l[Pids.PRESSAO_COLETOR]!!, 1e-9)
            assertEquals(5.0, l[Pids.MAF]!!, 1e-9)
        }
    }

    @Test
    fun `lote recusado no handshake fica desligado e o motivo vai para o log`() = runTest {
        val recusas = mapOf(
            "?\r\r" to "?",
            "NO DATA\r\r" to "NO DATA",
            "BUFFER FULL\r\r" to "BUFFER FULL",
            "7F0112\r\r" to "7F 01 12",
            "410C0FA00D32\r\r" to "resposta truncada", // sem o 0B
        )
        for ((resposta, motivo) in recusas) {
            val t = Ecu(cruze().apply { put("010C0D0B", resposta) })
            val obd = cliente(t)
            obd.conectar(dispositivo, "Chevrolet Cruze")

            assertFalse(resposta, obd.usaLoteMultiPid)
            assertTrue(resposta, obd.eventos().any { "Multi-PID em lote desligado: 010C0D0B → $motivo" in it })
            t.enviados.clear()
            val leituras = obd.lerTelemetria(intervaloMinimoMs = 1).take(2).toList()
            // Fallback: 1 PID por vez, com o sufixo de resposta única, e nada de lote no polling.
            assertTrue(resposta, t.enviados.none { it == "010C0B0D10" })
            assertEquals(resposta, 2, t.enviados.count { it == "010C1" })
            assertEquals(1000.0, leituras.last()[Pids.RPM]!!, 1e-9)
        }
    }

    @Test
    fun `duas falhas seguidas do lote no polling voltam a 1 PID por vez sem perder leitura`() = runTest {
        val t = Ecu(cruze().apply { put("010C0B0D10", "BUFFER FULL\r\r") })
        val obd = cliente(t)
        obd.conectar(dispositivo, "Chevrolet Cruze")
        assertTrue(obd.usaLoteMultiPid)
        t.enviados.clear()

        val leituras = obd.lerTelemetria(intervaloMinimoMs = 1).take(4).toList()

        assertEquals("2 tentativas e desliga", 2, t.enviados.count { it == "010C0B0D10" })
        assertFalse(obd.usaLoteMultiPid)
        assertTrue(obd.eventos().any { "Multi-PID em lote desligado após 2 falhas seguidas (010C0B0D10 → BUFFER FULL)" in it })
        // Nos ciclos com falha, os PIDs que faltaram foram lidos 1 a 1: o painel nunca ficou sem RPM.
        assertEquals(4, t.enviados.count { it == "010C1" })
        assertTrue(leituras.all { it[Pids.RPM] == 1000.0 && it[Pids.MAF] == 5.0 })
    }

    @Test
    fun `uma falha isolada do lote nao desliga`() = runTest {
        val t = Ecu(cruze())
        val obd = cliente(t)
        obd.conectar(dispositivo, "Chevrolet Cruze")
        val respostaBoa = t.respostas.getValue("010C0B0D10")
        val truncada = "410C0FA00B650D32\r\r" // sem o MAF

        for (resposta in listOf(truncada, respostaBoa, truncada)) {
            t.respostas["010C0B0D10"] = resposta
            val leitura = obd.lerTelemetria(intervaloMinimoMs = 1).take(1).toList().single()
            assertEquals(5.0, leitura[Pids.MAF]!!, 1e-9) // o MAF que faltou veio pela leitura individual
        }

        assertTrue("falha → sucesso → falha não são 2 seguidas", obd.usaLoteMultiPid)
    }

    @Test
    fun `fora do CAN o lote nem e testado`() = runTest {
        val t = Ecu(cruze().apply { put("ATDP", "AUTO, ISO 9141-2\r\r") })
        val obd = cliente(t)
        obd.conectar(dispositivo, "Chevrolet Cruze")

        assertTrue(t.enviados.none { it == "010C0D0B" })
        assertFalse(obd.usaLoteMultiPid)
        assertTrue(obd.eventos().any { "não é CAN ISO 15765-4" in it })
    }

    @Test
    fun `SW4 no modo 21 nunca recebe lote nem pedido do modo 01 agrupado`() = runTest {
        val t = Ecu(sw4())
        val obd = cliente(t)
        obd.conectar(dispositivo, "Toyota Hilux SW4")
        obd.ativarPerfilFabricante("Toyota")

        obd.lerTelemetria(intervaloMinimoMs = 1).take(6).toList()

        assertFalse(obd.usaLoteMultiPid)
        assertTrue(t.enviados.none { it.startsWith("01") && it.length > 5 })
        assertTrue(obd.eventos().none { "Multi-PID" in it })
        assertTrue(t.enviados.count { it == "210C" } >= 6)
    }

    // ---------------------------------------------------------------- câmbio (TCM 7E1)

    @Test
    fun `perfil GM no CAN sonda o ATF com ATSH 7E1 e ATCRA 7E9 e volta ao funcional`() = runTest {
        val t = Ecu(cruze())
        val obd = cliente(t)
        obd.conectar(dispositivo, "Chevrolet Cruze")
        t.enviados.clear()

        val perfil = obd.ativarPerfilFabricante("Chevrolet")

        assertTrue(perfil is ResultadoPerfil.Ativo)
        assertEquals(listOf("GM_ATF"), (perfil as ResultadoPerfil.Ativo).pids.map { it.comando })
        val i = t.enviados.indexOf("ATSH7E1")
        assertEquals(listOf("ATSH7E1", "ATCRA7E9", "221940"), t.enviados.subList(i, i + 3))
        assertEquals("7DF", t.cabecalho)
        assertNull(t.filtro)
        assertEquals("ATSH7DF", t.enviados.last { it.startsWith("ATSH") })
    }

    @Test
    fun `ATF fora da faixa plausivel nao entra no polling`() = runTest {
        val t = Ecu(cruze().apply { put("7E1:221940", "621940FF\r\r") }) // 215 °C: lixo
        val obd = cliente(t)
        obd.conectar(dispositivo, "Chevrolet Cruze")

        val perfil = obd.ativarPerfilFabricante("Chevrolet")

        assertTrue(perfil is ResultadoPerfil.Indisponivel)
        assertFalse("GM_ATF" in obd.pidsSuportados.value)
        val atf = CatalogoTeste.estendido["GM_ATF"]!!
        assertEquals(RespostaObd.Ok(90.0), ObdParser.parsePid("62194082", atf))
        assertEquals(RespostaObd.Ok(-20.0), ObdParser.parsePid("62194014", atf))
        assertTrue(ObdParser.parsePid("62194013", atf) is RespostaObd.Invalido) // −21 °C
        assertTrue(ObdParser.parsePid("621940BF", atf) is RespostaObd.Invalido) // 151 °C
    }

    @Test
    fun `ATF do Cruze em cadencia leve e cabecalho restaurado depois de cada visita ao 7E1`() = runTest {
        val t = Ecu(cruze())
        val obd = cliente(t)
        obd.conectar(dispositivo, "Chevrolet Cruze")
        obd.ativarPerfilFabricante("Chevrolet")
        t.enviados.clear()

        val leituras = obd.lerTelemetria(intervaloMinimoMs = 1).take(8).toList()

        assertEquals("ciclos 0 e 4", 2, t.enviados.count { it == "2219401" })
        t.enviados.forEachIndexed { i, c ->
            if (c == "2219401") {
                assertEquals("ATCRA7E9", t.enviados[i - 1])
                assertEquals(listOf("ATSH7DF", "ATCRA"), t.enviados.subList(i + 1, i + 3))
            }
        }
        assertEquals(90.0, leituras.first()["GM_ATF"]!!, 1e-9)
        assertEquals(8, t.enviados.count { it == "010C0B0D10" })
        assertEquals("7DF", t.cabecalho)
    }

    @Test
    fun `falhas do TCM pelo 03 no 7E1 com o cabecalho restaurado`() = runTest {
        val t = Ecu(cruze().apply { put("7E1:03", "43010700\r\r") })
        val obd = cliente(t)
        obd.conectar(dispositivo, "Chevrolet Cruze")
        t.enviados.clear()

        val r = obd.lerCodigosFalhaCambio()

        assertEquals(listOf("P0700"), r.getOrThrow()!!.map { it.codigo })
        val i = t.enviados.indexOf("03")
        assertEquals(listOf("ATSH7E1", "ATCRA7E9"), t.enviados.subList(i - 2, i))
        assertTrue(t.enviados.none { it == "1902FF" })
        assertEquals(listOf("ATSH7DF", "ATCRA"), t.enviados.takeLast(2))
        assertEquals("7DF", t.cabecalho)
        assertNull(t.filtro)
    }

    @Test
    fun `TCM que recusa o 03 cai para o UDS 1902FF`() = runTest {
        val t = Ecu(cruze().apply {
            put("7E1:03", "7F0311\r\r")
            put("7E1:1902FF", "5902FF07000008\r\r")
        })
        val obd = cliente(t)
        obd.conectar(dispositivo, "Chevrolet Cruze")

        assertEquals(listOf("P0700"), obd.lerCodigosFalhaCambio().getOrThrow()!!.map { it.codigo })
        assertEquals("7DF", t.cabecalho)
    }

    @Test
    fun `TCM mudo nao e erro e sem falhas e lista vazia`() = runTest {
        val mudo = Ecu(cruze())
        val obd = cliente(mudo)
        obd.conectar(dispositivo, "Chevrolet Cruze")
        assertNull(obd.lerCodigosFalhaCambio().getOrThrow())
        assertTrue(obd.eventos().any { "TCM 7E1 sem falhas legíveis" in it })
        assertEquals("7DF", mudo.cabecalho)

        val semFalhas = Ecu(cruze().apply { put("7E1:03", "4300\r\r") })
        val obd2 = cliente(semFalhas)
        obd2.conectar(dispositivo, "Chevrolet Cruze")
        assertEquals(emptyList<DtcLido>(), obd2.lerCodigosFalhaCambio().getOrThrow())
    }

    @Test
    fun `SW4 no modo 21 nao gasta comando com as falhas do TCM no OBD2 padrao`() = runTest {
        val t = Ecu(sw4())
        val obd = cliente(t)
        obd.conectar(dispositivo, "Toyota Hilux SW4")
        t.enviados.clear()

        assertNull(obd.lerCodigosFalhaCambio().getOrThrow())
        assertTrue(t.enviados.isEmpty())
    }
}
