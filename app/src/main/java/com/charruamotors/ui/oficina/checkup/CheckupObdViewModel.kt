package com.charruamotors.ui.oficina.checkup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.log.RelatorioSessaoTxt
import com.charruamotors.obd.sessao.SessaoObd
import com.charruamotors.oficina.checkup.CheckupProntidao
import com.charruamotors.oficina.checkup.DisponibilidadeCheckup
import com.charruamotors.oficina.checkup.EnvioManual
import com.charruamotors.oficina.checkup.FalhasIgnicaoModo06
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/** Ferramenta sob demanda da aba Oficina; [exigeCan]: o Modo 06 por MID só existe no CAN. */
enum class FerramentaCheckup(val exigeCan: Boolean) {
    PRONTIDAO(exigeCan = false),
    MISFIRE(exigeCan = true),
}

data class CheckupUi(
    val ocupado: Boolean = false,
    /** Resumo em texto limpo da última leitura (o que a tela mostra e o usuário copia/compartilha). */
    val resumo: String? = null,
    /** Aviso de por que não leu (desconectado, Modo 21, sem resposta); null = nenhum. */
    val aviso: String? = null,
)

/**
 * Checkup OBD2 / Prontidão e Falhas de Ignição (Modo 06) na sessão OBD compartilhada: cada leitura abre
 * uma sessão manual (o polling, inclusive o do HUD, pausa sem fechar o socket), roda os comandos curtos
 * e devolve o adaptador ao polling ao fim — mesmo se a tela fechar no meio.
 */
class CheckupObdViewModel(
    private val sessao: SessaoObd,
    val ferramenta: FerramentaCheckup,
    private val veiculo: suspend () -> Veiculo?,
) : ViewModel() {

    private val _ui = MutableStateFlow(CheckupUi())
    val ui: StateFlow<CheckupUi> = _ui.asStateFlow()

    val estado: StateFlow<EstadoConexaoObd> = sessao.estado

    fun ler() {
        if (_ui.value.ocupado) return
        if (estado.value !is EstadoConexaoObd.Conectado) {
            _ui.update { it.copy(aviso = AVISO_DESCONECTADO) }
            return
        }
        DisponibilidadeCheckup.motivoIndisponivel(sessao.infoConexao.value, ferramenta.exigeCan)?.let { motivo ->
            _ui.update { it.copy(aviso = motivo) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(ocupado = true, aviso = null) }
            val cliente = sessao.cliente
            try {
                if (cliente.iniciarSessaoManual().isFailure) {
                    _ui.update { it.copy(aviso = AVISO_DESCONECTADO) }
                    return@launch
                }
                val enviar: EnvioManual = { comando, timeoutMs -> cliente.enviarComandoManual(comando, timeoutMs).getOrNull() }
                val nome = runCatching { veiculo() }.getOrNull()?.let { "${it.marca} ${it.modelo} ${it.ano}".trim() }
                val agora = RelatorioSessaoTxt.dataHora(System.currentTimeMillis(), ZoneId.systemDefault())
                val (resumo, respondeu) = when (ferramenta) {
                    FerramentaCheckup.PRONTIDAO -> CheckupProntidao(enviar).executar().let { CheckupProntidao.resumo(it, nome, agora) to it.algumaResposta }
                    FerramentaCheckup.MISFIRE -> FalhasIgnicaoModo06(enviar).executar().let { FalhasIgnicaoModo06.resumo(it, nome, agora) to it.algumaResposta }
                }
                sessao.registrarEvento(
                    "${if (ferramenta == FerramentaCheckup.PRONTIDAO) "Checkup OBD2 / Prontidão" else "Falhas de ignição (Modo 06)"} lido " +
                        if (respondeu) "na Oficina" else "na Oficina: a ECU não respondeu",
                )
                _ui.update { it.copy(resumo = resumo, aviso = if (respondeu) null else AVISO_SEM_RESPOSTA) }
            } finally {
                cliente.encerrarSessaoManual()
                _ui.update { it.copy(ocupado = false) }
            }
        }
    }

    companion object {
        const val AVISO_DESCONECTADO = "Conecte o scanner no Painel antes de ler."
        const val AVISO_SEM_RESPOSTA = "A ECU não respondeu a estes comandos. Ligue a ignição e tente de novo."

        fun factory(ferramenta: FerramentaCheckup) = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CharruaMotorsApp
                val veiculoId = createSavedStateHandle().get<Long>(DiagnosticoActivity.EXTRA_VEICULO_ID) ?: 0L
                CheckupObdViewModel(app.sessaoObd, ferramenta) { app.veiculoRepository.buscarPorId(veiculoId) }
            }
        }
    }
}
