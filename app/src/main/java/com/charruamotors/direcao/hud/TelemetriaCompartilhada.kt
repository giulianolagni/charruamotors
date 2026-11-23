package com.charruamotors.direcao.hud

import com.charruamotors.obd.DadosObd
import com.charruamotors.sistema.unidades.UnidadePressao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * Ponte entre a aba OBD2 e o HUD flutuante, mantida no Application (vive enquanto o processo
 * viver). O `ObdViewModel` publica a telemetria e a conexão; o `HudFlutuanteService` desenha;
 * e o ViewModel lê [hudAtivo] para continuar o polling com o app minimizado sob o Waze.
 */
class TelemetriaCompartilhada(
    /** Unidade escolhida nas Configurações para o Turbo/MAP (kPa, bar ou psi). */
    private val unidadePressao: Flow<UnidadePressao> = flowOf(UnidadePressao.PADRAO),
) {

    private val _dados = MutableStateFlow<DadosObd?>(null)
    val dados: StateFlow<DadosObd?> = _dados.asStateFlow()

    private val _conectado = MutableStateFlow(false)
    val conectado: StateFlow<Boolean> = _conectado.asStateFlow()

    private val _hudAtivo = MutableStateFlow(false)
    /** O HUD está na tela? Enquanto estiver, a telemetria não pausa em segundo plano. */
    val hudAtivo: StateFlow<Boolean> = _hudAtivo.asStateFlow()

    /** Painel do HUD, recalculado a cada instantâneo (só emite quando algum texto muda). */
    val painel: Flow<PainelHud> = combine(_dados, _conectado, unidadePressao) { dados, conectado, unidade ->
        FormatadorHud.montar(dados, conectado, unidade)
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
