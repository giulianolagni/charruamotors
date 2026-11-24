package com.charruamotors.ui.oficina.terminal

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
import com.charruamotors.obd.ObdClient
import com.charruamotors.oficina.terminal.EntradaTerminal
import com.charruamotors.oficina.terminal.FormatadorTerminal
import com.charruamotors.oficina.terminal.LaudoTerminal
import com.charruamotors.oficina.terminal.TradutorTerminal
import com.charruamotors.oficina.varredura.IdentificacaoPid
import com.charruamotors.oficina.varredura.IdentificadorPids
import com.charruamotors.oficina.varredura.MetricasVarredura
import com.charruamotors.oficina.varredura.StatusIdentificacao
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.oficina.varredura.ConfigVarredura
import com.charruamotors.oficina.varredura.ProgressoVarredura
import com.charruamotors.oficina.varredura.RelatorioVarredura
import com.charruamotors.oficina.varredura.ResultadoPid
import com.charruamotors.oficina.varredura.ResultadoVarredura
import com.charruamotors.oficina.varredura.ResultadoVarreduraMulti
import com.charruamotors.oficina.varredura.VarreduraPids
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** Estado da aba Varredura de PIDs (amigável: cada resposta já cruzada com o catálogo). */
data class EstadoVarredura(
    val rodando: Boolean = false,
    val config: ConfigVarredura? = null,
    val progresso: ProgressoVarredura? = null,
    val resultados: List<ResultadoPid> = emptyList(),
    val identificacoes: List<IdentificacaoPid> = emptyList(),
    val mostrarTudo: Boolean = false,
    /** Varredura terminada (concluída, parada ou interrompida); null enquanto roda ou antes da primeira. */
    val final: ResultadoVarredura? = null,
    /** Varredura multi-módulo (Completa SW4): os módulos na ordem; vazio numa varredura simples. */
    val planejados: List<ConfigVarredura> = emptyList(),
    /** Índice do módulo em andamento na multi-módulo. */
    val moduloAtual: Int = 0,
    /** Resultado consolidado da multi-módulo terminada. */
    val multi: ResultadoVarreduraMulti? = null,
) {
    val multiModulo: Boolean get() = planejados.size > 1

    /** Filtro "Ver apenas o que o carro respondeu": identificados e desconhecidos; com [mostrarTudo], tudo. */
    val visiveis: List<IdentificacaoPid>
        get() = if (mostrarTudo) identificacoes else identificacoes.filter { it.status != StatusIdentificacao.IGNORADO }

    val metricas: MetricasVarredura
        get() = MetricasVarredura.de(identificacoes, if (multiModulo) planejados.sumOf { it.quantidade } else config?.quantidade ?: 0)
}

sealed interface EventoTerminal {
    data class Mensagem(@param:StringRes val resId: Int) : EventoTerminal
    data class Texto(val texto: String) : EventoTerminal
    data class SemConexao(@param:StringRes val resId: Int) : EventoTerminal
}

/**
 * Terminal Manual ELM327 e Varredura Automática de PIDs sobre a conexão Bluetooth ativa, numa
 * sessão manual: o polling dos sensores fica pausado enquanto a tela está aberta e, ao fechar, o
 * cliente restaura o `ATSH` ativo (e a configuração, se um comando a mudou). Terminal e varredura
 * não rodam ao mesmo tempo (o `ATSH` de um mudaria o do outro).
 */
class TerminalObdViewModel(
    private val cliente: ObdClient,
    private val veiculo: suspend () -> Veiculo?,
    private val identificador: IdentificadorPids,
    val tradutor: TradutorTerminal,
    private val conversorAtual: () -> ConversorUnidades = { ConversorUnidades.PADRAO },
    private val relogioParede: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _log = MutableStateFlow<List<EntradaTerminal>>(emptyList())
    val log: StateFlow<List<EntradaTerminal>> = _log.asStateFlow()

    private val _enviando = MutableStateFlow(false)
    val enviando: StateFlow<Boolean> = _enviando.asStateFlow()

    private val _varredura = MutableStateFlow(EstadoVarredura())
    val varredura: StateFlow<EstadoVarredura> = _varredura.asStateFlow()

    private val _eventos = Channel<EventoTerminal>(Channel.BUFFERED)
    val eventos: Flow<EventoTerminal> = _eventos.receiveAsFlow()

    private val dadosVeiculo = viewModelScope.async { runCatching { veiculo() }.getOrNull() }
    private val descricaoVeiculo = viewModelScope.async { dadosVeiculo.await()?.let { "${it.descricao} (${it.placaFormatada})" } }

    /** Modelo do veículo para o filtro ("Ver apenas o que a SW4 respondeu"); null até carregar. */
    suspend fun modeloVeiculo(): String? = dadosVeiculo.await()?.modelo?.takeIf { it.isNotBlank() }

    /** `ATSH` em vigor no log (para traduzir `21xx`/`22xxxx` no módulo certo). */
    fun cabecalhoAtivo(): String? = tradutor.cabecalhoAtivo(_log.value)

    fun conversor(): ConversorUnidades = conversorAtual()

    private var sessao: Job? = null
    private var jobVarredura: Job? = null
    private var inicioVarreduraMs = 0L
    private var duracaoVarreduraMs: Long? = null
    @Volatile private var pararSolicitado = false

    init {
        sessao = viewModelScope.launch {
            if (cliente.iniciarSessaoManual().isFailure) {
                _eventos.send(EventoTerminal.SemConexao(R.string.oficina_sem_conexao))
            }
        }
    }

    // ---------------------------------------------------------------- terminal

    fun enviar(comando: String) {
        val texto = comando.trim()
        if (texto.isEmpty()) return
        if (_varredura.value.rodando) {
            _eventos.trySend(EventoTerminal.Mensagem(R.string.terminal_ocupado))
            return
        }
        if (_enviando.value) return
        viewModelScope.launch {
            sessao?.join()
            _enviando.value = true
            try {
                val instante = relogioParede()
                val entrada = cliente.enviarComandoManual(texto).fold(
                    onSuccess = { EntradaTerminal.de(it, instante) },
                    onFailure = { EntradaTerminal.falha(texto.uppercase(), it.message ?: "falha de comunicação", instante) },
                )
                adicionar(entrada)
                verificarConexao()
            } finally {
                _enviando.value = false
            }
        }
    }

    private fun adicionar(entrada: EntradaTerminal) {
        _log.value = (_log.value + entrada).takeLast(MAX_ENTRADAS_LOG)
    }

    fun limparLog() {
        _log.value = emptyList()
    }

    fun textoLog(): String? = _log.value.takeIf { it.isNotEmpty() }?.let { FormatadorTerminal.formatar(it) }

    /** Laudo da sessão do terminal (resumo, leituras traduzidas e log bruto); null se nada foi enviado. */
    suspend fun laudo(): String? {
        val entradas = _log.value.takeIf { it.isNotEmpty() } ?: return null
        return LaudoTerminal.gerar(
            entradas, tradutor, LocalDateTime.now(),
            veiculo = descricaoVeiculo.await(),
            protocolo = cliente.infoConexao.value.protocolo,
            conversor = conversorAtual(),
        )
    }

    // ---------------------------------------------------------------- varredura

    /** Valida os campos e inicia a varredura; erro de validação vira mensagem. */
    fun iniciarVarredura(cabecalho: String, servico: String, inicioHex: String, fimHex: String) {
        if (_varredura.value.rodando || _enviando.value) return
        val config = VarreduraPids.configurar(cabecalho, servico, inicioHex, fimHex).getOrElse {
            _eventos.trySend(EventoTerminal.Texto(it.message ?: ""))
            return
        }
        jobVarredura = viewModelScope.launch {
            sessao?.join()
            inicioVarreduraMs = SystemClock.elapsedRealtime()
            duracaoVarreduraMs = null
            _varredura.value = _varredura.value.copy(
                rodando = true, config = config, progresso = null, resultados = emptyList(), identificacoes = emptyList(), final = null,
                planejados = emptyList(), moduloAtual = 0, multi = null,
            )
            val acumulados = ArrayList<ResultadoPid>()
            val identificados = ArrayList<IdentificacaoPid>()
            val conversor = conversorAtual()
            pararSolicitado = false
            val final = try {
                VarreduraPids.executar(
                    config,
                    enviar = { cliente.enviarComandoManual(it, ObdClient.TIMEOUT_MANUAL_MS, registrar = false) },
                    deveParar = { pararSolicitado },
                ) { progresso, resultado ->
                    resultado?.let {
                        acumulados += it
                        identificados += identificador.identificar(it, config.cabecalho, conversor)
                    }
                    _varredura.value = _varredura.value.copy(
                        progresso = progresso, resultados = acumulados.toList(), identificacoes = identificados.toList(),
                    )
                }
            } catch (e: CancellationException) {
                concluir(ResultadoVarredura(config, acumulados.toList(), config.quantidade, VarreduraPids.PARADA_USUARIO))
                throw e
            }
            concluir(final)
            verificarConexao()
        }
    }

    /**
     * Varredura multi-módulo (Varredura Completa SW4: 7E0 + 7E1): os cabeçalhos em
     * sequência, com a lista ao vivo acumulando todos e um relatório consolidado no fim.
     */
    fun iniciarVarreduraModulos(modulos: List<ConfigVarredura>) {
        if (_varredura.value.rodando || _enviando.value || modulos.isEmpty()) return
        jobVarredura = viewModelScope.launch {
            sessao?.join()
            inicioVarreduraMs = SystemClock.elapsedRealtime()
            duracaoVarreduraMs = null
            _varredura.value = _varredura.value.copy(
                rodando = true, config = modulos.first(), progresso = null, resultados = emptyList(), identificacoes = emptyList(),
                final = null, planejados = modulos, moduloAtual = 0, multi = null,
            )
            val anteriores = ArrayList<ResultadoVarredura>() // módulos já concluídos
            val acumulados = ArrayList<ResultadoPid>()
            val identificados = ArrayList<IdentificacaoPid>()
            val conversor = conversorAtual()
            var atual = modulos.first()
            pararSolicitado = false
            val final = try {
                VarreduraPids.executarModulos(
                    modulos,
                    enviar = { cliente.enviarComandoManual(it, ObdClient.TIMEOUT_MANUAL_MS, registrar = false) },
                    deveParar = { pararSolicitado },
                    aoIniciarModulo = { i, c ->
                        atual = c
                        _varredura.value = _varredura.value.copy(config = c, moduloAtual = i, progresso = null)
                    },
                    aoModuloConcluido = { anteriores += it },
                ) { progresso, resultado ->
                    resultado?.let {
                        acumulados += it
                        identificados += identificador.identificar(it, atual.cabecalho, conversor)
                    }
                    _varredura.value = _varredura.value.copy(
                        progresso = progresso, resultados = acumulados.toList(), identificacoes = identificados.toList(),
                    )
                }
            } catch (e: CancellationException) {
                concluirModulos(ResultadoVarreduraMulti(modulos, anteriores.toList(), VarreduraPids.PARADA_USUARIO))
                throw e
            }
            concluirModulos(final)
            verificarConexao()
        }
    }

    private fun concluirModulos(multi: ResultadoVarreduraMulti) {
        duracaoVarreduraMs = SystemClock.elapsedRealtime() - inicioVarreduraMs
        val conversor = conversorAtual()
        val identificacoes = multi.modulos.flatMap { identificador.identificarTodos(it.resultados, it.config.cabecalho, conversor) }
        _varredura.value = _varredura.value.copy(
            rodando = false,
            resultados = multi.modulos.flatMap { it.resultados },
            identificacoes = identificacoes,
            final = multi.modulos.lastOrNull(),
            multi = multi,
        )
    }

    private fun concluir(final: ResultadoVarredura) {
        duracaoVarreduraMs = SystemClock.elapsedRealtime() - inicioVarreduraMs
        val identificacoes = identificador.identificarTodos(final.resultados, final.config.cabecalho, conversorAtual())
        _varredura.value = _varredura.value.copy(rodando = false, resultados = final.resultados, identificacoes = identificacoes, final = final)
    }

    /** Parada cooperativa: a varredura termina depois do comando em andamento. */
    fun pararVarredura() {
        pararSolicitado = true
    }

    fun definirMostrarTudo(mostrar: Boolean) {
        _varredura.value = _varredura.value.copy(mostrarTudo = mostrar)
    }

    /** Relatório da última varredura (ou da parcial em andamento); null se nada foi lido. */
    suspend fun relatorio(): String? {
        val estado = _varredura.value
        val config = estado.config ?: return null
        if (estado.resultados.isEmpty()) return null
        if (estado.multiModulo) {
            val multi = estado.multi ?: ResultadoVarreduraMulti(estado.planejados, parciais(estado), "em andamento")
            return RelatorioVarredura.gerarConsolidado(
                multi,
                LocalDateTime.now(),
                veiculo = descricaoVeiculo.await(),
                protocolo = cliente.infoConexao.value.protocolo,
                duracaoMs = duracaoVarreduraMs,
                identificacoes = estado.identificacoes.groupBy { it.cabecalho.orEmpty() },
            )
        }
        val resultado = estado.final ?: ResultadoVarredura(config, estado.resultados, config.quantidade, "em andamento")
        return RelatorioVarredura.gerar(
            resultado,
            LocalDateTime.now(),
            veiculo = descricaoVeiculo.await(),
            protocolo = cliente.infoConexao.value.protocolo,
            duracaoMs = duracaoVarreduraMs,
            identificacoes = estado.identificacoes,
        )
    }

    /** Multi-módulo em andamento: os resultados acumulados, separados por cabeçalho, na ordem planejada. */
    private fun parciais(estado: EstadoVarredura): List<ResultadoVarredura> {
        val porCabecalho = estado.identificacoes.groupBy { it.cabecalho }
        return estado.planejados.mapNotNull { c ->
            porCabecalho[c.cabecalho]?.let { ids -> ResultadoVarredura(c, ids.map { it.resultado }, c.quantidade) }
        }
    }

    private suspend fun verificarConexao() {
        if (cliente.estado.value !is EstadoConexaoObd.Conectado) _eventos.send(EventoTerminal.SemConexao(R.string.oficina_conexao_perdida))
    }

    /**
     * Tela fechada de vez: para a varredura e encerra a sessão manual (restaura o `ATSH` e o polling).
     * Sempre encerra (é idempotente): se a tela fechar enquanto `iniciarSessaoManual()` esperava o
     * ciclo de polling, a sessão já pode ter começado sem o retorno chegar aqui, e o polling congelaria.
     */
    override fun onCleared() {
        jobVarredura?.cancel()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { cliente.encerrarSessaoManual() }
    }

    companion object {
        const val MAX_ENTRADAS_LOG = 300

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CharruaMotorsApp
                val veiculoId = createSavedStateHandle().get<Long>(DiagnosticoActivity.EXTRA_VEICULO_ID) ?: 0L
                TerminalObdViewModel(
                    cliente = app.obdClientDe(app.modoObd),
                    veiculo = { app.veiculoRepository.buscarPorId(veiculoId) },
                    identificador = app.identificadorPids,
                    tradutor = TradutorTerminal(app.catalogoPids, app.identificadorPids),
                    conversorAtual = { app.preferenciasApp.config.value.conversor },
                )
            }
        }
    }
}
