package com.charruamotors.obd.sessao

import com.charruamotors.direcao.hud.TelemetriaCompartilhada
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.ModoObd
import com.charruamotors.obd.ObdClientFake
import com.charruamotors.obd.dtc.CatalogoDtc
import com.charruamotors.obd.log.DestinoMemoria
import com.charruamotors.obd.log.GeradorCsv
import com.charruamotors.obd.log.OrganizadorLogs
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.pid.Pids
import com.charruamotors.oficina.replay.LeitorCsvLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * Sessão OBD compartilhada (caixa-preta): início manual e automático (espera da ECU e 3
 * tentativas), uma conexão só, encerramento pelo usuário, pelo Bluetooth do carro e pela ECU muda
 * — sempre fechando o CSV e gravando o relatório .txt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessaoObdTest {

    @get:Rule
    val pasta = TemporaryFolder()

    private val inicio = LocalDateTime.of(2026, 11, 22, 14, 30, 5).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val sw4 = VeiculoSessao(7, "Toyota SW4 2015", "Toyota SW4", "Toyota", "ABC-1D23", TipoCombustivel.DIESEL)
    private val scanner = DispositivoObd("OBDII", "00:1D:A5:68:98:8B")

    private val fake = ObdClientFake(random = Random(42), latenciaMs = 50)
    private val destino = DestinoMemoria()
    private val telemetria = TelemetriaCompartilhada()
    private val ativacoes = mutableListOf<OrigemSessao>()
    private var config = ConfigAutoConexao(veiculoId = 7)
    private val escopos = mutableListOf<CoroutineScope>()

    @After
    fun cancelarEscopos() = escopos.forEach { it.cancel() }

    private fun TestScope.novaSessao(
        modo: ModoObd = ModoObd.SIMULADO,
        gpsAtivo: Boolean = false,
        velocidadeGps: () -> Double? = { null },
    ) = SessaoObd(
        clientePorModo = { fake },
        modoInicial = modo,
        catalogoPids = CatalogoTeste.padrao,
        catalogoDtc = CatalogoDtc.VAZIO,
        config = { config },
        buscarVeiculo = { id -> sw4.takeIf { id == it.id } },
        destinoLogs = destino,
        pastaTemporaria = pasta.newFolder(),
        // Escopo próprio no mesmo relógio virtual (o backgroundScope não roda no advanceUntilIdle).
        escopo = CoroutineScope(StandardTestDispatcher(testScheduler)).also { escopos += it },
        io = StandardTestDispatcher(testScheduler),
        telemetria = telemetria,
        aoAtivar = { ativacoes += it },
        relogio = { inicio + testScheduler.currentTime },
        zona = ZoneOffset.UTC,
        gpsAtivo = { gpsAtivo },
        velocidadeGps = velocidadeGps,
    )

    // ---------------------------------------------------------------- velocidade GPS (v2.0)

    @Test
    fun `velocidade GPS ativa entra nos instantaneos, no HUD e numa coluna do CSV`() = runTest {
        val sessao = novaSessao(ModoObd.BLUETOOTH, gpsAtivo = true, velocidadeGps = { 42.5 })
        sessao.conectar(scanner, veiculoId = 7, origem = OrigemSessao.MANUAL)
        avancar(5_000)

        assertEquals(42.5, sessao.dados.value!![Pids.VELOCIDADE_GPS]!!, 1e-9)
        assertEquals(42.5, telemetria.dados.value!![Pids.VELOCIDADE_GPS]!!, 1e-9)
        assertNotNull("a ECU segue publicando a própria velocidade", sessao.dados.value!![Pids.VELOCIDADE])
        sessao.desconectar()
        advanceUntilIdle()

        // A coluna do celular vem depois das da ECU (última do arquivo).
        val csv = destino.terminadoEm(".csv").conteudo
        val cabecalho = csv.removePrefix(GeradorCsv.BOM.toString()).lineSequence().first()
        assertTrue(cabecalho, cabecalho.endsWith(",Velocidade GPS (km/h) [GPS_VEL]"))
        val linhas = csv.lines().drop(1).filter { it.isNotBlank() }
        assertTrue(linhas.isNotEmpty())
        assertTrue(linhas.all { it.substringAfterLast(',') == "42.5" })
    }

    @Test
    fun `GPS sem fix nao inventa valor - a chave some e a coluna fica vazia`() = runTest {
        val sessao = novaSessao(ModoObd.BLUETOOTH, gpsAtivo = true, velocidadeGps = { null })
        sessao.conectar(scanner, veiculoId = 7, origem = OrigemSessao.MANUAL)
        avancar(5_000)

        assertNull(sessao.dados.value!![Pids.VELOCIDADE_GPS])
        sessao.desconectar()
        advanceUntilIdle()

        val csv = destino.terminadoEm(".csv").conteudo
        assertTrue(csv.removePrefix(GeradorCsv.BOM.toString()).lineSequence().first().endsWith(",Velocidade GPS (km/h) [GPS_VEL]"))
        assertTrue(csv.lines().drop(1).filter { it.isNotBlank() }.all { it.endsWith(",") })
    }

    @Test
    fun `GPS desativado nao cria coluna nem consulta o provedor`() = runTest {
        var consultas = 0
        val sessao = novaSessao(ModoObd.BLUETOOTH, gpsAtivo = false, velocidadeGps = { consultas++; 50.0 })
        sessao.conectar(scanner, veiculoId = 7, origem = OrigemSessao.MANUAL)
        avancar(5_000)

        assertNull(sessao.dados.value!![Pids.VELOCIDADE_GPS])
        assertEquals(0, consultas)
        assertTrue(sessao.colunasDoAparelho().isEmpty())
        sessao.desconectar()
        advanceUntilIdle()
        assertFalse("[GPS_VEL]" in destino.terminadoEm(".csv").conteudo)
    }

    private fun TestScope.avancar(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `modo simulado sinaliza SIMULADO no nome dos arquivos e no cabecalho do txt`() = runTest {
        val sessao = novaSessao(ModoObd.SIMULADO)
        sessao.conectar(scanner, veiculoId = 7, origem = OrigemSessao.MANUAL)
        avancar(5_000)
        sessao.desconectar()
        advanceUntilIdle()

        val csv = destino.terminadoEm(".csv")
        val txt = destino.terminadoEm(".txt")
        assertEquals("CharruaOBD_SIMULADO_20261122_143005_Toyota_SW4.csv", csv.nome)
        assertEquals("CharruaOBD_SIMULADO_20261122_143005_Toyota_SW4.txt", txt.nome)
        assertTrue(txt.conteudo.lines().any { it.trimStart().startsWith("Modo") && it.endsWith(": SIMULADO") })
        assertTrue(OrganizadorLogs.ehSimulado(csv.nome) && OrganizadorLogs.ehSimulado(txt.nome))
    }

    @Test
    fun `sessao com o scanner real segue com o nome normal e Modo REAL`() = runTest {
        val sessao = novaSessao(ModoObd.BLUETOOTH)
        sessao.conectar(scanner, veiculoId = 7, origem = OrigemSessao.MANUAL)
        avancar(5_000)
        sessao.desconectar()
        advanceUntilIdle()

        val txt = destino.terminadoEm(".txt")
        assertEquals("CharruaOBD_20261122_143005_Toyota_SW4.txt", txt.nome)
        assertFalse(OrganizadorLogs.ehSimulado(txt.nome))
        assertTrue(txt.conteudo.lines().any { it.trimStart().startsWith("Modo") && it.endsWith(": REAL") })
    }

    @Test
    fun `sessao manual conecta, grava CSV incremental e salva o txt ao desconectar`() = runTest {
        val sessao = novaSessao(ModoObd.BLUETOOTH)
        assertTrue(sessao.conectar(scanner, veiculoId = 7, origem = OrigemSessao.MANUAL))
        assertEquals(listOf(OrigemSessao.MANUAL), ativacoes)
        assertTrue(sessao.estadoSessao.value.ativa)

        avancar(5_000)
        assertEquals(EstadoConexaoObd.Conectado, fake.estado.value)
        val estado = sessao.estadoSessao.value
        assertEquals(FaseSessao.Conectada, estado.fase)
        assertEquals("Toyota SW4", estado.veiculo)
        assertTrue(estado.gravandoLogs)
        assertNotNull(sessao.dados.value)
        assertTrue("o HUD recebe a telemetria da sessão", telemetria.conectado.value)

        // CSV aberto e já sincronizado durante a viagem (não só no fim).
        val csv = destino.terminadoEm(".csv")
        assertEquals("CharruaOBD_20261122_143005_Toyota_SW4.csv", csv.nome)
        assertTrue(csv.conteudo.startsWith(GeradorCsv.BOM + "timestamp_ms,data_hora,tempo_s,"))
        assertFalse(csv.fechado)
        assertTrue(csv.sincronizacoes >= 1)
        assertNull("o .txt só é gerado no fim", destino.algumTerminadoEm(".txt"))

        sessao.desconectar()
        advanceUntilIdle()

        assertFalse(sessao.estadoSessao.value.ativa)
        assertEquals(EstadoConexaoObd.Desconectado, fake.estado.value)
        assertNull(sessao.dados.value)
        assertFalse(telemetria.conectado.value)
        assertTrue(csv.fechado)
        val log = LeitorCsvLog.ler(csv.conteudo)
        assertTrue("amostras: ${log.amostras}", log.amostras >= 20)
        assertEquals(0, log.linhasIgnoradas)

        val txt = destino.terminadoEm(".txt")
        assertTrue(txt.fechado)
        val texto = txt.conteudo
        listOf(
            "Toyota SW4 2015", "ABC-1D23", "Manual (botão Conectar)", "OBDII (00:1D:A5:68:98:8B)",
            "Desconectado pelo usuário", "AUTO, ISO 15765-4 (CAN 11/500)", "RPM máximo",
            "P0300", "P0133", // DTCs lidos automaticamente para o relatório
            "ATZ", "Sessão: Encerrada: Desconectado pelo usuário",
        ).forEach { assertTrue("faltou \"$it\" no relatório:\n$texto", texto.contains(it)) }
    }

    @Test
    fun `sessao automatica espera 4 s pela ECU antes de conectar`() = runTest {
        val sessao = novaSessao()
        assertTrue(sessao.iniciarAutomatico("Toyota SW4"))
        assertEquals(listOf(OrigemSessao.AUTOMATICA), ativacoes)
        assertEquals(ModoObd.BLUETOOTH, sessao.modo.value)

        avancar(3_900)
        assertEquals(FaseSessao.AguardandoEcu, sessao.estadoSessao.value.fase)
        assertEquals(EstadoConexaoObd.Desconectado, fake.estado.value)

        avancar(120) // 4,02 s: conectando (o scanner simulado responde em 50 ms)
        assertEquals(FaseSessao.Conectando(1, 3), sessao.estadoSessao.value.fase)
        assertEquals(EstadoConexaoObd.Conectando, fake.estado.value)

        avancar(1_000)
        assertEquals(EstadoConexaoObd.Conectado, fake.estado.value)
        assertTrue(sessao.estadoSessao.value.automatica)
        // Scanner escolhido pelo nome entre os pareados (nenhum vinculado ainda).
        assertEquals("OBDII Simulado (ELM327)", sessao.estadoSessao.value.scanner)
        sessao.desconectar()
        advanceUntilIdle()
    }

    @Test
    fun `sessao automatica tenta 3 vezes espacadas de 5 s e salva o txt da falha`() = runTest {
        fake.simularFalhaNaConexao = true
        val sessao = novaSessao()
        assertTrue(sessao.iniciarAutomatico("Toyota SW4"))

        avancar(4_100) // 1ª tentativa: 4,0–4,05 s
        avancar(4_000)
        assertEquals("ainda no intervalo entre tentativas", FaseSessao.Conectando(1, 3), sessao.estadoSessao.value.fase)
        assertTrue(fake.estado.value is EstadoConexaoObd.Erro)
        avancar(1_000) // 2ª tentativa em ~9,05 s
        assertEquals(FaseSessao.Conectando(2, 3), sessao.estadoSessao.value.fase)
        advanceUntilIdle()

        assertFalse(sessao.estadoSessao.value.ativa)
        assertTrue("3 tentativas levam ~14 s", testScheduler.currentTime in 14_000..15_000)
        assertNull(destino.algumTerminadoEm(".csv"))
        val txt = destino.terminadoEm(".txt").conteudo
        assertTrue(txt.contains("Tentativa 1/3 falhou"))
        assertTrue(txt.contains("Tentativa 3/3 falhou"))
        assertFalse(txt.contains("Tentativa 4/3"))
        assertTrue(txt.contains("Falha no handshake (não conectou)"))
        assertTrue(txt.contains("UNABLE TO CONNECT"))
        assertTrue(txt.contains("Automático (Bluetooth do carro: Toyota SW4)"))
    }

    @Test
    fun `sessao manual falhando tenta uma vez so e tambem salva o txt`() = runTest {
        fake.simularFalhaNaConexao = true
        val sessao = novaSessao()
        sessao.conectar(scanner, 7, OrigemSessao.MANUAL)
        advanceUntilIdle()
        assertFalse(sessao.estadoSessao.value.ativa)
        assertTrue(testScheduler.currentTime < 1_000)
        val txt = destino.terminadoEm(".txt").conteudo
        assertTrue(txt.contains("Falha na conexão"))
        assertFalse(txt.contains("Tentativa"))
    }

    @Test
    fun `Bluetooth do carro desconectando encerra so a sessao automatica`() = runTest {
        val sessao = novaSessao()
        sessao.conectar(scanner, 7, OrigemSessao.MANUAL)
        avancar(2_000)
        assertFalse("sessão manual não é do carro", sessao.encerrarPorGatilho("Toyota SW4"))
        avancar(1_000)
        assertTrue(sessao.estadoSessao.value.ativa)
        sessao.desconectar()
        advanceUntilIdle()

        destino.arquivos.clear()
        assertTrue(sessao.iniciarAutomatico("Toyota SW4"))
        avancar(8_000)
        assertEquals(EstadoConexaoObd.Conectado, fake.estado.value)
        assertTrue(sessao.encerrarPorGatilho("Toyota SW4"))
        advanceUntilIdle()

        assertFalse(sessao.estadoSessao.value.ativa)
        assertEquals(EstadoConexaoObd.Desconectado, fake.estado.value)
        assertTrue(destino.terminadoEm(".csv").fechado)
        assertTrue(destino.terminadoEm(".txt").conteudo.contains("Bluetooth do carro (Toyota SW4) desconectado"))
    }

    @Test
    fun `ECU parando de responder com a ignicao desligada encerra e salva os logs`() = runTest {
        val sessao = novaSessao()
        sessao.iniciarAutomatico("Toyota SW4")
        avancar(10_000)
        assertEquals(FaseSessao.Conectada, sessao.estadoSessao.value.fase)

        fake.simularIgnicaoDesligada()
        advanceUntilIdle()

        assertFalse(sessao.estadoSessao.value.ativa)
        val csv = destino.terminadoEm(".csv")
        assertTrue(csv.fechado)
        assertTrue(LeitorCsvLog.ler(csv.conteudo).amostras > 0)
        val txt = destino.terminadoEm(".txt").conteudo
        assertTrue(txt.contains(ObdClientFake.MENSAGEM_ECU_MUDA))
        assertTrue(txt.contains("Resultado"))
        assertTrue(txt.contains(": Conectado"))
    }

    @Test
    fun `com a sessao ativa a tela nao abre um segundo socket`() = runTest {
        val sessao = novaSessao()
        sessao.iniciarAutomatico("Toyota SW4")
        avancar(6_000)
        assertFalse(sessao.conectar(scanner, 7, OrigemSessao.MANUAL))
        assertFalse(sessao.iniciarAutomatico("Toyota SW4"))
        assertFalse("modo não troca com sessão ativa", sessao.selecionarModo(ModoObd.SIMULADO))
        assertEquals(listOf(OrigemSessao.AUTOMATICA), ativacoes)
        sessao.desconectar()
        advanceUntilIdle()
        assertEquals("um só par de arquivos", 2, destino.arquivos.size)
    }

    @Test
    fun `cancelar durante a espera da ECU encerra sem conectar`() = runTest {
        val sessao = novaSessao()
        sessao.iniciarAutomatico("Toyota SW4")
        avancar(2_000)
        sessao.desconectar()
        advanceUntilIdle()
        assertFalse(sessao.estadoSessao.value.ativa)
        assertEquals(EstadoConexaoObd.Desconectado, fake.estado.value)
        assertTrue(destino.terminadoEm(".txt").conteudo.contains("Desconectado pelo usuário"))
    }

    @Test
    fun `logs automaticos desligados nao gravam arquivos`() = runTest {
        config = config.copy(gravarLogsAutomaticos = false)
        val sessao = novaSessao()
        sessao.conectar(scanner, 7, OrigemSessao.MANUAL)
        avancar(3_000)
        assertFalse(sessao.estadoSessao.value.gravandoLogs)
        assertNotNull(sessao.dados.value)
        sessao.desconectar()
        advanceUntilIdle()
        assertTrue(destino.arquivos.isEmpty())
    }

    @Test
    fun `sessao automatica usa o scanner vinculado`() = runTest {
        config = config.copy(scanner = DispositivoObd("Scanner Reserva (simulado)", "00:00:00:00:00:02"))
        val sessao = novaSessao()
        sessao.iniciarAutomatico(null)
        runCurrent()
        assertEquals("Scanner Reserva (simulado)", sessao.estadoSessao.value.scanner)
        assertEquals("Toyota SW4", sessao.estadoSessao.value.gatilho) // nome configurado
        sessao.desconectar()
        advanceUntilIdle()
    }
}
