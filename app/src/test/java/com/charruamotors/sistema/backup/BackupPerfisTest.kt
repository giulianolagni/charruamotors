package com.charruamotors.sistema.backup

import com.charruamotors.carros.CamadaPreferida
import com.charruamotors.carros.PerfilCarro
import com.charruamotors.direcao.desempenho.RecordeDesempenho
import com.charruamotors.direcao.painel.CodecPainel
import com.charruamotors.direcao.painel.ConfigHud
import com.charruamotors.direcao.painel.ConfigPainel
import com.charruamotors.direcao.painel.PresetHud
import com.charruamotors.direcao.painel.SecaoPainel
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.direcao.painel.TamanhoHudEsportivo
import com.charruamotors.domain.model.Abastecimento
import com.charruamotors.domain.model.CodigoFalha
import com.charruamotors.domain.model.LeituraObd
import com.charruamotors.domain.model.Manutencao
import com.charruamotors.domain.model.PecaTrocada
import com.charruamotors.domain.model.StatusCodigoFalha
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.TipoManutencao
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.sessao.ConfigAutoConexao
import com.charruamotors.sistema.preferencias.TemaApp
import com.charruamotors.sistema.unidades.UnidadeConsumo
import com.charruamotors.sistema.unidades.UnidadePressao
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * v2.3.0 — backup `.json` formato 2: perfis isolados de cada carro (Painel/HUD, Cluster, perfil
 * técnico, recordes), unidades globais e o carro ativo; validação de arquivos corrompidos; backups
 * v1 e a mesclagem sem conflito de IDs.
 */
class BackupPerfisTest {

    private val sw4 = Veiculo(id = 7, marca = "Toyota", modelo = "SW4", ano = 2013, placa = "ABC1D23", kmAtual = 182_450, tipoCombustivel = TipoCombustivel.DIESEL, calibracaoConsumo = 1.07)
    private val cruze = Veiculo(id = 9, marca = "Chevrolet", modelo = "Cruze", ano = 2018, placa = "CRZ1A18", kmAtual = 60_000, tipoCombustivel = TipoCombustivel.FLEX)

    private val painelSw4 = CodecPainel.normalizar(
        ConfigPainel().ocultar(SecaoPainel.MUSICA).adicionarMostrador()
            .copy(hud = ConfigHud(preset = PresetHud.ESPORTIVO, tamanhoEsportivo = TamanhoHudEsportivo.GRANDE)),
    )
    private val painelCruze = CodecPainel.normalizar(ConfigPainel().aplicarLayout(ConfigPainel.LAYOUT_TURBO_FLEX))

    private val perfilSw4 = PerfilCarro(
        veiculoId = 7,
        painel = painelSw4,
        cluster = listOf(SensorPainel.TEMP_CAMBIO, null, SensorPainel.TURBO, SensorPainel.TENSAO),
        camada = CamadaPreferida.MODO_21,
        gatilhoNome = "Toyota SW4",
        gatilhoEndereco = "AA:BB:CC:DD:EE:01",
        favoritosGraficos = listOf("010C", "21D9"),
        recorde = RecordeDesempenho(9.4, 14.1),
    )
    private val perfilCruze = PerfilCarro(veiculoId = 9, painel = painelCruze, perfilCambioId = "gm_6t35", recorde = RecordeDesempenho(7.6, null))

    private val dados = DadosBackup(
        geradoEm = "2026-11-25T09:00:00Z",
        versaoApp = "2.3.0 (30)",
        veiculos = listOf(sw4, cruze),
        manutencoes = listOf(Manutencao(id = 3, veiculoId = 9, data = LocalDate.of(2026, 10, 2), km = 58_000, tipo = TipoManutencao.entries.first(), descricao = "Óleo", oficina = null, custoTotal = 420.0)),
        pecas = listOf(PecaTrocada(id = 11, manutencaoId = 3, nome = "Filtro", quantidade = 1, valorUnitario = 60.0)),
        abastecimentos = listOf(Abastecimento(id = 21, veiculoId = 9, data = LocalDate.of(2026, 11, 20), km = 59_900, litros = 40.0, valorTotal = 230.0, tipoCombustivel = TipoCombustivel.FLEX, tanqueCheio = true)),
        leituras = listOf(LeituraObd(id = 31, veiculoId = 9, dataHora = LocalDateTime.of(2026, 11, 22, 8, 30), rpm = 800, velocidade = 0, temperaturaMotor = 90, cargaMotor = 20.0, tensaoBateria = 14.1)),
        codigosFalha = listOf(CodigoFalha(id = 41, veiculoId = 9, dataHora = LocalDateTime.of(2026, 9, 1, 14, 0), codigo = "P0300", descricao = null, status = StatusCodigoFalha.entries.first())),
        preferencias = PreferenciasBackup(
            caixaPreta = ConfigAutoConexao(veiculoId = 7, veiculoDescricao = "Toyota SW4 2013"),
            tema = TemaApp.ESCURO,
            unidadePressao = UnidadePressao.PSI,
            unidadeConsumo = UnidadeConsumo.L_100KM,
        ),
        perfisCarros = listOf(perfilSw4, perfilCruze),
        veiculoAtivoId = 9,
    )

    // ---------------------------------------------------------------- exportação / importação

    @Test
    fun `ida e volta preserva perfis isolados unidades e carro ativo`() {
        val json = BackupJson.serializar(dados)
        val lido = BackupJson.desserializar(json)
        assertEquals(dados, lido)
        assertEquals(perfilSw4, lido.perfisCarros.first { it.veiculoId == 7L })
        assertEquals(PresetHud.ESPORTIVO, lido.perfisCarros.first { it.veiculoId == 7L }.painel.hud.preset)
        assertEquals(UnidadeConsumo.L_100KM, lido.preferencias.unidadeConsumo)
        assertEquals(1.07, lido.veiculos.first { it.id == 7L }.calibracaoConsumo, 1e-9)

        val raiz = JSONObject(json)
        assertEquals(2, raiz.getInt("versaoFormato"))
        assertEquals(2, raiz.getJSONArray("perfisCarros").length())
        assertEquals(9L, raiz.getLong("veiculoAtivoId"))
        assertEquals("MODO_21", raiz.getJSONArray("perfisCarros").getJSONObject(0).getJSONObject("perfil").getString("camada"))
    }

    @Test
    fun `arquivo corrompido ou de outro app vira mensagem sem derrubar`() {
        val json = BackupJson.serializar(dados)
        val idTexto = JSONObject(json).apply { getJSONArray("perfisCarros").getJSONObject(0).put("veiculoId", "sete") }.toString()
        val enumDesconhecido = JSONObject(json).apply { getJSONArray("veiculos").getJSONObject(0).put("tipoCombustivel", "PLASMA") }.toString()
        val perfilNaoObjeto = JSONObject(json).apply { put("perfisCarros", org.json.JSONArray(listOf("x"))) }.toString()
        val casos = listOf(
            "" to "JSON válido",
            "isto não é json" to "JSON válido",
            json.take(json.length / 2) to "JSON válido",
            """{"tipo":"outro-app","versaoFormato":2}""" to "não é um backup",
            """{"tipo":"${BackupJson.TIPO}","versaoFormato":3}""" to "versão mais nova",
            idTexto to "corrompido",
            enumDesconhecido to "corrompido",
            perfilNaoObjeto to "corrompido",
        )
        for ((texto, esperado) in casos) {
            try {
                BackupJson.desserializar(texto)
                fail("deveria recusar: ${texto.take(40)}")
            } catch (e: BackupInvalidoException) {
                assertTrue("${e.message} deveria conter '$esperado'", e.message!!.contains(esperado))
            }
        }
    }

    @Test
    fun `perfis invalidos sao recusados na validacao`() {
        fun comPerfis(vararg perfis: JSONObject): String {
            val raiz = JSONObject(BackupJson.serializar(dados.copy(perfisCarros = emptyList())))
            raiz.put("perfisCarros", org.json.JSONArray(perfis.toList()))
            return raiz.toString()
        }
        fun perfil(id: Long, schema: Int = 1) = JSONObject().put("veiculoId", id).put("schema", schema).put("painel", JSONObject()).put("perfil", JSONObject())

        assertInvalido(comPerfis(perfil(55)), "não está no arquivo")
        assertInvalido(comPerfis(perfil(7), perfil(7)), "mesmo veículo")
        assertInvalido(comPerfis(perfil(7, schema = 99)), "versão mais nova")
        assertInvalido(comPerfis(perfil(0)), "perfil sem veículo")
        // Perfil mínimo (só o id) é válido: o resto volta ao padrão.
        val minimo = BackupJson.desserializar(comPerfis(JSONObject().put("veiculoId", 9)))
        assertEquals(PerfilCarro(9), minimo.perfisCarros.single())
    }

    @Test
    fun `backup v1 sem perfis manda o painel global para o carro padrao`() {
        val v1 = """
            {"tipo":"${BackupJson.TIPO}","versaoFormato":1,"geradoEm":"2026-11-01T00:00:00Z",
             "veiculos":[
               {"id":7,"marca":"Toyota","modelo":"SW4","ano":2013,"placa":"ABC1D23","kmAtual":1000,"tipoCombustivel":"DIESEL"},
               {"id":9,"marca":"Chevrolet","modelo":"Cruze","ano":2018,"placa":"CRZ1A18","kmAtual":500,"tipoCombustivel":"FLEX"}],
             "preferencias":{"caixaPreta":{"veiculoId":9},"painel":${JSONObject(CodecPainel.codificar(painelSw4))}}}
        """.trimIndent()
        val lido = BackupJson.desserializar(v1)
        assertTrue(lido.perfisCarros.isEmpty())
        assertEquals(UnidadeConsumo.KM_L, lido.preferencias.unidadeConsumo)

        val completo = MesclaBackup.perfisDe(lido)
        assertEquals(painelSw4, completo.perfisCarros.first { it.veiculoId == 9L }.painel) // o favorito do arquivo
        assertEquals(ConfigPainel(), completo.perfisCarros.first { it.veiculoId == 7L }.painel)
        assertEquals(9L, completo.veiculoAtivoId)
        // Formato 2 já com perfis: nada muda.
        assertSame(dados, MesclaBackup.perfisDe(dados))
    }

    // ---------------------------------------------------------------- mesclagem

    @Test
    fun `mesclar acrescenta so os carros novos renumerando tudo`() {
        // No app: só a SW4 (id 1) com perfil próprio, e uma manutenção/abastecimento com ids que colidem com os do arquivo.
        val sw4NoApp = sw4.copy(id = 1, kmAtual = 190_000)
        val atual = DadosBackup(
            geradoEm = "agora",
            veiculos = listOf(sw4NoApp),
            manutencoes = listOf(Manutencao(id = 3, veiculoId = 1, data = LocalDate.of(2026, 1, 1), km = 185_000, tipo = TipoManutencao.entries.first(), descricao = null, oficina = null, custoTotal = 10.0)),
            abastecimentos = listOf(Abastecimento(id = 21, veiculoId = 1, data = LocalDate.of(2026, 1, 2), km = 186_000, litros = 50.0, valorTotal = 300.0, tipoCombustivel = TipoCombustivel.DIESEL, tanqueCheio = false)),
            preferencias = PreferenciasBackup(tema = TemaApp.CLARO),
            perfisCarros = listOf(PerfilCarro(1, camada = CamadaPreferida.OBD2)),
            veiculoAtivoId = 1,
        )
        assertEquals(listOf(cruze), MesclaBackup.veiculosNovos(atual, dados))

        val m = MesclaBackup.mesclar(atual, dados)
        // A SW4 do app (mesma placa) fica intacta, com o perfil dela; nada do arquivo a sobrescreve.
        assertEquals(sw4NoApp, m.veiculos.first { it.placa == sw4.placa })
        assertEquals(CamadaPreferida.OBD2, m.perfisCarros.first { it.veiculoId == 1L }.camada)
        assertEquals(TemaApp.CLARO, m.preferencias.tema)
        assertEquals(1L, m.veiculoAtivoId)

        // O Cruze entra com id livre e todos os filhos renumerados e religados.
        val novoCruze = m.veiculos.first { it.placa == cruze.placa }
        assertEquals(2L, novoCruze.id)
        assertEquals(2, m.manutencoes.size)
        val manutencaoCruze = m.manutencoes.first { it.veiculoId == 2L }
        assertEquals(4L, manutencaoCruze.id)
        assertEquals(manutencaoCruze.id, m.pecas.single().manutencaoId)
        assertEquals(listOf(21L, 22L), m.abastecimentos.map { it.id })
        assertEquals(2L, m.abastecimentos.last().veiculoId)
        assertEquals(2L, m.leituras.single().veiculoId)
        assertEquals(2L, m.codigosFalha.single().veiculoId)
        assertEquals(painelCruze, m.perfisCarros.first { it.veiculoId == 2L }.painel)
        assertEquals("gm_6t35", m.perfisCarros.first { it.veiculoId == 2L }.perfilCambioId)

        // O resultado é um backup consistente (passa pela mesma validação do arquivo).
        assertEquals(m, BackupJson.desserializar(BackupJson.serializar(m)))
        // Mesclar de novo não duplica nada.
        assertEquals(m, MesclaBackup.mesclar(m, dados))
    }

    private fun assertInvalido(json: String, trecho: String) {
        try {
            BackupJson.desserializar(json)
            fail("deveria recusar")
        } catch (e: BackupInvalidoException) {
            assertTrue("${e.message} deveria conter '$trecho'", e.message!!.contains(trecho))
        }
    }
}
