package com.charruamotors.ui.oficina.checkup

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
import com.charruamotors.R
import com.charruamotors.databinding.ActivityCheckupObdBinding
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.obd.sessao.FerramentaManualObd
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.util.TextoCompartilhavel
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import kotlinx.coroutines.launch

/**
 * Oficina → Checkup OBD2 / Prontidão (pré-compra) ou Falhas de Ignição (Modo 06), conforme o
 * [EXTRA_FERRAMENTA]. Leitura em 1 clique, resumo em texto limpo e Copiar/Compartilhar.
 */
class CheckupObdActivity : AppCompatActivity(), FerramentaManualObd {

    private lateinit var binding: ActivityCheckupObdBinding

    private val ferramenta: FerramentaCheckup by lazy {
        runCatching { FerramentaCheckup.valueOf(intent.getStringExtra(EXTRA_FERRAMENTA).orEmpty()) }.getOrDefault(FerramentaCheckup.PRONTIDAO)
    }
    private val viewModel: CheckupObdViewModel by viewModels { CheckupObdViewModel.factory(ferramenta) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityCheckupObdBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val prontidao = ferramenta == FerramentaCheckup.PRONTIDAO
        binding.toolbar.setTitle(if (prontidao) R.string.checkup_titulo else R.string.misfire_titulo)
        binding.textoDescricao.setText(if (prontidao) R.string.checkup_descricao else R.string.misfire_descricao)
        binding.botaoLer.setText(if (prontidao) R.string.checkup_ler else R.string.misfire_ler)
        binding.botaoLer.setOnClickListener { viewModel.ler() }
        binding.botaoCopiar.setOnClickListener { texto()?.let { TextoCompartilhavel.copiar(this, titulo(), it) } }
        binding.botaoCompartilhar.setOnClickListener { texto()?.let { TextoCompartilhavel.compartilhar(this, titulo(), it) } }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.estado.collect { mostrar(viewModel.ui.value) } }
                launch { viewModel.ui.collect(::mostrar) }
            }
        }
    }

    private fun titulo() = binding.toolbar.title?.toString().orEmpty()

    private fun texto(): String? = viewModel.ui.value.resumo

    private fun mostrar(ui: CheckupUi) = with(binding) {
        val conectado = viewModel.estado.value is EstadoConexaoObd.Conectado
        botaoLer.isEnabled = conectado && !ui.ocupado
        progresso.isVisible = ui.ocupado
        val aviso = ui.aviso ?: if (!conectado) getString(R.string.oficina_requer_conexao) else null
        textoEstado.isVisible = aviso != null
        textoEstado.text = aviso
        cardResultado.isVisible = ui.resumo != null
        grupoAcoes.isVisible = ui.resumo != null
        textoResultado.text = ui.resumo
    }

    companion object {
        const val EXTRA_FERRAMENTA = "ferramenta_checkup"

        fun intent(context: Context, veiculoId: Long, ferramenta: FerramentaCheckup): Intent =
            Intent(context, CheckupObdActivity::class.java)
                .putExtra(DiagnosticoActivity.EXTRA_VEICULO_ID, veiculoId)
                .putExtra(EXTRA_FERRAMENTA, ferramenta.name)
    }
}
