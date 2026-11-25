package com.charruamotors.ui.oficina.atuadores

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.ObdClient
import com.charruamotors.obd.ObdClientFake
import com.charruamotors.obd.PerfilSimulacao
import com.charruamotors.oficina.atuadores.Atuador
import com.charruamotors.oficina.atuadores.ComandoAtuador
import com.charruamotors.oficina.atuadores.EtapaAtuadores
import com.charruamotors.oficina.atuadores.MapeamentoAtuadores
import com.charruamotors.oficina.atuadores.ResultadoAtuador
import com.charruamotors.oficina.atuadores.ResultadoSessao
import com.charruamotors.oficina.atuadores.TesteAtuadores
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Teste em andamento (cartão "ATIVO"): o que observar e o progresso do pulso. */
data class AtuadorEmTeste(
    val titulo: String,
    val observar: String,
    val comando: String,
    val inicioMs: Long,
    val duracaoMs: Long,
)

/** Estado da tela do Teste de Atuadores. */
data class TesteAtuadoresUi(
    val etapa: EtapaAtuadores = EtapaAtuadores.INATIVA,
    val sessao: ResultadoSessao? = null,
    val rpm: Double? = null,
    val emTeste: AtuadorEmTeste? = null,
    val ultimo: ResultadoAtuador? = null,
    val historico: List<String> = emptyList(),
    val mapeamento: MapeamentoAtuadores = MapeamentoAtuadores.GENERICO,
    /** Carro simulado (só no Modo Simulado: habilita a simulação visual/didática). */
    val simulacao: PerfilSimulacao? = null,
    val motorSimuladoLigado: Boolean = false,
    val etanolSimulado: Int? = null,
)

sealed interface EventoAtuadores {
    data object SemConexao : EventoAtuadores
    data object ConexaoPerdida : EventoAtuadores
    data object Ocupado : EventoAtuadores
}

/**
 * Teste de Atuadores numa sessão manual: o polling da telemetria fica pausado (sem fechar o socket)
 * enquanto a tela estiver aberta, a sessão estendida `10 03` é mantida pelo `3E 00` a cada 2 s e
 * cada teste passa pela máquina [TesteAtuadores] (trava de RPM, ativa/devolve, NRC explicado). No
 * Modo Simulado, liga o "motor desligado" do [ObdClientFake] (RPM 0 nas respostas manuais) e oferece
 * a simulação do motor ligado para demonstrar a trava.
 */
class TesteAtuadoresViewModel(
    private val cliente: ObdClient,
    private val veiculo: suspend () -> Veiculo?,
    private val rpmConhecido: () -> Double?,
    private val relogio: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {

    private val fake = cliente as? ObdClientFake
    private val teste = TesteAtuadores(
        enviar = { c -> cliente.enviarComandoManual(c, TIMEOUT_COMANDO_MS).getOrNull() },
        rpmConhecido = rpmConhecido,
    )

    private val _ui = MutableStateFlow(TesteAtuadoresUi())
    val ui: StateFlow<TesteAtuadoresUi> = _ui.asStateFlow()

    private val _eventos = Channel<EventoAtuadores>(Channel.BUFFERED)
    val eventos: Flow<EventoAtuadores> = _eventos.receiveAsFlow()

    private var execucao: Job? = null
    private var manutencao: Job? = null

    init {
        viewModelScope.launch { iniciar() }
    }

    private suspend fun iniciar() {
        if (cliente.iniciarSessaoManual().isFailure) {
            _eventos.send(EventoAtuadores.SemConexao)
            return
        }
        val marca = runCatching { veiculo() }.getOrNull()?.marca
        val mapeamento = MapeamentoAtuadores.paraMarca(marca)
        fake?.simularMotor(ligado = false)
        _ui.update {
            it.copy(
                etapa = EtapaAtuadores.PREPARANDO,
                mapeamento = mapeamento,
                simulacao = fake?.perfilSimulacao,
                etanolSimulado = fake?.takeIf { f -> f.perfilSimulacao == PerfilSimulacao.CRUZE_14T_FLEX }
                    ?.let { f -> f.etanolSimulado.toInt() },
            )
        }
        val sessao = teste.preparar(mapeamento.cabecalhoPadrao)
        anotar(descreverSessao(sessao))
        _ui.update { it.copy(etapa = teste.etapa, sessao = sessao) }
        atualizarRpm()
        manutencao = viewModelScope.launch { manterSessao() }
    }

    /** Tester Present a cada 2 s e o RPM atualizado; acusa a queda da conexão. */
    private suspend fun manterSessao() {
        while (viewModelScope.isActive) {
            delay(TesteAtuadores.INTERVALO_TESTER_PRESENT_MS)
            if (cliente.estado.value !is EstadoConexaoObd.Conectado) {
                _eventos.send(EventoAtuadores.ConexaoPerdida)
                return
            }
            if (execucao?.isActive == true) continue // o próprio pulso envia o 3E 00
            if (teste.sessaoEstendida) teste.manterSessao()
            atualizarRpm()
        }
    }

    private suspend fun atualizarRpm() {
        val rpm = teste.lerRpm()
        _ui.update { it.copy(rpm = rpm) }
    }

    fun testar(atuador: Atuador) {
        val comando = _ui.value.mapeamento[atuador]
        executar(atuador.titulo, atuador.observar, comando, atuador.duracaoMs) { teste.executar(atuador, comando) }
    }

    /** Comando customizado; false = texto inválido (a tela mostra o erro). */
    fun testarCustomizado(texto: String, cabecalho: String, duracaoS: Int): Boolean {
        val comando = ComandoAtuador.customizado(texto, cabecalho) ?: return false
        val duracao = duracaoS.coerceIn(1, MAX_DURACAO_CUSTOMIZADA_S) * 1000L
        executar("Comando customizado 2F ${comando.did}", OBSERVAR_CUSTOMIZADO, comando, duracao) {
            teste.executarCustomizado(comando, duracao)
        }
        return true
    }

    private fun executar(
        titulo: String,
        observar: String,
        comando: ComandoAtuador?,
        duracaoMs: Long,
        bloco: suspend () -> ResultadoAtuador,
    ) {
        if (execucao?.isActive == true) {
            _eventos.trySend(EventoAtuadores.Ocupado)
            return
        }
        if (_ui.value.etapa == EtapaAtuadores.INATIVA || _ui.value.etapa == EtapaAtuadores.PREPARANDO) {
            _eventos.trySend(EventoAtuadores.Ocupado)
            return
        }
        val rotulo = comando?.let { "${it.cabecalho} · ${it.ativar.chunked(2).joinToString(" ")}" }.orEmpty()
        execucao = viewModelScope.launch {
            _ui.update { it.copy(emTeste = AtuadorEmTeste(titulo, observar, rotulo, relogio(), duracaoMs), etapa = EtapaAtuadores.EXECUTANDO) }
            val resultado = try {
                bloco()
            } catch (e: CancellationException) {
                anotar(descrever(ResultadoAtuador.Interrompido(titulo)))
                _ui.update { it.copy(emTeste = null, etapa = teste.etapa, ultimo = ResultadoAtuador.Interrompido(titulo)) }
                throw e
            }
            anotar(descrever(resultado))
            _ui.update { it.copy(emTeste = null, etapa = teste.etapa, ultimo = resultado, rpm = teste.ultimoRpm ?: it.rpm) }
        }
    }

    /** Parar: cancela o pulso; a máquina devolve o controle (`2F <DID> 00`) mesmo cancelada. */
    fun parar() {
        execucao?.cancel()
    }

    /** Modo Simulado: liga/desliga o "motor" para demonstrar a trava de RPM dos itens críticos. */
    fun simularMotorLigado(ligado: Boolean) {
        val f = fake ?: return
        f.simularMotor(ligado)
        _ui.update { it.copy(motorSimuladoLigado = ligado) }
        viewModelScope.launch { if (execucao?.isActive != true) atualizarRpm() }
    }

    /** Modo Simulado (Cruze): teor de etanol do tanque, para demonstrar o Auto Flex no Painel. */
    fun simularEtanol(percentual: Int) {
        fake?.simularEtanol(percentual.toDouble())
        _ui.update { it.copy(etanolSimulado = percentual) }
    }

    private fun anotar(linha: String) {
        val hora = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        _ui.update { it.copy(historico = (listOf("$hora  $linha") + it.historico).take(MAX_HISTORICO)) }
    }

    private fun descreverSessao(s: ResultadoSessao): String = when (s) {
        ResultadoSessao.Estendida -> "10 03 → 50 03: sessão estendida ativa"
        is ResultadoSessao.Recusada -> "10 03 → ${s.resposta.rotulo}"
        is ResultadoSessao.SemResposta -> "10 03 → sem resposta (${s.motivo})"
    }

    private fun descrever(r: ResultadoAtuador): String = when (r) {
        is ResultadoAtuador.Sucesso -> "${r.titulo}: OK (${r.resposta})"
        is ResultadoAtuador.Recusado -> "${r.titulo}: ${r.negativa.rotulo}"
        is ResultadoAtuador.BloqueadoMotor -> "${r.titulo}: bloqueado (RPM ${r.rpm?.toInt() ?: "?"})"
        is ResultadoAtuador.SemResposta -> "${r.titulo}: sem resposta (${r.motivo})"
        is ResultadoAtuador.Falha -> "${r.titulo}: ${r.mensagem}"
        is ResultadoAtuador.Interrompido -> "${r.titulo}: interrompido, controle devolvido"
    }

    /**
     * Tela fechada: para o pulso (o controle volta à central), sai da sessão estendida (`10 01`) e
     * encerra a sessão manual (restaura o `ATSH` e libera o polling), num escopo próprio.
     */
    override fun onCleared() {
        execucao?.cancel()
        manutencao?.cancel()
        val pulso = execucao
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            pulso?.join()
            teste.encerrar()
            fake?.simularMotor(ligado = true)
            cliente.encerrarSessaoManual()
        }
    }

    companion object {
        const val TIMEOUT_COMANDO_MS = 2_000L
        const val MAX_DURACAO_CUSTOMIZADA_S = 10
        private const val MAX_HISTORICO = 40
        private const val OBSERVAR_CUSTOMIZADO = "Comando livre: confira no veículo o efeito do DID enviado."

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CharruaMotorsApp
                val veiculoId = createSavedStateHandle().get<Long>(DiagnosticoActivity.EXTRA_VEICULO_ID) ?: 0L
                TesteAtuadoresViewModel(
                    cliente = app.obdClientDe(app.modoObd),
                    veiculo = { app.veiculoRepository.buscarPorId(veiculoId) },
                    rpmConhecido = { app.sessaoObd.dados.value?.rpm?.toDouble() },
                )
            }
        }
    }
}
