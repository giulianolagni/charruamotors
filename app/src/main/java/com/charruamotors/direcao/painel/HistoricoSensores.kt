package com.charruamotors.direcao.painel

import com.charruamotors.obd.ValoresSensores

/**
 * Memória (por execução do app) dos sensores que a ECU atual nunca respondeu (`NO DATA`): depois
 * de [CICLOS_ATE_DESISTIR] leituras seguidas sem valor, o sensor vai para "Não suportados neste
 * veículo" nos chips; qualquer leitura válida o tira de lá. Zera ao trocar de veículo/conexão.
 */
object HistoricoSensores {
    const val CICLOS_ATE_DESISTIR = 8

    private val semValor = mutableMapOf<SensorPainel, Int>()

    @Synchronized
    fun registrar(dados: ValoresSensores?) {
        if (dados == null) return
        // Sem nenhum sensor lido o problema é a conexão, não o veículo: não condena ninguém.
        if (SensorPainel.entries.none { it.ler(dados) != null }) return
        for (s in SensorPainel.entries) {
            if (s == SensorPainel.CONSUMO_KML) continue // calculado: nunca vem da ECU
            if (s.ler(dados) != null) semValor.remove(s) else semValor[s] = (semValor[s] ?: 0) + 1
        }
    }

    @Synchronized
    fun naoSuportados(): Set<SensorPainel> =
        semValor.filterValues { it >= CICLOS_ATE_DESISTIR }.keys.toSet()

    @Synchronized
    fun limpar() = semValor.clear()
}
