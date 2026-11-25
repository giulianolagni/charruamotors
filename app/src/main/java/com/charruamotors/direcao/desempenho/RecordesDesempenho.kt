package com.charruamotors.direcao.desempenho

import android.content.Context
import com.charruamotors.carros.GerenciadorPerfisCarro
import kotlinx.coroutines.flow.StateFlow

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

/**
 * Recordes 0–60 / 0–100 por veículo. v2.3.0: ficam no perfil isolado de cada carro
 * ([GerenciadorPerfisCarro], chave = `veiculoId`); até a v2.2.9 eram por nome curto no arquivo
 * `desempenho` ([lerLegado], migrado uma vez).
 */
class RecordesDesempenho(private val perfis: GerenciadorPerfisCarro) {

    /** Muda a cada gravação de perfil (as telas recalculam o texto do recorde). */
    val recordes: StateFlow<Long> get() = perfis.alteracoes

    fun recordeDe(veiculoId: Long): RecordeDesempenho = if (veiculoId > 0) perfis.ler(veiculoId).recorde else RecordeDesempenho()

    fun registrar(veiculoId: Long, t060: Double?, t0100: Double?) = perfis.registrarRecorde(veiculoId, t060, t0100)

    companion object {
        private const val ARQUIVO_LEGADO = "desempenho"

        /** Recordes da v2.0–v2.2.9 (`<nome curto>|060`, `<nome curto>|0100`), só para a migração. */
        fun lerLegado(context: Context): Map<String, RecordeDesempenho> {
            val prefs = context.applicationContext.getSharedPreferences(ARQUIVO_LEGADO, Context.MODE_PRIVATE)
            val todos = prefs.all
            val chaves = todos.keys.mapNotNull { it.substringBeforeLast('|', "").ifEmpty { null } }.toSet()
            fun tempo(chave: String): Double? = (todos[chave] as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0 }
            return chaves.associateWith { RecordeDesempenho(tempo("$it|060"), tempo("$it|0100")) }
        }
    }
}
