package com.charruamotors.obd.pid

import com.charruamotors.obd.ModoComunicacaoEcu
import com.charruamotors.obd.dtc.normalizarMarca
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Protocolo físico forçado na camada 2 da cascata (`ATSP<codigo>`). */
data class ProtocoloFisico(val codigo: String, val nome: String) {
    init {
        require(Regex("^[1-9A-C]$").matches(codigo)) { "Código de protocolo do ELM327 inválido: $codigo" }
    }

    /** Comando que seleciona o protocolo no ELM327 (`ATSP6`). */
    val comando: String get() = "ATSP$codigo"

    /** CAN de 11 bits (6 = 500 kbps, 8 = 250 kbps): o único em que o cabeçalho físico `7E0` vale. */
    val ehCan11Bits: Boolean get() = codigo == "6" || codigo == "8"
}

/**
 * Comandos de falhas de um modo de serviço: [ler] é tentado em ordem até um responder
 * positivamente; [limpar], em ordem até a ECU confirmar a limpeza.
 */
data class ComandosDtc(val ler: List<String>, val limpar: List<String>) {
    init {
        require(ler.isNotEmpty() && limpar.isNotEmpty()) { "Modo de serviço sem comandos de leitura/limpeza de falhas" }
        require(ler.all { it.take(2) in SERVICOS_LEITURA && REGEX_HEX.matches(it) }) { "Comando de leitura de falhas inválido: $ler" }
        require(limpar.all { it.take(2) in SERVICOS_LIMPEZA && REGEX_HEX.matches(it) }) { "Comando de limpeza de falhas inválido: $limpar" }
    }

    private companion object {
        val REGEX_HEX = Regex("^([0-9A-F]{2})+$")

        /** 03 (OBD2), 13 (KWP readDTC), 18 (KWP readDTCByStatus), 19 (UDS readDTCInformation). */
        val SERVICOS_LEITURA = setOf("03", "13", "18", "19")

        /** 04 (OBD2) e 14 (KWP/UDS clearDiagnosticInformation). */
        val SERVICOS_LIMPEZA = setOf("04", "14")
    }
}

/**
 * Camada 3 da cascata: um serviço de diagnóstico da montadora (21 KWP / 22 UDS) endereçado ao
 * motor pelo [cabecalho], com os [pids] espelhados do SAE (cada um na chave canônica do sensor).
 */
class ModoServico(
    val id: String,
    val nome: String,
    val descricao: String,
    val cabecalho: String,
    val servico: String,
    marcas: Collection<String>,
    val dtc: ComandosDtc,
    val pids: List<DefinicaoPid>,
) {
    /** Marcas (normalizadas) que tentam este modo antes dos outros. */
    val marcas: Set<String> = marcas.map(::normalizarMarca).filter { it.isNotEmpty() }.toSet()

    val tipo: ModoComunicacaoEcu = ModoComunicacaoEcu.doServico(servico)
        ?: throw IllegalArgumentException("$id: serviço $servico sem modo de comunicação correspondente")

    init {
        require(nome.isNotBlank()) { "$id: modo sem nome" }
        require(pids.isNotEmpty()) { "$id: modo sem PIDs" }
        require(pids.all { it.ehEspelho && it.estendida?.servico == servico && it.estendida?.cabecalho == cabecalho }) {
            "$id: todos os PIDs precisam ser espelhos do serviço $servico no cabeçalho $cabecalho"
        }
        require(pids.map { it.comando }.distinct().size == pids.size) { "$id: chave semântica repetida" }
    }

    /** O veículo da [marca] (ex.: "Toyota Hilux") prefere este modo? */
    fun preferidoPor(marca: String?): Boolean {
        val m = normalizarMarca(marca.orEmpty())
        if (m.isEmpty()) return false
        return m in marcas || m.substringBefore(' ') in marcas
    }
}

/**
 * Catálogo da cascata de conexão (`assets/cascata_conexao.json`): protocolos físicos da camada 2
 * e modos de serviço da camada 3. Objeto puro, testável na JVM.
 */
class CatalogoCascata(
    val protocolosFisicos: List<ProtocoloFisico>,
    val modosServico: List<ModoServico>,
) {
    init {
        require(protocolosFisicos.map { it.codigo }.distinct().size == protocolosFisicos.size) { "Protocolo físico repetido" }
        require(modosServico.map { it.id }.distinct().size == modosServico.size) { "Modo de serviço repetido" }
    }

    /** Ordem de tentativa da camada 3: os modos que preferem a [marca] primeiro, depois a ordem do JSON. */
    fun modosPara(marca: String?): List<ModoServico> = modosServico.sortedBy { if (it.preferidoPor(marca)) 0 else 1 }

    /**
     * v2.3.0: com a camada preferida do perfil do carro ([servico] `21`), esse serviço vai para a
     * frente; os demais seguem na ordem da [marca]. Null = só a ordem da marca.
     */
    fun modosPara(marca: String?, servico: String?): List<ModoServico> {
        val porMarca = modosPara(marca)
        if (servico == null) return porMarca
        return porMarca.sortedBy { if (it.servico.equals(servico, ignoreCase = true)) 0 else 1 }
    }

    companion object {
        const val ARQUIVO_PADRAO = "cascata_conexao.json"

        val VAZIO = CatalogoCascata(emptyList(), emptyList())

        /**
         * Lê o JSON. Cada PID de modo de serviço precisa de `identificador` e `chave`; o resto
         * (`nome`, `unidade`, `bytes`, `formula`, `min`, `max`, `casas`) é herdado do PID do
         * [padrao] que tem a mesma chave, quando omitido.
         * @throws IllegalArgumentException se o JSON ou alguma definição for inválida.
         */
        fun deJson(json: String, padrao: CatalogoPids): CatalogoCascata = try {
            val raiz = JSONObject(json)
            val protocolos = raiz.optJSONArray("protocolosFisicos").objetos().map {
                ProtocoloFisico(it.getString("codigo").trim().uppercase(), it.optString("nome", ""))
            }
            val modos = raiz.optJSONArray("modosServico").objetos().map { modoDeJson(it, padrao) }
            CatalogoCascata(protocolos, modos)
        } catch (e: JSONException) {
            throw IllegalArgumentException("Catálogo da cascata com JSON inválido: ${e.message}", e)
        }

        private fun modoDeJson(o: JSONObject, padrao: CatalogoPids): ModoServico {
            val id = o.getString("id").trim()
            val cabecalho = o.getString("cabecalho").trim().uppercase()
            val servico = o.getString("servico").trim().uppercase()
            val dtc = o.getJSONObject("dtc")
            return try {
                ModoServico(
                    id = id,
                    nome = o.getString("nome").trim(),
                    descricao = o.optString("descricao", ""),
                    cabecalho = cabecalho,
                    servico = servico,
                    marcas = o.optJSONArray("marcas").textos(),
                    dtc = ComandosDtc(
                        ler = dtc.textosOuTexto("ler").map { it.compactarHex() },
                        limpar = dtc.textosOuTexto("limpar").map { it.compactarHex() },
                    ),
                    pids = o.getJSONArray("pids").objetos().map { espelhoDeJson(it, cabecalho, servico, padrao) },
                )
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Modo $id: ${e.message}", e)
            }
        }

        private fun espelhoDeJson(o: JSONObject, cabecalho: String, servico: String, padrao: CatalogoPids): DefinicaoPid {
            val chave = ChaveSemantica.deTexto(o.getString("chave"))!!
            val canonico = padrao[chave]
                ?: throw IllegalArgumentException("chave $chave sem PID canônico em ${CatalogoPids.ARQUIVO_PADRAO}")
            val requisicao = RequisicaoEstendida(cabecalho, servico, o.getString("identificador").trim().uppercase())
            return DefinicaoPid(
                comando = canonico.comando,
                nome = o.optString("nome", "").ifBlank { canonico.nome },
                unidade = o.optString("unidade", "").ifBlank { canonico.unidade },
                bytes = if (o.has("bytes")) o.getInt("bytes") else canonico.bytes,
                formula = if (o.has("formula")) Formula.compilar(o.getString("formula")) else canonico.formula,
                min = if (o.has("min")) o.getDouble("min") else canonico.min,
                max = if (o.has("max")) o.getDouble("max") else canonico.max,
                casasDecimais = if (o.has("casas")) o.getInt("casas") else canonico.casasDecimais,
                estendida = requisicao,
                chave = chave,
            )
        }

        private fun JSONArray?.objetos(): List<JSONObject> = (0 until (this?.length() ?: 0)).map { this!!.getJSONObject(it) }

        private fun JSONArray?.textos(): List<String> = (0 until (this?.length() ?: 0)).map { this!!.getString(it) }

        /** `"ler": "13"` ou `"ler": ["13", "1800FF00"]`. */
        private fun JSONObject.textosOuTexto(campo: String): List<String> =
            optJSONArray(campo)?.textos() ?: listOf(getString(campo))

        private fun String.compactarHex() = filter { !it.isWhitespace() }.uppercase()
    }
}
