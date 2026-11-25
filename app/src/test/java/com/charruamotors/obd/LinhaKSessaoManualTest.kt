package com.charruamotors.obd

import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.transporte.ObdTransport
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.3.4: retomada do polling na linha K depois da sessão manual e o `ATRV` corrompido do clone v2.1. */
class LinhaKSessaoManualTest {

    private class TransporteKwp : ObdTransport {
        val enviados = mutableListOf<String>()

        override suspend fun conectar() = Unit

        override suspend fun enviarComando(comando: String, timeoutMs: Long): String {
            enviados += comando
            return when (comando) {
                "ATZ" -> "ELM327 v2.1\r\r"
                "ATDP" -> "AUTO, ISO 14230-4 (KWP FAST)\r\r"
                "ATDPN" -> "A5\r\r"
                "ATRV" -> ":.0V\r\r"
                "0100", "01001" -> "41 00 BE 1F A8 13\r\r"
                else -> if (comando.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"
            }
        }

        override fun fechar() = Unit
    }

    private suspend fun TestScope.conectado(): Pair<TransporteKwp, Elm327BluetoothClient> {
        val t = TransporteKwp()
        val obd = Elm327BluetoothClient(
            adaptador = null,
            catalogo = CatalogoTeste.padrao,
            criarTransporte = { t },
            dispatcher = StandardTestDispatcher(testScheduler),
            relogioMs = { testScheduler.currentTime },
        )
        assertTrue(obd.conectar(DispositivoObd("ELM327", "00:11:22:33:44:55")).isSuccess)
        t.enviados.clear()
        return t to obd
    }

    @Test
    fun `KWP ocioso na sessao manual reinicia com ATPC e 0100 sem ATSH de CAN`() = runTest {
        val (t, obd) = conectado()
        obd.iniciarSessaoManual()
        advanceTimeBy(Elm327BluetoothClient.OCIOSIDADE_LINHA_K_MS)
        obd.enviarComandoManual("ATRV")
        obd.enviarComandoManual("ATH1") // força a reaplicação da configuração e do cabeçalho
        obd.encerrarSessaoManual()

        assertEquals(listOf("ATPC", "0100"), t.enviados.filter { it == "ATPC" || it == "0100" })
        assertTrue(t.enviados.none { it in listOf("ATSH7DF", "ATSH7E1", "ATSH7E2") })
    }

    @Test
    fun `ATRV corrompido do clone v21 vale 10 volts`() {
        assertEquals(RespostaObd.Ok(10.0), ObdParser.parseTensao(":.0V\r\r"))
    }
}
