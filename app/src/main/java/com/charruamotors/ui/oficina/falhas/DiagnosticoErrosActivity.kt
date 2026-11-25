package com.charruamotors.ui.oficina.falhas

import com.charruamotors.obd.sessao.FerramentaManualObd
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.ActivityDiagnosticoErrosBinding
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.diagnostico.obd.DiagnosticoDtcAdapter
import com.charruamotors.ui.diagnostico.obd.ResultadoDiagnostico
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.charruamotors.ui.util.comoDecimal
import com.charruamotors.ui.util.comoMilhar
import com.charruamotors.ui.util.comoNumero
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Oficina → Diagnóstico de Erros (DTCs): lê e apaga as falhas da injeção. Mostra os códigos por
 * categoria (confirmados, pendentes e permanentes) com a descrição do banco offline e o Freeze
 * Frame com velocidade e temperatura nas unidades globais.
 */
class DiagnosticoErrosActivity : AppCompatActivity(), FerramentaManualObd {

    private lateinit var binding: ActivityDiagnosticoErrosBinding
    private val viewModel: DiagnosticoErrosViewModel by viewModels { DiagnosticoErrosViewModel.Factory }
    private val adapter = DiagnosticoDtcAdapter()
    private val app get() = application as CharruaMotorsApp

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticoErrosBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.listaCodigos.adapter = adapter
        binding.botaoLerCodigos.setOnClickListener { viewModel.lerCodigos() }
        binding.botaoLimparCodigos.setOnClickListener { confirmarLimpeza() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.estado.collect { atualizarBotoes() } }
                launch { viewModel.ocupado.collect { atualizarBotoes() } }
                launch { app.sessaoObd.infoConexao.collect { binding.textoFalhasModo21.isVisible = it.modo.avisaModos07e0A } }
                launch { viewModel.diagnostico.collect(::mostrarDiagnostico) }
                launch { app.preferenciasApp.config.collect { mostrarDiagnostico(viewModel.diagnostico.value) } }
                launch {
                    viewModel.eventos.collect { evento ->
                        val texto = when (evento) {
                            is EventoFalhas.Mensagem -> getString(evento.resId)
                            is EventoFalhas.Texto -> evento.texto
                        }
                        if (texto.isNotBlank()) Snackbar.make(binding.root, texto, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun atualizarBotoes() = with(binding) {
        val conectado = viewModel.estado.value is EstadoConexaoObd.Conectado
        val livre = conectado && !viewModel.ocupado.value
        botaoLerCodigos.isEnabled = livre
        botaoLimparCodigos.isEnabled = livre
        textoFalhasEstado.setText(if (conectado) R.string.falhas_descricao else R.string.oficina_requer_conexao)
    }

    private fun confirmarLimpeza() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.obd_limpar_titulo)
            .setMessage(R.string.obd_limpar_mensagem)
            .setNegativeButton(R.string.botao_cancelar, null)
            .setPositiveButton(R.string.obd_limpar_confirmar) { _, _ -> viewModel.limparCodigos() }
            .show()
    }

    private fun mostrarDiagnostico(resultado: ResultadoDiagnostico?) = with(binding) {
        adapter.submitList(resultado?.itens().orEmpty())
        textoCodigosVazio.isVisible = resultado == null
        cardFreezeFrame.isVisible = resultado != null
        if (resultado == null) return@with
        val ff = resultado.freezeFrame
        if (ff == null || ff.vazio) {
            grupoFfDados.isVisible = false
            textoFfVazio.isVisible = true
            textoFfVazio.setText(if (ff == null) R.string.obd_ff_nao_suportado else R.string.obd_ff_vazio)
            return@with
        }
        grupoFfDados.isVisible = true
        textoFfVazio.isVisible = false
        val conversor: ConversorUnidades = app.preferenciasApp.config.value.conversor
        val vazio = getString(R.string.valor_indisponivel)
        textoFfDtc.text = ff.dtc ?: getString(R.string.obd_ff_sem_dtc)
        textoFfDescricao.isVisible = resultado.infoFreezeFrame != null
        textoFfDescricao.text = resultado.infoFreezeFrame?.titulo
        valorFfRpm.text = ff.rpm?.let { getString(R.string.obd_valor_rpm, it.comoMilhar()) } ?: vazio
        valorFfVelocidade.text = ff.velocidade?.let {
            getString(R.string.obd_valor_unidade, conversor.velocidade(it.toDouble()).comoNumero(0), conversor.simboloVelocidade)
        } ?: vazio
        valorFfTemperatura.text = ff.temperaturaMotor?.let {
            getString(R.string.obd_valor_unidade, conversor.temperatura(it.toDouble()).comoNumero(0), conversor.simboloTemperatura)
        } ?: vazio
        valorFfCarga.text = ff.cargaMotor?.let { getString(R.string.obd_valor_carga, it.comoDecimal(1)) } ?: vazio
    }

    companion object {
        fun intent(context: Context, veiculoId: Long): Intent =
            Intent(context, DiagnosticoErrosActivity::class.java).putExtra(DiagnosticoActivity.EXTRA_VEICULO_ID, veiculoId)
    }
}
