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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Polling multi-módulo da SW4 (1KD-FTV + A750F) no modo 21: o motor `7E0` em toda volta e o câmbio
 * `7E1` (alavanca, ATF, marcha) em cadência leve, com o cabeçalho sempre devolvido ao `7E0`.
 */
class PollingCambio7E1Test {

    /** ELM327 roteirizado: resposta por `"<cabecalho>:<comando>"` (o cabeçalho acompanha o `ATSH`). */
    private class Ecu(respostas: Map<String, String>) : ObdTransport {
        val respostas = respostas.toMutableMap()
        val enviados = mutableListOf<String>()
        var cabecalho = "7DF"
        var filtro: String? = null

        /** Como um ELM327 real: com um `ATCRA` ativo, módulos de outro endereço de resposta ficam mudos. */
        var respeitaFiltro = false
        private var aberto = false

        override suspend fun conectar() {
            aberto = true
        }

        override suspend fun enviarComando(comando: String, timeoutMs: Long): String {
            if (!aberto) throw IOException("fechado")
            enviados += comando
            val r = respostas["$cabecalho:$comando"] ?: respostas[comando]
                ?: if (comando.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"
            val ok = "OK" in r
            if (comando.startsWith("ATSH") && ok) cabecalho = comando.removePrefix("ATSH")
            if (comando.startsWith("ATCRA") && ok) filtro = comando.removePrefix("ATCRA").ifEmpty { null }
            if (comando == "ATAR" && ok) filtro = null
            val respondeNoFiltro = filtro == null || filtro == "%03X".format(cabecalho.toInt(16) + 8)
            return if (respeitaFiltro && !comando.startsWith("AT") && !respondeNoFiltro) "NO DATA\r\r" else r
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

    /** SW4 real: o OBD2 padrão é recusado (`7F 01 11`) e o motor responde ao modo 21 no `7E0`. */
    private fun sw4(comCambio: Boolean) = mutableMapOf(
        "ATZ" to "\r\rELM327 v2.1\r\r",
        "0100" to "SEARCHING...\r7F0111\r\r",
        "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)\r\r",
        "ATRV" to "13.8V\r\r",
        "7E0:210C" to "610C0C3B\r\r",
        "7E0:2105" to "61055F\r\r",
        "7E0:2104" to "61042D\r\r",
        "7E0:210B" to "610B4A\r\r",
        "7E0:13" to "5300\r\r",
        "7E0:2130" to "6130 23\r\r", // 7E0 2130 = contador de aquecimentos (35): nunca é marcha
    ).apply {
        if (comCambio) {
            // 21D9 (11 bytes): alavanca em D (C = 00, D = 04) e ATF em b4:b5; 2130: 0xFF fixo (log real).
            put("7E1:21D9", "61D90000000442E000000000\r\r")
            put("7E1:2130", "6130FF\r\r")
        }
    }

    private fun Elm327BluetoothClient.eventos() =
        registroComunicacao().filterIsInstance<EntradaRegistro.Evento>().map { it.texto }

    @Test
    fun `marcha nao existe mais no 7E0 2130`() {
        assertNull(CatalogoTeste.estendido["TOYOTA_MARCHA_ECM"])
        assertTrue(CatalogoTeste.estendido.definicoes.none { it.estendida?.cabecalho == "7E0" && it.estendida?.identificador == "30" })
        assertEquals("7E1 · 2130", CatalogoTeste.estendido["TOYOTA_MARCHA"]!!.rotuloComando)
    }

    @Test
    fun `sondagem do modo 21 testa o 7E1 com ATSH 7E1 ATCRA 7E9 e restaura o 7E0`() = runTest {
        val t = Ecu(sw4(comCambio = true))
        val obd = cliente(t)
        obd.conectar(dispositivo, "Toyota Hilux SW4")
        t.enviados.clear()

        val perfil = obd.ativarPerfilFabricante("Toyota")

        assertTrue(perfil is ResultadoPerfil.Ativo)
        val i = t.enviados.indexOf("ATSH7E1")
        assertTrue(t.enviados.toString(), i >= 0)
        // O 2130 ficou fora (0xFF fixo na SW4 2013): só o 21D9 é sondado.
        assertEquals(listOf("ATSH7E1", "ATCRA7E9", "21D9"), t.enviados.subList(i, i + 3))
        assertTrue(t.enviados.none { it == "2130" })
        // Logo depois da visita: ATSH 7E0 e o ATCRA padrão (sem endereço).
        assertEquals(listOf("ATSH7E0", "ATCRA"), t.enviados.subList(i + 3, i + 5))
        assertEquals("7E0", t.cabecalho)
        assertNull(t.filtro)
        assertTrue(t.enviados.none { it == "21D91" || it == "21AD1" })

        val suportados = obd.pidsSuportados.value
        assertTrue(setOf("TOYOTA_ALAVANCA", "TOYOTA_ATF_CAMBIO").all { it in suportados })
        assertFalse("TOYOTA_MARCHA" in suportados) // estimada pelo app (CambioA750f), não lida
        assertFalse("TOYOTA_MARCHA_ECM" in suportados)
        assertTrue(obd.eventos().any { "Câmbio 7E1 respondeu" in it })
    }

    @Test
    fun `7E1 mudo nao entra no polling e o cabecalho do motor volta`() = runTest {
        val t = Ecu(sw4(comCambio = false))
        val obd = cliente(t)
        obd.conectar(dispositivo, "Toyota Hilux SW4")

        obd.ativarPerfilFabricante("Toyota")

        assertEquals("7E0", t.cabecalho)
        assertNull(t.filtro)
        assertFalse("TOYOTA_MARCHA" in obd.pidsSuportados.value)
        assertTrue(obd.eventos().any { "Câmbio 7E1 não respondeu" in it })

        t.enviados.clear()
        obd.lerTelemetria(intervaloMinimoMs = 1).take(4).toList()
        assertFalse(t.enviados.any { it == "ATSH7E1" })
    }

    @Test
    fun `polling le o 7E1 a cada 4 ciclos e devolve o cabecalho ao 7E0 sem sufixo no 21D9`() = runTest {
        val t = Ecu(sw4(comCambio = true))
        val obd = cliente(t)
        obd.conectar(dispositivo, "Toyota Hilux SW4")
        obd.ativarPerfilFabricante("Toyota")
        t.enviados.clear()

        val leituras = obd.lerTelemetria(intervaloMinimoMs = 1, lentosPorVez = 1, ciclosPorLento = 3).take(9).toList()

        // Ciclos 0, 4 e 8: visitas ao câmbio.
        assertEquals(3, t.enviados.count { it == "ATSH7E1" })
        assertEquals(3, t.enviados.count { it == "ATCRA7E9" })
        assertEquals(3, t.enviados.count { it == "21D9" })
        assertEquals(0, t.enviados.count { it == "2130" }) // ~150 ms a menos por visita
        assertTrue(t.enviados.none { it == "21D91" || it == "21AD1" })
        // Toda visita ao 7E1 é seguida logo por ATSH 7E0 + ATCRA, antes de qualquer PID do motor.
        t.enviados.forEachIndexed { i, c ->
            if (c == "21D9") {
                assertEquals(listOf("ATSH7E0", "ATCRA"), t.enviados.subList(i + 1, i + 3))
            }
        }
        assertEquals("7E0", t.cabecalho)
        // RPM a cada ciclo: o câmbio não rouba a fila rápida.
        assertEquals(9, t.enviados.count { it == "210C" })
        assertTrue(leituras.all { it[Pids.RPM] != null })
        // O câmbio aparece no primeiro ciclo e o valor persiste nos ciclos sem visita.
        val primeira = leituras.first()
        assertEquals(3.0, primeira["TOYOTA_ALAVANCA"]!!, 1e-9) // D
        assertNotNull(leituras[1]["TOYOTA_ALAVANCA"])
        assertTrue(leituras.none { "TOYOTA_MARCHA" in it.valores })
        assertNotNull(primeira["TOYOTA_ATF_CAMBIO"])
    }

    @Test
    fun `clone que recusa o ATCRA sem endereco volta ao filtro padrao pelo ATAR e o motor segue respondendo`() = runTest {
        val t = Ecu(sw4(comCambio = true).apply { put("ATCRA", "?\r\r") })
        t.respeitaFiltro = true
        val obd = cliente(t)
        obd.conectar(dispositivo, "Toyota Hilux SW4")
        obd.ativarPerfilFabricante("Toyota")
        assertNull(t.filtro)
        t.enviados.clear()

        val leituras = obd.lerTelemetria(intervaloMinimoMs = 1, lentosPorVez = 1, ciclosPorLento = 3).take(9).toList()

        // O filtro do câmbio continua sendo aplicado a cada visita (o clone aceita a forma com endereço)...
        assertEquals(3, t.enviados.count { it == "ATCRA7E9" })
        // ...e a volta ao motor recorre ao ATAR quando o ATCRA puro é recusado.
        t.enviados.forEachIndexed { i, c ->
            if (c == "21D9") assertEquals(listOf("ATSH7E0", "ATCRA", "ATAR"), t.enviados.subList(i + 1, i + 4))
        }
        assertNull(t.filtro)
        assertTrue(leituras.all { it[Pids.RPM] != null })
        assertEquals(3.0, leituras.first()["TOYOTA_ALAVANCA"]!!, 1e-9) // o câmbio segue com filtro
    }

    @Test
    fun `volta ao filtro padrao que falha fica pendente e o proximo comando insiste ate conseguir`() = runTest {
        val t = Ecu(sw4(comCambio = true).apply {
            put("ATCRA", "?\r\r")
            put("ATAR", "?\r\r")
        })
        t.respeitaFiltro = true
        val obd = cliente(t)
        obd.conectar(dispositivo, "Toyota Hilux SW4")
        obd.ativarPerfilFabricante("Toyota")
        assertEquals("7E9", t.filtro) // adaptador preso no filtro do câmbio
        assertTrue(obd.eventos().any { "Filtro de recepção não voltou ao padrão" in it })

        // O adaptador volta a aceitar o ATCRA: o próximo comando ao motor (mesmo cabeçalho 7E0) já o reenvia.
        t.respostas.remove("ATCRA")
        t.enviados.clear()
        val leituras = obd.lerTelemetria(intervaloMinimoMs = 1, lentosPorVez = 1, ciclosPorLento = 3).take(2).toList()

        assertNull(t.filtro)
        assertEquals("ATCRA", t.enviados.first { it.startsWith("ATCRA") || it == "ATAR" })
        assertTrue(t.enviados.indexOf("ATCRA") < t.enviados.indexOf("210C"))
        assertTrue(leituras.all { it[Pids.RPM] != null })
    }

    @Test
    fun `escalonador separa a fila leve da lenta e a le a cada ciclosPorLeve`() {
        val esc = EscalonadorPolling(
            lentosPorVez = 1,
            ciclosPorLento = 3,
            candidatosRapidos = listOf("010C"),
            leves = setOf("TOYOTA_ALAVANCA", "TOYOTA_MARCHA"),
            ciclosPorLeve = 4,
        )
        esc.atualizarSuportados(listOf("010C", "0105", "TOYOTA_ALAVANCA", "TOYOTA_MARCHA"))

        assertEquals(listOf("010C"), esc.filaRapida)
        assertEquals(listOf("0105"), esc.filaLenta)
        assertEquals(listOf("TOYOTA_ALAVANCA", "TOYOTA_MARCHA"), esc.filaLeve)

        val ciclos = List(9) { esc.proximoCiclo() }
        val comLeve = ciclos.mapIndexedNotNull { i, c -> i.takeIf { "TOYOTA_MARCHA" in c } }
        assertEquals(listOf(0, 4, 8), comLeve)
        assertTrue(ciclos.all { "010C" in it })
    }

    @Test
    fun `com o 7E1 respondendo a alavanca do 7E0 sai do polling`() = runTest {
        // A SW4 2013 também responde ao 7E0 · 21D9 (sempre P): com o 7E1 vivo ele é redundante (v2.2.6).
        val t = Ecu(sw4(comCambio = true).apply { put("7E0:21D9", "61D900\r\r") })
        val obd = cliente(t)
        obd.conectar(dispositivo, "Toyota Hilux SW4")

        val perfil = obd.ativarPerfilFabricante("Toyota") as ResultadoPerfil.Ativo

        assertTrue("TOYOTA_ALAVANCA" in obd.pidsSuportados.value)
        assertFalse("TOYOTA_ALAVANCA_ECM" in obd.pidsSuportados.value)
        assertTrue(perfil.pids.none { it.comando == "TOYOTA_ALAVANCA_ECM" })
        assertTrue(obd.eventos().any { "TOYOTA_ALAVANCA_ECM (7E0 · 21D9) fora do polling" in it })
    }

    @Test
    fun `sem o 7E1 a alavanca do 7E0 continua no polling`() = runTest {
        val t = Ecu(sw4(comCambio = false).apply { put("7E0:21D9", "61D900\r\r") })
        val obd = cliente(t)
        obd.conectar(dispositivo, "Toyota Hilux SW4")

        obd.ativarPerfilFabricante("Toyota")

        assertTrue("TOYOTA_ALAVANCA_ECM" in obd.pidsSuportados.value)
        assertFalse("TOYOTA_ALAVANCA" in obd.pidsSuportados.value)
    }
}
