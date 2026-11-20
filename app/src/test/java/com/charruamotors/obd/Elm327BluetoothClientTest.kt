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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Protocolo ELM327 do cliente real, exercitado com um [ObdTransport] roteirizado
 * (sem Bluetooth): detecção de bitmask, sufixo de resposta única e novos modos.
 */
class Elm327BluetoothClientTest {

    /** Responde como um ELM327 + ECU; comandos não roteirizados devolvem `NO DATA`. */
    private class TransporteRoteirizado(respostas: Map<String, String>) : ObdTransport {
        val respostas = respostas.toMutableMap()
        val enviados = mutableListOf<String>()
        var aberto = false
        var cairNoComando: String? = null

        override suspend fun conectar() {
            aberto = true
        }

        override suspend fun enviarComando(comando: String, timeoutMs: Long): String {
            if (!aberto) throw IOException("fechado")
            enviados += comando
            if (comando == cairNoComando) throw IOException("link caiu")
            return respostas[comando]
                ?: if (comando.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"
        }

        override fun fechar() {
            aberto = false
        }
    }

    private val dispositivo = DispositivoObd("ELM327", "00:11:22:33:44:55")

    /** ECU de teste: suporta 01..20 (BE 1F A8 13), 21..40 (80 02 20 01) e 41..60 (44 00 00 10). */
    private fun ecuPadrao() = mutableMapOf(
        "ATZ" to "ELM327 v1.5\r\r",
        "ATRV" to "12.6V\r\r",
        "0100" to "SEARCHING...\r41 00 BE 1F A8 13\r\r",
        "0120" to "41 20 80 02 20 01\r\r",
        "0140" to "41 40 44 00 00 10\r\r",
        "01001" to "41 00 BE 1F A8 13\r\r",
        "010C1" to "41 0C 1A F8\r\r",
        "010D1" to "41 0D 5A\r\r",
        "015C1" to "41 5C 87\r\r",
        "010C" to "41 0C 0F A0\r\r",
    )

    private fun TestScope.cliente(transporte: ObdTransport) = Elm327BluetoothClient(
        adaptador = null,
        catalogo = CatalogoTeste.padrao,
        criarTransporte = { transporte },
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    // ---------------------------------------------------------------- inicialização e bitmasks

    @Test
    fun `conexao le os bitmasks 0100 0120 e 0140 e expoe os PIDs suportados do catalogo`() = runTest {
        val t = TransporteRoteirizado(ecuPadrao())
        val obd = cliente(t)

        assertTrue(obd.conectar(dispositivo).isSuccess)
        assertEquals(EstadoConexaoObd.Conectado, obd.estado.value)
        assertEquals(
            listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "0100", "ATDP", "ATRV", "0120", "0140", "01001"),
            t.enviados,
        )

        val suportados = obd.pidsSuportados.value
        listOf("0104", "0105", "010C", "010D", "0110", "0111", "0121", "012F", "0133", "0142", "0146", "015C", "ATRV")
            .forEach { assertTrue("$it deveria ser suportado", it in suportados) }
        listOf("010B", "0123", "015E", "0144").forEach { assertFalse("$it não é suportado", it in suportados) }
        assertFalse("PIDs de bitmask não são sensores", "0120" in suportados || "0140" in suportados)
    }

    @Test
    fun `nao le a proxima faixa se o bit de continuacao estiver desligado`() = runTest {
        val ecu = ecuPadrao().apply { put("0100", "41 00 BE 1F A8 12\r\r") } // bit 0x20 desligado
        val t = TransporteRoteirizado(ecu)
        cliente(t).conectar(dispositivo)
        assertFalse("0120" in t.enviados)
        assertFalse("0140" in t.enviados)
    }

    @Test
    fun `faixa seguinte sem resposta mantem o que ja foi detectado`() = runTest {
        val ecu = ecuPadrao().apply { remove("0120") } // NO DATA
        val obd = cliente(TransporteRoteirizado(ecu))
        assertTrue(obd.conectar(dispositivo).isSuccess)
        assertTrue("010C" in obd.pidsSuportados.value)
        assertFalse("012F" in obd.pidsSuportados.value)
    }

    @Test
    fun `ECU sem resposta ao 0100 leva ao estado Erro e fecha o transporte`() = runTest {
        val t = TransporteRoteirizado(ecuPadrao().apply { put("0100", "SEARCHING...\rUNABLE TO CONNECT\r\r") })
        val obd = cliente(t)

        val r = obd.conectar(dispositivo)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("ignição"))
        assertTrue(obd.estado.value is EstadoConexaoObd.Erro)
        assertFalse(t.aberto)
        assertTrue(obd.pidsSuportados.value.isEmpty())
    }

    // ---------------------------------------------------------------- polling com sufixo de resposta única

    @Test
    fun `polling usa o sufixo 1 nos PIDs do modo 01 e so consulta os suportados`() = runTest {
        val t = TransporteRoteirizado(ecuPadrao())
        val obd = cliente(t)
        obd.conectar(dispositivo)
        assertTrue(obd.usaSufixoRespostaUnica)
        t.enviados.clear()

        val dados = obd.lerDadosEmTempoReal(comandos = listOf("010C", "010B", "015C", "ATRV")).first()

        assertEquals(listOf("010C1", "015C1", "ATRV"), t.enviados) // 010B não é suportado
        assertEquals(1726, dados.rpm)
        assertEquals(95.0, dados["015C"]!!, 0.0)
        assertEquals(12.6, dados.tensaoBateria!!, 0.0)
        assertEquals(setOf("010C", "015C", "ATRV"), dados.valores.keys)
    }

    @Test
    fun `adaptador sem suporte ao sufixo envia os PIDs sem ele`() = runTest {
        val t = TransporteRoteirizado(ecuPadrao().apply { put("01001", "?\r\r") })
        val obd = cliente(t)
        obd.conectar(dispositivo)
        assertFalse(obd.usaSufixoRespostaUnica)
        t.enviados.clear()

        val dados = obd.lerDadosEmTempoReal(comandos = listOf("010C")).first()
        assertEquals(listOf("010C"), t.enviados)
        assertEquals(1000, dados.rpm)
    }

    @Test
    fun `sufixo rejeitado durante o polling e desligado e o comando repetido`() = runTest {
        val t = TransporteRoteirizado(ecuPadrao().apply { put("010C1", "?\r\r") })
        val obd = cliente(t)
        obd.conectar(dispositivo)
        t.enviados.clear()

        val dados = obd.lerDadosEmTempoReal(comandos = listOf("010C")).first()
        assertEquals(listOf("010C1", "010C"), t.enviados)
        assertEquals(1000, dados.rpm)
        assertFalse(obd.usaSufixoRespostaUnica)
    }

    @Test
    fun `comandos nulos leem todos os PIDs suportados`() = runTest {
        val t = TransporteRoteirizado(ecuPadrao())
        val obd = cliente(t)
        obd.conectar(dispositivo)
        t.enviados.clear()

        obd.lerDadosEmTempoReal(comandos = null).first()
        val esperados = obd.pidsSuportados.value.map { if (it.startsWith("AT")) it else it + "1" }.toSet()
        assertEquals(esperados, t.enviados.toSet())
    }

    @Test
    fun `queda do link durante o polling leva ao estado Erro`() = runTest {
        val t = TransporteRoteirizado(ecuPadrao())
        val obd = cliente(t)
        obd.conectar(dispositivo)
        t.cairNoComando = "010C1"

        val leituras = mutableListOf<DadosObd>()
        obd.lerDadosEmTempoReal(comandos = listOf("010C")).collect { leituras += it }
        assertTrue(leituras.isEmpty())
        assertTrue(obd.estado.value is EstadoConexaoObd.Erro)
        assertTrue(obd.pidsSuportados.value.isEmpty())
    }

    // ---------------------------------------------------------------- modos avançados

    @Test
    fun `le DTCs armazenados pendentes e permanentes`() = runTest {
        val ecu = ecuPadrao().apply {
            put("03", "43 02 03 00 01 71\r\r")
            put("07", "47 01 01 33\r\r")
            put("0A", "4A 00\r\r")
        }
        val obd = cliente(TransporteRoteirizado(ecu))
        obd.conectar(dispositivo)

        assertEquals(listOf("P0300", "P0171"), obd.lerCodigosFalha().getOrThrow().map { it.codigo })
        val pendentes = obd.lerCodigosPendentes().getOrThrow()
        assertEquals(listOf("P0133"), pendentes.map { it.codigo })
        assertTrue(pendentes.single().descricao.contains("lambda"))
        assertEquals(emptyList<DtcLido>(), obd.lerCodigosPermanentes().getOrThrow())
    }

    @Test
    fun `NO DATA nos modos de DTC significa nenhum codigo`() = runTest {
        val obd = cliente(TransporteRoteirizado(ecuPadrao()))
        obd.conectar(dispositivo)
        assertTrue(obd.lerCodigosPendentes().getOrThrow().isEmpty())
        assertTrue(obd.lerCodigosPermanentes().getOrThrow().isEmpty())
    }

    @Test
    fun `le o freeze frame com o DTC causador`() = runTest {
        val ecu = ecuPadrao().apply {
            put("020200", "42 02 00 03 00\r\r")
            put("020C00", "42 0C 00 24 B8\r\r")
            put("020D00", "42 0D 00 3E\r\r")
            put("020500", "42 05 00 89\r\r")
            put("020400", "42 04 00 C8\r\r")
        }
        val obd = cliente(TransporteRoteirizado(ecu))
        obd.conectar(dispositivo)

        val ff = obd.lerFreezeFrame().getOrThrow()
        assertEquals("P0300", ff.dtc)
        assertEquals(2350, ff.rpm)
        assertEquals(62, ff.velocidade)
        assertEquals(97, ff.temperaturaMotor)
        assertEquals(78.4, ff.cargaMotor!!, 0.05)
        assertFalse(ff.vazio)
    }

    @Test
    fun `freeze frame vazio quando a ECU nao tem quadro armazenado`() = runTest {
        val ecu = ecuPadrao().apply { put("020200", "42 02 00 00 00\r\r") }
        val obd = cliente(TransporteRoteirizado(ecu))
        obd.conectar(dispositivo)
        assertTrue(obd.lerFreezeFrame().getOrThrow().vazio)
    }

    @Test
    fun `le o VIN e falha com mensagem quando a ECU nao suporta`() = runTest {
        val ecu = ecuPadrao().apply {
            put("0902", "014\r0: 49 02 01 31 44 34\r1: 47 50 30 30 52 35 35\r2: 42 31 32 33 34 35 36\r\r")
        }
        val obd = cliente(TransporteRoteirizado(ecu))
        obd.conectar(dispositivo)
        assertEquals("1D4GP00R55B123456", obd.lerVin().getOrThrow())

        val semVin = cliente(TransporteRoteirizado(ecuPadrao()))
        semVin.conectar(dispositivo)
        val r = semVin.lerVin()
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is ObdException)
    }

    @Test
    fun `modos avancados sem conexao falham sem enviar nada`() = runTest {
        val t = TransporteRoteirizado(ecuPadrao())
        val obd = cliente(t)
        assertTrue(obd.lerCodigosPendentes().isFailure)
        assertTrue(obd.lerCodigosPermanentes().isFailure)
        assertTrue(obd.lerFreezeFrame().isFailure)
        assertTrue(obd.lerVin().isFailure)
        assertTrue(t.enviados.isEmpty())
    }

    @Test
    fun `desconectar fecha o transporte e limpa os PIDs suportados`() = runTest {
        val t = TransporteRoteirizado(ecuPadrao())
        val obd = cliente(t)
        obd.conectar(dispositivo)
        obd.desconectar()
        assertFalse(t.aberto)
        assertEquals(EstadoConexaoObd.Desconectado, obd.estado.value)
        assertTrue(obd.pidsSuportados.value.isEmpty())
    }
}
