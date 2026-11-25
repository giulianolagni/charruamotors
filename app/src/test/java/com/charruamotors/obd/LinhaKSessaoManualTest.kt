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

/** v2.3.4/2.3.5: retomada do polling na linha K depois da sessão manual e o `ATRV` corrompido do clone v2.1. */
class LinhaKSessaoManualTest {

    private class TransporteKwp : ObdTransport {
        val enviados = mutableListOf<String>()

        /** Resposta ao `0100` depois do `ATPC`: só com o `ATSP0` o clone busca o protocolo. */
        var respostaReinicio = "SEARCHING...\rOK\r4100BE1FA813\r\r"

        override suspend fun conectar() = Unit

        override suspend fun enviarComando(comando: String, timeoutMs: Long): String {
            enviados += comando
            return when (comando) {
                "ATZ" -> "ELM327 v2.1\r\r"
                "ATDP" -> "AUTO, ISO 14230-4 (KWP FAST)\r\r"
                "ATDPN" -> "A5\r\r"
                "ATRV" -> ":.0V\r\r"
                "0100" -> when {
                    "ATPC" !in enviados -> "41 00 BE 1F A8 13\r\r"
                    enviados.lastIndexOf("ATSP0") > enviados.lastIndexOf("ATPC") -> respostaReinicio
                    else -> "NO DATA\r\r"
                }
                "01001" -> "41 00 BE 1F A8 13\r\r"
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
    fun `KWP ocioso na sessao manual reinicia com ATSP0 e 0100 e restaura o ATST19`() = runTest {
        val (t, obd) = conectado()
        obd.iniciarSessaoManual()
        advanceTimeBy(Elm327BluetoothClient.OCIOSIDADE_LINHA_K_MS)
        obd.enviarComandoManual("ATRV")
        obd.enviarComandoManual("ATH1") // força a reaplicação da configuração e do cabeçalho
        obd.encerrarSessaoManual()

        val reinicio = t.enviados.drop(t.enviados.indexOf("ATPC"))
        assertEquals(listOf("ATPC", "ATSP0", "ATST64", "0100", "ATST19"), reinicio)
        assertTrue(t.enviados.none { it in listOf("ATSH7DF", "ATSH7E1", "ATSH7E2") })
    }

    @Test
    fun `re-init sem resposta nao restaura o ATST19 nem se repete na retomada`() = runTest {
        val (t, obd) = conectado()
        t.respostaReinicio = "NO DATA\r\r"
        obd.iniciarSessaoManual()
        advanceTimeBy(Elm327BluetoothClient.OCIOSIDADE_LINHA_K_MS)
        obd.encerrarSessaoManual()
        obd.lerCodigosFalha() // primeiro comando OBD retomado, também sem resposta

        assertEquals(1, t.enviados.count { it == "ATPC" })
        assertTrue("ATST19" !in t.enviados)
    }

    @Test
    fun `ATRV corrompido do clone v21 vale 10 volts`() {
        assertEquals(RespostaObd.Ok(10.0), ObdParser.parseTensao(":.0V\r\r"))
    }
}
