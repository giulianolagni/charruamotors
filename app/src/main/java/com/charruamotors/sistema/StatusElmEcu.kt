package com.charruamotors.sistema

import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.domain.perfis.ImpressaoEcu
import com.charruamotors.obd.InfoConexao
import com.charruamotors.obd.sessao.EstadoSessao
import com.charruamotors.obd.sessao.FaseSessao

/** Link com o adaptador ELM327 (Bluetooth RFCOMM + interpretador AT). */
enum class EstadoElm(val texto: String) {
    DESCONECTADO("Desconectado"),
    CONECTANDO("Conectando"),
    CONECTADO("Conectado"),
}

/** Conversa do adaptador com a ECU do veículo (OBD2 padrão ou modos 21/22). */
enum class EstadoEcu(val texto: String) {
    AGUARDANDO("Aguardando"),
    CONECTADA("Conectado"),
    SEM_RESPOSTA("Sem resposta"),
}

/**
 * Dois indicadores do card de conexão (v2.3.0): o adaptador ELM327 e a ECU, separados — "o
 * adaptador respondeu, mas a ECU não" (ignição desligada, protocolo errado) deixa de parecer uma
 * falha do Bluetooth. Lógica pura.
 */
data class StatusElmEcu(
    val elm: EstadoElm,
    /** `ELM327 v2.1` (ATZ/ATI), quando o adaptador já se identificou nesta conexão. */
    val versaoElm: String?,
    val ecu: EstadoEcu,
    /** Camada/protocolo ativos (`OBD2 Padrão · CAN 11/500`, `Modo 21 (M-OBD / KWP)`); só com a ECU conectada. */
    val camadaEcu: String?,
) {
    /** `Conectado (ELM327 v2.1)`, `Conectando`, `Desconectado (ELM327 v2.1 respondeu)`. */
    val textoElm: String
        get() = when {
            versaoElm == null -> elm.texto
            elm == EstadoElm.DESCONECTADO -> "${elm.texto} ($versaoElm respondeu)"
            else -> "${elm.texto} ($versaoElm)"
        }

    /** `Conectado · Modo 21 (M-OBD / KWP)`, `Aguardando`, `Sem resposta`. */
    val textoEcu: String get() = camadaEcu?.let { "${ecu.texto} · $it" } ?: ecu.texto

    companion object {
        /**
         * - Conectando, antes do ATZ: ELM conectando, ECU aguardando;
         * - adaptador identificado, ECU ainda calada: ELM conectado, ECU aguardando;
         * - conectado: os dois conectados, com a camada;
         * - erro depois de o adaptador responder: ELM desconectado (o socket fecha), ECU sem resposta;
         * - erro antes disso (Bluetooth/RFCOMM): ELM desconectado, ECU aguardando.
         */
        fun de(estado: EstadoConexaoObd, sessao: EstadoSessao, info: InfoConexao): StatusElmEcu {
            val versao = info.adaptador
            val sessaoConectando = sessao.ativa && (sessao.fase is FaseSessao.AguardandoEcu || sessao.fase is FaseSessao.Conectando)
            return when {
                estado is EstadoConexaoObd.Conectado ->
                    StatusElmEcu(EstadoElm.CONECTADO, versao, EstadoEcu.CONECTADA, camada(info))
                estado is EstadoConexaoObd.Conectando || (sessaoConectando && estado !is EstadoConexaoObd.Erro) ->
                    if (versao != null) StatusElmEcu(EstadoElm.CONECTADO, versao, EstadoEcu.AGUARDANDO, null)
                    else StatusElmEcu(EstadoElm.CONECTANDO, null, EstadoEcu.AGUARDANDO, null)
                estado is EstadoConexaoObd.Erro && versao != null ->
                    StatusElmEcu(EstadoElm.DESCONECTADO, versao, EstadoEcu.SEM_RESPOSTA, null)
                else -> StatusElmEcu(EstadoElm.DESCONECTADO, null, EstadoEcu.AGUARDANDO, null)
            }
        }

        /** `OBD2 Padrão · CAN 11/500` ou o nome do modo de serviço. */
        fun camada(info: InfoConexao): String {
            val curto = StatusConexaoPainel.ecu(info)
            return if (curto == null || curto == info.nomeModo) info.nomeModo else "${info.nomeModo} · $curto"
        }
    }
}

/** Uma linha do resumo técnico da tela inicial (rótulo + valor). */
data class LinhaResumoTecnico(val rotulo: String, val valor: String)

/** Bloco compacto "Protocolo · VIN · CALID · TCM" do card de conexão (v2.3.0). Lógica pura. */
object ResumoTecnicoConexao {

    const val SEM_DADO = "—"

    /**
     * As quatro linhas, ou vazio sem a ECU conectada. TCM só entra quando a sondagem o detectou ou
     * quando o câmbio do perfil em uso tem módulo próprio ([tcmPerfil], ex. `7E1`).
     */
    fun linhas(
        conectado: Boolean,
        info: InfoConexao,
        vin: String?,
        impressao: ImpressaoEcu?,
        tcmPerfil: String? = null,
    ): List<LinhaResumoTecnico> {
        if (!conectado) return emptyList()
        return buildList {
            add(LinhaResumoTecnico("Protocolo", info.protocolo?.trim()?.removePrefix("AUTO,")?.trim()?.takeIf { it.isNotEmpty() } ?: SEM_DADO))
            add(LinhaResumoTecnico("VIN", vin?.trim()?.takeIf { it.isNotEmpty() } ?: SEM_DADO))
            add(LinhaResumoTecnico("CALID", impressao?.calid?.trim()?.takeIf { it.isNotEmpty() } ?: SEM_DADO))
            when {
                impressao?.tcmDetectado == true -> add(LinhaResumoTecnico("TCM", impressao.tcmCabecalho ?: "detectado"))
                !tcmPerfil.isNullOrBlank() -> add(LinhaResumoTecnico("TCM", "$tcmPerfil (perfil)"))
            }
        }
    }
}
