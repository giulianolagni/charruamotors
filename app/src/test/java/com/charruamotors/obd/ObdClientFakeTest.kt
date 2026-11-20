package com.charruamotors.obd

import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.domain.model.StatusCodigoFalha
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

class ObdClientFakeTest {

    private val dispositivo = DispositivoObd("Teste", "00:00:00:00:00:01")

    private fun fake(codigos: List<String> = listOf("P0300", "P0171", "P0420")) =
        ObdClientFake(random = Random(42), latenciaMs = 0, codigosIniciais = codigos)

    @Test
    fun `comeca desconectado`() {
        assertEquals(EstadoConexaoObd.Desconectado, fake().estado.value)
    }

    @Test
    fun `lista dispositivos simulados`() = runTest {
        assertTrue(fake().listarDispositivos().isNotEmpty())
    }

    @Test
    fun `conectar leva ao estado Conectado e desconectar volta`() = runTest {
        val obd = fake()
        assertTrue(obd.conectar(dispositivo).isSuccess)
        assertEquals(EstadoConexaoObd.Conectado, obd.estado.value)
        obd.desconectar()
        assertEquals(EstadoConexaoObd.Desconectado, obd.estado.value)
    }

    @Test
    fun `falha simulada leva ao estado Erro`() = runTest {
        val obd = fake().apply { simularFalhaNaConexao = true }
        val r = obd.conectar(dispositivo)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is ObdException)
        assertTrue(obd.estado.value is EstadoConexaoObd.Erro)
    }

    @Test
    fun `leituras em tempo real ficam em faixas realistas`() = runTest {
        val obd = fake()
        obd.conectar(dispositivo)
        val leituras = obd.lerDadosEmTempoReal(intervaloMs = 100).take(200).toList()

        assertEquals(200, leituras.size)
        leituras.forEach {
            assertTrue("rpm ${it.rpm}", it.rpm!! in 600..6500)
            assertTrue("velocidade ${it.velocidade}", it.velocidade!! in 0..180)
            assertTrue("temp ${it.temperaturaMotor}", it.temperaturaMotor!! in 30..95)
            assertTrue("carga ${it.cargaMotor}", it.cargaMotor!! in 0.0..100.0)
            assertTrue("tensao ${it.tensaoBateria}", it.tensaoBateria!! in 13.5..14.5)
        }
    }

    @Test
    fun `leituras variam dinamicamente e o motor esquenta`() = runTest {
        val obd = fake()
        obd.conectar(dispositivo)
        val leituras = obd.lerDadosEmTempoReal(intervaloMs = 100).take(100).toList()

        assertTrue(leituras.map { it.rpm }.distinct().size > 10)
        assertTrue(leituras.map { it.velocidade }.distinct().size > 5)
        assertTrue(leituras.last().temperaturaMotor!! > leituras.first().temperaturaMotor!!)
    }

    @Test
    fun `mesma semente produz a mesma simulacao`() = runTest {
        val a = fake().also { it.conectar(dispositivo) }.lerDadosEmTempoReal(100).take(20).toList()
        val b = fake().also { it.conectar(dispositivo) }.lerDadosEmTempoReal(100).take(20).toList()
        assertEquals(a, b)
    }

    @Test
    fun `fluxo termina ao desconectar sem emitir nada`() = runTest {
        val obd = fake()
        // nunca conectou
        assertTrue(obd.lerDadosEmTempoReal(100).toList().isEmpty())
        obd.conectar(dispositivo)
        obd.desconectar()
        assertTrue(obd.lerDadosEmTempoReal(100).toList().isEmpty())
    }

    @Test
    fun `le codigos de falha de teste com descricao`() = runTest {
        val obd = fake()
        obd.conectar(dispositivo)
        val codigos = obd.lerCodigosFalha().getOrThrow()

        assertEquals(listOf("P0300", "P0171", "P0420"), codigos.map { it.codigo })
        assertTrue(codigos.all { it.descricao.isNotBlank() })
    }

    @Test
    fun `limpar codigos zera a lista`() = runTest {
        val obd = fake()
        obd.conectar(dispositivo)
        assertTrue(obd.limparCodigosFalha().isSuccess)
        assertTrue(obd.lerCodigosFalha().getOrThrow().isEmpty())
    }

    @Test
    fun `ler e limpar sem conexao falham`() = runTest {
        val obd = fake()
        assertTrue(obd.lerCodigosFalha().isFailure)
        assertTrue(obd.limparCodigosFalha().isFailure)
    }

    @Test
    fun `dados e codigos convertem para entidades do historico`() = runTest {
        val obd = fake()
        obd.conectar(dispositivo)
        val agora = LocalDateTime.of(2026, 6, 1, 10, 0)

        val dados = obd.lerDadosEmTempoReal(100).take(1).toList().single()
        val leitura = dados.paraLeitura(veiculoId = 7, dataHora = agora)
        assertEquals(7, leitura.veiculoId)
        assertEquals(dados.rpm, leitura.rpm)
        assertEquals(agora, leitura.dataHora)

        val dtc = obd.lerCodigosFalha().getOrThrow().first()
        val falha = dtc.paraCodigoFalha(veiculoId = 7, dataHora = agora)
        assertEquals("P0300", falha.codigo)
        assertEquals(StatusCodigoFalha.ATIVO, falha.status)
        assertFalse(falha.descricao.isNullOrBlank())
        assertNotEquals(0, falha.descricao!!.length)
    }
}
