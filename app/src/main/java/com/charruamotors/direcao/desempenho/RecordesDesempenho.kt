package com.charruamotors.direcao.desempenho

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Melhores tempos (segundos) de um veículo; null = ainda não medido. */
data class RecordeDesempenho(val melhor060: Double? = null, val melhor0100: Double? = null) {
    val texto: String
        get() = if (melhor060 == null && melhor0100 == null) "Sem recorde ainda"
        else "Melhor 0–60: ${EstadoDesempenho.formatarTempo(melhor060)} · 0–100: ${EstadoDesempenho.formatarTempo(melhor0100)}"

    /** Aplica um resultado e devolve o novo recorde (só melhora; tempos menores vencem). */
    fun com(t060: Double?, t0100: Double?) = RecordeDesempenho(
        melhor060 = menor(melhor060, t060),
        melhor0100 = menor(melhor0100, t0100),
    )

    private fun menor(a: Double?, b: Double?) = if (a == null) b else if (b == null) a else minOf(a, b)
}

/** Recordes por veículo (chave = nome curto do veículo ou [CHAVE_PADRAO]), em SharedPreferences. */
class RecordesDesempenho(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("desempenho", Context.MODE_PRIVATE)
    private val _recordes = MutableStateFlow(carregar())
    val recordes: StateFlow<Map<String, RecordeDesempenho>> = _recordes.asStateFlow()

    fun recordeDe(chave: String): RecordeDesempenho = _recordes.value[chave] ?: RecordeDesempenho()

    @Synchronized
    fun registrar(chave: String, t060: Double?, t0100: Double?) {
        val atual = recordeDe(chave)
        val novo = atual.com(t060, t0100)
        if (novo == atual) return
        prefs.edit().apply {
            novo.melhor060?.let { putFloat("$chave|060", it.toFloat()) }
            novo.melhor0100?.let { putFloat("$chave|0100", it.toFloat()) }
        }.apply()
        _recordes.value = _recordes.value + (chave to novo)
    }

    private fun carregar(): Map<String, RecordeDesempenho> {
        val chaves = prefs.all.keys.mapNotNull { it.substringBeforeLast('|', "").ifEmpty { null } }.toSet()
        return chaves.associateWith {
            RecordeDesempenho(
                melhor060 = prefs.takeIf { p -> p.contains("$it|060") }?.getFloat("$it|060", 0f)?.toDouble(),
                melhor0100 = prefs.takeIf { p -> p.contains("$it|0100") }?.getFloat("$it|0100", 0f)?.toDouble(),
            )
        }
    }

    companion object {
        const val CHAVE_PADRAO = "padrao"
    }
}
