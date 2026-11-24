package com.charruamotors.obd.pid

import com.charruamotors.obd.dtc.normalizarMarca
import org.json.JSONException
import org.json.JSONObject

/**
 * Requisição a um serviço específico de montadora, endereçada fisicamente a um módulo pelo
 * cabeçalho CAN de 11 bits (`ATSH 7E0` = motor, `7E1` = transmissão):
 * - serviço `21` (KWP2000 *readDataByLocalIdentifier*, usado pela Toyota): identificador de 1 byte;
 * - serviço `22` (UDS *readDataByIdentifier*, usado pela VW): DID de 2 bytes.
 *
 * A resposta positiva é `serviço + 0x40` seguido do identificador (`61 82 ...`, `62 F4 0C ...`);
 * a negativa é `7F <serviço> <NRC>`.
 */
data class RequisicaoEstendida(val cabecalho: String, val servico: String, val identificador: String) {
    init {
        require(REGEX_CABECALHO.matches(cabecalho)) { "Cabeçalho CAN de 11 bits inválido: $cabecalho" }
        val bytesId = TAMANHO_IDENTIFICADOR[servico] ?: throw IllegalArgumentException("Serviço não suportado: $servico")
        require(Regex("^[0-9A-F]{${bytesId * 2}}$").matches(identificador)) {
            "Identificador \"$identificador\" inválido para o serviço $servico ($bytesId byte(s) hexadecimais)"
        }
    }

    /** Texto enviado ao ELM327 depois do `ATSH` (ex.: `2182`, `22F40C`). */
    val comando: String get() = servico + identificador

    /** Início da resposta positiva (ex.: `6182`, `62F40C`). */
    val prefixoResposta: String get() = "%02X".format(servico.toInt(16) + RESPOSTA_POSITIVA) + identificador

    /**
     * A resposta vem em vários quadros ISO-TP (`21D9` do câmbio A750F, `21AD` dos bicos injetores
     * da 1KD-FTV): o sufixo `1` de resposta única faz o ELM327 descartar ou truncar a resposta,
     * então essas requisições nunca o recebem.
     */
    val multiFrame: Boolean get() = servico == "21" && identificador in IDS_MULTI_FRAME_TOYOTA

    /** Início da resposta negativa (`7F21`, `7F22`). */
    val prefixoNegativa: String get() = "7F$servico"

    companion object {
        private val REGEX_CABECALHO = Regex("^[0-7][0-9A-F]{2}$")
        private val TAMANHO_IDENTIFICADOR = mapOf("21" to 1, "22" to 2)
        private const val RESPOSTA_POSITIVA = 0x40
        private val IDS_MULTI_FRAME_TOYOTA = setOf("D9", "AD")

        /** Cabeçalho funcional (broadcast) padrão do OBD2 em CAN de 11 bits. */
        const val CABECALHO_FUNCIONAL = "7DF"

        /**
         * Filtro de recepção (`ATCRA`) que o [cabecalho] físico exige: a resposta de um módulo
         * vem no endereço + 8 (`7B0` → `7B8`), e o ELM327 só aceita por padrão as respostas OBD2
         * `7E8`–`7EF`. Null para o funcional `7DF`, os módulos `7E0`–`7E7` (cobertos pelo filtro
         * padrão) e cabeçalhos de 29 bits.
         */
        fun filtroRecepcao(cabecalho: String?): String? {
            val c = cabecalho?.trim()?.uppercase() ?: return null
            if (!REGEX_CABECALHO.matches(c)) return null
            val endereco = c.toInt(16)
            if (endereco == 0x7DF || endereco in 0x7E0..0x7E7 || endereco + 8 > 0x7FF) return null
            return "%03X".format(endereco + 8)
        }
    }
}

/**
 * Perfil de PIDs específicos de uma montadora (ex.: Toyota modo 21, VW UDS 22), ativado só
 * quando a marca do veículo cadastrado é atendida e a ECU responde positivamente.
 */
class PerfilFabricante(
    val nome: String,
    aliases: Collection<String>,
    val pids: List<DefinicaoPid>,
    /** Aviso sobre a origem dos endereços/fórmulas (exibido e documentado no JSON). */
    val observacao: String = "",
) {
    val aliases: Set<String> = aliases.map(::normalizarMarca).toSet() + normalizarMarca(nome)

    init {
        require(nome.isNotBlank()) { "Perfil sem nome" }
        require(pids.all { it.ehEstendido }) { "$nome: o perfil só aceita PIDs estendidos" }
    }

    /** A marca do veículo (ex.: "VW Gol", "Toyota") é deste perfil? Mesma regra do catálogo de DTCs. */
    fun atende(marca: String): Boolean {
        val m = normalizarMarca(marca)
        if (m.isEmpty()) return false
        return m in aliases || m.substringBefore(' ') in aliases
    }

    /** Requisições distintas: PIDs que compartilham a resposta (ex.: injetores 1–4) são lidos de uma vez. */
    val requisicoes: Map<RequisicaoEstendida, List<DefinicaoPid>> get() = pids.groupBy { it.estendida!! }
}

/**
 * Catálogo de perfis de montadora (`assets/pids_estendidos.json`). Objeto puro, testável na JVM.
 * Chaves (`id`) são únicas em todo o catálogo e nunca colidem com PIDs do modo 01/AT.
 */
class CatalogoEstendido(val perfis: List<PerfilFabricante>) {

    private val porChave: Map<String, DefinicaoPid> = perfis.flatMap { it.pids }.associateBy { it.comando }

    init {
        val todas = perfis.flatMap { it.pids }
        require(porChave.size == todas.size) {
            "IDs repetidos no catálogo estendido: ${todas.groupBy { it.comando }.filterValues { it.size > 1 }.keys}"
        }
    }

    val definicoes: List<DefinicaoPid> get() = perfis.flatMap { it.pids }

    operator fun get(chave: String): DefinicaoPid? = porChave[chave.uppercase()]

    /** Perfil da marca do veículo; null se a marca não tiver perfil (ou não for informada). */
    fun perfilPara(marca: String?): PerfilFabricante? =
        marca?.let { m -> perfis.firstOrNull { it.atende(m) } }

    companion object {
        const val ARQUIVO_PADRAO = "pids_estendidos.json"

        val VAZIO = CatalogoEstendido(emptyList())

        /**
         * `{"perfis": [{"fabricante", "aliases", "observacao", "pids": [{"id", "cabecalho", "servico",
         * "identificador", "nome", "unidade", "bytes", "formula", "min", "max", "casas"}]}]}`.
         * @throws IllegalArgumentException se o JSON ou alguma definição for inválida.
         */
        fun deJson(json: String): CatalogoEstendido = try {
            val perfis = JSONObject(json).optJSONArray("perfis")
            CatalogoEstendido(
                (0 until (perfis?.length() ?: 0)).map { i ->
                    val p = perfis!!.getJSONObject(i)
                    val nome = p.getString("fabricante").trim()
                    val aliases = p.optJSONArray("aliases")
                    val pids = p.getJSONArray("pids")
                    PerfilFabricante(
                        nome = nome,
                        aliases = (0 until (aliases?.length() ?: 0)).map { aliases!!.getString(it) },
                        observacao = p.optString("observacao", ""),
                        pids = (0 until pids.length()).map { j ->
                            val o = pids.getJSONObject(j)
                            val requisicao = try {
                                RequisicaoEstendida(
                                    cabecalho = o.getString("cabecalho").trim().uppercase(),
                                    servico = o.getString("servico").trim().uppercase(),
                                    identificador = o.getString("identificador").trim().uppercase(),
                                )
                            } catch (e: IllegalArgumentException) {
                                throw IllegalArgumentException("$nome / ${o.optString("id")}: ${e.message}", e)
                            }
                            CatalogoPids.definicaoDeJson(o, requisicao)
                        },
                    )
                },
            )
        } catch (e: JSONException) {
            throw IllegalArgumentException("Catálogo estendido com JSON inválido: ${e.message}", e)
        }
    }
}
