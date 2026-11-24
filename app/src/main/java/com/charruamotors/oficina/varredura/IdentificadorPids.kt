package com.charruamotors.oficina.varredura

import com.charruamotors.obd.pid.CatalogoCascata
import com.charruamotors.obd.pid.CatalogoEstendido
import com.charruamotors.obd.pid.CatalogoPids
import com.charruamotors.obd.pid.DefinicaoPid
import com.charruamotors.obd.pid.RequisicaoEstendida
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.sistema.unidades.ValorExibicao
import com.charruamotors.ui.util.comoNumero

/**
 * Um identificador do catálogo do app: [definicoes] decodificadas da mesma resposta (os 4
 * injetores da Toyota vêm todos no `2113`), a [origem] (OBD2 padrão, modo 21/22, perfil de
 * montadora) e o [cabecalho] físico em que ele vale (null = qualquer um, como o modo 01 no `7DF`).
 */
data class PidConhecido(
    val requisicao: String,
    val cabecalho: String?,
    val definicoes: List<DefinicaoPid>,
    val origem: String,
) {
    val nome: String get() = definicoes.first().nome
}

/** Como a varredura amigável enxerga cada identificador. */
enum class StatusIdentificacao {
    /** Respondeu e está no catálogo: nome do sensor e valor decodificado. */
    IDENTIFICADO,

    /** Respondeu, mas o app ainda não sabe o que é (candidato a mapear). */
    DESCONHECIDO,

    /** NO DATA, resposta negativa 7F ou erro: fora do filtro "o que o carro respondeu". */
    IGNORADO,
}

/** Resultado de um identificador cruzado com o catálogo, pronto para a lista ao vivo. */
data class IdentificacaoPid(
    val resultado: ResultadoPid,
    val status: StatusIdentificacao,
    val conhecido: PidConhecido? = null,
    /** Valor do primeiro sensor da resposta, já na unidade global; null se não decodificou. */
    val valor: ValorExibicao? = null,
    /** Cabeçalho físico consultado (`7B0`): distingue o mesmo `2101` em módulos diferentes. */
    val cabecalho: String? = null,
    /** Rótulo do estado decodificado (alavanca `D`, 4x4 `H4`), quando o sensor tem rótulos. */
    val rotulo: String? = null,
) {
    val comando: String get() = resultado.comando

    /**
     * `Identificado: Rotação do motor (PID 210C)`, `Desconhecido (PID 21D4 - resposta: 61D4...)`
     * ou `Sem resposta (PID 2155 - NO DATA)`.
     */
    val descricao: String
        get() = when (status) {
            StatusIdentificacao.IDENTIFICADO -> {
                val extras = (conhecido?.definicoes?.size ?: 1) - 1
                "Identificado: ${conhecido?.nome} (PID $comando)" + if (extras > 0) " +$extras sensor${if (extras > 1) "es" else ""}" else ""
            }
            StatusIdentificacao.DESCONHECIDO -> "Desconhecido (PID $comando - resposta: ${IdentificadorPids.abreviar(resultado.bytesHex)})"
            StatusIdentificacao.IGNORADO -> "Sem resposta (PID $comando - ${motivo()})"
        }

    /** `850 rpm`, `92 °F`; vazio quando não há valor. */
    val valorTexto: String get() = rotulo ?: valor?.let { "${it.valor.comoNumero(it.casas)} ${it.unidade}".trim() }.orEmpty()

    private fun motivo(): String = when (resultado.tipo) {
        TipoRespostaPid.NEGATIVA -> "7F ${comando.take(2)} ${resultado.nrc.orEmpty()}"
        TipoRespostaPid.SEM_DADOS -> "NO DATA"
        else -> resultado.erro ?: "erro"
    }
}

/** Contadores da barra colorida da varredura. */
data class MetricasVarredura(
    val identificados: Int = 0,
    val desconhecidos: Int = 0,
    val ignorados: Int = 0,
    val total: Int = 0,
) {
    /** "X sensores encontrados": tudo o que respondeu positivamente. */
    val encontrados: Int get() = identificados + desconhecidos
    val feitos: Int get() = encontrados + ignorados

    companion object {
        fun de(identificacoes: List<IdentificacaoPid>, total: Int) = MetricasVarredura(
            identificados = identificacoes.count { it.status == StatusIdentificacao.IDENTIFICADO },
            desconhecidos = identificacoes.count { it.status == StatusIdentificacao.DESCONHECIDO },
            ignorados = identificacoes.count { it.status == StatusIdentificacao.IGNORADO },
            total = total,
        )
    }
}

/**
 * Varredura "amigável": cruza cada resposta crua com tudo o que o app já conhece — PIDs padrão do
 * modo 01, espelhos dos modos 21/22 da cascata (`210C` = RPM no `7E0`) e perfis de montadora
 * (`2182` = ATF no `7E1`) — e decodifica o valor pela fórmula do catálogo. Objeto puro.
 */
class IdentificadorPids(conhecidos: List<PidConhecido>) {

    /** requisição → candidatos (com e sem cabeçalho específico). */
    private val porRequisicao: Map<String, List<PidConhecido>> = conhecidos.groupBy { it.requisicao.uppercase() }

    val quantidade: Int get() = porRequisicao.size

    /**
     * O catálogo conhece [comando] no [cabecalho]? O cabeçalho exato tem prioridade; depois, um sem
     * cabeçalho fixo e, por fim, um do mesmo trem de força (`7E0`–`7E7`/`7DF`). Módulos de chassi
     * (ABS `7B0`, painel `7C0`) só casam com o próprio endereço: o `2101` do motor não é o 4x4.
     */
    fun conhecido(cabecalho: String?, comando: String): PidConhecido? {
        val candidatos = porRequisicao[comando.uppercase()] ?: return null
        val cab = cabecalho?.uppercase()
        return candidatos.firstOrNull { it.cabecalho == cab }
            ?: candidatos.firstOrNull { it.cabecalho == null }
            ?: candidatos.firstOrNull { cab == null || (tremDeForca(cab) && tremDeForca(it.cabecalho)) }
    }

    private fun tremDeForca(cabecalho: String?) = RequisicaoEstendida.filtroRecepcao(cabecalho) == null

    fun identificar(
        resultado: ResultadoPid,
        cabecalho: String?,
        conversor: ConversorUnidades = ConversorUnidades.PADRAO,
    ): IdentificacaoPid {
        val cab = cabecalho?.uppercase()
        if (!resultado.respondeu) {
            return IdentificacaoPid(resultado, StatusIdentificacao.IGNORADO, conhecido(cabecalho, resultado.comando), cabecalho = cab)
        }
        val conhecido = conhecido(cabecalho, resultado.comando)
            ?: return IdentificacaoPid(resultado, StatusIdentificacao.DESCONHECIDO, cabecalho = cab)
        val digitos = if (resultado.comando.startsWith("22")) 4 else 2
        val definicao = conhecido.definicoes.first()
        val dados = resultado.dados(digitos)
        val bruto = if (definicao.rotulos.isEmpty()) null else runCatching { definicao.converter(dados) }.getOrNull()
        return IdentificacaoPid(
            resultado, StatusIdentificacao.IDENTIFICADO, conhecido, decodificar(definicao, dados, conversor),
            cabecalho = cab,
            rotulo = definicao.rotulo(bruto?.takeIf(definicao::dentroDaFaixa)),
        )
    }

    fun identificarTodos(resultados: List<ResultadoPid>, cabecalho: String?, conversor: ConversorUnidades = ConversorUnidades.PADRAO) =
        resultados.map { identificar(it, cabecalho, conversor) }

    companion object {
        const val ORIGEM_PADRAO = "OBD2 padrão"

        /** `61D4A0B1C2D3E4...`: hex compacto, cortado em [maximo] caracteres. */
        fun abreviar(bytesHex: String, maximo: Int = 12): String {
            val compacto = bytesHex.replace(" ", "")
            return if (compacto.length <= maximo) compacto else compacto.take(maximo) + "..."
        }

        /** Valor pela fórmula do catálogo, só se a resposta tiver bytes suficientes e cair na faixa teórica. */
        fun decodificar(definicao: DefinicaoPid, dados: List<Int>, conversor: ConversorUnidades): ValorExibicao? {
            if (definicao.ehComandoAt) return null
            val valor = runCatching { definicao.converter(dados) }.getOrNull()?.takeIf(definicao::dentroDaFaixa) ?: return null
            return conversor.converter(valor, definicao.unidade, definicao.casasDecimais)
        }

        /** Índice com os três catálogos do app. */
        fun de(
            padrao: CatalogoPids,
            estendido: CatalogoEstendido = CatalogoEstendido.VAZIO,
            cascata: CatalogoCascata = CatalogoCascata.VAZIO,
        ): IdentificadorPids {
            val lista = mutableListOf<PidConhecido>()
            padrao.pidsModo01.forEach { lista += PidConhecido(it.comando, null, listOf(it), ORIGEM_PADRAO) }
            for (modo in cascata.modosServico) {
                modo.pids.forEach { p ->
                    val req = p.estendida ?: return@forEach
                    lista += PidConhecido(req.comando, req.cabecalho, listOf(p), modo.nome)
                }
            }
            for (perfil in estendido.perfis) {
                perfil.requisicoes.forEach { (req, defs) -> lista += PidConhecido(req.comando, req.cabecalho, defs, perfil.nome) }
            }
            return IdentificadorPids(lista)
        }
    }
}
