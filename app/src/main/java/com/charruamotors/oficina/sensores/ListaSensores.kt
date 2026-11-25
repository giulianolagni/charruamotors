package com.charruamotors.oficina.sensores

import com.charruamotors.direcao.painel.ConfigPainel
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.obd.pid.DefinicaoPid
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.sistema.unidades.ValorExibicao
import com.charruamotors.ui.util.comoNumero
import java.text.Normalizer

/** Textos de um sensor na unidade das Configurações. */
object FormatadorSensor {
    const val SEM_LEITURA = "—"

    private fun exibir(def: DefinicaoPid, valor: Double, conversor: ConversorUnidades): ValorExibicao =
        // O rail sai em bar/psi como no Painel (em kPa teria 6 dígitos).
        if (SensorPainel.doComando(def.comando) == SensorPainel.RAIL) {
            SensorPainel.RAIL.exibir(valor, conversor)
        } else {
            conversor.converter(valor, def.unidade, def.casasDecimais)
        }

    /** O rótulo do estado (`D`, `3ª`) ou o número convertido, sem a unidade; "—" sem leitura. */
    fun valor(def: DefinicaoPid, valor: Double?, conversor: ConversorUnidades): String {
        if (valor == null || !valor.isFinite()) return SEM_LEITURA
        def.rotulo(valor)?.let { return it }
        val v = exibir(def, valor, conversor)
        return if (v.valor.isFinite()) v.valor.comoNumero(v.casas) else SEM_LEITURA
    }

    fun unidade(def: DefinicaoPid, conversor: ConversorUnidades): String =
        if (def.rotulos.isNotEmpty()) "" else exibir(def, 0.0, conversor).unidade
}

/** Uma linha da tela Todos os Sensores. */
data class LinhaSensor(
    val comando: String,
    val nome: String,
    /** PID discreto: `010C`, `ATRV` ou `7E0 · 210C`. */
    val pid: String,
    val valor: String,
    val unidade: String,
    /** Vazios nos sensores de estado e nos que não responderam. */
    val minimo: String,
    val maximo: String,
    /** Respondeu ao menos uma vez nesta sessão. */
    val respondeu: Boolean,
    val fixado: Boolean,
)

/** Monta a lista da tela Todos os Sensores. Puro (JVM). */
object MontadorListaSensores {

    /**
     * @param definicoes sensores do perfil da sessão (os repetidos por comando são ignorados).
     * @param filtro busca por nome ou PID, sem diferenciar maiúsculas nem acentos.
     * @param soAtivos só os que responderam na sessão; false = todos os do perfil.
     */
    fun montar(
        definicoes: List<DefinicaoPid>,
        estatisticas: Map<String, MinMax>,
        atual: Map<String, Double>,
        conversor: ConversorUnidades,
        painel: ConfigPainel,
        filtro: String = "",
        soAtivos: Boolean = true,
    ): List<LinhaSensor> {
        val busca = normalizar(filtro)
        return definicoes.distinctBy { it.comando }
            .filter { !soAtivos || it.comando in estatisticas }
            .filter { d -> busca.isEmpty() || listOf(d.nome, d.comando, d.rotuloComando).any { busca in normalizar(it) } }
            .map { d ->
                val extremos = estatisticas[d.comando]?.takeIf { d.rotulos.isEmpty() }
                LinhaSensor(
                    comando = d.comando,
                    nome = d.nome,
                    pid = d.rotuloComando,
                    valor = FormatadorSensor.valor(d, atual[d.comando], conversor),
                    unidade = FormatadorSensor.unidade(d, conversor),
                    minimo = extremos?.let { FormatadorSensor.valor(d, it.min, conversor) }.orEmpty(),
                    maximo = extremos?.let { FormatadorSensor.valor(d, it.max, conversor) }.orEmpty(),
                    respondeu = d.comando in estatisticas,
                    fixado = painel.fixadoNoPainel(d.comando),
                )
            }
    }

    private fun normalizar(texto: String): String =
        Normalizer.normalize(texto.trim(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
}
