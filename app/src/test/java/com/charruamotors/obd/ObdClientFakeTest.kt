package com.charruamotors.obd

import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.domain.model.StatusCodigoFalha
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.pid.Pids
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

    // ---------------------------------------------------------------- catálogo completo de PIDs

    @Test
    fun `conectado suporta todos os PIDs do catalogo e desconectado nenhum`() = runTest {
        val obd = fake()
        assertTrue(obd.pidsSuportados.value.isEmpty())
        obd.conectar(dispositivo)
        assertEquals(CatalogoTeste.padrao.comandos, obd.pidsSuportados.value)
        obd.desconectar()
        assertTrue(obd.pidsSuportados.value.isEmpty())
    }

    @Test
    fun `todos os PIDs simulados ficam dentro da faixa do catalogo`() = runTest {
        val obd = fake()
        obd.conectar(dispositivo)
        val leituras = obd.lerDadosEmTempoReal(intervaloMs = 100, comandos = null).take(300).toList()

        leituras.forEach { dados ->
            assertEquals(CatalogoTeste.padrao.comandos, dados.valores.keys)
            dados.valores.forEach { (comando, valor) ->
                val def = CatalogoTeste.pid(comando)
                assertTrue("$comando = $valor fora de ${def.min}..${def.max}", def.dentroDaFaixa(valor))
            }
        }
    }

    @Test
    fun `valores simulados sao realistas e coerentes entre si`() = runTest {
        val obd = fake()
        obd.conectar(dispositivo)
        val leituras = obd.lerDadosEmTempoReal(intervaloMs = 100, comandos = null).take(300).toList()

        leituras.forEach {
            assertTrue("MAP ${it["010B"]} acima da barométrica", it["010B"]!! <= it["0133"]!!)
            assertTrue("STFT ${it["0106"]}", it["0106"]!! in -10.0..10.0)
            assertTrue("MAF ${it["0110"]}", it["0110"]!! in 0.5..150.0)
            assertTrue("avanço ${it["010E"]}", it["010E"]!! in -5.0..40.0)
            assertTrue("rail ${it["0123"]}", it["0123"]!! in 3_000.0..15_000.0)
            assertTrue("sonda ${it["0114"]}", it["0114"]!! in 0.0..1.0)
        }
        // O óleo esquenta junto com o motor e o tempo de motor ligado avança 1 s por leitura.
        assertTrue(leituras.last()["015C"]!! > leituras.first()["015C"]!!)
        assertEquals(leituras.size.toDouble(), leituras.last()["011F"]!!, 0.0)
        // O nível de combustível cai (consumo), mas pouco em 5 minutos.
        assertTrue(leituras.last()["012F"]!! in 55.0..63.0)
    }

    @Test
    fun `leitura devolve apenas os comandos pedidos`() = runTest {
        val obd = fake()
        obd.conectar(dispositivo)
        val dados = obd.lerDadosEmTempoReal(100, listOf("010C", "015C", "01FF")).take(1).toList().single()
        assertEquals(setOf("010C", "015C"), dados.valores.keys)
    }

    @Test
    fun `por padrao le apenas os PIDs do painel`() = runTest {
        val obd = fake()
        obd.conectar(dispositivo)
        val dados = obd.lerDadosEmTempoReal().take(1).toList().single()
        assertEquals(Pids.PAINEL.toSet(), dados.valores.keys)
    }

    // ---------------------------------------------------------------- modos avançados

    @Test
    fun `le codigos pendentes e permanentes`() = runTest {
        val obd = fake()
        obd.conectar(dispositivo)
        assertEquals(listOf("P0133"), obd.lerCodigosPendentes().getOrThrow().map { it.codigo })
        assertEquals(listOf("P0420"), obd.lerCodigosPermanentes().getOrThrow().map { it.codigo })
    }

    @Test
    fun `freeze frame congela as condicoes da primeira falha`() = runTest {
        val obd = fake()
        obd.conectar(dispositivo)
        val ff = obd.lerFreezeFrame().getOrThrow()
        assertEquals("P0300", ff.dtc)
        assertEquals(Pids.FREEZE_FRAME.toSet(), ff.valores.keys)
        assertTrue(ff.rpm!! in 600..6500)
        assertTrue(ff.temperaturaMotor!! in 60..120)
    }

    @Test
    fun `sem codigos armazenados nao ha freeze frame`() = runTest {
        val obd = fake(codigos = emptyList())
        obd.conectar(dispositivo)
        assertTrue(obd.lerFreezeFrame().getOrThrow().vazio)
    }

    @Test
    fun `limpar apaga armazenados pendentes e freeze frame mas mantem permanentes`() = runTest {
        val obd = fake()
        obd.conectar(dispositivo)
        obd.limparCodigosFalha().getOrThrow()

        assertTrue(obd.lerCodigosFalha().getOrThrow().isEmpty())
        assertTrue(obd.lerCodigosPendentes().getOrThrow().isEmpty())
        assertTrue(obd.lerFreezeFrame().getOrThrow().vazio)
        assertEquals(listOf("P0420"), obd.lerCodigosPermanentes().getOrThrow().map { it.codigo })

        val dados = obd.lerDadosEmTempoReal(100, listOf("0121", "0131")).take(1).toList().single()
        assertEquals(0.0, dados["0121"]!!, 0.0) // distância com a luz acesa zerada
        assertTrue(dados["0131"]!! < 1.0) // distância desde a limpeza recomeça
    }

    @Test
    fun `le o VIN de teste`() = runTest {
        val obd = fake()
        obd.conectar(dispositivo)
        val vin = obd.lerVin().getOrThrow()
        assertEquals(17, vin.length)
        assertEquals("9BWZZZ377VT004251", vin)
    }

    @Test
    fun `modos avancados sem conexao falham`() = runTest {
        val obd = fake()
        assertTrue(obd.lerCodigosPendentes().isFailure)
        assertTrue(obd.lerCodigosPermanentes().isFailure)
        assertTrue(obd.lerFreezeFrame().isFailure)
        assertTrue(obd.lerVin().isFailure)
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
