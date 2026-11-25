package com.charruamotors.carros

import com.charruamotors.direcao.desempenho.RecordeDesempenho
import com.charruamotors.direcao.painel.ClusterPainel
import com.charruamotors.direcao.painel.ConfigPainel
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.domain.calculo.LimitesProtecao
import com.charruamotors.domain.faixas.FaixasVeiculo
import com.charruamotors.domain.faixas.NivelPerfil
import com.charruamotors.domain.faixas.ResolucaoPerfil
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.domain.perfis.CarregadorPerfis
import com.charruamotors.domain.perfis.CatalogoPerfis
import com.charruamotors.domain.perfis.PerfilCambio

/**
 * Camada de comunicação preferida do veículo (v2.3.0). Não força nada que a ECU não aceite: o
 * [OBD2] só desliga os PIDs proprietários da montadora; o [MODO_21] põe o KWP Modo 21 na frente
 * da camada 3 da cascata (quando a ECU recusa o Modo 01).
 */
enum class CamadaPreferida(val titulo: String, val descricao: String, val servico: String?) {
    AUTO("Automática", "OBD2 padrão e, se a ECU recusar, os modos 21/22 da montadora", null),
    OBD2("OBD2 padrão", "Só o OBD2 genérico (Modo 01), sem PIDs proprietários da montadora", null),
    MODO_21("Modo 21 (KWP)", "Prioriza o KWP2000 Modo 21 (Toyota M-OBD) quando a ECU recusar o Modo 01", "21"),
    ;

    companion object {
        val PADRAO = AUTO

        fun deCodigo(codigo: String?): CamadaPreferida = entries.firstOrNull { it.name.equals(codigo, ignoreCase = true) } ?: PADRAO
    }
}

/**
 * Perfil 100% isolado de um veículo (v2.3.0): tudo o que antes era global e agora vive sob o
 * `veiculoId` — Painel e HUD ([painel]), as bolinhas do Cluster horizontal, o perfil técnico
 * (faixas, câmbio, camada e o Bluetooth do carro), favoritos de gráficos e os recordes de
 * aceleração. A calibração do consumo continua no próprio [Veiculo] (Room), também por veículo.
 * Objeto puro (JVM); a persistência fica no [GerenciadorPerfisCarro].
 */
data class PerfilCarro(
    val veiculoId: Long,
    /** Painel (blocos, mostradores, lista rápida) e HUD flutuante (preset Padrão/Esportivo, P/M/G). */
    val painel: ConfigPainel = ConfigPainel(),
    /** As [ClusterPainel.BOLINHAS] do Cluster horizontal; null = padrão/automático da bolinha. */
    val cluster: List<SensorPainel?> = CLUSTER_PADRAO,
    /**
     * Perfil de faixas/motor do catálogo (`assets/perfis/veiculos.json`): null = reconhecido pelo
     * cadastro (marca/modelo/ano), [OBD2_GENERICO] = sem catálogo, ou o id de um preset.
     */
    val perfilFaixasId: String? = null,
    /** Câmbio do catálogo (`cambios/<id>.json`) no lugar do câmbio do perfil; null = o do perfil. */
    val perfilCambioId: String? = null,
    val camada: CamadaPreferida = CamadaPreferida.PADRAO,
    /** Bluetooth do carro (multimídia) que dispara a conexão automática deste veículo; null = o global. */
    val gatilhoNome: String? = null,
    val gatilhoEndereco: String? = null,
    /** Chaves dos sensores favoritos nos gráficos (preparado para as próximas versões). */
    val favoritosGraficos: List<String> = emptyList(),
    /** Melhores 0–60 / 0–100 deste veículo. */
    val recorde: RecordeDesempenho = RecordeDesempenho(),
) {
    /** Carro sem perfil específico, no OBD2 genérico (sem PIDs da SW4, do Cruze ou de outra montadora). */
    val obd2Generico: Boolean get() = perfilFaixasId == OBD2_GENERICO

    /** Usa os recursos da montadora (PIDs estendidos, ordem dos modos 21/22, estimador de marcha GM)? */
    val usaMontadora: Boolean get() = !obd2Generico && camada != CamadaPreferida.OBD2

    /** Marca passada à conexão e ao perfil de PIDs da montadora: null no OBD2 genérico/padrão. */
    fun marcaMontadora(marca: String?): String? = marca.takeIf { usaMontadora }

    /** Serviço que a camada 3 deve tentar primeiro (`21`); null = a ordem da marca. */
    val servicoPreferido: String? get() = camada.servico.takeIf { usaMontadora }

    /** O Bluetooth do carro foi definido para este veículo? */
    val temGatilho: Boolean get() = !gatilhoNome.isNullOrBlank()

    /**
     * Cascata do perfil com a escolha do usuário na frente: [OBD2_GENERICO] → só as faixas
     * genéricas do cadastro; id de preset → esse perfil; senão (ou id desconhecido) o
     * reconhecimento pelo cadastro ([ResolucaoPerfil.deVeiculo]).
     */
    fun resolver(v: Veiculo, catalogo: CatalogoPerfis = CarregadorPerfis.catalogo): ResolucaoPerfil {
        val limites = LimitesProtecao.deVeiculo(v)
        if (obd2Generico) return ResolucaoPerfil(NivelPerfil.OBD2_PADRAO, null, FaixasVeiculo.genericas(v.tipoCombustivel, limites))
        val escolhido = perfilFaixasId?.let { catalogo[it] } ?: return ResolucaoPerfil.deVeiculo(v)
        return if (escolhido.generico) {
            ResolucaoPerfil(NivelPerfil.GENERICO, escolhido, FaixasVeiculo.comLimitesDoCadastro(escolhido, limites))
        } else {
            ResolucaoPerfil(NivelPerfil.EXATO, escolhido, escolhido.faixas)
        }
    }

    /** Câmbio em uso: o escolhido ([perfilCambioId]) ou o do perfil exato; null nos genéricos. */
    fun cambio(resolucao: ResolucaoPerfil, catalogo: CatalogoPerfis = CarregadorPerfis.catalogo): PerfilCambio? =
        perfilCambioId?.let { catalogo.cambios[it] } ?: resolucao.takeIf { !it.generico }?.preset?.perfilCambio

    /**
     * Tag curta do seletor do topo: `Modo 21`, `CAN OBD2`, `OBD2` ou `OBD2 Genérico` — a camada
     * preferida ou, no automático, o serviço/protocolo do perfil resolvido.
     */
    fun tag(resolucao: ResolucaoPerfil): String {
        val preset = resolucao.preset
        val can = preset?.protocolo?.let(::protocoloCan) == true
        return when {
            obd2Generico -> TAG_GENERICO
            camada == CamadaPreferida.OBD2 -> if (can) TAG_CAN else TAG_OBD2
            camada == CamadaPreferida.MODO_21 -> TAG_MODO_21
            resolucao.nivel == NivelPerfil.EXATO && preset?.servicoMontadora == "21" -> TAG_MODO_21
            can && resolucao.nivel == NivelPerfil.EXATO -> TAG_CAN
            else -> TAG_OBD2
        }
    }

    companion object {
        const val OBD2_GENERICO = "obd2_generico"

        const val TAG_GENERICO = "OBD2 Genérico"
        const val TAG_CAN = "CAN OBD2"
        const val TAG_OBD2 = "OBD2"
        const val TAG_MODO_21 = "Modo 21"

        /** `turbo_flex` em `veiculos.json`: o layout rápido do Cruze. */
        const val LAYOUT_TURBO_FLEX = "turbo_flex"

        val CLUSTER_PADRAO: List<SensorPainel?> = List(ClusterPainel.BOLINHAS) { null }

        /** `ATSP6`..`ATSP9` (ISO 15765-4 CAN 11/29 bits, 500/250 kbps). */
        fun protocoloCan(comando: String): Boolean {
            val c = comando.trim().uppercase()
            return c.startsWith("ATSP") && c.drop(4).singleOrNull() in '6'..'9'
        }

        /**
         * Perfil inicial de um carro recém-cadastrado: com perfil exato no catálogo, o automático
         * (e o layout sugerido pelo perfil); sem ele, limpo no **OBD2 Genérico** — sem herdar os PIDs
         * proprietários da SW4 nem do Cruze, pronto para a Sessão de Mapeamento.
         */
        fun paraCarroNovo(veiculoId: Long, resolucao: ResolucaoPerfil): PerfilCarro {
            if (resolucao.nivel != NivelPerfil.EXATO) {
                return PerfilCarro(veiculoId, perfilFaixasId = OBD2_GENERICO, camada = CamadaPreferida.OBD2)
            }
            val layout = resolucao.preset?.layoutSugerido
            val painel = if (layout == LAYOUT_TURBO_FLEX) ConfigPainel().aplicarLayout(ConfigPainel.LAYOUT_TURBO_FLEX) else ConfigPainel()
            return PerfilCarro(veiculoId, painel = painel)
        }
    }
}
