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
import com.charruamotors.direcao.painel.CodecPainel
import com.charruamotors.direcao.painel.ConfigPainel
import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.sessao.ConfigAutoConexao
import com.charruamotors.sistema.preferencias.TemaApp
import com.charruamotors.sistema.unidades.SistemaUnidades
import com.charruamotors.sistema.unidades.UnidadePressao
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/** Preferências que vão junto do banco no backup (caixa-preta, aparência, unidades e Painel). */
data class PreferenciasBackup(
    val caixaPreta: ConfigAutoConexao = ConfigAutoConexao(),
    val tema: TemaApp = TemaApp.PADRAO,
    val unidadePressao: UnidadePressao = UnidadePressao.PADRAO,
    /** Unidades globais (v1.5; backups antigos = métrico). */
    val sistemaUnidades: SistemaUnidades = SistemaUnidades.PADRAO,
    /** Personalização do Painel e do HUD (v1.5); null = backup antigo, a restauração não mexe nela. */
    val painel: ConfigPainel? = null,
)

/**
 * Conteúdo completo de um backup: todas as tabelas do banco local ligadas aos veículos (os
 * usuários do login simulado ficam de fora) e as preferências. Os IDs originais são mantidos,
 * para as chaves estrangeiras e o veículo padrão da caixa-preta continuarem apontando certo.
 */
data class DadosBackup(
    val versaoFormato: Int = BackupJson.VERSAO_FORMATO,
    /** Instante da exportação, ISO-8601 (`2026-11-23T10:15:30Z`). */
    val geradoEm: String,
    /** `1.3 (4)`. */
    val versaoApp: String? = null,
    val veiculos: List<Veiculo> = emptyList(),
    val manutencoes: List<Manutencao> = emptyList(),
    val pecas: List<PecaTrocada> = emptyList(),
    val abastecimentos: List<Abastecimento> = emptyList(),
    val leituras: List<LeituraObd> = emptyList(),
    val codigosFalha: List<CodigoFalha> = emptyList(),
    val preferencias: PreferenciasBackup = PreferenciasBackup(),
) {
    /** Caixa-preta a restaurar: o veículo padrão só vale se existir entre os veículos do backup. */
    fun caixaPretaRestauravel(): ConfigAutoConexao {
        val c = preferencias.caixaPreta
        return if (c.veiculoId == 0L || veiculos.any { it.id == c.veiculoId }) c else c.copy(veiculoId = 0, veiculoDescricao = null)
    }
}

/** Backup ilegível, de outro app ou de uma versão de formato mais nova. */
class BackupInvalidoException(mensagem: String, causa: Throwable? = null) : Exception(mensagem, causa)

/**
 * (De)serialização do backup em JSON com `org.json` (nativo no Android; nos testes JVM vem da
 * dependência `org.json:json`). Lógica pura: o acesso a arquivos (SAF) fica no `BackupRepository`.
 */
object BackupJson {

    const val TIPO = "charrua-obd-pro-backup"
    const val VERSAO_FORMATO = 1

    // ------------------------------------------------------------------ exportação

    fun serializar(dados: DadosBackup): String = JSONObject().apply {
        put("tipo", TIPO)
        put("versaoFormato", dados.versaoFormato)
        put("geradoEm", dados.geradoEm)
        putOpt("versaoApp", dados.versaoApp)
        put("veiculos", JSONArray(dados.veiculos.map(::veiculoJson)))
        put("manutencoes", JSONArray(dados.manutencoes.map(::manutencaoJson)))
        put("pecas", JSONArray(dados.pecas.map(::pecaJson)))
        put("abastecimentos", JSONArray(dados.abastecimentos.map(::abastecimentoJson)))
        put("leiturasObd", JSONArray(dados.leituras.map(::leituraJson)))
        put("codigosFalha", JSONArray(dados.codigosFalha.map(::codigoJson)))
        put("preferencias", preferenciasJson(dados.preferencias))
    }.toString(2)

    private fun veiculoJson(v: Veiculo) = JSONObject().apply {
        put("id", v.id)
        put("marca", v.marca)
        put("modelo", v.modelo)
        put("ano", v.ano)
        put("placa", v.placa)
        putOpt("cor", v.cor)
        put("kmAtual", v.kmAtual)
        put("tipoCombustivel", v.tipoCombustivel.name)
        putOpt("capacidadeTanque", v.capacidadeTanque)
        put("tempMaxAguaC", v.tempMaxAguaC)
        put("tempMaxAtfC", v.tempMaxAtfC)
        put("tensaoMinAlternadorV", v.tensaoMinAlternadorV)
        put("calibracaoConsumo", v.calibracaoConsumo)
    }

    private fun manutencaoJson(m: Manutencao) = JSONObject().apply {
        put("id", m.id)
        put("veiculoId", m.veiculoId)
        put("data", m.data.toString())
        put("km", m.km)
        put("tipo", m.tipo.name)
        putOpt("descricao", m.descricao)
        putOpt("oficina", m.oficina)
        put("custoTotal", m.custoTotal)
    }

    private fun pecaJson(p: PecaTrocada) = JSONObject().apply {
        put("id", p.id)
        put("manutencaoId", p.manutencaoId)
        put("nome", p.nome)
        put("quantidade", p.quantidade)
        put("valorUnitario", p.valorUnitario)
    }

    private fun abastecimentoJson(a: Abastecimento) = JSONObject().apply {
        put("id", a.id)
        put("veiculoId", a.veiculoId)
        put("data", a.data.toString())
        put("km", a.km)
        put("litros", a.litros)
        put("valorTotal", a.valorTotal)
        put("tipoCombustivel", a.tipoCombustivel.name)
        put("tanqueCheio", a.tanqueCheio)
    }

    private fun leituraJson(l: LeituraObd) = JSONObject().apply {
        put("id", l.id)
        put("veiculoId", l.veiculoId)
        put("dataHora", l.dataHora.toString())
        putOpt("rpm", l.rpm)
        putOpt("velocidade", l.velocidade)
        putOpt("temperaturaMotor", l.temperaturaMotor)
        putOpt("cargaMotor", l.cargaMotor)
        putOpt("tensaoBateria", l.tensaoBateria)
    }

    private fun codigoJson(c: CodigoFalha) = JSONObject().apply {
        put("id", c.id)
        put("veiculoId", c.veiculoId)
        put("dataHora", c.dataHora.toString())
        put("codigo", c.codigo)
        putOpt("descricao", c.descricao)
        put("status", c.status.name)
    }

    private fun preferenciasJson(p: PreferenciasBackup) = JSONObject().apply {
        val c = p.caixaPreta
        put("caixaPreta", JSONObject().apply {
            put("autoConexaoAtiva", c.autoConexaoAtiva)
            put("gravarLogsAutomaticos", c.gravarLogsAutomaticos)
            put("logCompletoTrafego", c.logCompletoTrafego)
            put("gatilhoNome", c.gatilhoNome)
            putOpt("gatilhoEndereco", c.gatilhoEndereco)
            putOpt("scannerNome", c.scanner?.nome)
            putOpt("scannerEndereco", c.scanner?.endereco)
            put("veiculoId", c.veiculoId)
            putOpt("veiculoDescricao", c.veiculoDescricao)
        })
        put("tema", p.tema.name)
        put("unidadePressao", p.unidadePressao.name)
        put("sistemaUnidades", p.sistemaUnidades.name)
        p.painel?.let { put("painel", JSONObject(CodecPainel.codificar(it))) }
    }

    // ------------------------------------------------------------------ importação

    /** Lê e valida um backup. Lança [BackupInvalidoException] com uma mensagem para o usuário. */
    fun desserializar(json: String): DadosBackup {
        val raiz = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw BackupInvalidoException("O arquivo não é um JSON válido", e)
        }
        if (raiz.optString("tipo") != TIPO) throw BackupInvalidoException("O arquivo não é um backup do Charrua OBD Pro")
        val versao = raiz.optInt("versaoFormato", 0)
        if (versao !in 1..VERSAO_FORMATO) {
            throw BackupInvalidoException("Backup de uma versão mais nova do app (formato $versao); atualize o app para restaurar")
        }
        val dados = try {
            DadosBackup(
                versaoFormato = versao,
                geradoEm = raiz.optString("geradoEm"),
                versaoApp = raiz.textoOuNull("versaoApp"),
                veiculos = raiz.lista("veiculos", ::veiculoDe),
                manutencoes = raiz.lista("manutencoes", ::manutencaoDe),
                pecas = raiz.lista("pecas", ::pecaDe),
                abastecimentos = raiz.lista("abastecimentos", ::abastecimentoDe),
                leituras = raiz.lista("leiturasObd", ::leituraDe),
                codigosFalha = raiz.lista("codigosFalha", ::codigoDe),
                preferencias = raiz.optJSONObject("preferencias")?.let(::preferenciasDe) ?: PreferenciasBackup(),
            )
        } catch (e: BackupInvalidoException) {
            throw e
        } catch (e: Exception) {
            // JSONException, DateTimeParseException, enum desconhecido, require do modelo...
            throw BackupInvalidoException("Backup corrompido: ${e.message}", e)
        }
        validarReferencias(dados)
        return dados
    }

    /** Toda linha filha precisa apontar para um veículo/manutenção do próprio backup. */
    private fun validarReferencias(d: DadosBackup) {
        val veiculos = d.veiculos.map { it.id }.toSet()
        if (veiculos.size != d.veiculos.size) throw BackupInvalidoException("Backup corrompido: veículos com ID repetido")
        val manutencoes = d.manutencoes.map { it.id }.toSet()
        val orfao = d.manutencoes.any { it.veiculoId !in veiculos } ||
            d.abastecimentos.any { it.veiculoId !in veiculos } ||
            d.leituras.any { it.veiculoId !in veiculos } ||
            d.codigosFalha.any { it.veiculoId !in veiculos } ||
            d.pecas.any { it.manutencaoId !in manutencoes }
        if (orfao) throw BackupInvalidoException("Backup corrompido: registros sem o veículo ou a manutenção correspondente")
    }

    private fun veiculoDe(o: JSONObject) = Veiculo(
        id = o.getLong("id"),
        marca = o.getString("marca"),
        modelo = o.getString("modelo"),
        ano = o.getInt("ano"),
        placa = o.getString("placa"),
        cor = o.textoOuNull("cor"),
        kmAtual = o.getInt("kmAtual"),
        tipoCombustivel = TipoCombustivel.valueOf(o.getString("tipoCombustivel")),
        capacidadeTanque = o.doubleOuNull("capacidadeTanque"),
        // Backups anteriores à v1.7 não têm os limites: voltam aos padrões.
        tempMaxAguaC = o.optInt("tempMaxAguaC", Veiculo.LIMITE_AGUA_PADRAO_C),
        tempMaxAtfC = o.optInt("tempMaxAtfC", Veiculo.LIMITE_ATF_PADRAO_C),
        tensaoMinAlternadorV = o.optDouble("tensaoMinAlternadorV", Veiculo.LIMITE_TENSAO_PADRAO_V),
        calibracaoConsumo = o.optDouble("calibracaoConsumo", Veiculo.CALIBRACAO_PADRAO),
    )

    private fun manutencaoDe(o: JSONObject) = Manutencao(
        id = o.getLong("id"),
        veiculoId = o.getLong("veiculoId"),
        data = LocalDate.parse(o.getString("data")),
        km = o.getInt("km"),
        tipo = TipoManutencao.valueOf(o.getString("tipo")),
        descricao = o.textoOuNull("descricao"),
        oficina = o.textoOuNull("oficina"),
        custoTotal = o.getDouble("custoTotal"),
    )

    private fun pecaDe(o: JSONObject) = PecaTrocada(
        id = o.getLong("id"),
        manutencaoId = o.getLong("manutencaoId"),
        nome = o.getString("nome"),
        quantidade = o.optInt("quantidade", 1),
        valorUnitario = o.getDouble("valorUnitario"),
    )

    private fun abastecimentoDe(o: JSONObject) = Abastecimento(
        id = o.getLong("id"),
        veiculoId = o.getLong("veiculoId"),
        data = LocalDate.parse(o.getString("data")),
        km = o.getInt("km"),
        litros = o.getDouble("litros"),
        valorTotal = o.getDouble("valorTotal"),
        tipoCombustivel = TipoCombustivel.valueOf(o.getString("tipoCombustivel")),
        tanqueCheio = o.getBoolean("tanqueCheio"),
    )

    private fun leituraDe(o: JSONObject) = LeituraObd(
        id = o.getLong("id"),
        veiculoId = o.getLong("veiculoId"),
        dataHora = LocalDateTime.parse(o.getString("dataHora")),
        rpm = o.intOuNull("rpm"),
        velocidade = o.intOuNull("velocidade"),
        temperaturaMotor = o.intOuNull("temperaturaMotor"),
        cargaMotor = o.doubleOuNull("cargaMotor"),
        tensaoBateria = o.doubleOuNull("tensaoBateria"),
    )

    private fun codigoDe(o: JSONObject) = CodigoFalha(
        id = o.getLong("id"),
        veiculoId = o.getLong("veiculoId"),
        dataHora = LocalDateTime.parse(o.getString("dataHora")),
        codigo = o.getString("codigo"),
        descricao = o.textoOuNull("descricao"),
        status = StatusCodigoFalha.valueOf(o.optString("status", StatusCodigoFalha.ATIVO.name)),
    )

    private fun preferenciasDe(o: JSONObject): PreferenciasBackup {
        val padrao = ConfigAutoConexao()
        val c = o.optJSONObject("caixaPreta")
        val caixa = if (c == null) {
            padrao
        } else {
            val scannerEndereco = c.textoOuNull("scannerEndereco")
            ConfigAutoConexao(
                autoConexaoAtiva = c.optBoolean("autoConexaoAtiva", padrao.autoConexaoAtiva),
                gravarLogsAutomaticos = c.optBoolean("gravarLogsAutomaticos", padrao.gravarLogsAutomaticos),
                logCompletoTrafego = c.optBoolean("logCompletoTrafego", padrao.logCompletoTrafego),
                gatilhoNome = c.textoOuNull("gatilhoNome")?.takeIf { it.isNotBlank() } ?: padrao.gatilhoNome,
                gatilhoEndereco = c.textoOuNull("gatilhoEndereco"),
                scanner = scannerEndereco?.let { DispositivoObd(c.textoOuNull("scannerNome") ?: it, it) },
                veiculoId = c.optLong("veiculoId", 0),
                veiculoDescricao = c.textoOuNull("veiculoDescricao"),
            )
        }
        return PreferenciasBackup(
            caixaPreta = caixa,
            tema = TemaApp.deCodigo(o.textoOuNull("tema")),
            unidadePressao = UnidadePressao.deCodigo(o.textoOuNull("unidadePressao")),
            sistemaUnidades = SistemaUnidades.deCodigo(o.textoOuNull("sistemaUnidades")),
            painel = o.optJSONObject("painel")?.let { p -> CodecPainel.decodificar { chave -> p.textoOuNull(chave) } },
        )
    }

    // ------------------------------------------------------------------ utilitários

    private fun <T> JSONObject.lista(nome: String, ler: (JSONObject) -> T): List<T> {
        val array = optJSONArray(nome) ?: return emptyList()
        return (0 until array.length()).map { ler(array.getJSONObject(it)) }
    }

    /** Ausente ou `null` = null (o `isNull` do org.json cobre os dois casos). */
    private fun JSONObject.textoOuNull(nome: String): String? = if (isNull(nome)) null else optString(nome)

    private fun JSONObject.intOuNull(nome: String): Int? = if (isNull(nome)) null else getInt(nome)

    private fun JSONObject.doubleOuNull(nome: String): Double? = if (isNull(nome)) null else getDouble(nome)
}
