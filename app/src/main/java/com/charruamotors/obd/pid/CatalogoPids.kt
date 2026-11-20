package com.charruamotors.obd.pid

import org.json.JSONException
import org.json.JSONObject

/**
 * Definição de um sensor lido do veículo: comando enviado ao ELM327, fórmula de conversão
 * dos bytes da resposta, unidade e faixa teórica de valores.
 *
 * @property comando PID do modo 01 (ex.: `010C`) ou comando AT do ELM327 (ex.: `ATRV`).
 * @property bytes quantidade de bytes de dados da resposta (0 para comandos AT).
 * @property min limite inferior da faixa teórica; leituras fora de [min]..[max] são descartadas.
 * @property casasDecimais casas decimais sugeridas para exibição.
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
) {
    init {
        require(ehComandoAt || REGEX_MODO_01.matches(comando)) { "Comando inválido: $comando" }
        require(bytes in 0..MAX_BYTES) { "$comando: quantidade de bytes inválida ($bytes)" }
        require(formula.bytesNecessarios <= bytes) {
            "$comando: a fórmula \"$formula\" usa ${formula.bytesNecessarios} byte(s), mas o PID declara $bytes"
        }
        require(!ehComandoAt || formula.variaveis.all { it == 'X' }) {
            "$comando: comandos AT só podem usar a variável X"
        }
        require(min < max) { "$comando: faixa inválida ($min..$max)" }
        require(casasDecimais in 0..6) { "$comando: casas decimais inválidas ($casasDecimais)" }
    }

    /** Comando AT do adaptador (não é enviado à ECU e não aparece nos bitmasks de suporte). */
    val ehComandoAt: Boolean get() = comando.startsWith("AT")

    /** Número do PID no modo 01 (`010C` → `0x0C`); null para comandos AT. */
    val pid: Int? get() = if (ehComandoAt) null else comando.substring(2).toInt(16)

    /** Byte hexadecimal do PID (`"0C"`); null para comandos AT. */
    val pidHex: String? get() = if (ehComandoAt) null else comando.substring(2)

    /** Converte os bytes de dados (ou o valor textual [x], em comandos AT) na unidade do sensor. */
    fun converter(dados: List<Int> = emptyList(), x: Double = 0.0): Double = formula.avaliar(dados, x)

    /** A leitura está dentro da faixa teórica? (NaN/Infinito nunca estão.) */
    fun dentroDaFaixa(valor: Double): Boolean =
        valor.isFinite() && valor >= min - TOLERANCIA && valor <= max + TOLERANCIA

    companion object {
        private val REGEX_MODO_01 = Regex("^01[0-9A-F]{2}$")
        private const val MAX_BYTES = 8
        private const val TOLERANCIA = 1e-9
    }
}

/**
 * Catálogo dinâmico de PIDs, carregado de `assets/pids_padrao.json`. Novos sensores entram
 * editando o JSON, sem mudar código. Objeto puro (usa apenas `org.json`), testável na JVM.
 */
class CatalogoPids(val pids: List<DefinicaoPid>) {

    private val porComando: Map<String, DefinicaoPid> = pids.associateBy { it.comando }

    init {
        require(porComando.size == pids.size) {
            "Comandos duplicados no catálogo: ${pids.groupBy { it.comando }.filterValues { it.size > 1 }.keys}"
        }
    }

    val comandos: Set<String> get() = porComando.keys

    operator fun get(comando: String): DefinicaoPid? = porComando[comando.uppercase()]

    operator fun contains(comando: String): Boolean = get(comando) != null

    /** PIDs do modo 01 (sem os comandos AT), na ordem do catálogo. */
    val pidsModo01: List<DefinicaoPid> get() = pids.filter { !it.ehComandoAt }

    companion object {
        /** Nome do arquivo em `app/src/main/assets`. */
        const val ARQUIVO_PADRAO = "pids_padrao.json"

        /**
         * Lê o catálogo do JSON (`{"pids": [{"comando", "nome", "unidade", "bytes", "formula", "min", "max", "casas"}]}`).
         * @throws IllegalArgumentException se o JSON ou alguma definição for inválida.
         */
        fun deJson(json: String): CatalogoPids = try {
            val lista = JSONObject(json).getJSONArray("pids")
            CatalogoPids(
                (0 until lista.length()).map { i ->
                    val o = lista.getJSONObject(i)
                    val comando = o.getString("comando").trim().uppercase()
                    try {
                        DefinicaoPid(
                            comando = comando,
                            nome = o.getString("nome"),
                            unidade = o.getString("unidade"),
                            bytes = o.getInt("bytes"),
                            formula = Formula.compilar(o.getString("formula")),
                            min = o.getDouble("min"),
                            max = o.getDouble("max"),
                            casasDecimais = o.optInt("casas", 0),
                        )
                    } catch (e: IllegalArgumentException) {
                        throw IllegalArgumentException("PID $comando: ${e.message}", e)
                    }
                },
            )
        } catch (e: JSONException) {
            throw IllegalArgumentException("Catálogo de PIDs com JSON inválido: ${e.message}", e)
        }
    }
}

/** Comandos dos sensores usados diretamente pelo app (painel, histórico, freeze frame). */
object Pids {
    const val CARGA_MOTOR = "0104"
    const val TEMPERATURA_MOTOR = "0105"
    const val PRESSAO_COLETOR = "010B"
    const val RPM = "010C"
    const val VELOCIDADE = "010D"
    const val TENSAO_BATERIA = "ATRV"

    /** Sensores exibidos no painel principal (lidos a cada ciclo de polling). */
    val PAINEL: List<String> = listOf(RPM, VELOCIDADE, TEMPERATURA_MOTOR, CARGA_MOTOR, TENSAO_BATERIA)

    /** Sensores congelados no Freeze Frame (modo 02), no momento em que a falha foi registrada. */
    val FREEZE_FRAME: List<String> = listOf(RPM, VELOCIDADE, TEMPERATURA_MOTOR, CARGA_MOTOR)
}
