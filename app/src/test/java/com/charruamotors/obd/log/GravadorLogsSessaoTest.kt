package com.charruamotors.obd.log

import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.DtcLido
import com.charruamotors.obd.InfoConexao
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.pid.Pids
import com.charruamotors.oficina.replay.LeitorCsvLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Logs automáticos da caixa-preta: CSV incremental (compatível com o visualizador) e relatório .txt. */
class GravadorLogsSessaoTest {

    @get:Rule
    val pasta = TemporaryFolder()

    private val zona = ZoneOffset.UTC
    private val inicio = LocalDateTime.of(2026, 11, 22, 14, 30, 5).toInstant(zona).toEpochMilli()
    private val destino = DestinoMemoria()

    private val cabecalho = CabecalhoSessao(
        veiculo = "Toyota SW4 2015",
        veiculoArquivo = "Toyota SW4",
        placa = "ABC-1D23",
        modoInicio = "Automático (Bluetooth do carro: Toyota SW4)",
        scanner = "OBDII (00:1D:A5:68:98:8B)",
        inicioMs = inicio,
    )

    private val colunas = listOf(Pids.RPM, Pids.VELOCIDADE, Pids.PRESSAO_COLETOR).map(CatalogoTeste::pid)

    private fun gravador(amostrasPorFlush: Int = 100, tmp: java.io.File = pasta.newFolder()) =
        GravadorLogsSessao(destino, cabecalho, tmp, zona, intervaloFlushMs = 10_000, amostrasPorFlush = amostrasPorFlush)

    private fun dados(rpm: Double, velocidade: Double? = null, map: Double? = null, vararg outros: Pair<String, Double>) =
        DadosObd(
            buildMap {
                put(Pids.RPM, rpm)
                velocidade?.let { put(Pids.VELOCIDADE, it) }
                map?.let { put(Pids.PRESSAO_COLETOR, it) }
                putAll(outros)
            },
        )

    // ------------------------------------------------------------------ nomes

    @Test
    fun `nome dos arquivos tem data, hora e veiculo`() {
        assertEquals("CharruaOBD_20261122_143005_Toyota_SW4", gravador().nomeBase)
        assertEquals("Citroen_C4_Lounge", RelatorioSessaoTxt.trechoArquivo("Citroën C4 Lounge"))
        assertEquals("VW_Gol_1_6", RelatorioSessaoTxt.trechoArquivo("VW Gol 1/6"))
        assertEquals("Fiat_Strada-Volcano", RelatorioSessaoTxt.trechoArquivo("Fiat Strada-Volcano"))
        assertEquals("SemVeiculo", RelatorioSessaoTxt.trechoArquivo(null))
        assertEquals("SemVeiculo", RelatorioSessaoTxt.trechoArquivo("  ///  "))
    }

    // ------------------------------------------------------------------ CSV incremental

    @Test
    fun `amostras anteriores as colunas entram no CSV quando ele abre`() {
        val g = gravador()
        g.registrar(dados(800.0, 0.0), inicio + 100)
        g.registrar(dados(820.0, 0.0), inicio + 200)
        assertTrue(destino.arquivos.isEmpty())

        g.iniciarCsv(colunas)
        val csv = destino.terminadoEm(".csv")
        assertEquals("CharruaOBD_20261122_143005_Toyota_SW4.csv", csv.nome)
        assertEquals(DestinoLogs.MIME_CSV, csv.mime)
        val linhas = csv.conteudo.split(GeradorCsv.FIM_DE_LINHA).filter { it.isNotEmpty() }
        assertEquals(GeradorCsv.BOM + GeradorCsv.cabecalho(colunas), linhas[0])
        assertEquals(3, linhas.size)
        assertTrue(linhas[1].startsWith("${inicio + 100},2026-11-22T14:30:05.100,0.100,800,0,"))
        assertEquals(1, csv.sincronizacoes) // o cabeçalho e a fila já vão para o disco
        assertEquals(2, g.amostrasGravadas)
    }

    @Test
    fun `CSV sincroniza a cada 10 segundos para nao perder o trajeto numa queda`() {
        val g = gravador()
        g.iniciarCsv(colunas)
        val csv = destino.terminadoEm(".csv")
        val antes = csv.sincronizacoes
        for (s in 1..9) g.registrar(dados(1000.0 + s, 40.0), inicio + s * 1000L)
        assertEquals("9 s de amostras ainda no buffer", antes, csv.sincronizacoes)
        g.registrar(dados(2000.0, 50.0), inicio + 10_000)
        assertEquals(antes + 1, csv.sincronizacoes)
        // Tudo até os 10 s já estaria no disco se o celular reiniciasse agora.
        assertTrue(csv.conteudoSincronizado.contains(",2000,50,"))
        assertFalse(csv.fechado)
    }

    @Test
    fun `CSV tambem sincroniza a cada bloco de amostras`() {
        val g = gravador(amostrasPorFlush = 5)
        g.iniciarCsv(colunas)
        val csv = destino.terminadoEm(".csv")
        val antes = csv.sincronizacoes
        repeat(5) { g.registrar(dados(900.0), inicio + 100L * (it + 1)) }
        assertEquals(antes + 1, csv.sincronizacoes)
    }

    @Test
    fun `CSV incremental abre no visualizador de logs da v1_2`() {
        val g = gravador()
        g.iniciarCsv(colunas)
        g.registrar(dados(800.0, 0.0, 101.0), inicio + 250)
        g.registrar(dados(1500.0, null, 140.0), inicio + 500) // velocidade sem resposta no ciclo
        g.registrar(dados(2500.0, 60.0, 180.0), inicio + 750)
        val salvos = g.finalizar(inicio + 1_000, conectou = true, motivoFim = "Desconectado")

        val csv = destino.terminadoEm(".csv")
        assertTrue(csv.fechado)
        assertEquals(csv.nome, salvos.csv?.nome)
        val log = LeitorCsvLog.ler(csv.conteudo)
        assertEquals(3, log.amostras)
        assertEquals(0, log.linhasIgnoradas)
        assertTrue(log.tempoAbsoluto)
        assertEquals(listOf(Pids.RPM, Pids.VELOCIDADE, Pids.PRESSAO_COLETOR), log.colunas.map { it.comando })
        assertEquals(2500.0, log.series[0][2], 0.0)
        assertTrue(log.series[1][1].isNaN())
        assertEquals(inicio + 250, log.instantesMs[0])
    }

    @Test
    fun `sem sensores suportados nao ha CSV e o relatorio avisa`() {
        val g = gravador()
        g.registrar(dados(800.0), inicio + 100)
        g.iniciarCsv(emptyList())
        g.finalizar(inicio + 5_000, conectou = true, motivoFim = "Desconectado")
        assertNull(destino.algumTerminadoEm(".csv"))
        val txt = destino.terminadoEm(".txt").conteudo
        assertTrue(txt.contains("a ECU não informou sensores suportados"))
    }

    // ------------------------------------------------------------------ relatório .txt

    @Test
    fun `relatorio tem cabecalho, DTCs, resumo da viagem e log bruto completo`() {
        val tmp = pasta.newFolder()
        val g = gravador(tmp = tmp)
        g.evento("Aguardando 4 s para a ignição/ECU terminar o boot", inicio)
        g.registrarEntrada(EntradaRegistro.Comando(inicio + 4_100, "ATZ", "\r\rELM327 v1.5\r\r", 812))
        g.registrarEntrada(EntradaRegistro.Comando(inicio + 4_900, "ATRV", "12.6V\r\r", 40))
        g.conexaoEstabelecida(InfoConexao(protocolo = "AUTO, ISO 15765-4 (CAN 11/500)", nomeModo = "Modo 21 (M-OBD / KWP)"))
        g.iniciarCsv(colunas)
        g.registrar(dados(780.0, 0.0, 98.0, Pids.TENSAO_BATERIA to 12.6, Pids.TEMPERATURA_MOTOR to 40.0), inicio + 6_000)
        g.registrar(dados(3420.0, 95.0, 245.0, Pids.TENSAO_BATERIA to 14.3, Pids.PRESSAO_RAIL to 145_000.0, Pids.TEMPERATURA_MOTOR to 92.0), inicio + 60_000)
        g.registrar(dados(2100.0, 118.0, 160.0, Pids.TENSAO_BATERIA to 12.1, Pids.PRESSAO_RAIL to 90_000.0, Pids.TEMPERATURA_MOTOR to 88.0), inicio + 120_000)
        g.registrarDtcs(
            DtcsSessao(
                confirmados = listOf(DtcLido("P0087", "Pressão do rail de combustível muito baixa")),
                pendentes = emptyList(),
                permanentes = null,
            ),
        )
        g.registrarEntrada(EntradaRegistro.Evento(inicio + 2_559_000, "Conexão perdida: A ECU parou de responder"))
        val salvos = g.finalizar(inicio + 2_559_000, conectou = true, motivoFim = "A ECU parou de responder. A ignição foi desligada?")

        val arquivo = destino.terminadoEm(".txt")
        assertEquals("CharruaOBD_20261122_143005_Toyota_SW4.txt", arquivo.nome)
        assertEquals(DestinoLogs.MIME_TXT, arquivo.mime)
        assertTrue(arquivo.fechado)
        assertEquals(2, salvos.arquivos.size)
        assertTrue(salvos.erros.isEmpty())
        val txt = arquivo.conteudo

        // Cabeçalho
        listOf(
            "Veículo", "Toyota SW4 2015",
            "Placa", "ABC-1D23",
            "Modo de início", "Automático (Bluetooth do carro: Toyota SW4)",
            "Início", "22/11/2026 14:30:05",
            "Fim", "22/11/2026 15:12:44",
            "Duração", "42 min 39 s",
            "Resultado", "Conectado",
            "Protocolo", "AUTO, ISO 15765-4 (CAN 11/500)",
            "Camada", "Modo 21 (M-OBD / KWP)",
            "Tensão inicial", "12,6 V",
        ).chunked(2).forEach { (rotulo, valor) -> assertTrue("$rotulo: $valor", linha(txt, rotulo).endsWith(": $valor")) }
        assertTrue(linha(txt, "Telemetria (CSV)").endsWith("CharruaOBD_20261122_143005_Toyota_SW4.csv (3 amostras)"))
        assertTrue(linha(txt, "Encerramento").endsWith("A ignição foi desligada?"))

        // DTCs
        assertTrue(txt.contains("Confirmados (modo 03): 1\n    • P0087 — Pressão do rail de combustível muito baixa"))
        assertTrue(txt.contains("Pendentes (modo 07): nenhum"))
        assertTrue(txt.contains("Permanentes (modo 0A): não lido / não suportado"))

        // Resumo da viagem
        assertTrue(linha(txt, "RPM máximo").endsWith(": 3.420 rpm"))
        assertTrue(linha(txt, "Velocidade máxima").endsWith(": 118 km/h"))
        assertTrue(linha(txt, "Pico de MAP/Turbo").endsWith(": 245 kPa (≈ +1,44 bar de turbo)"))
        assertTrue(linha(txt, "Temperatura máx. do motor").endsWith(": 92 °C"))
        assertTrue(linha(txt, "Pressão máx. Common Rail").endsWith(": 145.000 kPa (1.450 bar)"))
        assertTrue(linha(txt, "Tensão mín. / máx.").endsWith(": 12,1 V / 14,3 V"))

        // Log bruto completo, depois do resumo e na ordem
        val bruto = txt.substringAfter(RelatorioSessaoTxt.TITULO_LOG_BRUTO)
        assertTrue(txt.indexOf("RESUMO DA VIAGEM") < txt.indexOf(RelatorioSessaoTxt.TITULO_LOG_BRUTO))
        val ordem = listOf(
            "14:30:05.000  ● Sessão: Aguardando 4 s",
            "14:30:09.100  ATZ → \"\\r\\rELM327 v1.5\\r\\r\" (812 ms)",
            "14:30:09.900  ATRV → \"12.6V\\r\\r\" (40 ms)",
            "15:12:44.000  ● Conexão perdida: A ECU parou de responder",
        ).map { bruto.indexOf(it) }
        assertTrue("log bruto fora de ordem: $ordem\n$bruto", ordem.all { it >= 0 } && ordem == ordem.sorted())
        assertTrue(txt.trimEnd().endsWith("— fim do relatório (4 linhas de log) —"))

        // O temporário do log bruto some depois de copiado.
        assertTrue(tmp.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `falha no handshake tambem gera o txt com o log bruto`() {
        val g = gravador()
        g.registrarEntrada(EntradaRegistro.Comando(inicio + 500, "ATZ", "\r\rELM327 v2.1\r\r", 900))
        g.registrarEntrada(EntradaRegistro.Comando(inicio + 7_000, "0100", "SEARCHING...\rUNABLE TO CONNECT\r\r", 6_400))
        g.evento("Tentativa 3/3 falhou: Scanner conectado, mas a ECU não respondeu", inicio + 15_000)
        val salvos = g.finalizar(inicio + 15_000, conectou = false, motivoFim = "Falha na conexão: a ECU não respondeu")

        assertNull(destino.algumTerminadoEm(".csv"))
        assertEquals(listOf("CharruaOBD_20261122_143005_Toyota_SW4.txt"), salvos.arquivos.map { it.nome })
        val txt = destino.terminadoEm(".txt").conteudo
        assertTrue(linha(txt, "Resultado").endsWith(": Falha no handshake (não conectou)"))
        assertTrue(linha(txt, "Telemetria (CSV)").endsWith(": não gerado"))
        assertTrue(txt.contains("Não lidos nesta sessão."))
        assertTrue(txt.contains("Nenhuma leitura de telemetria nesta sessão."))
        assertTrue(txt.contains("0100 → \"SEARCHING...\\rUNABLE TO CONNECT\\r\\r\" (6400 ms)"))
        assertTrue(txt.contains("Sessão: Tentativa 3/3 falhou"))
    }

    @Test
    fun `finalizar uma segunda vez nao reescreve nem aceita novas entradas`() {
        val g = gravador()
        g.finalizar(inicio + 1_000, conectou = false, motivoFim = "x")
        g.registrarEntrada(EntradaRegistro.Evento(inicio + 2_000, "atrasada"))
        val segunda = g.finalizar(inicio + 3_000, conectou = false, motivoFim = "y")
        assertTrue(segunda.arquivos.isEmpty())
        assertEquals(1, destino.arquivos.size)
        assertFalse(destino.terminadoEm(".txt").conteudo.contains("atrasada"))
    }

    @Test
    fun `duracao formatada`() {
        assertEquals("35 s", RelatorioSessaoTxt.duracao(35_400))
        assertEquals("42 min 39 s", RelatorioSessaoTxt.duracao(2_559_000))
        assertEquals("1 h 02 min 05 s", RelatorioSessaoTxt.duracao(3_725_000))
    }

    /** A linha do relatório cujo rótulo (preenchido com pontos) é [rotulo]. */
    private fun linha(txt: String, rotulo: String): String =
        txt.lines().firstOrNull { it.trimStart().startsWith("$rotulo.") || it.trimStart().startsWith("$rotulo:") }
            ?: error("linha \"$rotulo\" ausente:\n$txt")
}
