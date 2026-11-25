package com.charruamotors.obd.dtc

import com.charruamotors.obd.DescricaoDtc
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.Normalizer

/** Categoria de um DTC pelo modo OBD2 em que foi lido. */
enum class TipoDtc(val modo: String) {
    /** Modo 03: falha confirmada (acende a luz de injeção). */
    CONFIRMADO("03"),

    /** Modo 07: detectada no ciclo atual/anterior, ainda não confirmada. */
    PENDENTE("07"),

    /** Modo 0A: não é apagada pelo modo 04; só a ECU a remove após validar o reparo. */
    PERMANENTE("0A"),

    /** v2.2.9: falha do câmbio automático lida no TCM `7E1` (`03`, ou UDS `19 02 FF`). */
    CAMBIO("03 · 7E1"),
}

/** Entrada do catálogo: título curto e descrição técnica (causas prováveis e o que verificar). */
data class EntradaDtc(val codigo: String, val titulo: String, val descricao: String)

/** Resultado da consulta de um código ao catálogo. */
data class InfoDtc(
    val codigo: String,
    val titulo: String,
    /** Descrição técnica; null quando só há o texto genérico por sistema. */
    val descricao: String?,
    /** Fabricante cuja tabela forneceu o significado (null = código genérico SAE ou desconhecido). */
    val fabricante: String? = null,
    /** O código estava no catálogo (false = texto genérico por sistema). */
    val catalogado: Boolean = true,
)

/** Tabela de códigos específicos de uma marca. */
class FabricanteDtc(val nome: String, aliases: Collection<String>, codigos: Collection<EntradaDtc>) {
    val aliases: Set<String> = aliases.map(::normalizarMarca).toSet() + normalizarMarca(nome)
    val codigos: Map<String, EntradaDtc> = codigos.associateBy { it.codigo }

    /** A marca do veículo cadastrado (ex.: "VW", "Volkswagen", "Toyota Hilux") é desta tabela? */
    fun atende(marca: String): Boolean {
        val m = normalizarMarca(marca)
        if (m.isEmpty()) return false
        val primeiraPalavra = m.substringBefore(' ')
        return m in aliases || primeiraPalavra in aliases
    }
}

/**
 * Banco offline de DTCs (`assets/dtc_catalogo.json`): códigos genéricos SAE e tabelas por
 * fabricante. Códigos fora do catálogo recebem o texto genérico por sistema do [DescricaoDtc].
 * Objeto puro (usa apenas `org.json`), testável na JVM.
 */
class CatalogoDtc(
    genericos: Collection<EntradaDtc>,
    val fabricantes: List<FabricanteDtc> = emptyList(),
) {
    val genericos: Map<String, EntradaDtc> = genericos.associateBy { it.codigo }

    /** Total de entradas (genéricas + de todos os fabricantes). */
    val total: Int get() = genericos.size + fabricantes.sumOf { it.codigos.size }

    /**
     * Significado do [codigo] para um veículo da [marca] (opcional). Ordem de busca:
     * 1. tabela do fabricante da marca; 2. códigos genéricos; 3. tabelas dos demais fabricantes
     * (se só um conhece o código, usa esse e informa qual; se vários, lista os significados);
     * 4. texto genérico por sistema.
     */
    fun descrever(codigo: String, marca: String? = null): InfoDtc {
        val c = codigo.trim().uppercase()
        val daMarca = marca?.let { m -> fabricantes.firstOrNull { it.atende(m) } }
        daMarca?.codigos?.get(c)?.let { return it.paraInfo(daMarca.nome) }
        genericos[c]?.let { return it.paraInfo(null) }

        val candidatos = fabricantes.filter { it !== daMarca }.mapNotNull { f -> f.codigos[c]?.let { f.nome to it } }
        val generico = DescricaoDtc.descricaoPorSistema(c)
        return when {
            candidatos.size == 1 -> candidatos.single().let { (nome, e) -> e.paraInfo(nome) }
            candidatos.size > 1 -> InfoDtc(
                codigo = c,
                titulo = generico,
                descricao = "O significado depende do fabricante. " +
                    candidatos.joinToString("; ") { (nome, e) -> "$nome: ${e.titulo}" } + ".",
                catalogado = false,
            )
            else -> InfoDtc(c, generico, descricao = null, catalogado = false)
        }
    }

    private fun EntradaDtc.paraInfo(fabricante: String?) = InfoDtc(codigo, titulo, descricao, fabricante)

    companion object {
        /** Nome do arquivo em `app/src/main/assets`. */
        const val ARQUIVO_PADRAO = "dtc_catalogo.json"

        /** Catálogo vazio: tudo cai no texto genérico por sistema. */
        val VAZIO = CatalogoDtc(emptyList())

        private val REGEX_CODIGO = Regex("^[PCBU][0-3][0-9A-F]{3}$")

        /**
         * Lê o catálogo (`{"genericos": [...], "fabricantes": [{"nome", "aliases", "codigos": [...]}]}`).
         * @throws IllegalArgumentException se o JSON for inválido, um código estiver malformado,
         *   sem título, ou repetido dentro da mesma tabela.
         */
        fun deJson(json: String): CatalogoDtc = try {
            val raiz = JSONObject(json)
            val genericos = lerEntradas(raiz.optJSONArray("genericos") ?: JSONArray(), "genéricos")
            val fabricantes = raiz.optJSONArray("fabricantes")?.let { lista ->
                (0 until lista.length()).map { i ->
                    val f = lista.getJSONObject(i)
                    val nome = f.getString("nome").trim()
                    require(nome.isNotEmpty()) { "Fabricante sem nome" }
                    val aliases = f.optJSONArray("aliases")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                    FabricanteDtc(nome, aliases, lerEntradas(f.getJSONArray("codigos"), nome))
                }
            }.orEmpty()
            CatalogoDtc(genericos, fabricantes)
        } catch (e: JSONException) {
            throw IllegalArgumentException("Catálogo de DTCs com JSON inválido: ${e.message}", e)
        }

        private fun lerEntradas(lista: JSONArray, tabela: String): List<EntradaDtc> {
            val entradas = (0 until lista.length()).map { i ->
                val o = lista.getJSONObject(i)
                val codigo = o.getString("codigo").trim().uppercase()
                require(REGEX_CODIGO.matches(codigo)) { "Código inválido em $tabela: \"$codigo\"" }
                val titulo = o.getString("titulo").trim()
                require(titulo.isNotEmpty()) { "$codigo ($tabela) sem título" }
                EntradaDtc(codigo, titulo, o.optString("descricao", "").trim())
            }
            val repetidos = entradas.groupBy { it.codigo }.filterValues { it.size > 1 }.keys
            require(repetidos.isEmpty()) { "Códigos repetidos em $tabela: $repetidos" }
            return entradas
        }
    }
}

/** "Volkswagen " → "volkswagen", "Citroën" → "citroen": minúsculas, sem acentos e espaços extras. */
internal fun normalizarMarca(marca: String): String =
    Normalizer.normalize(marca.trim().lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("\\s+"), " ")
