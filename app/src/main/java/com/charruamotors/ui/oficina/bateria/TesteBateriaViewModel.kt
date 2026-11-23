package com.charruamotors.ui.oficina.bateria

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.FaseBateriaSimulada
import com.charruamotors.obd.ObdClient
import com.charruamotors.obd.ObdClientFake
import com.charruamotors.oficina.bateria.EtapaBateria
import com.charruamotors.oficina.bateria.EventoTesteBateria
import com.charruamotors.oficina.bateria.LaudoBateria
import com.charruamotors.oficina.bateria.LeituraTensao
import com.charruamotors.oficina.bateria.ResultadoCarga
import com.charruamotors.oficina.bateria.ResultadoPartida
import com.charruamotors.oficina.bateria.ResultadoRepouso
import com.charruamotors.oficina.bateria.TesteBateria
import com.charruamotors.oficina.bateria.VereditoResumo
import com.charruamotors.ui.componentes.PontoTensao
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** Tudo o que a tela do teste mostra, recalculado a cada amostra. */
data class TesteBateriaUi(
    val etapa: EtapaBateria = EtapaBateria.REPOUSO,
    val capturando: Boolean = false,
    /** 0–1; null = indeterminado (esperando a partida) ou sem captura. */
    val progresso: Float? = null,
    /** Última tensão válida. */
    val tensao: Double? = null,
    val amostrasPorSegundo: Double = 0.0,
    val lvResets: Int = 0,
    val quedaDetectada: Boolean = false,
    /** Vale da captura da partida (marcado no gráfico). */
    val vale: Double? = null,
    val pontos: List<PontoTensao> = emptyList(),
    val repouso: ResultadoRepouso? = null,
    val partida: ResultadoPartida? = null,
    val lenta: ResultadoCarga? = null,
    val carga: ResultadoCarga? = null,
    val puladas: Set<EtapaBateria> = emptySet(),
    val vereditoBateria: VereditoResumo? = null,
    val vereditoAlternador: VereditoResumo? = null,
    /** Laudo em texto, quando o teste termina. */
    val laudo: String? = null,
)

sealed interface EventoBateria {
    data class Mensagem(@param:StringRes val resId: Int) : EventoBateria
    data class EtapaConcluida(val etapa: EtapaBateria) : EventoBateria

    /** Sem conexão ao abrir, ou a conexão caiu: a tela avisa e fecha. */
    data class SemConexao(@param:StringRes val resId: Int) : EventoBateria
}

/**
 * Teste de Bateria e Alternador: lê `ATRV` em alta taxa (o adaptador dita o ritmo, ~20 amostras/s
 * num ELM327 rápido; [PERIODO_MINIMO_MS] é só o piso) numa sessão manual — o polling dos sensores
 * fica pausado e volta ao fechar a tela, sem derrubar o Bluetooth — e entrega cada amostra à
 * máquina de estados [TesteBateria]. No modo simulado, avisa o [ObdClientFake] de cada fase
 * para a curva de tensão acompanhar o roteiro.
 */
class TesteBateriaViewModel(
    private val cliente: ObdClient,
    private val veiculo: suspend () -> Veiculo?,
    private val simulador: ((FaseBateriaSimulada?) -> Unit)? = null,
    private val relogio: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {

    private val teste = TesteBateria()
    private val pontos = ArrayDeque<PontoTensao>()
    private var ultimaTensao: Double? = null
    private var faseSimulada: FaseBateriaSimulada? = null
    private var amostragem: Job? = null
    private var laudoCache: String? = null

    private val descricaoVeiculo = viewModelScope.async {
        runCatching { veiculo() }.getOrNull()?.let { "${it.descricao} (${it.placaFormatada})" }
    }
    private var veiculoTexto: String? = null

    private val _ui = MutableStateFlow(TesteBateriaUi())
    val ui: StateFlow<TesteBateriaUi> = _ui.asStateFlow()

    private val _eventos = Channel<EventoBateria>(Channel.BUFFERED)
    val eventos: Flow<EventoBateria> = _eventos.receiveAsFlow()

    init {
        viewModelScope.launch { veiculoTexto = descricaoVeiculo.await() }
        sincronizarSimulador()
        amostragem = viewModelScope.launch { amostrar() }
    }

    private suspend fun amostrar() {
        if (cliente.iniciarSessaoManual().isFailure) {
            _eventos.send(EventoBateria.SemConexao(R.string.oficina_sem_conexao))
            return
        }
        while (viewModelScope.isActive) {
            if (cliente.estado.value !is EstadoConexaoObd.Conectado) {
                _eventos.send(EventoBateria.SemConexao(R.string.oficina_conexao_perdida))
                return
            }
            val inicio = relogio()
            val resposta = cliente.enviarComandoManual(COMANDO_TENSAO, TIMEOUT_ATRV_MS, registrar = false)
            val leitura = resposta.fold(
                onSuccess = { LeituraTensao.deResposta(it.resposta, it.semPrompt) },
                onFailure = { LeituraTensao.Perdida },
            )
            processar(leitura, relogio())
            val decorrido = relogio() - inicio
            delay((PERIODO_MINIMO_MS - decorrido).coerceAtLeast(RESPIRO_MINIMO_MS))
        }
    }

    private fun processar(leitura: LeituraTensao, agora: Long) {
        val volts = (leitura as? LeituraTensao.Valida)?.volts
        if (volts != null) ultimaTensao = volts
        pontos.addLast(PontoTensao(agora, volts, lvReset = leitura == LeituraTensao.LvReset))
        while (pontos.isNotEmpty() && agora - pontos.first().instanteMs > JANELA_GRAFICO_MS) pontos.removeFirst()
        when (val evento = teste.registrar(leitura, agora)) {
            is EventoTesteBateria.EtapaConcluida -> {
                _eventos.trySend(EventoBateria.EtapaConcluida(evento.etapa))
                sincronizarSimulador()
            }
            EventoTesteBateria.PartidaNaoDetectada -> {
                _eventos.trySend(EventoBateria.Mensagem(R.string.bateria_partida_nao_detectada))
                sincronizarSimulador()
            }
            EventoTesteBateria.LeiturasInsuficientes -> _eventos.trySend(EventoBateria.Mensagem(R.string.bateria_leituras_insuficientes))
            null -> Unit
        }
        publicar(agora)
    }

    private fun publicar(agora: Long = relogio()) {
        val umSegundo = pontos.count { agora - it.instanteMs <= 1_000 }
        _ui.value = TesteBateriaUi(
            etapa = teste.etapa,
            capturando = teste.capturando,
            progresso = teste.progresso(agora),
            tensao = ultimaTensao,
            amostrasPorSegundo = umSegundo.toDouble(),
            lvResets = if (teste.capturando) teste.lvResetsNaCaptura else 0,
            quedaDetectada = teste.quedaDetectada,
            vale = teste.valeAtual.takeIf { teste.etapa == EtapaBateria.PARTIDA && teste.capturando && teste.quedaDetectada },
            pontos = pontos.toList(),
            repouso = teste.resultadoRepouso,
            partida = teste.resultadoPartida,
            lenta = teste.resultadoLenta,
            carga = teste.resultadoCarga,
            puladas = teste.puladas.toSet(),
            vereditoBateria = LaudoBateria.vereditoBateria(teste),
            vereditoAlternador = LaudoBateria.vereditoAlternador(teste),
            laudo = laudoAtual(),
        )
    }

    /** O laudo é gerado uma vez quando o teste termina (a tela segue amostrando a ~20/s). */
    private fun laudoAtual(): String? {
        if (!teste.concluido) {
            laudoCache = null
            return null
        }
        return laudoCache ?: LaudoBateria.gerar(teste, veiculoTexto, LocalDateTime.now()).also { laudoCache = it }
    }

    // ---------------------------------------------------------------- ações da tela

    /** Botão principal: Medir / Iniciar captura da partida, ou Cancelar durante a captura. */
    fun alternarCaptura() {
        if (teste.concluido) return
        if (teste.capturando) {
            teste.cancelarCaptura()
        } else {
            teste.iniciarCaptura(relogio())
        }
        sincronizarSimulador()
        publicar()
    }

    fun pularEtapa() {
        teste.pular()
        sincronizarSimulador()
        publicar()
    }

    fun reiniciar() {
        teste.reiniciar()
        sincronizarSimulador()
        publicar()
    }

    /**
     * Modo simulado: a fase da curva de tensão segue o roteiro — repouso até a captura da partida
     * começar, o roteiro da partida durante ela e o alternador depois que o motor "pegou".
     */
    private fun sincronizarSimulador() {
        val simular = simulador ?: return
        val fase = when (teste.etapa) {
            EtapaBateria.REPOUSO -> FaseBateriaSimulada.REPOUSO
            EtapaBateria.PARTIDA -> if (teste.capturando) FaseBateriaSimulada.PARTIDA else FaseBateriaSimulada.REPOUSO
            EtapaBateria.ALTERNADOR_LENTA, EtapaBateria.CONCLUIDO -> FaseBateriaSimulada.MARCHA_LENTA
            EtapaBateria.ALTERNADOR_CARGA -> FaseBateriaSimulada.CARGA
        }
        // A partida sempre recomeça o roteiro; as demais fases só mudam quando trocam.
        if (fase != faseSimulada || fase == FaseBateriaSimulada.PARTIDA) {
            faseSimulada = fase
            simular(fase)
        }
    }

    /**
     * Tela fechada de vez: para a amostragem e encerra a sessão manual (restaura o `ATSH` e libera
     * o polling). O `viewModelScope` já está cancelado aqui, então usa um escopo próprio. Sempre
     * encerra (é idempotente): a tela pode fechar depois de a sessão começar e antes do retorno de
     * `iniciarSessaoManual()`, e sem isso o polling ficaria congelado com o estado "Conectado".
     */
    override fun onCleared() {
        amostragem?.cancel()
        simulador?.invoke(null)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { cliente.encerrarSessaoManual() }
        super.onCleared()
    }

    companion object {
        const val COMANDO_TENSAO = "ATRV"

        /** Piso entre amostras: ~20/s; o ELM327 real costuma levar 30–60 ms por `ATRV` no Bluetooth. */
        const val PERIODO_MINIMO_MS = 50L
        private const val RESPIRO_MINIMO_MS = 5L

        /** Prazo curto: na partida uma amostra perdida não pode segurar as seguintes. */
        const val TIMEOUT_ATRV_MS = 1_000L
        const val JANELA_GRAFICO_MS = 20_000L

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CharruaMotorsApp
                val veiculoId = createSavedStateHandle().get<Long>(DiagnosticoActivity.EXTRA_VEICULO_ID) ?: 0L
                val cliente = app.obdClientDe(app.modoObd)
                TesteBateriaViewModel(
                    cliente = cliente,
                    veiculo = { app.veiculoRepository.buscarPorId(veiculoId) },
                    simulador = (cliente as? ObdClientFake)?.let { fake -> fake::simularBateria },
                )
            }
        }
    }
}
