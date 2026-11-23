package com.charruamotors.obd.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Logs reais x SIMULADO e ordenação do mais recente (última viagem) para o mais antigo. */
class OrganizadorLogsTest {

    private val zona = ZoneOffset.UTC

    private data class Log(override val nome: String, override val modificadoMs: Long = 0) : ItemLog

    private fun ms(ano: Int, mes: Int, dia: Int, h: Int, m: Int, s: Int) =
        LocalDateTime.of(ano, mes, dia, h, m, s).toInstant(zona).toEpochMilli()

    private val real1 = Log("CharruaOBD_20261120_080000_Toyota_SW4.csv")
    private val real1Txt = Log("CharruaOBD_20261120_080000_Toyota_SW4.txt")
    private val real2 = Log("CharruaOBD_20261122_143005_Toyota_SW4.csv")
    private val real2Txt = Log("CharruaOBD_20261122_143005_Toyota_SW4.txt")
    private val sim1 = Log("CharruaOBD_SIMULADO_20261121_100000_Toyota_SW4.csv")
    private val sim2 = Log("CharruaOBD_SIMULADO_20261123_090000_Toyota_SW4.txt")
    private val dataLogger = Log("charrua_obd_2026-11-21_18-45-10.csv")
    private val dataLoggerSim = Log("charrua_obd_SIMULADO_2026-11-19_07-00-00.csv")

    @Test
    fun `identifica logs simulados pelo nome`() {
        assertTrue(OrganizadorLogs.ehSimulado(sim1.nome))
        assertTrue(OrganizadorLogs.ehSimulado(sim2.nome))
        assertTrue(OrganizadorLogs.ehSimulado(dataLoggerSim.nome))
        assertFalse(OrganizadorLogs.ehSimulado(real1.nome))
        assertFalse(OrganizadorLogs.ehSimulado(real2Txt.nome))
        assertFalse(OrganizadorLogs.ehSimulado(dataLogger.nome))
        assertFalse(OrganizadorLogs.ehSimulado("CharruaOBD_20261122_143005_Carro.csv"))
    }

    @Test
    fun `le o horario gravado no nome dos dois formatos`() {
        assertEquals(ms(2026, 11, 22, 14, 30, 5), OrganizadorLogs.instanteDoNome(real2.nome, zona))
        assertEquals(ms(2026, 11, 23, 9, 0, 0), OrganizadorLogs.instanteDoNome(sim2.nome, zona))
        assertEquals(ms(2026, 11, 21, 18, 45, 10), OrganizadorLogs.instanteDoNome(dataLogger.nome, zona))
        assertEquals(ms(2026, 11, 19, 7, 0, 0), OrganizadorLogs.instanteDoNome(dataLoggerSim.nome, zona))
        assertNull(OrganizadorLogs.instanteDoNome("meu_log.csv", zona))
        assertNull("data impossível não derruba a ordenação", OrganizadorLogs.instanteDoNome("CharruaOBD_20269999_999999_X.csv", zona))
    }

    @Test
    fun `ordena do mais recente para o mais antigo`() {
        val ordenado = OrganizadorLogs.ordenar(listOf(real1, sim1, real2, sim2, dataLogger, dataLoggerSim), zona)
        // 23/11 > 22/11 > 21/11 18:45 > 21/11 10:00 > 20/11 > 19/11
        assertEquals(listOf(sim2, real2, dataLogger, sim1, real1, dataLoggerSim), ordenado)
    }

    @Test
    fun `csv e txt da mesma sessao ficam juntos e a ordem nao depende da data de modificacao`() {
        // DATE_MODIFIED trocado de propósito (arquivo antigo copiado/reindexado depois): vale o horário do nome.
        val antigoReindexado = real1.copy(modificadoMs = ms(2026, 12, 1, 0, 0, 0))
        val novo = real2.copy(modificadoMs = ms(2026, 11, 22, 14, 31, 0))
        val ordenado = OrganizadorLogs.ordenar(listOf(antigoReindexado, real2Txt, novo, real1Txt), zona)
        assertEquals(listOf(real2Txt.nome, novo.nome, real1Txt.nome, antigoReindexado.nome), ordenado.map { it.nome })
    }

    @Test
    fun `sem horario no nome usa a data de modificacao`() {
        val a = Log("exportado_a.csv", modificadoMs = ms(2026, 11, 10, 10, 0, 0))
        val b = Log("exportado_b.csv", modificadoMs = ms(2026, 11, 12, 10, 0, 0))
        val c = Log("CharruaOBD_20261111_120000_X.csv")
        assertEquals(listOf(b, c, a), OrganizadorLogs.ordenar(listOf(a, b, c), zona))
    }

    @Test
    fun `separa reais de simulados mantendo cada grupo do mais novo para o mais antigo`() {
        val (reais, simulados) = OrganizadorLogs.separar(listOf(sim1, real1, real2Txt, sim2, real2, real1Txt), zona)
        assertEquals(listOf(real2Txt, real2, real1Txt, real1), reais)
        assertEquals(listOf(sim2, sim1), simulados)
    }

    @Test
    fun `lista mostra a pasta Simulados no topo recolhida e so os reais abaixo`() {
        val linhas = OrganizadorLogs.montarLinhas(listOf(sim1, real1, sim2, real2), expandido = false, zona = zona)
        assertEquals(
            listOf(
                LinhaLog.CabecalhoSimulados(quantidade = 2, expandido = false),
                LinhaLog.Arquivo(real2, simulado = false),
                LinhaLog.Arquivo(real1, simulado = false),
            ),
            linhas,
        )
    }

    @Test
    fun `lista expandida mostra os simulados com selo logo abaixo do cabecalho`() {
        val linhas = OrganizadorLogs.montarLinhas(listOf(sim1, real1, sim2, real2), expandido = true, zona = zona)
        assertEquals(
            listOf(
                LinhaLog.CabecalhoSimulados(quantidade = 2, expandido = true),
                LinhaLog.Arquivo(sim2, simulado = true),
                LinhaLog.Arquivo(sim1, simulado = true),
                LinhaLog.Arquivo(real2, simulado = false),
                LinhaLog.Arquivo(real1, simulado = false),
            ),
            linhas,
        )
    }

    @Test
    fun `sem logs simulados nao ha cabecalho`() {
        val linhas = OrganizadorLogs.montarLinhas(listOf(real1, real2), expandido = true, zona = zona)
        assertEquals(listOf(LinhaLog.Arquivo(real2, simulado = false), LinhaLog.Arquivo(real1, simulado = false)), linhas)
        assertTrue(OrganizadorLogs.montarLinhas(emptyList<Log>(), expandido = false, zona = zona).isEmpty())
    }
}
