package com.charruamotors.direcao.painel

import com.charruamotors.obd.ValoresSensores
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.sistema.unidades.UnidadePressao
import com.charruamotors.sistema.unidades.ValorExibicao
import com.charruamotors.ui.componentes.EscalaGauge

/**
 * Sensores que podem ocupar um dos 4 mostradores do Painel ou uma célula do HUD flutuante, com a
 * escala de cada unidade de exibição (métrica e imperial). [comando] é a chave do PID canônico no
 * catálogo (`010C`, `ATRV`...): os espelhos dos modos 21/22 publicam no mesmo lugar.
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
        "0149", "Acelerador", "PEDAL", "%", 0,
        mapOf("%" to EscalaGauge(0f, 100f, null, 5, 4)),
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
    ;

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
        return if (unidade.equals(rotuloHud, ignoreCase = true)) rotuloHud else "$rotuloHud $unidade"
    }

    /** Leitura do sensor no instantâneo [dados] (ainda na unidade do catálogo). */
    fun ler(dados: ValoresSensores?): Double? = dados?.get(comando)?.takeIf { it.isFinite() }

    companion object {
        fun deCodigo(codigo: String?): SensorPainel? = entries.firstOrNull { it.name.equals(codigo?.trim(), ignoreCase = true) }

        fun doComando(comando: String): SensorPainel? = entries.firstOrNull { it.comando.equals(comando, ignoreCase = true) }
    }
}
