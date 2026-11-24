package com.charruamotors.obd.aprendizado

import com.charruamotors.obd.log.OrganizadorLogs
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** CSV de uma sessão **real** vinculado ao veículo que a gerou ([uri] é por onde o app o reabre). */
data class EntradaLogReal(val veiculoId: Long, val nome: String, val uri: String)

/**
 * Índice (no armazenamento interno) de qual CSV real pertence a qual `veiculoId`. O nome do arquivo
 * só traz "Marca Modelo" (dois carros iguais colidiriam), então o vínculo exato é gravado aqui, no
 * fim de cada sessão real. Sessões simuladas nunca entram. Índice ilegível = vazio (nada é atribuído
 * a um veículo por palpite).
 */
class IndiceLogsReais(private val arquivo: File) {

    /** Registra o CSV de uma sessão real; false = recusado (simulado, sem veículo ou sem nome). */
    @Synchronized
    fun registrar(veiculoId: Long, nome: String, uri: String, simulado: Boolean): Boolean {
        if (simulado || veiculoId <= 0 || nome.isBlank() || OrganizadorLogs.ehSimulado(nome)) return false
        val atuais = ler()
        if (atuais.any { it.veiculoId == veiculoId && it.nome == nome }) return true
        return gravar(atuais + EntradaLogReal(veiculoId, nome, uri))
    }

    /** Só os logs reais **deste** veículo, na ordem em que foram registrados. */
    @Synchronized
    fun entradas(veiculoId: Long): List<EntradaLogReal> =
        ler().filter { it.veiculoId == veiculoId && !OrganizadorLogs.ehSimulado(it.nome) }

    /**
     * Remove do índice os vínculos dos arquivos [nomes] (de qualquer veículo) e devolve os
     * `veiculoId`s afetados, para o aprendizado deles ser refeito.
     */
    @Synchronized
    fun remover(nomes: Collection<String>): Set<Long> {
        if (nomes.isEmpty()) return emptySet()
        val alvo = nomes.toSet()
        val atuais = ler()
        val removidas = atuais.filter { it.nome in alvo }
        if (removidas.isEmpty()) return emptySet()
        gravar(atuais - removidas.toSet())
        return removidas.map { it.veiculoId }.toSet()
    }

    /** Todas as entradas do índice (de todos os veículos). */
    @Synchronized
    fun todas(): List<EntradaLogReal> = ler()

    private fun ler(): List<EntradaLogReal> {
        if (!arquivo.isFile) return emptyList()
        return try {
            val a = JSONArray(arquivo.readText(Charsets.UTF_8))
            List(a.length()) {
                val o = a.getJSONObject(it)
                EntradaLogReal(o.getLong("veiculoId"), o.getString("nome"), o.getString("uri"))
            }
        } catch (_: JSONException) {
            emptyList()
        } catch (_: IOException) {
            emptyList()
        }
    }

    private fun gravar(lista: List<EntradaLogReal>): Boolean = try {
        val json = JSONArray()
        lista.forEach { json.put(JSONObject().put("veiculoId", it.veiculoId).put("nome", it.nome).put("uri", it.uri)) }
        arquivo.parentFile?.mkdirs()
        val tmp = File(arquivo.parentFile, arquivo.name + ".tmp")
        tmp.writeText(json.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(arquivo)) {
            arquivo.writeText(json.toString(), Charsets.UTF_8)
            tmp.delete()
        }
        true
    } catch (_: IOException) {
        false
    }
}
