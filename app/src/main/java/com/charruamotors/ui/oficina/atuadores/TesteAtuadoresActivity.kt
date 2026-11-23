package com.charruamotors.ui.oficina.atuadores

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.R
import com.charruamotors.databinding.ActivityTesteAtuadoresBinding
import com.charruamotors.obd.sessao.FerramentaManualObd
import com.charruamotors.oficina.atuadores.Atuador
import com.charruamotors.oficina.atuadores.EtapaAtuadores
import com.charruamotors.oficina.atuadores.GrupoAtuador
import com.charruamotors.oficina.atuadores.ResultadoAtuador
import com.charruamotors.oficina.atuadores.ResultadoSessao
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Ferramenta — Teste de Atuadores (UDS `2F`) da aba Oficina: botões por grupo, cartão "ATIVO" com o
 * que observar e o progresso do pulso (simulação visual/didática), comando customizado e o
 * resultado com a explicação técnica do NRC quando a central recusa. A tela fica acesa.
 */
class TesteAtuadoresActivity : AppCompatActivity(), FerramentaManualObd {

    private lateinit var binding: ActivityTesteAtuadoresBinding
    private val viewModel: TesteAtuadoresViewModel by viewModels { TesteAtuadoresViewModel.Factory }
    private val botoes = mutableMapOf<Atuador, MaterialButton>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityTesteAtuadoresBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        montarBotoes()
        binding.botaoParar.setOnClickListener { viewModel.parar() }
        binding.switchSimularMotor.setOnCheckedChangeListener { _, ligado -> viewModel.simularMotorLigado(ligado) }
        binding.sliderEtanol.addOnChangeListener { _, valor, doUsuario -> if (doUsuario) viewModel.simularEtanol(valor.roundToInt()) }
        binding.botaoEnviarCustomizado.setOnClickListener { enviarCustomizado() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.ui.collect(::mostrar) }
                launch { animarProgresso() }
                launch {
                    viewModel.eventos.collect { e ->
                        when (e) {
                            EventoAtuadores.SemConexao -> avisarSemConexao(R.string.oficina_sem_conexao)
                            EventoAtuadores.ConexaoPerdida -> avisarSemConexao(R.string.oficina_conexao_perdida)
                            EventoAtuadores.Ocupado -> Snackbar.make(binding.root, R.string.atuadores_ocupado, Snackbar.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    /** Um título por grupo e um botão por atuador (os críticos com a marca "crítico · RPM 0"). */
    private fun montarBotoes() {
        val container = binding.containerAtuadores
        for (grupo in GrupoAtuador.entries) {
            val titulo = TextView(this).apply {
                text = grupo.titulo
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                setPadding(0, resources.getDimensionPixelSize(R.dimen.espaco_medio), 0, 0)
            }
            container.addView(titulo)
            for (atuador in Atuador.entries.filter { it.grupo == grupo }) {
                val botao = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = if (atuador.critico) "${atuador.titulo}  ·  ${getString(R.string.atuadores_critico)}" else atuador.titulo
                    isAllCaps = false
                    setOnClickListener { viewModel.testar(atuador) }
                }
                container.addView(botao, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                botoes[atuador] = botao
            }
        }
    }

    private fun mostrar(ui: TesteAtuadoresUi) = with(binding) {
        toolbar.subtitle = ui.simulacao?.titulo ?: ui.mapeamento.nome
        textoSessao.text = when (val s = ui.sessao) {
            null -> getString(R.string.atuadores_preparando, ui.mapeamento.cabecalhoPadrao)
            ResultadoSessao.Estendida -> getString(R.string.atuadores_sessao_estendida)
            is ResultadoSessao.Recusada -> getString(R.string.atuadores_sessao_recusada, s.resposta.rotulo)
            is ResultadoSessao.SemResposta -> getString(R.string.atuadores_sessao_sem_resposta, s.motivo, ui.mapeamento.cabecalhoPadrao)
        }
        val rpm = ui.rpm
        textoRpm.text = when {
            rpm == null -> getString(R.string.atuadores_rpm_desconhecido)
            rpm < 1.0 -> getString(R.string.atuadores_rpm_parado)
            else -> getString(R.string.atuadores_rpm_ligado, rpm.roundToInt().toString())
        }
        textoMapeamento.text = getString(R.string.atuadores_mapeamento, ui.mapeamento.nome) + "\n" + ui.mapeamento.observacao

        val simulado = ui.simulacao != null
        switchSimularMotor.isVisible = simulado
        if (switchSimularMotor.isChecked != ui.motorSimuladoLigado) switchSimularMotor.isChecked = ui.motorSimuladoLigado
        textoDidatico.isVisible = simulado
        if (simulado) textoDidatico.text = getString(R.string.atuadores_modo_didatico, ui.simulacao?.titulo)
        val etanol = ui.etanolSimulado
        textoEtanol.isVisible = etanol != null
        sliderEtanol.isVisible = etanol != null
        if (etanol != null) {
            textoEtanol.text = getString(R.string.atuadores_etanol_simulado, etanol)
            if (sliderEtanol.value.roundToInt() != etanol) sliderEtanol.value = etanol.toFloat()
        }

        val emTeste = ui.emTeste
        cardAtivo.isVisible = emTeste != null
        if (emTeste != null) {
            textoAtivo.text = getString(R.string.atuadores_ativo, emTeste.titulo)
            textoObservar.text = emTeste.observar
            textoComandoAtivo.text = emTeste.comando
        }
        val livre = ui.etapa == EtapaAtuadores.PRONTA
        botoes.values.forEach { it.isEnabled = livre }
        botaoEnviarCustomizado.isEnabled = livre

        textoResultado.text = ui.ultimo?.let(::textoResultado).orEmpty()
        textoHistorico.text = ui.historico.joinToString("\n")
    }

    /** Barra do pulso andando enquanto o atuador está ativo (simulação visual do acionamento). */
    private suspend fun animarProgresso() {
        while (true) { // cancelado pelo repeatOnLifecycle (no delay)
            val emTeste = viewModel.ui.value.emTeste
            if (emTeste != null && emTeste.duracaoMs > 0) {
                val fracao = ((android.os.SystemClock.elapsedRealtime() - emTeste.inicioMs).toFloat() / emTeste.duracaoMs).coerceIn(0f, 1f)
                binding.progressoAtivo.setProgressCompat((fracao * 1000).toInt(), false)
            }
            delay(PASSO_ANIMACAO_MS)
        }
    }

    private fun textoResultado(r: ResultadoAtuador): String = when (r) {
        is ResultadoAtuador.Sucesso -> getString(R.string.atuadores_ok, r.titulo, r.resposta)
        is ResultadoAtuador.Recusado -> getString(R.string.atuadores_recusado, r.titulo, r.negativa.rotulo, r.negativa.explicacao)
        is ResultadoAtuador.BloqueadoMotor -> r.rpm?.let { getString(R.string.atuadores_bloqueado_ligado, r.titulo, it.roundToInt().toString()) }
            ?: getString(R.string.atuadores_bloqueado_desconhecido, r.titulo)
        is ResultadoAtuador.SemResposta -> getString(R.string.atuadores_sem_resposta, r.titulo, r.motivo)
        is ResultadoAtuador.Falha -> getString(R.string.atuadores_falha, r.titulo, r.mensagem)
        is ResultadoAtuador.Interrompido -> getString(R.string.atuadores_interrompido, r.titulo)
    }

    private fun enviarCustomizado() = with(binding) {
        val duracao = campoDuracao.text?.toString()?.toIntOrNull() ?: 2
        val ok = viewModel.testarCustomizado(campoComando.text.toString(), campoCabecalho.text.toString(), duracao)
        layoutComando.error = if (ok) null else getString(R.string.atuadores_customizado_invalido)
    }

    private fun avisarSemConexao(resId: Int) {
        if (isFinishing) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.atuadores_titulo)
            .setMessage(resId)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .show()
    }

    companion object {
        private const val PASSO_ANIMACAO_MS = 50L

        fun intent(context: Context, veiculoId: Long): Intent =
            Intent(context, TesteAtuadoresActivity::class.java).putExtra(DiagnosticoActivity.EXTRA_VEICULO_ID, veiculoId)
    }
}
