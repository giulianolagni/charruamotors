package com.charruamotors.sistema

import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.sessao.ConfigAutoConexao
import com.charruamotors.obd.sessao.EscolhaScanner
import com.charruamotors.obd.sessao.EstadoSessao
import com.charruamotors.obd.sessao.FaseSessao
import com.charruamotors.obd.sessao.GatilhoBluetooth
import com.charruamotors.obd.sessao.SessaoObd

/** Indicador da sessão OBD2 no cabeçalho do menu lateral (bolinha verde/cinza + texto). */
data class StatusCabecalho(val conectado: Boolean, val texto: String)

/** Textos de status da caixa-preta fora da aba OBD2 (menu lateral e faixa discreta). Lógica pura. */
object StatusCaixaPreta {

    const val EM_ESPERA = "Em espera"

    /** `Conectado · Gravando log`, `Conectando ao OBDII…` ou `Em espera`. */
    fun cabecalho(e: EstadoSessao): StatusCabecalho = when (e.fase) {
        FaseSessao.Inativa -> StatusCabecalho(false, EM_ESPERA)
        FaseSessao.Conectada -> StatusCabecalho(true, if (e.gravandoLogs) "Conectado · Gravando log" else "Conectado")
        // Aguardando a ECU, conectando ou encerrando: o veículo já aparece na linha de cima.
        else -> StatusCabecalho(false, SessaoObd.descreverStatus(e.copy(veiculo = null, gatilho = null)))
    }

    /** Faixa da aba OBD2 (abre as Configurações ao tocar). */
    fun faixa(config: ConfigAutoConexao, e: EstadoSessao): String = when {
        e.ativa -> "Caixa-preta · " + SessaoObd.descreverStatus(e)
        config.autoConexaoAtiva -> "Caixa-preta ativa · Gatilho: ${config.gatilhoNome}"
        config.gravarLogsAutomaticos -> "Caixa-preta: só logs ao conectar · auto-conexão desligada"
        else -> "Caixa-preta desligada · toque para configurar"
    }
}

/** Pré-seleções da etapa 2 do Assistente Inicial (scanner `OBDII` e Bluetooth `Toyota SW4`). */
object PreSelecaoAssistente {

    const val NOME_SCANNER_PADRAO = "OBDII"

    /** O já vinculado; senão o `OBDII` (ignorando espaços/maiúsculas); senão o primeiro com nome de scanner. */
    fun scanner(pareados: List<DispositivoObd>, atual: DispositivoObd?): DispositivoObd? =
        pareados.firstOrNull { atual != null && it.endereco.equals(atual.endereco, ignoreCase = true) }
            ?: pareados.firstOrNull { GatilhoBluetooth.normalizar(it.nome) == GatilhoBluetooth.normalizar(NOME_SCANNER_PADRAO) }
            ?: pareados.firstOrNull(EscolhaScanner::pareceScannerObd)

    /** O pareado que corresponde ao gatilho configurado (padrão `Toyota SW4`), nunca o próprio scanner. */
    fun gatilho(pareados: List<DispositivoObd>, config: ConfigAutoConexao, scanner: DispositivoObd?): DispositivoObd? =
        pareados.firstOrNull { it.endereco != scanner?.endereco && GatilhoBluetooth.corresponde(it.nome, it.endereco, config) }
}
