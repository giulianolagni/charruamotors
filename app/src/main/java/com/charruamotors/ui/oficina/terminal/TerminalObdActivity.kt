package com.charruamotors.ui.oficina.terminal

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import com.charruamotors.oficina.varredura.TipoRespostaPid
import com.charruamotors.oficina.varredura.ResultadoPid
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
 * Ferramenta 3 — Terminal Manual ELM327 (comandos brutos, atalhos e log com latência) e Varredura
 * Automática de PIDs (cabeçalho, serviço e faixa, com presets Toyota), sobre a conexão ativa.
 */
class TerminalObdActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalObdBinding
    private val viewModel: TerminalObdViewModel by viewModels { TerminalObdViewModel.Factory }
    private val adapterResultados = ResultadoAdapter()

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
        val abaInicial = savedInstanceState?.getInt(ESTADO_ABA) ?: 0
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
        for (atalho in ATALHOS) {
            grupoAtalhos.addView(Chip(this@TerminalObdActivity).apply {
                text = atalho
                typeface = android.graphics.Typeface.MONOSPACE
                setOnClickListener { viewModel.enviar(atalho) }
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
        botaoLimparLog.setOnClickListener { viewModel.limparLog() }
        botaoCopiarLog.setOnClickListener {
            viewModel.textoLog()?.let { TextoCompartilhavel.copiar(this@TerminalObdActivity, getString(R.string.terminal_log_assunto), it) }
        }
    }

    private fun enviarDigitado() {
        val texto = binding.campoComando.text?.toString().orEmpty()
        if (texto.isBlank()) return
        viewModel.enviar(texto)
        binding.campoComando.setText("")
    }

    private fun mostrarLog(entradas: List<EntradaTerminal>) = with(binding) {
        textoLog.definirTexto(if (entradas.isEmpty()) getString(R.string.terminal_log_vazio) else FormatadorTerminal.formatar(entradas))
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
        // Ao trocar de serviço, a faixa padrão muda de largura (DIDs de 4 dígitos no 22).
        campoServico.setOnItemClickListener { _, _, posicao, _ ->
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
        switchMostrarTudo.setOnCheckedChangeListener { _, marcado -> viewModel.definirMostrarTudo(marcado) }
        botaoVarredura.setOnClickListener {
            if (viewModel.varredura.value.rodando) {
                viewModel.pararVarredura()
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
        campoPreset.setText(preset.nome, false)
        campoCabecalho.setText(c.cabecalho, false)
        campoServico.setText(c.servico, false)
        campoInicio.setText(c.hex(c.inicio))
        campoFim.setText(c.hex(c.fim))
    }

    private fun mostrarVarredura(estado: EstadoVarredura) = with(binding) {
        botaoVarredura.definirTexto(getString(if (estado.rodando) R.string.varredura_parar else R.string.varredura_iniciar))
        listOf(layoutPreset, layoutCabecalho, layoutServico).forEach { it.isEnabled = !estado.rodando }
        campoInicio.isEnabled = !estado.rodando
        campoFim.isEnabled = !estado.rodando
        if (switchMostrarTudo.isChecked != estado.mostrarTudo) switchMostrarTudo.isChecked = estado.mostrarTudo

        val progresso = estado.progresso
        progressoVarredura.isInvisible = !estado.rodando
        if (progresso != null && progresso.total > 0) {
            progressoVarredura.setProgressCompat(progresso.feitos * 100 / progresso.total, true)
        }
        val final = estado.final
        textoProgresso.definirTexto(when {
            estado.rodando && progresso != null ->
                getString(R.string.varredura_progresso, progresso.feitos, progresso.total, progresso.responderam, progresso.atual ?: "")
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

    private class ResultadoAdapter : ListAdapter<ResultadoPid, ResultadoAdapter.Item>(Diferenca) {

        class Item(val binding: ItemResultadoPidBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Item(ItemResultadoPidBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Item, position: Int) {
            val r = getItem(position)
            val contexto = holder.itemView.context
            val b = holder.binding
            b.textoComando.text = r.comando
            val digitos = if (r.comando.startsWith("22")) 4 else 2
            val tipo = when (r.tipo) {
                TipoRespostaPid.POSITIVA -> contexto.getString(R.string.varredura_dados, r.dados(digitos).size)
                TipoRespostaPid.NEGATIVA -> contexto.getString(
                    R.string.varredura_nrc, r.comando.take(2), r.nrc.orEmpty(), ObdParser.descricaoNrc(r.nrc.orEmpty()),
                )
                TipoRespostaPid.SEM_DADOS -> "NO DATA"
                TipoRespostaPid.ERRO -> r.erro ?: "?"
            }
            b.textoTipo.text = "$tipo · ${r.latenciaMs} ms"
            b.textoBytes.isVisible = r.respondeu
            b.textoBytes.text = r.bytesHex
            b.textoComando.setTextColor(
                ContextCompat.getColor(contexto, if (r.respondeu) R.color.azul_primario else R.color.texto_secundario),
            )
        }

        private object Diferenca : DiffUtil.ItemCallback<ResultadoPid>() {
            override fun areItemsTheSame(a: ResultadoPid, b: ResultadoPid) = a.comando == b.comando
            override fun areContentsTheSame(a: ResultadoPid, b: ResultadoPid) = a == b
        }
    }

    companion object {
        private const val ESTADO_ABA = "aba"

        /** Atalhos do terminal: protocolo, tensão e os cabeçalhos do motor/câmbio/funcional, mais exemplos de leitura. */
        val ATALHOS = listOf("ATDP", "ATRV", "ATSH 7E0", "ATSH 7E1", "ATSH 7DF", "ATI", "0100", "2101", "13")

        fun intent(context: Context, veiculoId: Long): Intent =
            Intent(context, TerminalObdActivity::class.java).putExtra(DiagnosticoActivity.EXTRA_VEICULO_ID, veiculoId)
    }
}
