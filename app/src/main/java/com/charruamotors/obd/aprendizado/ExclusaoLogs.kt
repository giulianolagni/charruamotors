package com.charruamotors.obd.aprendizado

import com.charruamotors.obd.log.ItemLog

/** Resultado de uma exclusão de logs: nomes apagados e nomes que não foi possível apagar. */
data class ResultadoExclusao(val apagados: List<String>, val falhas: List<String>) {
    val total: Int get() = apagados.size + falhas.size
}

/**
 * Exclusão de logs do Histórico (v2.2.5). Apagar um `.csv`/`.txt` também tira o vínculo dele do
 * `indice_logs_reais.json` e refaz o `modelo_aprendido_<veiculoId>.json` do veículo, para o Modo
 * Simulado não continuar aprendendo com logs que o usuário já excluiu.
 */
object ExclusaoLogs {

    /**
     * Apaga [itens] com [apagar] (true = apagado) e, só para os que de fato sumiram, sincroniza o
     * índice e o aprendizado. Nunca lança por causa de um arquivo: a falha vai em [ResultadoExclusao.falhas].
     */
    fun <T : ItemLog> executar(itens: List<T>, aprendizado: AprendizLogsVeiculo?, apagar: (T) -> Boolean): ResultadoExclusao {
        val apagados = mutableListOf<String>()
        val falhas = mutableListOf<String>()
        for (item in itens) {
            val ok = try {
                apagar(item)
            } catch (_: Exception) {
                false
            }
            if (ok) apagados += item.nome else falhas += item.nome
        }
        if (apagados.isNotEmpty() && aprendizado != null) runCatching { aprendizado.logsApagados(apagados) }
        return ResultadoExclusao(apagados, falhas)
    }

    /**
     * Logs do veículo atual: os `.csv` vinculados a ele no índice ([nomesIndexados]), o `.txt` da mesma
     * sessão (mesmo nome-base) e, para os logs de antes do índice (≤ 2.2.3), os de nome
     * `CharruaOBD_[SIMULADO_]AAAAMMDD_HHMMSS_<trecho do veículo>`. Logs do Data Logger manual
     * (sem veículo no nome) só saem em "todos".
     */
    fun <T : ItemLog> doVeiculo(arquivos: List<T>, nomesIndexados: Collection<String>, trechoVeiculo: String?): List<T> {
        val bases = nomesIndexados.map(::semExtensao).toSet()
        val padrao = trechoVeiculo?.takeIf { it.isNotBlank() }?.let {
            Regex("^CharruaOBD_(SIMULADO_)?\\d{8}_\\d{6}_${Regex.escape(it)}$")
        }
        return arquivos.filter { semExtensao(it.nome) in bases || padrao?.matches(semExtensao(it.nome)) == true }
    }

    private fun semExtensao(nome: String): String = nome.substringBeforeLast('.', nome)
}
