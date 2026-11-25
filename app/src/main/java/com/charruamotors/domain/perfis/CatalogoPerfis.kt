package com.charruamotors.domain.perfis

import com.charruamotors.domain.faixas.FaixaEsperada
import com.charruamotors.domain.faixas.ParametroFaixa
import com.charruamotors.domain.faixas.PresetVeiculo
import com.charruamotors.domain.model.TipoCombustivel
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** Maturidade de um perfil: `validado` = conferido em carro real; `base` = valores típicos, sem validação. */
enum class StatusPerfil(val rotulo: String) {
    VALIDADO("Validado"),
    BASE("Base"),
    ;

    companion object {
        fun de(texto: String): StatusPerfil = when (texto.trim().lowercase()) {
            "validado" -> VALIDADO
            "base" -> BASE
            else -> throw IllegalArgumentException("Status de perfil inválido: \"$texto\" (use validado ou base)")
        }
    }
}

/**
 * Camada do motor (`assets/perfis/motores/<id>.json`): rede da ECU (protocolo, cabeçalho `7E0`,
 * serviço extra `21`/`22`), PIDs opcionais da montadora (ids de `pids_estendidos.json`), limites
 * de alerta sugeridos ao aplicar o preset e as faixas esperadas.
 */
data class PerfilMotor(
    val id: String,
    val status: StatusPerfil,
    val nome: String,
    /** Código do motor (`1KD-FTV`, `LE2`). */
    val codigo: String,
    val combustivel: TipoCombustivel,
    val turbo: Boolean,
    /** Comando de protocolo do ELM327 (`ATSP6` = ISO 15765-4 CAN 11 bits 500 kbps; `ATSP0` = automático). */
    val protocolo: String,
    val descricaoProtocolo: String,
    val cabecalho: String,
    /** Serviço da montadora além do OBD2 (`21` KWP Toyota, `22` UDS); null = só o modo 01. */
    val servicoExtra: String?,
    val pidsOpcionais: List<String>,
    val aguaMaxC: Int,
    val tensaoMinV: Double,
    val observacao: String,
    val faixas: List<FaixaEsperada>,
)

enum class TipoCambio { AUTOMATICO, CVT, MANUAL }

/** Posição da alavanca pelos bytes C e D da resposta (`00 04` = D na A750F). */
data class PosicaoAlavanca(val rotulo: String, val byteC: Int, val byteD: Int)

/** Mapa da alavanca lido no TCM (`7E1 · 21D9` → `TOYOTA_ALAVANCA`). */
data class MapaAlavanca(val chave: String, val cabecalho: String, val comando: String, val posicoes: List<PosicaoAlavanca>)

/** Marcha engatada quando a razão `rpm / velocidade (km/h)` é ≥ [razaoMinima] (a última marcha usa 0). */
data class RelacaoMarcha(val marcha: Int, val razaoMinima: Double)

/**
 * Camada do câmbio (`assets/perfis/cambios/<id>.json`): TCM (`7E1`/`7E2`, vazio = sem TCM), PIDs
 * de ATF opcionais, mapa da alavanca opcional, faixa do ATF e as razões `rpm / velocidade` que
 * estimam a marcha quando a ECU não informa.
 */
data class PerfilCambio(
    val id: String,
    val status: StatusPerfil,
    val nome: String,
    val tipo: TipoCambio,
    val cabecalhos: List<String>,
    val servico: String?,
    val pidsAtf: List<String>,
    val mapaAlavanca: MapaAlavanca?,
    val limiteAtfC: Int,
    val observacao: String,
    val faixas: List<FaixaEsperada>,
    val relacoes: List<RelacaoMarcha>,
    /**
     * v2.2.6: fator da caixa de transferência na reduzida (`4L`), ex.: `2.57` na A750F da SW4.
     * Null = câmbio sem reduzida (o estimador nunca entra em `4L`).
     */
    val fatorReduzida: Double? = null,
) {
    init {
        require(fatorReduzida == null || (fatorReduzida.isFinite() && fatorReduzida > 1.0)) { "$id: fatorReduzida inválido ($fatorReduzida)" }
    }

    /** Tem módulo de câmbio para sondar (`7E1`/`7E2`)? */
    val temTcm: Boolean get() = cabecalhos.isNotEmpty()

    /** A marcha pode ser estimada pela razão `rpm / velocidade`? */
    val estimaMarcha: Boolean get() = relacoes.size >= 2

    /**
     * Marcha pela razão `rpm / velocidade` (km/h), a primeira das [relacoes] cujo mínimo a razão
     * atinge. Null sem relações, abaixo de [VELOCIDADE_MINIMA_KMH] ou sem leitura válida.
     */
    fun marchaPorRazao(rpm: Double?, velocidadeKmh: Double?): Int? {
        if (!estimaMarcha) return null
        if (rpm == null || velocidadeKmh == null || !rpm.isFinite() || !velocidadeKmh.isFinite()) return null
        if (velocidadeKmh < VELOCIDADE_MINIMA_KMH || rpm <= 0.0) return null
        return marchaDaRazao(rpm / velocidadeKmh)
    }

    /** Marcha da tabela para uma razão `rpm / velocidade` já calculada (ex.: dividida pelo [fatorReduzida]). */
    fun marchaDaRazao(razao: Double): Int? {
        if (!estimaMarcha || !razao.isFinite() || razao <= 0.0) return null
        return (relacoes.firstOrNull { razao >= it.razaoMinima } ?: relacoes.last()).marcha
    }

    companion object {
        const val VELOCIDADE_MINIMA_KMH = 5.0
    }
}

/**
 * Como reconhecer o veículo pelo cadastro: a marca bate com um dos [marcas] (palavra inteira), o
 * modelo contém um dos [modelos], nenhum dos [excluirModelo] e — se houver confirmação — algum
 * dos [confirmacaoModelo] ou o ano em [anoDe]..[anoAte].
 */
data class DeteccaoVeiculo(
    val marcas: List<String>,
    val modelos: List<String>,
    val excluirModelo: List<Regex> = emptyList(),
    val confirmacaoModelo: List<Regex> = emptyList(),
    val confirmaPorAno: Boolean = false,
    val anoDe: Int? = null,
    val anoAte: Int? = null,
) {
    fun reconhece(marca: String?, modelo: String?, ano: Int?): Boolean {
        if (!TextoPerfis.marcaCorresponde(marca, marcas)) return false
        val mod = TextoPerfis.normalizar(modelo)
        if (modelos.none { it in mod }) return false
        if (excluirModelo.any { it.containsMatchIn(mod) }) return false
        if (confirmacaoModelo.isEmpty() && !confirmaPorAno) return true
        val porAno = confirmaPorAno && ano != null && (anoDe == null || ano >= anoDe) && (anoAte == null || ano <= anoAte)
        return porAno || confirmacaoModelo.any { it.containsMatchIn(mod) }
    }
}

/** Entrada de `veiculos.json`: liga montadora/modelo/anos a um motor e a um câmbio. */
data class PerfilVeiculo(
    val id: String,
    val titulo: String,
    val montadora: String,
    val modelo: String,
    val anoInicio: Int?,
    val anoFim: Int?,
    /** Ano preenchido ao aplicar o preset (null = mantém o do cadastro). */
    val anoReferencia: Int?,
    val motorId: String,
    val cambioId: String,
    val status: StatusPerfil,
    /** Layout rápido do painel sugerido (`padrao`, `turbo_flex`). */
    val layoutSugerido: String,
    /** Fallback por motorização/combustível (sem detecção pelo cadastro). */
    val generico: Boolean,
    val deteccao: DeteccaoVeiculo?,
    /**
     * Padrões (regex sobre o VIN completo, ancorados no início: WMI + VDS, ex. `^8AGB[TN]`) que
     * identificam este perfil pelo chassi, sem depender do cadastro. Vazio = sem identificação por VIN.
     */
    val padroesVin: List<Regex> = emptyList(),
) {
    /** O [vin] (já normalizado, maiúsculas) casa com algum dos [padroesVin]? */
    fun casaVin(vin: String): Boolean = padroesVin.any { it.containsMatchIn(vin) }

    /** `2005–2015`, `2020+`. */
    val faixaAnos: String
        get() = when {
            anoInicio == null && anoFim == null -> ""
            anoFim == null -> "$anoInicio+"
            anoInicio == null -> "até $anoFim"
            else -> "$anoInicio–$anoFim"
        }

    fun cobreAno(ano: Int?): Boolean = ano == null || ((anoInicio == null || ano >= anoInicio) && (anoFim == null || ano <= anoFim))
}

/**
 * Catálogo modular de perfis (`assets/perfis/`): veículos → motor + câmbio. Objeto puro (JVM);
 * o [CarregadorPerfis] lê os arquivos. Arquivos inválidos ou referências quebradas não derrubam o
 * catálogo: o veículo afetado fica de fora e o motivo vai para [erros].
 */
class CatalogoPerfis(
    val veiculos: List<PerfilVeiculo>,
    val motores: Map<String, PerfilMotor>,
    val cambios: Map<String, PerfilCambio>,
    val erros: List<String> = emptyList(),
) {
    /** Presets completos (veículo com motor e câmbio resolvidos), na ordem de `veiculos.json`. */
    val presets: List<PresetVeiculo> = veiculos.mapNotNull { v ->
        val motor = motores[v.motorId]
        val cambio = cambios[v.cambioId]
        if (motor != null && cambio != null) PresetVeiculo(v, motor, cambio) else null
    }

    operator fun get(id: String): PresetVeiculo? = presets.firstOrNull { it.id == id }

    /** Perfil exato reconhecido pela marca/modelo/ano do cadastro (os genéricos nunca entram). */
    fun detectar(marca: String?, modelo: String?, ano: Int?): PresetVeiculo? =
        presets.firstOrNull { p -> !p.generico && p.perfil.deteccao?.reconhece(marca, modelo, ano) == true }

    /**
     * Perfil exato pelo chassi: o primeiro perfil não genérico cujo [PerfilVeiculo.padroesVin] casa com
     * o VIN (WMI + VDS) e cujos anos cobrem o ano-modelo do 10º caractere (ano inválido não restringe).
     */
    fun detectarPorVin(vin: VinDecodificado?): PresetVeiculo? {
        if (vin == null) return null
        return presets.firstOrNull { p ->
            !p.generico && p.perfil.padroesVin.isNotEmpty() && p.perfil.casaVin(vin.vin) && p.perfil.cobreAno(vin.anoModelo)
        }
    }

    /**
     * Perfil genérico pela motorização: diesel → Diesel Common Rail; demais combustíveis → Flex
     * Turbo se o modelo indicar turbo (`turbo`, `TSI`, `T200`, `1.0T`...), senão Flex Aspirado.
     */
    fun genericoPara(combustivel: TipoCombustivel?, modelo: String?): PresetVeiculo? {
        val genericos = presets.filter { it.generico }
        if (combustivel == TipoCombustivel.DIESEL) return genericos.firstOrNull { it.perfilMotor.combustivel == TipoCombustivel.DIESEL }
        val turbo = TextoPerfis.indicaTurbo(modelo)
        return genericos.firstOrNull { it.perfilMotor.combustivel != TipoCombustivel.DIESEL && it.perfilMotor.turbo == turbo }
    }

    /** Perfis da [montadora] (nome do VIN, ex. `Chevrolet`) que cobrem o [ano]; os genéricos ficam de fora. */
    fun sugestoesPara(montadora: String?, ano: Int?): List<PresetVeiculo> {
        if (montadora.isNullOrBlank()) return emptyList()
        return presets.filter { p ->
            !p.generico && p.perfil.cobreAno(ano) &&
                (TextoPerfis.marcaCorresponde(montadora, p.perfil.deteccao?.marcas.orEmpty()) ||
                    TextoPerfis.normalizar(p.perfil.montadora) == TextoPerfis.normalizar(montadora))
        }
    }

    companion object {
        val VAZIO = CatalogoPerfis(emptyList(), emptyMap(), emptyMap())

        const val PASTA = "perfis"
        const val ARQUIVO_VEICULOS = "veiculos.json"
        const val PASTA_MOTORES = "motores"
        const val PASTA_CAMBIOS = "cambios"

        private val REGEX_ID = Regex("^[a-z0-9_]+$")
        private val REGEX_CABECALHO = Regex("^[0-7][0-9A-F]{2}$")
        private val REGEX_BYTES = Regex("^[0-9A-Fa-f]{2} [0-9A-Fa-f]{2}$")

        /**
         * Lê `veiculos.json` e cada motor/câmbio referenciado por ele (`motores/<id>.json`,
         * `cambios/<id>.json`) pela [fonte]. Nunca lança: sem `veiculos.json` válido, catálogo vazio.
         */
        fun carregar(fonte: FontePerfis): CatalogoPerfis {
            val erros = mutableListOf<String>()
            val veiculos = try {
                veiculosDeJson(fonte.ler(ARQUIVO_VEICULOS) ?: return CatalogoPerfis(emptyList(), emptyMap(), emptyMap(), listOf("$ARQUIVO_VEICULOS ausente")))
            } catch (e: Exception) {
                return CatalogoPerfis(emptyList(), emptyMap(), emptyMap(), listOf("$ARQUIVO_VEICULOS: ${e.message}"))
            }
            fun <T> lerTodos(pasta: String, ids: Set<String>, parse: (String) -> T, idDe: (T) -> String): Map<String, T> =
                ids.mapNotNull { id ->
                    val caminho = "$pasta/$id.json"
                    try {
                        val texto = fonte.ler(caminho) ?: throw IllegalArgumentException("arquivo ausente")
                        parse(texto).also { require(idDe(it) == id) { "id \"${idDe(it)}\" difere do nome do arquivo" } }
                    } catch (e: Exception) {
                        erros += "$caminho: ${e.message}"
                        null
                    }?.let { id to it }
                }.toMap()
            val motores = lerTodos(PASTA_MOTORES, veiculos.mapTo(linkedSetOf()) { it.motorId }, ::motorDeJson) { it.id }
            val cambios = lerTodos(PASTA_CAMBIOS, veiculos.mapTo(linkedSetOf()) { it.cambioId }, ::cambioDeJson) { it.id }
            return CatalogoPerfis(veiculos, motores, cambios, erros)
        }

        fun motorDeJson(texto: String): PerfilMotor {
            val o = JSONObject(texto)
            val id = id(o)
            return PerfilMotor(
                id = id,
                status = StatusPerfil.de(o.getString("status")),
                nome = o.getString("nome"),
                codigo = o.getString("codigo"),
                combustivel = combustivel(o.getString("combustivel")),
                turbo = o.getBoolean("turbo"),
                protocolo = o.getString("protocolo").also { require(it.matches(Regex("^ATSP[0-9A-C]$"))) { "$id: protocolo inválido ($it)" } },
                descricaoProtocolo = o.getString("descricaoProtocolo"),
                cabecalho = cabecalho(o.getString("cabecalho"), id),
                servicoExtra = servico(o.textoOuNulo("servicoExtra"), id),
                pidsOpcionais = o.optJSONArray("pidsOpcionais").textos(),
                aguaMaxC = o.getJSONObject("limites").getInt("aguaMaxC"),
                tensaoMinV = o.getJSONObject("limites").getDouble("tensaoMinV"),
                observacao = o.optString("observacao"),
                faixas = faixas(o.getJSONArray("faixas"), id),
            )
        }

        fun cambioDeJson(texto: String): PerfilCambio {
            val o = JSONObject(texto)
            val id = id(o)
            val relacoes = o.getJSONArray("relacoes").objetos().map { r ->
                RelacaoMarcha(r.getInt("marcha"), r.getDouble("razaoMinima"))
            }
            relacoes.forEachIndexed { i, r ->
                require(r.marcha == i + 1) { "$id: marchas fora de ordem (esperada ${i + 1}ª, veio ${r.marcha}ª)" }
                if (i > 0) require(r.razaoMinima < relacoes[i - 1].razaoMinima) { "$id: razões rpm/velocidade devem decrescer (${r.marcha}ª)" }
            }
            require(relacoes.isEmpty() || relacoes.last().razaoMinima == 0.0) { "$id: a última marcha deve ter razaoMinima 0" }
            return PerfilCambio(
                id = id,
                status = StatusPerfil.de(o.getString("status")),
                nome = o.getString("nome"),
                tipo = when (o.getString("tipo").lowercase()) {
                    "automatico" -> TipoCambio.AUTOMATICO
                    "cvt" -> TipoCambio.CVT
                    "manual" -> TipoCambio.MANUAL
                    else -> throw IllegalArgumentException("$id: tipo de câmbio inválido (${o.getString("tipo")})")
                },
                cabecalhos = o.getJSONArray("cabecalhos").textos().map { cabecalho(it, id) },
                servico = servico(o.textoOuNulo("servico"), id),
                pidsAtf = o.optJSONArray("pidsAtf").textos(),
                mapaAlavanca = o.optJSONObject("mapaAlavanca")?.let { m ->
                    MapaAlavanca(
                        chave = m.getString("chave"),
                        cabecalho = cabecalho(m.getString("cabecalho"), id),
                        comando = m.getString("comando").uppercase(),
                        posicoes = m.getJSONArray("posicoes").objetos().map { p ->
                            val bytes = p.getString("bytesCD")
                            require(REGEX_BYTES.matches(bytes)) { "$id: bytesCD inválido ($bytes)" }
                            PosicaoAlavanca(p.getString("rotulo"), bytes.substring(0, 2).toInt(16), bytes.substring(3, 5).toInt(16))
                        },
                    )
                },
                limiteAtfC = o.getInt("limiteAtfC"),
                observacao = o.optString("observacao"),
                faixas = faixas(o.getJSONArray("faixas"), id),
                relacoes = relacoes,
                fatorReduzida = if (o.isNull("fatorReduzida")) null else o.getDouble("fatorReduzida"),
            ).also { c ->
                require(c.tipo != TipoCambio.MANUAL || !c.temTcm) { "$id: câmbio manual não tem TCM" }
            }
        }

        fun veiculosDeJson(texto: String): List<PerfilVeiculo> {
            val lista = JSONObject(texto).getJSONArray("veiculos").objetos().map { o ->
                val id = id(o)
                val generico = o.optBoolean("generico", false)
                val deteccao = o.optJSONObject("deteccao")?.let { d ->
                    val ano = d.optJSONObject("confirmacaoAno")
                    DeteccaoVeiculo(
                        marcas = d.getJSONArray("marcas").textos().map(TextoPerfis::normalizar),
                        modelos = d.getJSONArray("modelos").textos().map(TextoPerfis::normalizar),
                        excluirModelo = d.optJSONArray("excluirModelo").textos().map(::Regex),
                        confirmacaoModelo = d.optJSONArray("confirmacaoModelo").textos().map(::Regex),
                        confirmaPorAno = ano != null,
                        anoDe = ano?.inteiroOuNulo("de"),
                        anoAte = ano?.inteiroOuNulo("ate"),
                    )
                }
                require(generico || deteccao != null) { "$id: veículo sem 'deteccao' nem 'generico'" }
                PerfilVeiculo(
                    id = id,
                    titulo = o.getString("titulo"),
                    montadora = o.getString("montadora"),
                    modelo = o.getString("modelo"),
                    anoInicio = o.inteiroOuNulo("anoInicio"),
                    anoFim = o.inteiroOuNulo("anoFim"),
                    anoReferencia = o.inteiroOuNulo("anoReferencia"),
                    motorId = o.getString("motorId"),
                    cambioId = o.getString("cambioId"),
                    status = StatusPerfil.de(o.getString("status")),
                    layoutSugerido = o.optString("layoutSugerido", "padrao"),
                    generico = generico,
                    deteccao = deteccao,
                    padroesVin = o.optJSONArray("padroesVin").textos().map { padraoVin(id, it) },
                ).also { v ->
                    require(v.anoInicio == null || v.anoFim == null || v.anoInicio <= v.anoFim) { "$id: anoInicio > anoFim" }
                }
            }
            val repetidos = lista.groupBy { it.id }.filterValues { it.size > 1 }.keys
            require(repetidos.isEmpty()) { "ids de veículo repetidos: $repetidos" }
            return lista
        }

        private fun id(o: JSONObject): String =
            o.getString("id").also { require(REGEX_ID.matches(it)) { "id inválido: \"$it\" (só a-z, 0-9 e _)" } }

        /**
         * Regex de um `padroesVin` (maiúsculas): texto sem `^` é tratado como prefixo literal do
         * VIN (WMI+VDS, 8–9 caracteres). Regex inválida ou que casa com texto vazio derruba o arquivo.
         */
        fun padraoVin(id: String, texto: String): Regex {
            val t = texto.trim().uppercase()
            require(t.isNotEmpty()) { "$id: padrão de VIN vazio" }
            val regex = try {
                Regex(if (t.startsWith("^")) t else "^" + Regex.escape(t))
            } catch (e: Exception) {
                throw IllegalArgumentException("$id: padrão de VIN inválido ($texto): ${e.message}")
            }
            require(!regex.matches("")) { "$id: padrão de VIN casa com texto vazio ($texto)" }
            return regex
        }

        private fun cabecalho(texto: String, id: String): String =
            texto.uppercase().also { require(REGEX_CABECALHO.matches(it)) { "$id: cabeçalho CAN inválido ($texto)" } }

        private fun servico(texto: String?, id: String): String? =
            texto?.also { require(it == "21" || it == "22") { "$id: serviço extra inválido ($it; use 21 ou 22)" } }

        private fun combustivel(texto: String): TipoCombustivel =
            TipoCombustivel.entries.firstOrNull { it.name.equals(texto.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Combustível inválido: $texto")

        private fun faixas(array: JSONArray, id: String): List<FaixaEsperada> {
            val lista = array.objetos().map { f ->
                val nome = f.getString("parametro")
                val parametro = ParametroFaixa.entries.firstOrNull { it.name == nome }
                    ?: throw IllegalArgumentException("$id: parâmetro de faixa desconhecido ($nome)")
                val min = f.decimalOuNulo("min")
                val max = f.decimalOuNulo("max")
                require(min == null || max == null || min < max) { "$id: faixa $nome com min ≥ max ($min..$max)" }
                FaixaEsperada(parametro, min, max)
            }
            val repetidas = lista.groupBy { it.parametro }.filterValues { it.size > 1 }.keys
            require(repetidas.isEmpty()) { "$id: faixas repetidas $repetidas" }
            return lista
        }

        private fun JSONObject.textoOuNulo(chave: String): String? = if (!has(chave) || isNull(chave)) null else getString(chave)
        private fun JSONObject.inteiroOuNulo(chave: String): Int? = if (!has(chave) || isNull(chave)) null else getInt(chave)
        private fun JSONObject.decimalOuNulo(chave: String): Double? = if (!has(chave) || isNull(chave)) null else getDouble(chave)
        private fun JSONArray?.textos(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }
        private fun JSONArray.objetos(): List<JSONObject> = List(length()) { getJSONObject(it) }
    }
}

/** Normalização de texto do cadastro e regras de palavra (marca, turbo) usadas na detecção. */
object TextoPerfis {

    private val REGEX_TURBO = Regex(
        "\\bturbo\\b|\\btsi\\b|\\btfsi\\b|\\bt200\\b|\\bt270\\b|\\bthp\\b|\\btgdi\\b|\\btce\\b|\\becoboost\\b|\\b\\d[.,]\\d ?t\\b",
    )

    /** Minúsculas, sem acentos e com espaços simples. */
    fun normalizar(texto: String?): String =
        Normalizer.normalize(texto.orEmpty().trim().lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\s+"), " ")

    private fun palavras(texto: String?): String = normalizar(texto).replace(Regex("[^a-z0-9]+"), " ").trim()

    /** A marca contém um dos [aliases] como palavra inteira (`Chevrolet / GM` → `gm`). */
    fun marcaCorresponde(marca: String?, aliases: Collection<String>): Boolean {
        val m = " ${palavras(marca)} "
        if (m.isBlank()) return false
        return aliases.any { a -> palavras(a).let { it.isNotEmpty() && " $it " in m } }
    }

    /** O modelo indica motor turbo (`Turbo`, `TSI`, `T200`, `1.0T`, `THP`, `EcoBoost`...)? */
    fun indicaTurbo(modelo: String?): Boolean = REGEX_TURBO.containsMatchIn(normalizar(modelo))
}
