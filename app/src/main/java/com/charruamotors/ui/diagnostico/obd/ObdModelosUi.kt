package com.charruamotors.ui.diagnostico.obd

import com.charruamotors.direcao.desempenho.FonteVelocidade
import com.charruamotors.domain.calculo.ConsumoInstantaneo
import com.charruamotors.domain.calculo.EstadoCronometro
import com.charruamotors.domain.calculo.ParametrosMotor
import com.charruamotors.domain.calculo.ResumoViagem
import com.charruamotors.obd.FreezeFrame
import com.charruamotors.obd.InfoConexao
import com.charruamotors.obd.ResultadoPerfil
import com.charruamotors.obd.dtc.InfoDtc
import com.charruamotors.obd.dtc.TipoDtc
import com.charruamotors.obd.pid.DefinicaoPid

/** Valor atual de um sensor suportado, para a grade de PIDs (null = sem resposta neste ciclo). */
data class LeituraSensor(val definicao: DefinicaoPid, val valor: Double?)

/** Informações da ECU conectada: VIN (null = ainda lendo ou indisponível), PIDs detectados e perfil de montadora. */
data class InfoEcu(
    val vin: String? = null,
    /** A leitura do VIN já terminou (com ou sem sucesso). */
    val vinLido: Boolean = false,
    /** PIDs do catálogo padrão suportados (sem os de montadora). */
    val quantidadePids: Int = 0,
    /** Perfil de montadora (modos 21/22); null = ainda verificando ou desconectado. */
    val perfil: ResultadoPerfil? = null,
    /** Protocolo (`ATDP`) e camada da cascata (OBD2 padrão / modo 21 / modo 22). */
    val conexao: InfoConexao = InfoConexao(),
)

/** Card do computador de bordo: consumo instantâneo e totais da viagem desde a conexão. */
data class ComputadorBordoUi(
    val consumo: ConsumoInstantaneo? = null,
    val viagem: ResumoViagem = ResumoViagem(),
    val motor: ParametrosMotor = ParametrosMotor(),
)

/** Card do cronômetro 0–100 km/h. */
data class CronometroUi(
    val estado: EstadoCronometro = EstadoCronometro.Desarmado,
    val melhorTempoMs: Long? = null,
    /** Fonte da última velocidade usada (null = nenhuma ainda): o card mostra "via GPS". */
    val fonte: FonteVelocidade? = null,
)

/** Estado do Data Logger exibido no botão "Gravar Log CSV". */
data class EstadoLog(val gravando: Boolean = false, val amostras: Int = 0)

/** DTC já classificado e descrito pelo catálogo offline. */
data class DtcExibicao(val tipo: TipoDtc, val info: InfoDtc) {
    val codigo: String get() = info.codigo
}

/**
 * Resultado da leitura completa de falhas. Listas `null` = modo não suportado pela ECU (ou erro),
 * diferente de lista vazia (suportado, sem códigos).
 */
data class ResultadoDiagnostico(
    val confirmados: List<DtcExibicao>,
    val pendentes: List<DtcExibicao>?,
    val permanentes: List<DtcExibicao>?,
    /** null = Freeze Frame não suportado/erro; [FreezeFrame.vazio] = nenhum quadro armazenado. */
    val freezeFrame: FreezeFrame?,
    /** Descrição do DTC que disparou o Freeze Frame. */
    val infoFreezeFrame: InfoDtc? = null,
) {
    val total: Int get() = confirmados.size + pendentes.orEmpty().size + permanentes.orEmpty().size

    /** Linhas da lista de falhas: cabeçalho por categoria, seguido dos códigos ou de uma linha "vazio". */
    fun itens(): List<ItemDiagnostico> = buildList {
        for (tipo in TipoDtc.entries) {
            val lista = when (tipo) {
                TipoDtc.CONFIRMADO -> confirmados
                TipoDtc.PENDENTE -> pendentes
                TipoDtc.PERMANENTE -> permanentes
            }
            add(ItemDiagnostico.Cabecalho(tipo, lista?.size))
            when {
                lista == null -> add(ItemDiagnostico.Vazio(tipo, suportado = false))
                lista.isEmpty() -> add(ItemDiagnostico.Vazio(tipo, suportado = true))
                else -> lista.forEach { add(ItemDiagnostico.Codigo(it)) }
            }
        }
    }
}

/** Linha da lista de falhas separada por categoria. */
sealed interface ItemDiagnostico {
    val chave: String

    /** Título da categoria; [quantidade] null = modo não suportado. */
    data class Cabecalho(val tipo: TipoDtc, val quantidade: Int?) : ItemDiagnostico {
        override val chave get() = "cabecalho-${tipo.name}"
    }

    data class Codigo(val dtc: DtcExibicao) : ItemDiagnostico {
        override val chave get() = "codigo-${dtc.tipo.name}-${dtc.codigo}"
    }

    data class Vazio(val tipo: TipoDtc, val suportado: Boolean) : ItemDiagnostico {
        override val chave get() = "vazio-${tipo.name}"
    }
}
