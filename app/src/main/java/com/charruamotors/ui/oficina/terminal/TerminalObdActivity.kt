package com.charruamotors.ui.oficina.terminal

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.charruamotors.R
import com.charruamotors.databinding.ActivityTerminalObdBinding
import com.charruamotors.databinding.ItemResultadoPidBinding
import com.charruamotors.obd.ObdParser
import com.charruamotors.oficina.terminal.EntradaTerminal
import com.charruamotors.oficina.terminal.FormatadorTerminal
import com.charruamotors.oficina.terminal.TradutorTerminal
import com.charruamotors.oficina.varredura.IdentificacaoPid
import com.charruamotors.oficina.varredura.PresetVarredura
import com.charruamotors.oficina.varredura.StatusIdentificacao
import com.charruamotors.oficina.varredura.TipoRespostaPid
import com.charruamotors.oficina.varredura.VarreduraPids
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.util.TextoCompartilhavel
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.charruamotors.ui.util.definirTexto
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

/**
 * Oficina — Terminal ELM327 didático (ajuda com sugestões, atalhos de um toque, descrição de cada
 * comando e a resposta traduzida ao lado do hex bruto, com laudo para compartilhar) e Varredura
 * amigável de PIDs (cabeçalho, serviço e faixa, com presets Toyota): cada resposta é cruzada com o
 * catálogo do app em tempo real, com barra colorida, métricas e o filtro "o que o carro respondeu".
 */
class TerminalObdActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalObdBinding
    private val viewModel: TerminalObdViewModel by viewModels { TerminalObdViewModel.Factory }
    private val adapterResultados = ResultadoAdapter()

    /** Preset multi-módulo escolhido (Varredura Completa SW4); null = os campos valem (um cabeçalho). */
    private var presetMulti: PresetVarredura? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalObdBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.abas.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(aba: TabLayout.Tab) = mostrarAba(aba.position)
            override fun onTabUnselected(aba: TabLayout.Tab) = Unit
            override fun onTabReselected(aba: TabLayout.Tab) = Unit
        })
        val abaInicial = savedInstanceState?.getInt(ESTADO_ABA) ?: intent.getIntExtra(EXTRA_ABA, ABA_TERMINAL)
        binding.abas.getTabAt(abaInicial)?.select()
        mostrarAba(abaInicial)

        configurarTerminal()
        configurarVarredura(savedInstanceState == null)
        observar()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(ESTADO_ABA, binding.abas.selectedTabPosition)
    }

    private fun mostrarAba(posicao: Int) {
        binding.painelTerminal.isVisible = posicao == 0
        binding.painelVarredura.isVisible = posicao == 1
    }

    // ---------------------------------------------------------------- terminal

    private fun configurarTerminal() = with(binding) {
        textoDicas.text = TradutorTerminal.DICAS.joinToString("\n") { "• $it" }
        botaoOcultarAjuda.setOnClickListener {
            textoDicas.isVisible = !textoDicas.isVisible
            botaoOcultarAjuda.setText(if (textoDicas.isVisible) R.string.terminal_ajuda_ocultar else R.string.terminal_ajuda_mostrar)
        }
        for (atalho in TradutorTerminal.ATALHOS) {
            grupoAtalhos.addView(Chip(this@TerminalObdActivity).apply {
                text = getString(R.string.terminal_atalho, atalho.comando, atalho.titulo)
                contentDescription = getString(R.string.terminal_atalho_descricao, atalho.comando, atalho.descricao)
                setOnClickListener {
                    mostrarDescricao(atalho.comando)
                    viewModel.enviar(atalho.comando)
                }
            })
        }
        botaoEnviar.setOnClickListener { enviarDigitado() }
        campoComando.setOnEditorActionListener { _, acao, _ ->
            if (acao == EditorInfo.IME_ACTION_SEND || acao == EditorInfo.IME_ACTION_DONE) {
                enviarDigitado()
                true
            } else {
                false
            }
        }
        campoComando.doAfterTextChanged { texto -> if (!texto.isNullOrBlank()) mostrarDescricao(texto.toString()) }
        botaoLimparLog.setOnClickListener { viewModel.limparLog() }
        botaoCopiarLog.setOnClickListener {
            viewModel.textoLog()?.let { TextoCompartilhavel.copiar(this@TerminalObdActivity, getString(R.string.terminal_log_assunto), it) }
        }
        botaoLaudoTerminal.setOnClickListener {
            lifecycleScope.launch {
                val laudo = viewModel.laudo()
                if (laudo == null) {
                    mensagem(getString(R.string.terminal_laudo_vazio))
                } else {
                    TextoCompartilhavel.mostrarDialogo(this@TerminalObdActivity, getString(R.string.terminal_laudo_titulo), laudo)
                }
            }
        }
    }

    /** "ATRV: Lê a tensão da bateria" abaixo dos atalhos (some quando o app não conhece o comando). */
    private fun mostrarDescricao(comando: String) = with(binding) {
        val descricao = viewModel.tradutor.descrever(comando)
        textoDescricaoComando.isVisible = descricao != null
        textoDescricaoComando.text = descricao?.let { getString(R.string.terminal_descricao_comando, comando.trim().uppercase(), it) }
    }

    private fun enviarDigitado() {
        val texto = binding.campoComando.text?.toString().orEmpty()
        if (texto.isBlank()) return
        viewModel.enviar(texto)
        binding.campoComando.setText("")
    }

    /**
     * Log como num terminal: cada comando com o hex bruto e a latência, a descrição do comando (cinza)
     * e a resposta traduzida (ciano), no contexto do `ATSH` em vigor naquele momento.
     */
    private fun mostrarLog(entradas: List<EntradaTerminal>) = with(binding) {
        if (entradas.isEmpty()) {
            textoLog.definirTexto(getString(R.string.terminal_log_vazio))
            return@with
        }
        val corDescricao = ContextCompat.getColor(this@TerminalObdActivity, R.color.terminal_descricao)
        val corTraducao = ContextCompat.getColor(this@TerminalObdActivity, R.color.terminal_traducao)
        val conversor = viewModel.conversor()
        val texto = SpannableStringBuilder()
        entradas.forEachIndexed { i, entrada ->
            if (i > 0) texto.append('\n')
            texto.append(FormatadorTerminal.formatar(entrada))
            viewModel.tradutor.descrever(entrada.comando)?.let { descricao ->
                texto.append('\n')
                val inicio = texto.length
                texto.append(RECUO_LOG).append("↳ ").append(descricao)
                texto.setSpan(ForegroundColorSpan(corDescricao), inicio, texto.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val cabecalho = viewModel.tradutor.cabecalhoAtivo(entradas.subList(0, i))
            viewModel.tradutor.traduzir(entrada, cabecalho, conversor)?.let { traducao ->
                texto.append('\n')
                val inicio = texto.length
                texto.append(RECUO_LOG).append("→ ").append(traducao)
                texto.setSpan(ForegroundColorSpan(corTraducao), inicio, texto.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                texto.setSpan(StyleSpan(Typeface.BOLD), inicio, texto.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        textoLog.text = texto
        // Acompanha o fim do log, como um terminal.
        rolagemLog.post { rolagemLog.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------- varredura

    private fun configurarVarredura(primeiraVez: Boolean) = with(binding) {
        val presets = VarreduraPids.PRESETS
        campoPreset.setAdapter(ArrayAdapter(this@TerminalObdActivity, android.R.layout.simple_list_item_1, presets.map { it.nome }))
        campoCabecalho.setAdapter(ArrayAdapter(this@TerminalObdActivity, android.R.layout.simple_list_item_1, VarreduraPids.CABECALHOS))
        campoServico.setAdapter(ArrayAdapter(this@TerminalObdActivity, android.R.layout.simple_list_item_1, VarreduraPids.SERVICOS))
        campoPreset.setOnItemClickListener { _, _, posicao, _ -> aplicarPreset(posicao) }
        // Escolher um cabeçalho à mão sai da varredura multi-módulo.
        campoCabecalho.setOnItemClickListener { _, _, _, _ -> sairDoPresetMulti() }
        // Ao trocar de serviço, a faixa padrão muda de largura (DIDs de 4 dígitos no 22).
        campoServico.setOnItemClickListener { _, _, posicao, _ ->
            sairDoPresetMulti()
            if (VarreduraPids.SERVICOS[posicao] == "22") {
                campoInicio.setText("F400")
                campoFim.setText("F4FF")
            } else {
                campoInicio.setText("00")
                campoFim.setText("FF")
            }
        }
        if (primeiraVez) aplicarPreset(0)

        listaResultados.adapter = adapterResultados
        listaResultados.itemAnimator = null
        chipSoRespondeu.text = getString(R.string.varredura_filtro_carro)
        chipSoRespondeu.setOnCheckedChangeListener { _, marcado -> viewModel.definirMostrarTudo(!marcado) }
        lifecycleScope.launch {
            viewModel.modeloVeiculo()?.let { chipSoRespondeu.text = getString(R.string.varredura_filtro_modelo, it) }
        }
        botaoVarredura.setOnClickListener {
            // Depois de uma rotação o campo volta sozinho; o preset multi é reconhecido pelo nome.
            val multi = presetMulti ?: VarreduraPids.PRESETS.firstOrNull { it.multiModulo && it.nome == campoPreset.text.toString() }
            if (viewModel.varredura.value.rodando) {
                viewModel.pararVarredura()
            } else if (multi != null) {
                viewModel.iniciarVarreduraModulos(multi.modulos)
            } else {
                viewModel.iniciarVarredura(
                    campoCabecalho.text.toString(),
                    campoServico.text.toString(),
                    campoInicio.text?.toString().orEmpty(),
                    campoFim.text?.toString().orEmpty(),
                )
            }
        }
        botaoRelatorio.setOnClickListener {
            lifecycleScope.launch {
                val texto = viewModel.relatorio()
                if (texto == null) {
                    mensagem(getString(R.string.varredura_vazia))
                } else {
                    TextoCompartilhavel.mostrarDialogo(this@TerminalObdActivity, getString(R.string.varredura_relatorio_titulo), texto)
                }
            }
        }
    }

    private fun aplicarPreset(posicao: Int) = with(binding) {
        val preset = VarreduraPids.PRESETS.getOrNull(posicao) ?: return@with
        val c = preset.config
        presetMulti = preset.takeIf { it.multiModulo }
        campoPreset.setText(preset.nome, false)
        campoCabecalho.setText(if (preset.multiModulo) preset.modulos.joinToString("+") { it.cabecalho } else c.cabecalho, false)
        campoServico.setText(c.servico, false)
        campoInicio.setText(c.hex(c.inicio))
        campoFim.setText(c.hex(c.fim))
    }

    private fun sairDoPresetMulti() = with(binding) {
        if (presetMulti == null) return@with
        presetMulti = null
        campoPreset.setText("", false)
        if (campoCabecalho.text.contains('+')) campoCabecalho.setText(VarreduraPids.CABECALHOS.first(), false)
    }

    private fun mostrarVarredura(estado: EstadoVarredura) = with(binding) {
        botaoVarredura.definirTexto(getString(if (estado.rodando) R.string.varredura_parar else R.string.varredura_iniciar))
        listOf(layoutPreset, layoutCabecalho, layoutServico).forEach { it.isEnabled = !estado.rodando }
        campoInicio.isEnabled = !estado.rodando
        campoFim.isEnabled = !estado.rodando
        if (chipSoRespondeu.isChecked == estado.mostrarTudo) chipSoRespondeu.isChecked = !estado.mostrarTudo

        val progresso = estado.progresso
        val metricas = estado.metricas
        barraVarredura.isInvisible = estado.config == null
        barraVarredura.metricas = metricas
        textoMetricas.isVisible = estado.config != null
        textoMetricas.definirTexto(
            resources.getQuantityString(R.plurals.varredura_encontrados, metricas.encontrados, metricas.encontrados) + " · " +
                getString(R.string.varredura_metricas, metricas.identificados, metricas.ignorados),
        )
        val final = estado.final
        val multi = estado.multi
        val etapa = estado.config?.takeIf { estado.multiModulo }?.let { c ->
            getString(R.string.varredura_modulo_etapa, estado.moduloAtual + 1, estado.planejados.size, c.cabecalho, VarreduraPids.nomeModulo(c.cabecalho)) + " · "
        }.orEmpty()
        textoProgresso.definirTexto(when {
            estado.rodando && progresso != null ->
                etapa + getString(R.string.varredura_progresso, progresso.feitos, progresso.total, progresso.responderam, progresso.atual ?: "")
            estado.rodando -> etapa.removeSuffix(" · ")
            multi != null -> (multi.motivoParada?.let { getString(R.string.varredura_parada, it) + " · " }.orEmpty()) +
                getString(R.string.varredura_multi_concluida, multi.positivas, multi.consultados, multi.modulosPresentes.size, multi.planejados.size)
            final?.motivoParada != null -> getString(R.string.varredura_parada, final.motivoParada) +
                " · " + getString(R.string.varredura_concluida, final.positivas.size, final.resultados.size)
            final != null -> getString(R.string.varredura_concluida, final.positivas.size, final.total)
            else -> ""
        })
        textoProgresso.isVisible = textoProgresso.text.isNotEmpty()

        val visiveis = estado.visiveis
        adapterResultados.submitList(visiveis)
        textoVarreduraVazia.isVisible = visiveis.isEmpty()
        botaoRelatorio.isEnabled = estado.resultados.isNotEmpty()
        botaoEnviar.isEnabled = !estado.rodando
        grupoAtalhos.isEnabled = !estado.rodando
    }

    // ---------------------------------------------------------------- observação

    private fun observar() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.log.collect(::mostrarLog) }
                launch { viewModel.varredura.collect(::mostrarVarredura) }
                launch { viewModel.enviando.collect { binding.botaoEnviar.isEnabled = !it && !viewModel.varredura.value.rodando } }
                launch {
                    viewModel.eventos.collect { evento ->
                        when (evento) {
                            is EventoTerminal.Mensagem -> mensagem(getString(evento.resId))
                            is EventoTerminal.Texto -> mensagem(evento.texto)
                            is EventoTerminal.SemConexao -> avisarSemConexao(evento.resId)
                        }
                    }
                }
            }
        }
    }

    private fun avisarSemConexao(resId: Int) {
        if (isFinishing) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.terminal_titulo)
            .setMessage(resId)
            .setCancelable(false)
            .setPositiveButton(R.string.botao_fechar) { _, _ -> finish() }
            .show()
    }

    private fun mensagem(texto: String) {
        if (texto.isNotBlank()) Snackbar.make(binding.root, texto, Snackbar.LENGTH_LONG).show()
    }

    /** Lista ao vivo da varredura amigável: status, valor decodificado, origem, latência e o hex bruto. */
    private class ResultadoAdapter : ListAdapter<IdentificacaoPid, ResultadoAdapter.Item>(Diferenca) {

        class Item(val binding: ItemResultadoPidBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Item(ItemResultadoPidBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Item, position: Int) {
            val id = getItem(position)
            val r = id.resultado
            val contexto = holder.itemView.context
            val b = holder.binding
            b.textoStatus.text = id.descricao
            b.textoValor.text = id.valorTexto
            b.textoValor.isVisible = id.valorTexto.isNotEmpty()
            b.textoComando.text = id.cabecalho?.let { "$it · ${r.comando}" } ?: r.comando
            val digitos = if (r.comando.startsWith("22")) 4 else 2
            val tipo = when (r.tipo) {
                TipoRespostaPid.POSITIVA -> contexto.getString(R.string.varredura_dados, r.dados(digitos).size)
                TipoRespostaPid.NEGATIVA -> contexto.getString(
                    R.string.varredura_nrc, r.comando.take(2), r.nrc.orEmpty(), ObdParser.descricaoNrc(r.nrc.orEmpty()),
                )
                TipoRespostaPid.SEM_DADOS -> "NO DATA"
                TipoRespostaPid.ERRO -> r.erro ?: "?"
            }
            b.textoTipo.text = listOfNotNull(id.conhecido?.origem, tipo, "${r.latenciaMs} ms").joinToString(" · ")
            b.textoBytes.isVisible = r.respondeu
            b.textoBytes.text = r.bytesHex
            val cor = when (id.status) {
                StatusIdentificacao.IDENTIFICADO -> R.color.varredura_identificado
                StatusIdentificacao.DESCONHECIDO -> R.color.varredura_desconhecido
                StatusIdentificacao.IGNORADO -> R.color.varredura_ignorado
            }
            b.marcaStatus.setBackgroundColor(ContextCompat.getColor(contexto, cor))
            b.textoStatus.setTextColor(
                if (id.status == StatusIdentificacao.IGNORADO) ContextCompat.getColor(contexto, R.color.texto_secundario) else b.textoComando.currentTextColor,
            )
        }

        private object Diferenca : DiffUtil.ItemCallback<IdentificacaoPid>() {
            override fun areItemsTheSame(a: IdentificacaoPid, b: IdentificacaoPid) = a.comando == b.comando && a.cabecalho == b.cabecalho
            override fun areContentsTheSame(a: IdentificacaoPid, b: IdentificacaoPid) = a == b
        }
    }

    companion object {
        private const val ESTADO_ABA = "aba"
        private const val EXTRA_ABA = "extra_aba_terminal"
        private const val RECUO_LOG = "              "

        const val ABA_TERMINAL = 0
        const val ABA_VARREDURA = 1

        fun intent(context: Context, veiculoId: Long, aba: Int = ABA_TERMINAL): Intent =
            Intent(context, TerminalObdActivity::class.java)
                .putExtra(DiagnosticoActivity.EXTRA_VEICULO_ID, veiculoId)
                .putExtra(EXTRA_ABA, aba)
    }
}
