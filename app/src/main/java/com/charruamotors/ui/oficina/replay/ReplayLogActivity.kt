package com.charruamotors.ui.oficina.replay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.charruamotors.R
import com.charruamotors.databinding.ActivityReplayLogBinding
import com.charruamotors.databinding.ItemArquivoLogBinding
import com.charruamotors.oficina.replay.JanelaReplay
import com.charruamotors.ui.componentes.LogReplayChartView
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.charruamotors.ui.util.comoNumero
import com.charruamotors.ui.util.definirTexto
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Ferramenta 2 — Visualizador Interno de Logs CSV: lista os logs do Data Logger (ou abre outro CSV
 * pelo seletor do sistema) e faz o replay em [LogReplayChartView] com até 3 sensores, zoom por
 * pinça, arraste, cursor com horário e valores e o resumo Mín/Máx/Média da janela visível.
 * Não usa a conexão OBD2.
 */
class ReplayLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReplayLogBinding
    private val viewModel: ReplayLogViewModel by viewModels { ReplayLogViewModel.Factory }
    private val adapter = ArquivoAdapter { viewModel.abrir(it) }
    private val coresSeries by lazy {
        listOf(R.color.serie_1, R.color.serie_2, R.color.serie_3).map { ContextCompat.getColor(this, it) }
    }

    private val abrirDocumento = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::abrirDocumento)
    }

    /** Com um log aberto, o "voltar" retorna à lista em vez de fechar a ferramenta. */
    private val voltarParaLista = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = viewModel.fecharLog()
    }

    private var formatoTempo = FormatoTempo(true, 0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityReplayLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, voltarParaLista)

        binding.listaArquivos.adapter = adapter
        binding.botaoAbrirOutro.setOnClickListener {
            runCatching { abrirDocumento.launch(TIPOS_CSV) }.onFailure { mensagem(it.message ?: "") }
        }
        binding.botaoAtualizar.setOnClickListener { viewModel.atualizarLista() }
        binding.botaoOutrosLogs.setOnClickListener { viewModel.fecharLog() }
        binding.botaoVerTudo.setOnClickListener {
            viewModel.log.value?.let { binding.graficoReplay.definirJanela(it.total) }
        }
        binding.graficoReplay.ouvinte = object : LogReplayChartView.Ouvinte {
            override fun aoMudarJanela(janela: JanelaReplay) = viewModel.mudarJanela(janela)
            override fun aoMoverCursor(indice: Int?) = viewModel.moverCursor(indice)
        }
        binding.graficoReplay.formatarTempo = { formatoTempo.eixo(it) }
        binding.graficoReplay.formatarValor = ::formatarValor

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.arquivos.collect {
                        adapter.submitList(it)
                        binding.textoListaVazia.isVisible = it.isEmpty()
                    }
                }
                launch { viewModel.carregando.collect { binding.progressoCarregando.isInvisible = !it } }
                // Log ou seleção mudou: redesenha o gráfico com as séries novas.
                launch { combine(viewModel.log, viewModel.selecionados) { log, sel -> log to sel }.collect { (log, sel) -> mostrarLog(log, sel) } }
                launch { viewModel.resumo.collect(::mostrarResumo) }
                launch {
                    viewModel.eventos.collect { evento ->
                        when (evento) {
                            is EventoReplay.Erro -> mensagem(getString(R.string.replay_erro_ler, evento.detalhe))
                            EventoReplay.MaximoSensores -> mensagem(getString(R.string.replay_max_sensores))
                        }
                    }
                }
            }
        }
    }

    private fun mostrarLog(aberto: LogAberto?, selecionados: List<Int>) = with(binding) {
        painelLista.isVisible = aberto == null
        painelGrafico.isVisible = aberto != null
        voltarParaLista.isEnabled = aberto != null
        if (aberto == null) {
            toolbar.subtitle = null
            return@with
        }
        val log = aberto.log
        formatoTempo = FormatoTempo(log.tempoAbsoluto, log.inicioMs)
        toolbar.subtitle = aberto.nome
        textoNomeLog.definirTexto(aberto.nome)
        val info = getString(
            R.string.replay_info_log,
            log.amostras.toDouble().comoNumero(0),
            log.colunas.size,
            duracao(aberto.total.duracaoMs),
        )
        textoInfoLog.definirTexto(
            if (log.linhasIgnoradas > 0) info + " · " + getString(R.string.replay_linhas_ignoradas, log.linhasIgnoradas) else info,
        )
        montarChips(aberto, selecionados)
        val series = selecionados.mapIndexed { i, coluna ->
            LogReplayChartView.Serie(log.colunas[coluna].rotulo, log.series[coluna], coresSeries[i % coresSeries.size])
        }
        textoSemSensor.isVisible = series.isEmpty()
        graficoReplay.definirDados(
            log.instantesMs,
            series,
            aberto.total,
            viewModel.janela.value ?: aberto.total,
            aberto.duracaoMinimaMs,
            viewModel.cursor.value,
        )
    }

    /** Um chip por sensor com leitura; os selecionados ficam marcados com a cor da série. */
    private fun montarChips(aberto: LogAberto, selecionados: List<Int>) {
        val grupo = binding.grupoSensores
        grupo.removeAllViews()
        for (coluna in aberto.log.colunasComDados) {
            val posicao = selecionados.indexOf(coluna)
            val chip = Chip(this).apply {
                text = aberto.log.colunas[coluna].rotulo
                isCheckable = true
                isChecked = posicao >= 0
                if (posicao >= 0) {
                    chipStrokeWidth = 2 * resources.displayMetrics.density
                    chipStrokeColor = android.content.res.ColorStateList.valueOf(coresSeries[posicao % coresSeries.size])
                }
                // O estado marcado vem do ViewModel: o clique só pede a troca (o 4º sensor é recusado).
                setOnClickListener {
                    isChecked = posicao >= 0
                    viewModel.alternarSensor(coluna)
                }
            }
            grupo.addView(chip)
        }
    }

    private fun mostrarResumo(resumo: ResumoReplay?) = with(binding) {
        if (resumo == null) return@with
        val janela = resumo.janela
        textoJanela.definirTexto(
            getString(R.string.replay_janela, formatoTempo.eixo(janela.inicioMs), formatoTempo.eixo(janela.fimMs), duracao(janela.duracaoMs)),
        )
        textoCursor.definirTexto(
            resumo.instanteCursor?.let { getString(R.string.replay_cursor, formatoTempo.cursor(it)) } ?: getString(R.string.replay_cursor_vazio),
        )
        val lista = listaResumo
        while (lista.childCount > resumo.series.size) lista.removeViewAt(lista.childCount - 1)
        while (lista.childCount < resumo.series.size) {
            lista.addView(TextView(this@ReplayLogActivity).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                fontFeatureSettings = "tnum"
            })
        }
        resumo.series.forEachIndexed { i, s ->
            val texto = buildString {
                append("● ").append(s.definicao.rotulo)
                s.valorCursor?.let { append(": ").append(formatarValor(it)) }
                append("\n   ")
                val e = s.estatistica
                append(
                    if (e == null) getString(R.string.replay_sem_leituras)
                    else getString(R.string.replay_estatistica, formatarValor(e.minimo), formatarValor(e.maximo), formatarValor(e.media)),
                )
            }
            (lista.getChildAt(i) as TextView).apply {
                definirTexto(texto)
                setTextColor(coresSeries[s.posicao % coresSeries.size])
            }
        }
    }

    /** Casas decimais conforme a grandeza: 3.420 rpm, 101,3 kPa, 0,82 V. */
    private fun formatarValor(v: Double): String = v.comoNumero(
        when {
            abs(v) >= 100 -> 0
            abs(v) >= 10 -> 1
            else -> 2
        },
    )

    private fun duracao(ms: Long): String {
        val s = ms / 1000
        return when {
            s >= 3600 -> "${s / 3600} h ${(s % 3600) / 60} min"
            s >= 60 -> "${s / 60} min ${s % 60} s"
            else -> "${(ms / 1000.0).comoNumero(1)} s"
        }
    }

    private fun mensagem(texto: String) {
        if (texto.isNotBlank()) Snackbar.make(binding.root, texto, Snackbar.LENGTH_LONG).show()
    }

    /** Relógio (HH:mm:ss) quando o CSV tem `timestamp_ms`; senão o tempo desde o início (m:ss). */
    private class FormatoTempo(private val absoluto: Boolean, private val inicioMs: Long) {
        private val zona = ZoneId.systemDefault()
        private val eixo = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val cursor = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        fun eixo(t: Long): String = if (absoluto) eixo.format(Instant.ofEpochMilli(t).atZone(zona)) else relativo(t, false)
        fun cursor(t: Long): String = if (absoluto) cursor.format(Instant.ofEpochMilli(t).atZone(zona)) else relativo(t, true)

        private fun relativo(t: Long, ms: Boolean): String {
            val d = (t - inicioMs).coerceAtLeast(0)
            val base = "%d:%02d".format(d / 60_000, (d / 1000) % 60)
            return if (ms) base + ".%03d".format(d % 1000) else base
        }
    }

    private class ArquivoAdapter(private val aoAbrir: (ArquivoLogCsv) -> Unit) :
        ListAdapter<ArquivoLogCsv, ArquivoAdapter.Item>(Diferenca) {

        class Item(val binding: ItemArquivoLogBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Item(ItemArquivoLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Item, position: Int) {
            val arquivo = getItem(position)
            val contexto = holder.itemView.context
            holder.binding.textoNome.text = arquivo.nome
            val data = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(Instant.ofEpochMilli(arquivo.modificadoMs).atZone(ZoneId.systemDefault()))
            holder.binding.textoDetalhe.text =
                contexto.getString(R.string.replay_item_detalhe, data, Formatter.formatShortFileSize(contexto, arquivo.tamanhoBytes))
            holder.itemView.setOnClickListener { aoAbrir(arquivo) }
        }

        private object Diferenca : DiffUtil.ItemCallback<ArquivoLogCsv>() {
            override fun areItemsTheSame(a: ArquivoLogCsv, b: ArquivoLogCsv) = a.uri == b.uri
            override fun areContentsTheSame(a: ArquivoLogCsv, b: ArquivoLogCsv) = a == b
        }
    }

    companion object {
        /** Alguns gerenciadores de arquivos marcam o CSV como texto genérico ou binário. */
        private val TIPOS_CSV = arrayOf(
            "text/csv", "text/comma-separated-values", "application/csv", "text/plain",
            "application/vnd.ms-excel", "application/octet-stream",
        )

        fun intent(context: Context): Intent = Intent(context, ReplayLogActivity::class.java)
    }
}
