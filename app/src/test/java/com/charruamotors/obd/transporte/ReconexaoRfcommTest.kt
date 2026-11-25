package com.charruamotors.obd.transporte

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.Closeable

/** Pausa de liberação do canal RFCOMM entre fechar um socket e abrir outro para o mesmo adaptador. */
class ReconexaoRfcommTest {

    private val pausa = ClassicBluetoothTransport.PAUSA_APOS_FECHAMENTO_MS

    @Test
    fun `primeira conexao ao adaptador nao espera`() {
        assertEquals(0L, ClassicBluetoothTransport.esperaAntesDeReconectar(10_000, null))
    }

    @Test
    fun `reconexao logo depois de fechar espera so o que falta da pausa`() {
        assertEquals(pausa, ClassicBluetoothTransport.esperaAntesDeReconectar(5_000, 5_000))
        assertEquals(pausa - 100, ClassicBluetoothTransport.esperaAntesDeReconectar(5_100, 5_000))
    }

    @Test
    fun `reconexao depois da pausa ou com relogio incoerente segue direto`() {
        assertEquals(0L, ClassicBluetoothTransport.esperaAntesDeReconectar(5_000 + pausa, 5_000))
        assertEquals(0L, ClassicBluetoothTransport.esperaAntesDeReconectar(60_000, 5_000))
        assertEquals(0L, ClassicBluetoothTransport.esperaAntesDeReconectar(4_000, 5_000))
    }

    @Test
    fun `fechamento silencioso engole falhas da pilha Bluetooth`() {
        var fechou = false
        ClassicBluetoothTransport.fecharSilencioso(Closeable { fechou = true; throw NullPointerException("pilha") })
        ClassicBluetoothTransport.fecharSilencioso(null)
        assertEquals(true, fechou)
    }
}
