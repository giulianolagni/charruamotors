package com.charruamotors.direcao.hud

import com.charruamotors.direcao.painel.ConfigHud
import com.charruamotors.obd.DadosObd
import com.charruamotors.sistema.unidades.ConversorUnidades
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * Ponte entre a aba Painel e o HUD flutuante, mantida no Application (vive enquanto o processo
 * viver). A sessão OBD publica a telemetria e a conexão; o `HudFlutuanteService` desenha;
 * e o ViewModel lê [hudAtivo] para continuar o polling com o app minimizado sob o Waze.
 */
class TelemetriaCompartilhada(
    /** Unidades globais (métrico/imperial) e a unidade de pressão das Configurações. */
    private val conversor: Flow<ConversorUnidades> = flowOf(ConversorUnidades.PADRAO),
    /** Tamanho, sensores e estilo escolhidos na ativação do HUD. */
    private val configHud: Flow<ConfigHud> = flowOf(ConfigHud()),
) {

    private val _dados = MutableStateFlow<DadosObd?>(null)
    val dados: StateFlow<DadosObd?> = _dados.asStateFlow()

    private val _conectado = MutableStateFlow(false)
    val conectado: StateFlow<Boolean> = _conectado.asStateFlow()

    private val _hudAtivo = MutableStateFlow(false)
    /** O HUD está na tela? Enquanto estiver, a telemetria não pausa em segundo plano. */
    val hudAtivo: StateFlow<Boolean> = _hudAtivo.asStateFlow()

    /** Painel do HUD, recalculado a cada instantâneo (só emite quando algum texto muda). */
    val painel: Flow<PainelHud> = combine(_dados, _conectado, conversor, configHud) { dados, conectado, conv, config ->
        FormatadorHud.montar(dados, conectado, config, conv)
    }
        .distinctUntilChanged()

    fun publicar(dados: DadosObd?, conectado: Boolean) {
        _dados.value = dados
        _conectado.value = conectado
    }

    fun definirHudAtivo(ativo: Boolean) {
        _hudAtivo.value = ativo
    }
}
