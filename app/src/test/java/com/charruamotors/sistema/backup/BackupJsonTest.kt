package com.charruamotors.sistema.backup

import com.charruamotors.domain.model.Abastecimento
import com.charruamotors.domain.model.CodigoFalha
import com.charruamotors.domain.model.LeituraObd
import com.charruamotors.domain.model.Manutencao
import com.charruamotors.domain.model.PecaTrocada
import com.charruamotors.domain.model.StatusCodigoFalha
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.TipoManutencao
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.sessao.ConfigAutoConexao
import com.charruamotors.sistema.preferencias.TemaApp
import com.charruamotors.sistema.unidades.UnidadePressao
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Serialização e desserialização do Backup/Restore (.json). */
class BackupJsonTest {

    private val sw4 = Veiculo(
        id = 7, marca = "Toyota", modelo = "SW4", ano = 2013, placa = "ABC1D23", cor = "Prata",
        kmAtual = 182_450, tipoCombustivel = TipoCombustivel.DIESEL, capacidadeTanque = 80.0,
    )
    private val gol = Veiculo(id = 9, marca = "VW", modelo = "Gol", ano = 2010, placa = "XYZ1234", kmAtual = 98_000, tipoCombustivel = TipoCombustivel.FLEX)

    private val completo = DadosBackup(
        geradoEm = "2026-11-23T10:15:30Z",
        versaoApp = "1.3 (4)",
        veiculos = listOf(sw4, gol),
        manutencoes = listOf(
            Manutencao(id = 3, veiculoId = 7, data = LocalDate.of(2026, 10, 2), km = 180_000, tipo = TipoManutencao.entries.first(), descricao = "Troca de óleo 15W40", oficina = null, custoTotal = 650.5),
        ),
        pecas = listOf(PecaTrocada(id = 11, manutencaoId = 3, nome = "Filtro de óleo", quantidade = 1, valorUnitario = 89.9)),
        abastecimentos = listOf(
            Abastecimento(id = 21, veiculoId = 7, data = LocalDate.of(2026, 11, 20), km = 182_400, litros = 62.35, valorTotal = 389.6, tipoCombustivel = TipoCombustivel.DIESEL, tanqueCheio = true),
        ),
        leituras = listOf(
            LeituraObd(id = 31, veiculoId = 7, dataHora = LocalDateTime.of(2026, 11, 22, 8, 30, 5), rpm = 780, velocidade = 0, temperaturaMotor = 88, cargaMotor = 21.5, tensaoBateria = null),
        ),
        codigosFalha = listOf(
            CodigoFalha(id = 41, veiculoId = 9, dataHora = LocalDateTime.of(2026, 9, 1, 14, 0), codigo = "P0300", descricao = "Falha de ignição aleatória", status = StatusCodigoFalha.entries.last()),
        ),
        preferencias = PreferenciasBackup(
            caixaPreta = ConfigAutoConexao(
                autoConexaoAtiva = false,
                gravarLogsAutomaticos = true,
                gatilhoNome = "Toyota SW4",
                gatilhoEndereco = "AA:BB:CC:DD:EE:01",
                scanner = DispositivoObd("OBDII", "00:1D:A5:68:98:8B"),
                veiculoId = 7,
                veiculoDescricao = "Toyota SW4 2013",
            ),
            tema = TemaApp.ESCURO,
            unidadePressao = UnidadePressao.BAR,
        ),
    )

    @Test
    fun `ida e volta preserva todas as tabelas e preferencias`() {
        val json = BackupJson.serializar(completo)
        assertEquals(completo, BackupJson.desserializar(json))
    }

    @Test
    fun `json identifica o app e a versao do formato`() {
        val raiz = JSONObject(BackupJson.serializar(completo))
        assertEquals(BackupJson.TIPO, raiz.getString("tipo"))
        assertEquals(BackupJson.VERSAO_FORMATO, raiz.getInt("versaoFormato"))
        assertEquals("1.3 (4)", raiz.getString("versaoApp"))
        assertEquals("2026-10-02", raiz.getJSONArray("manutencoes").getJSONObject(0).getString("data"))
        assertEquals("BAR", raiz.getJSONObject("preferencias").getString("unidadePressao"))
        assertEquals("OBDII", raiz.getJSONObject("preferencias").getJSONObject("caixaPreta").getString("scannerNome"))
    }

    @Test
    fun `campos opcionais nulos voltam nulos`() {
        val dados = BackupJson.desserializar(BackupJson.serializar(completo))
        assertNull(dados.veiculos.first { it.id == 9L }.cor)
        assertNull(dados.veiculos.first { it.id == 9L }.capacidadeTanque)
        assertNull(dados.manutencoes.single().oficina)
        assertNull(dados.leituras.single().tensaoBateria)
    }

    @Test
    fun `backup vazio e valido`() {
        val vazio = DadosBackup(geradoEm = "2026-11-23T10:00:00Z")
        assertEquals(vazio, BackupJson.desserializar(BackupJson.serializar(vazio)))
    }

    @Test
    fun `sem bloco de preferencias usa os padroes da caixa-preta`() {
        val json = """{"tipo":"${BackupJson.TIPO}","versaoFormato":1,"geradoEm":"x","veiculos":[]}"""
        val dados = BackupJson.desserializar(json)
        assertEquals(ConfigAutoConexao(), dados.preferencias.caixaPreta)
        assertEquals(TemaApp.SISTEMA, dados.preferencias.tema)
        assertEquals(UnidadePressao.KPA, dados.preferencias.unidadePressao)
    }

    @Test
    fun `arquivo que nao e JSON e recusado`() {
        assertInvalido("isto não é json", "JSON válido")
    }

    @Test
    fun `JSON de outro app e recusado`() {
        assertInvalido("""{"tipo":"outro","versaoFormato":1}""", "não é um backup")
    }

    @Test
    fun `formato mais novo pede atualizar o app`() {
        assertInvalido("""{"tipo":"${BackupJson.TIPO}","versaoFormato":99}""", "versão mais nova")
    }

    @Test
    fun `enum desconhecido vira backup corrompido`() {
        val json = BackupJson.serializar(completo).replace("\"DIESEL\"", "\"HIDROGENIO\"")
        assertInvalido(json, "corrompido")
    }

    @Test
    fun `registro orfao e recusado antes de tocar no banco`() {
        val orfao = completo.copy(abastecimentos = completo.abastecimentos.map { it.copy(veiculoId = 999) })
        assertInvalido(BackupJson.serializar(orfao), "corrompido")
        val pecaOrfa = completo.copy(pecas = completo.pecas.map { it.copy(manutencaoId = 999) })
        assertInvalido(BackupJson.serializar(pecaOrfa), "corrompido")
    }

    @Test
    fun `veiculo padrao inexistente nao e restaurado`() {
        assertEquals(7L, completo.caixaPretaRestauravel().veiculoId)
        val semSw4 = completo.copy(
            veiculos = listOf(gol),
            manutencoes = emptyList(),
            pecas = emptyList(),
            abastecimentos = emptyList(),
            leituras = emptyList(),
        )
        val caixa = semSw4.caixaPretaRestauravel()
        assertEquals(0L, caixa.veiculoId)
        assertNull(caixa.veiculoDescricao)
        assertEquals("OBDII", caixa.scanner?.nome)
    }

    @Test
    fun `resumo conta os registros`() {
        val resumo = ResumoBackup.de(completo)
        assertEquals(2, resumo.veiculos)
        assertEquals(1, resumo.manutencoes)
        assertEquals(1, resumo.abastecimentos)
        assertEquals(1, resumo.leituras)
        assertEquals(1, resumo.codigosFalha)
    }

    @Test
    fun `nome sugerido leva a data`() {
        assertEquals("charrua-obd-pro-backup-2026-11-23.json", BackupRepository.nomeSugerido(LocalDate.of(2026, 11, 23)))
    }

    private fun assertInvalido(json: String, trecho: String) {
        try {
            BackupJson.desserializar(json)
            fail("Deveria recusar: $json")
        } catch (e: BackupInvalidoException) {
            assertTrue("Mensagem \"${e.message}\" deveria conter \"$trecho\"", e.message.orEmpty().contains(trecho))
        }
    }
}
