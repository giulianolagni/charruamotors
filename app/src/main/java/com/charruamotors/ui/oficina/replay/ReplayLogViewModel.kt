package com.charruamotors.ui.oficina.replay

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.charruamotors.oficina.replay.AnaliseReplay
import com.charruamotors.oficina.replay.ColunaLog
import com.charruamotors.oficina.replay.CsvInvalidoException
import com.charruamotors.oficina.replay.EstatisticaSerie
import com.charruamotors.oficina.replay.JanelaReplay
import com.charruamotors.oficina.replay.LogCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

/** Log aberto no replay. */
data class LogAberto(val nome: String, val log: LogCsv, val total: JanelaReplay, val duracaoMinimaMs: Long)

/** Resumo de um sensor selecionado: estatísticas da janela e o valor no cursor. */
data class ResumoSerie(
    val coluna: Int,
    /** Posição na seleção (0–2): define a cor. */
    val posicao: Int,
    val definicao: ColunaLog,
    val estatistica: EstatisticaSerie?,
    val valorCursor: Double?,
)

data class ResumoReplay(val janela: JanelaReplay, val series: List<ResumoSerie>, val instanteCursor: Long?)

sealed interface EventoReplay {
    data class Erro(val detalhe: String) : EventoReplay
    data object MaximoSensores : EventoReplay

    /** Relatório `.txt` da sessão lido: a tela mostra no diálogo (aberto no topo). */
    data class Texto(val arquivo: ArquivoLogCsv, val texto: TextoLog) : EventoReplay
}

/**
 * Visualizador de Logs CSV: lista os logs de `Downloads/CharruaOBD`, abre um (ou outro arquivo
 * pelo seletor do sistema), guarda a seleção de até 3 sensores, a janela de tempo e o cursor
 * (sobrevivem à rotação) e calcula o resumo Mínimo/Máximo/Média da janela visível.
 */
class ReplayLogViewModel(private val repositorio: RepositorioLogsCsv) : ViewModel() {

    private val _arquivos = MutableStateFlow<List<ArquivoLogCsv>>(emptyList())
    val arquivos: StateFlow<List<ArquivoLogCsv>> = _arquivos.asStateFlow()

    private val _carregando = MutableStateFlow(false)
    val carregando: StateFlow<Boolean> = _carregando.asStateFlow()

    private val _log = MutableStateFlow<LogAberto?>(null)
    val log: StateFlow<LogAberto?> = _log.asStateFlow()

    private val _selecionados = MutableStateFlow<List<Int>>(emptyList())
    val selecionados: StateFlow<List<Int>> = _selecionados.asStateFlow()

    private val _janela = MutableStateFlow<JanelaReplay?>(null)
    val janela: StateFlow<JanelaReplay?> = _janela.asStateFlow()

    private val _cursor = MutableStateFlow<Int?>(null)
    val cursor: StateFlow<Int?> = _cursor.asStateFlow()

    private val _eventos = Channel<EventoReplay>(Channel.BUFFERED)
    val eventos: Flow<EventoReplay> = _eventos.receiveAsFlow()

    /** Recalculado a cada gesto (fora da main thread: logs longos têm dezenas de milhares de linhas). */
    val resumo: StateFlow<ResumoReplay?> = combine(_log, _selecionados, _janela, _cursor) { aberto, sel, janela, cursor ->
        if (aberto == null || janela == null) return@combine null
        val log = aberto.log
        val faixa = AnaliseReplay.faixaIndices(log.instantesMs, janela)
        ResumoReplay(
            janela = janela,
            series = sel.mapIndexed { posicao, coluna ->
                ResumoSerie(
                    coluna = coluna,
                    posicao = posicao,
                    definicao = log.colunas[coluna],
                    estatistica = AnaliseReplay.estatisticas(log.series[coluna], faixa),
                    valorCursor = cursor?.let { log.series[coluna].getOrNull(it) }?.takeUnless { it.isNaN() },
                )
            },
            instanteCursor = cursor?.let { log.instantesMs.getOrNull(it) },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var carregamento: Job? = null

    init {
        atualizarLista()
    }

    fun atualizarLista() {
        viewModelScope.launch { _arquivos.value = runCatching { repositorio.listar() }.getOrDefault(emptyList()) }
    }

    /** CSV → replay no gráfico; `.txt` (relatório da caixa-preta) → diálogo de texto. */
    fun abrir(arquivo: ArquivoLogCsv) {
        if (!arquivo.texto) return carregar(arquivo.uri) { arquivo.nome }
        carregamento?.cancel()
        carregamento = viewModelScope.launch {
            _carregando.value = true
            try {
                _eventos.send(EventoReplay.Texto(arquivo, repositorio.lerTexto(arquivo.uri)))
            } catch (e: IOException) {
                _eventos.send(EventoReplay.Erro(e.message ?: "falha de leitura"))
            } catch (e: SecurityException) {
                _eventos.send(EventoReplay.Erro("sem permissão para ler o arquivo"))
            } finally {
                _carregando.value = false
            }
        }
    }

    /** URI que outro app consegue ler, para compartilhar o arquivo. */
    fun uriCompartilhavel(arquivo: ArquivoLogCsv): Uri = repositorio.uriCompartilhavel(arquivo)

    /** Documento escolhido em "Abrir outro CSV" (Storage Access Framework). */
    fun abrirDocumento(uri: Uri) = carregar(uri) { repositorio.nomeDe(uri) }

    private fun carregar(uri: Uri, nome: suspend () -> String) {
        carregamento?.cancel()
        carregamento = viewModelScope.launch {
            _carregando.value = true
            try {
                val log = repositorio.abrir(uri)
                val total = AnaliseReplay.janelaCompleta(log)
                _log.value = LogAberto(nome(), log, total, AnaliseReplay.duracaoMinima(log))
                _selecionados.value = AnaliseReplay.selecaoInicial(log)
                _janela.value = total
                _cursor.value = null
            } catch (e: CsvInvalidoException) {
                _eventos.send(EventoReplay.Erro(e.message ?: "formato inválido"))
            } catch (e: IOException) {
                _eventos.send(EventoReplay.Erro(e.message ?: "falha de leitura"))
            } catch (e: SecurityException) {
                _eventos.send(EventoReplay.Erro("sem permissão para ler o arquivo"))
            } catch (e: OutOfMemoryError) {
                _eventos.send(EventoReplay.Erro("arquivo grande demais para a memória do aparelho"))
            } finally {
                _carregando.value = false
            }
        }
    }

    /** Volta para a lista de arquivos. */
    fun fecharLog() {
        _log.value = null
        _janela.value = null
        _cursor.value = null
        _selecionados.value = emptyList()
        atualizarLista()
    }

    fun alternarSensor(coluna: Int) {
        val nova = AnaliseReplay.alternarSelecao(_selecionados.value, coluna)
        if (nova == null) {
            _eventos.trySend(EventoReplay.MaximoSensores)
            return
        }
        _selecionados.value = nova
    }

    fun mudarJanela(janela: JanelaReplay) {
        _janela.value = janela
    }

    fun moverCursor(indice: Int?) {
        _cursor.value = indice
    }

    fun verTudo() {
        _log.value?.let { _janela.value = it.total }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY]!!
                ReplayLogViewModel(RepositorioLogsCsv(app))
            }
        }
    }
}
