package com.charruamotors.obd.pid

import org.json.JSONException
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Definição de um sensor lido do veículo: comando enviado ao ELM327, fórmula de conversão
 * dos bytes da resposta, unidade e faixa teórica de valores.
 *
 * @property comando chave única do sensor: PID do modo 01 (ex.: `010C`), comando AT do ELM327
 *   (ex.: `ATRV`) ou, nos PIDs de montadora, um identificador (ex.: `TOYOTA_ATF`).
 * @property bytes quantidade de bytes de dados da resposta (0 para comandos AT).
 * @property min limite inferior da faixa teórica; leituras fora de [min]..[max] são descartadas.
 * @property casasDecimais casas decimais sugeridas para exibição.
 * @property estendida requisição específica de montadora (modos 21/22 com cabeçalho CAN); null nos PIDs padrão.
 * @property chave sensor semântico ([ChaveSemantica]) que o PID representa; null nos que não têm equivalente.
 * @property rotulos estados discretos (ex.: alavanca do câmbio `0` → `P`, `3` → `D`): o valor inteiro
 *   é exibido pelo rótulo em vez do número. Vazio nos sensores contínuos.
 *
 * Um **espelho** é um PID dos modos 21/22 que lê o mesmo sensor de um PID padrão (`210C`,
 * `22F40C` ↔ `010C`): tem [estendida], [chave] e, como [comando], o do PID canônico — o valor
 * lido é publicado no mesmo lugar do OBD2 padrão.
 */
data class DefinicaoPid(
    val comando: String,
    val nome: String,
    val unidade: String,
    val bytes: Int,
    val formula: Formula,
    val min: Double,
    val max: Double,
    val casasDecimais: Int = 0,
    val estendida: RequisicaoEstendida? = null,
    val chave: ChaveSemantica? = null,
    val rotulos: Map<Int, String> = emptyMap(),
) {
    init {
        if (estendida == null) {
            require(ehComandoAt || REGEX_MODO_01.matches(comando)) { "Comando inválido: $comando" }
        } else {
            require(REGEX_ID_ESTENDIDO.matches(comando) || (chave != null && REGEX_MODO_01.matches(comando))) {
                "Identificador de PID estendido inválido: $comando"
            }
        }
        require(bytes in 0..MAX_BYTES) { "$comando: quantidade de bytes inválida ($bytes)" }
        require(formula.bytesNecessarios <= bytes) {
            "$comando: a fórmula \"$formula\" usa ${formula.bytesNecessarios} byte(s), mas o PID declara $bytes"
        }
        require(!ehComandoAt || formula.variaveis.all { it == 'X' }) {
            "$comando: comandos AT só podem usar a variável X"
        }
        require(min < max) { "$comando: faixa inválida ($min..$max)" }
        require(casasDecimais in 0..6) { "$comando: casas decimais inválidas ($casasDecimais)" }
        require(rotulos.values.all { it.isNotBlank() }) { "$comando: rótulo vazio" }
    }

    /** Rótulo do estado [valor] (`3.0` → `D`); null se o sensor não tiver rótulos ou o valor não for um deles. */
    fun rotulo(valor: Double?): String? = rotuloDe(rotulos, valor)

    /** Comando AT do adaptador (não é enviado à ECU e não aparece nos bitmasks de suporte). */
    val ehComandoAt: Boolean get() = estendida == null && comando.startsWith("AT")

    /** PID específico de montadora (modo 21 Toyota / 22 UDS), fora dos bitmasks do modo 01. */
    val ehEstendido: Boolean get() = estendida != null

    /** PID padrão SAE J1979 do modo 01. */
    val ehModo01: Boolean get() = !ehComandoAt && !ehEstendido

    /** Espelho de um PID padrão lido pelos modos 21/22 (comando = o do PID canônico, ex.: `010C`). */
    val ehEspelho: Boolean get() = estendida != null && REGEX_MODO_01.matches(comando)

    /** Número do PID no modo 01 (`010C` → `0x0C`); null para comandos AT e PIDs estendidos. */
    val pid: Int? get() = if (ehModo01) comando.substring(2).toInt(16) else null

    /** Byte hexadecimal do PID (`"0C"`); null para comandos AT e PIDs estendidos. */
    val pidHex: String? get() = if (ehModo01) comando.substring(2) else null

    /** Rótulo técnico para a UI: `010C`, `ATRV` ou `7E1 · 2182` (cabeçalho · requisição). */
    val rotuloComando: String get() = estendida?.let { "${it.cabecalho} · ${it.comando}" } ?: comando

    /** Converte os bytes de dados (ou o valor textual [x], em comandos AT) na unidade do sensor. */
    fun converter(dados: List<Int> = emptyList(), x: Double = 0.0): Double = formula.avaliar(dados, x)

    /** A leitura está dentro da faixa teórica? (NaN/Infinito nunca estão.) */
    fun dentroDaFaixa(valor: Double): Boolean =
        valor.isFinite() && valor >= min - TOLERANCIA && valor <= max + TOLERANCIA

    companion object {
        private val REGEX_MODO_01 = Regex("^01[0-9A-F]{2}$")
        private val REGEX_ID_ESTENDIDO = Regex("^[A-Z][A-Z0-9_]{2,31}$")
        private const val MAX_BYTES = 8
        private const val TOLERANCIA = 1e-9

        /** Rótulo de [valor] num mapa de estados discretos: só valores (quase) inteiros. */
        fun rotuloDe(rotulos: Map<Int, String>, valor: Double?): String? {
            if (rotulos.isEmpty() || valor == null || !valor.isFinite()) return null
            val inteiro = valor.roundToInt()
            return if (abs(valor - inteiro) < 0.01) rotulos[inteiro] else null
        }
    }
}

/**
 * Catálogo dinâmico de PIDs, carregado de `assets/pids_padrao.json`. Novos sensores entram
 * editando o JSON, sem mudar código. Objeto puro (usa apenas `org.json`), testável na JVM.
 */
class CatalogoPids(val pids: List<DefinicaoPid>) {

    private val porComando: Map<String, DefinicaoPid> = pids.associateBy { it.comando }

    private val porChave: Map<ChaveSemantica, DefinicaoPid> =
        pids.mapNotNull { p -> p.chave?.let { it to p } }.toMap()

    init {
        require(porComando.size == pids.size) {
            "Comandos duplicados no catálogo: ${pids.groupBy { it.comando }.filterValues { it.size > 1 }.keys}"
        }
        require(porChave.size == pids.count { it.chave != null }) {
            "Chaves semânticas repetidas no catálogo: ${pids.filter { it.chave != null }.groupBy { it.chave }.filterValues { it.size > 1 }.keys}"
        }
    }

    val comandos: Set<String> get() = porComando.keys

    operator fun get(comando: String): DefinicaoPid? = porComando[comando.uppercase()]

    /** PID canônico do sensor semântico (`RPM` → `010C`); null se o catálogo não tiver a chave. */
    operator fun get(chave: ChaveSemantica): DefinicaoPid? = porChave[chave]

    operator fun contains(comando: String): Boolean = get(comando) != null

    /** PIDs do modo 01 (sem os comandos AT), na ordem do catálogo. */
    val pidsModo01: List<DefinicaoPid> get() = pids.filter { it.ehModo01 }

    companion object {
        /** Nome do arquivo em `app/src/main/assets`. */
        const val ARQUIVO_PADRAO = "pids_padrao.json"

        /**
         * Lê o catálogo do JSON (`{"pids": [{"comando", "nome", "unidade", "bytes", "formula", "min", "max", "casas"}]}`).
         * Opcional em qualquer PID: `"rotulos": {"0": "P", "1": "R"}` (estados discretos).
         * @throws IllegalArgumentException se o JSON ou alguma definição for inválida.
         */
        fun deJson(json: String): CatalogoPids = try {
            val lista = JSONObject(json).getJSONArray("pids")
            CatalogoPids((0 until lista.length()).map { definicaoDeJson(lista.getJSONObject(it)) })
        } catch (e: JSONException) {
            throw IllegalArgumentException("Catálogo de PIDs com JSON inválido: ${e.message}", e)
        }

        /**
         * Uma definição do JSON (campos comuns ao catálogo padrão e ao estendido). Nos PIDs
         * estendidos a chave vem do campo `id`; nos padrão, de `comando`.
         * @throws IllegalArgumentException com o comando no texto, se a definição for inválida.
         * @throws JSONException se faltar um campo obrigatório.
         */
        internal fun definicaoDeJson(o: JSONObject, estendida: RequisicaoEstendida? = null): DefinicaoPid {
            val comando = o.getString(if (estendida == null) "comando" else "id").trim().uppercase()
            return try {
                DefinicaoPid(
                    comando = comando,
                    nome = o.getString("nome"),
                    unidade = o.getString("unidade"),
                    bytes = o.getInt("bytes"),
                    formula = Formula.compilar(o.getString("formula")),
                    min = o.getDouble("min"),
                    max = o.getDouble("max"),
                    casasDecimais = o.optInt("casas", 0),
                    estendida = estendida,
                    chave = ChaveSemantica.deTexto(o.optString("chave", "")),
                    rotulos = rotulosDeJson(o.optJSONObject("rotulos")),
                )
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("PID $comando: ${e.message}", e)
            }
        }

        private fun rotulosDeJson(o: JSONObject?): Map<Int, String> {
            if (o == null) return emptyMap()
            return o.keys().asSequence().associate { k ->
                val estado = k.trim().toIntOrNull() ?: throw IllegalArgumentException("rótulo com estado não inteiro: \"$k\"")
                estado to o.getString(k).trim()
            }.toSortedMap()
        }
    }
}

/** Comandos dos sensores usados diretamente pelo app (painel, filas de polling, cálculos, freeze frame). */
object Pids {
    const val CARGA_MOTOR = "0104"
    const val TEMPERATURA_MOTOR = "0105"
    const val STFT = "0106"
    const val LTFT = "0107"
    const val PRESSAO_COLETOR = "010B"
    const val RPM = "010C"
    const val VELOCIDADE = "010D"
    const val TEMPERATURA_AR_ADMISSAO = "010F"
    const val MAF = "0110"
    const val BORBOLETA = "0111"
    const val PRESSAO_RAIL = "0123"
    const val LAMBDA_COMANDADO = "0144"
    const val PERCENTUAL_ETANOL = "0152"
    const val CONSUMO_ECU = "015E"
    const val TENSAO_BATERIA = "ATRV"

    /** Sensores exibidos no painel principal (lidos a cada ciclo de polling). */
    val PAINEL: List<String> = listOf(RPM, VELOCIDADE, TEMPERATURA_MOTOR, CARGA_MOTOR, TENSAO_BATERIA)

    /**
     * Fila rápida do polling (lida em todo ciclo), em ordem de prioridade: só os mostradores
     * dinâmicos — RPM, MAP/turbo e velocidade. Borboleta e o resto vão para a fila lenta.
     */
    val CANDIDATOS_FILA_RAPIDA: List<String> = listOf(RPM, PRESSAO_COLETOR, VELOCIDADE)

    /**
     * Candidatos da fila rápida conforme o cálculo de consumo: com [consumoPorMaf] (gasolina,
     * etanol, flex) o MAF entra junto dos mostradores, para o consumo instantâneo acompanhar o
     * pé; no diesel (consumo pelo `015E`) ele fica na fila lenta.
     */
    fun candidatosFilaRapida(consumoPorMaf: Boolean): List<String> =
        if (consumoPorMaf) CANDIDATOS_FILA_RAPIDA + MAF else CANDIDATOS_FILA_RAPIDA

    /** Sensores congelados no Freeze Frame (modo 02), no momento em que a falha foi registrada. */
    val FREEZE_FRAME: List<String> = listOf(RPM, VELOCIDADE, TEMPERATURA_MOTOR, CARGA_MOTOR)
}
