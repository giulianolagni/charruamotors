package com.charruamotors.direcao.painel

import com.charruamotors.obd.ValoresSensores
import com.charruamotors.obd.pid.DefinicaoPid
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.sistema.unidades.UnidadePressao
import com.charruamotors.sistema.unidades.ValorExibicao
import com.charruamotors.ui.componentes.EscalaGauge
import com.charruamotors.ui.util.comoNumero

/** Correção dos bicos: ±3,00 mm³/st é a faixa saudável; a escala vai até ±4 (zona vermelha em 3). */
private val ESCALA_BICO = mapOf("mm³/st" to EscalaGauge(-4f, 4f, 3f, 8, 2))

/**
 * Sensores que podem ocupar um dos 4 mostradores do Painel ou uma célula do HUD flutuante, com a
 * escala de cada unidade de exibição (métrica e imperial). [comando] é a chave do PID canônico no
 * catálogo (`010C`, `ATRV`...): os espelhos dos modos 21/22 publicam no mesmo lugar. Os
 * [alternativos] são lidos quando o principal não vem (ex.: o pedal pelo `2149` Toyota quando a
 * ECU não tem o `0149`; a alavanca pelo motor `7E0` quando o câmbio `7E1` não responde). Sensores
 * de estado (alavanca, 4x4, VSC) mostram o [rotulos] do valor em vez do número; sem leitura, "—".
 */
enum class SensorPainel(
    val comando: String,
    /** Título no mostrador do Painel. */
    val titulo: String,
    /** Rótulo curto, em maiúsculas, do HUD flutuante. */
    val rotuloHud: String,
    /** Unidade do catálogo de PIDs (antes da conversão). */
    val unidadeCatalogo: String,
    val casas: Int,
    private val escalas: Map<String, EscalaGauge>,
    val alternativos: List<String> = emptyList(),
    val rotulos: Map<Int, String> = emptyMap(),
    /** Valores positivos levam `+` (correção dos bicos injetores: `+0,45`). */
    val comSinal: Boolean = false,
) {
    RPM(
        "010C", "RPM", "RPM", "rpm", 0,
        mapOf("rpm" to EscalaGauge(0f, 8000f, 6500f, 8, 5, 1000f)),
    ),
    VELOCIDADE(
        "010D", "Velocidade", "VELOC.", "km/h", 0,
        mapOf(
            "km/h" to EscalaGauge(0f, 240f, null, 6, 4),
            "mph" to EscalaGauge(0f, 160f, null, 8, 4),
        ),
    ),
    TURBO(
        "010B", "Turbo/MAP", "TURBO/MAP", "kPa", 0,
        mapOf(
            "kPa" to EscalaGauge(0f, 250f, 200f, 5, 5),
            "bar" to EscalaGauge(0f, 2.5f, 2.0f, 5, 5),
            "psi" to EscalaGauge(0f, 35f, 29f, 7, 5),
        ),
    ),
    TEMP_MOTOR(
        "0105", "Motor", "MOTOR", "°C", 0,
        mapOf(
            "°C" to EscalaGauge(40f, 130f, 110f, 9, 2),
            "°F" to EscalaGauge(100f, 280f, 230f, 9, 2),
        ),
    ),
    TEMP_ADMISSAO(
        "010F", "Admissão", "ADMISSÃO", "°C", 0,
        mapOf(
            "°C" to EscalaGauge(-20f, 100f, 70f, 6, 2),
            "°F" to EscalaGauge(0f, 210f, 160f, 7, 3),
        ),
    ),
    TEMP_OLEO(
        "015C", "Óleo", "ÓLEO", "°C", 0,
        mapOf(
            "°C" to EscalaGauge(40f, 160f, 130f, 6, 2),
            "°F" to EscalaGauge(100f, 320f, 265f, 11, 2),
        ),
    ),
    CARGA(
        "0104", "Carga", "CARGA", "%", 0,
        mapOf("%" to EscalaGauge(0f, 100f, 90f, 5, 4)),
    ),
    BORBOLETA(
        "0111", "Borboleta", "BORBOLETA", "%", 0,
        mapOf("%" to EscalaGauge(0f, 100f, null, 5, 4)),
    ),
    ACELERADOR(
        "0149", "Pedal Acelerador", "PEDAL", "%", 0,
        mapOf("%" to EscalaGauge(0f, 100f, null, 5, 4)),
        alternativos = listOf("TOYOTA_PEDAL_D", "TOYOTA_PEDAL_E"),
    ),
    TENSAO(
        "ATRV", "Bateria", "BATERIA", "V", 1,
        mapOf("V" to EscalaGauge(8f, 16f, 15f, 8, 2)),
    ),
    RAIL(
        "0123", "Common Rail", "RAIL", "kPa", 0,
        mapOf(
            "bar" to EscalaGauge(0f, 2000f, 1800f, 4, 5),
            "psi" to EscalaGauge(0f, 30000f, 26000f, 6, 5, 1000f),
        ),
    ),
    MAF(
        "0110", "MAF", "MAF", "g/s", 1,
        mapOf("g/s" to EscalaGauge(0f, 250f, null, 5, 5)),
    ),
    COMBUSTIVEL(
        "012F", "Tanque", "TANQUE", "%", 0,
        mapOf("%" to EscalaGauge(0f, 100f, null, 4, 5)),
    ),
    CONSUMO(
        "015E", "Consumo", "CONSUMO", "L/h", 1,
        mapOf(
            "L/h" to EscalaGauge(0f, 30f, null, 6, 5),
            "gal/h" to EscalaGauge(0f, 8f, null, 4, 4),
        ),
    ),

    // ---- Toyota SW4 / Hilux (perfil de montadora, pids_estendidos.json): só leem se o módulo responder.
    TEMP_CAMBIO(
        "TOYOTA_ATF_CAMBIO", "Temp. Câmbio (ATF)", "ATF", "°C", 0,
        mapOf(
            "°C" to EscalaGauge(40f, 150f, 120f, 11, 2),
            "°F" to EscalaGauge(100f, 300f, 250f, 10, 2),
        ),
        alternativos = listOf("TOYOTA_ATF", "TOYOTA_ATF_ECM"),
    ),
    MARCHA_ALAVANCA(
        "TOYOTA_ALAVANCA", "Marcha / Alavanca", "ALAVANCA", "", 0,
        mapOf("" to EscalaGauge(0f, 6f, null, 6, 1)),
        alternativos = listOf("TOYOTA_ALAVANCA_ECM"),
        rotulos = mapOf(0 to "P", 1 to "R", 2 to "N", 3 to "D", 4 to "3", 5 to "2", 6 to "L"),
    ),
    MARCHA_ATUAL(
        "TOYOTA_MARCHA", "Marcha", "MARCHA", "", 0,
        mapOf("" to EscalaGauge(0f, 6f, null, 6, 1)),
        rotulos = mapOf(0 to "N", 1 to "1ª", 2 to "2ª", 3 to "3ª", 4 to "4ª", 5 to "5ª", 255 to "Parado"),
    ),
    TRACAO_4X4(
        "TOYOTA_4WD", "Tração 4x4", "4X4", "", 0,
        mapOf("" to EscalaGauge(0f, 4f, null, 4, 1)),
        rotulos = mapOf(0 to "4x2 (H2)", 1 to "4x4 (H4)", 2 to "H4 bloqueado", 3 to "Reduzida (L4)", 4 to "L4 + bloqueio traseiro"),
    ),
    VSC(
        "TOYOTA_VSC", "VSC / Estabilidade", "VSC", "", 0,
        mapOf("" to EscalaGauge(0f, 4f, 3f, 4, 1)),
        rotulos = mapOf(0 to "Normal", 1 to "Atuando", 2 to "VSC OFF", 3 to "TRC OFF", 4 to "Falha"),
    ),
    TENSAO_ECU(
        "TOYOTA_TENSAO_ECU", "Tensão da ECU", "ECU", "V", 2,
        mapOf("V" to EscalaGauge(8f, 16f, 15f, 8, 2)),
    ),
    PRESSAO_BARO(
        "TOYOTA_BARO", "Pressão Barométrica", "BARO", "kPa", 0,
        mapOf(
            "kPa" to EscalaGauge(60f, 110f, null, 5, 5),
            "bar" to EscalaGauge(0.6f, 1.1f, null, 5, 5),
            "psi" to EscalaGauge(8f, 16f, null, 8, 2),
        ),
    ),
    BICO_1("TOYOTA_INJ1", "Bico Injetor #1", "BICO 1", "mm³/st", 2, ESCALA_BICO, comSinal = true),
    BICO_2("TOYOTA_INJ2", "Bico Injetor #2", "BICO 2", "mm³/st", 2, ESCALA_BICO, comSinal = true),
    BICO_3("TOYOTA_INJ3", "Bico Injetor #3", "BICO 3", "mm³/st", 2, ESCALA_BICO, comSinal = true),
    BICO_4("TOYOTA_INJ4", "Bico Injetor #4", "BICO 4", "mm³/st", 2, ESCALA_BICO, comSinal = true),
    ;

    /** Todas as chaves que alimentam o mostrador, na ordem de preferência. */
    val comandos: List<String> get() = listOf(comando) + alternativos

    /** Sensor de estado (P/R/N/D, 4x2/4x4...): o texto vem de [rotulos]. */
    val ehEstado: Boolean get() = rotulos.isNotEmpty()

    /** Texto de exibição: o rótulo do estado (`D`, `4x4 (H4)`) ou o número já convertido; "—" sem leitura. */
    fun texto(valor: Double?, conversor: ConversorUnidades): String {
        if (valor == null || !valor.isFinite()) return if (this == TRACAO_4X4 || this == VSC) NAO_DISPONIVEL else SEM_LEITURA
        DefinicaoPid.rotuloDe(rotulos, valor)?.let { return it }
        val v = exibir(valor, conversor)
        val numero = v.valor.comoNumero(v.casas)
        return if (comSinal && v.valor > 0 && !numero.startsWith("+")) "+$numero" else numero
    }

    /**
     * Valor na unidade de exibição do [conversor]. O rail sai em bar no métrico (kPa teria 6
     * dígitos) e em psi no imperial ou com psi escolhido.
     */
    fun exibir(valor: Double, conversor: ConversorUnidades): ValorExibicao {
        if (this == RAIL) {
            val unidade = if (conversor.pressao == UnidadePressao.PSI) UnidadePressao.PSI else UnidadePressao.BAR
            return ValorExibicao(unidade.deKpa(valor), unidade.simbolo, 0)
        }
        return conversor.converter(valor, unidadeCatalogo, casas)
    }

    /** Símbolo de exibição (`km/h` ou `mph`, `°C` ou `°F`, `bar`...). */
    fun unidade(conversor: ConversorUnidades): String = exibir(0.0, conversor).unidade

    /** Escala do mostrador na unidade de exibição; sem uma específica, 0–100. */
    fun escala(conversor: ConversorUnidades): EscalaGauge = escalas[unidade(conversor)] ?: escalas.values.firstOrNull() ?: EscalaGauge()

    /** `TURBO/MAP bar`, `MOTOR °F`; o RPM dispensa a unidade. */
    fun rotuloHud(conversor: ConversorUnidades): String {
        val unidade = unidade(conversor)
        return if (unidade.isBlank() || unidade.equals(rotuloHud, ignoreCase = true)) rotuloHud else "$rotuloHud $unidade"
    }

    /** Leitura do sensor no instantâneo [dados] (ainda na unidade do catálogo): o principal ou o 1º alternativo lido. */
    fun ler(dados: ValoresSensores?): Double? {
        if (dados == null) return null
        return comandos.firstNotNullOfOrNull { c -> dados[c]?.takeIf { it.isFinite() } }
    }

    companion object {
        const val SEM_LEITURA = "—"

        /** 4x4 (7C0) e VSC (7B0) não respondem na SW4 1KD-FTV + A750F. */
        const val NAO_DISPONIVEL = "N/D"

        fun deCodigo(codigo: String?): SensorPainel? = entries.firstOrNull { it.name.equals(codigo?.trim(), ignoreCase = true) }

        fun doComando(comando: String): SensorPainel? =
            entries.firstOrNull { s -> s.comandos.any { it.equals(comando, ignoreCase = true) } }
    }
}
