package com.charruamotors.ui.diagnostico

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.ActivityDiagnosticoBinding
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.sistema.navegacao.VeiculoFavorito
import com.charruamotors.ui.dashboard.DashboardActivity
import com.charruamotors.ui.navegacao.MenuLateralGlobal
import com.charruamotors.ui.painel.PersonalizarPainelActivity
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Tela principal (v1.5) — abre direto no veículo favorito. Recebe [EXTRA_VEICULO_ID] e organiza as
 * 4 abas na navegação inferior (BottomNavigationView + ViewPager2 sem deslizar): Painel (direção e
 * mostradores), Oficina (bateria, DTCs, varredura e terminal), Manutenções e Histórico
 * (abastecimentos, logs salvos e diário de viagens). [EXTRA_ABA] abre direto numa delas.
 *
 * A Toolbar traz sempre os 3 riscos do [MenuLateralGlobal], o nome do veículo ativo (toque = seletor
 * rápido ou Meus Veículos) e, só na aba Painel, o ícone de Personalizar Painel. As telas abertas
 * pelo menu ficam por cima desta: o Voltar retorna para a mesma aba. Nas outras abas o Voltar leva
 * ao Painel antes de sair, e a sessão OBD (no serviço) segue ativa ao sair.
 */
class DiagnosticoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticoBinding
    private val app get() = application as CharruaMotorsApp
    private val viewModel: DiagnosticoViewModel by viewModels { DiagnosticoViewModel.Factory }
    private val menuLateral = MenuLateralGlobal(this)

    /**
     * Voltar previsível (v1.7): fora da aba Painel, volta primeiro ao Painel (a tela principal);
     * só do Painel o Voltar sai. Registrado antes do menu lateral, que tem prioridade quando a
     * gaveta está aberta (fecha a gaveta primeiro).
     */
    private val voltarAoPainel = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = mostrarAba(DiagnosticoPagerAdapter.ABA_PAINEL)
    }

    /**
     * Meus Veículos abre por cima das abas; se o usuário escolher outro veículo lá, ele devolve o
     * id e esta tela troca no lugar (sem limpar a pilha de telas).
     */
    private val abrirMeusVeiculos = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { resultado ->
        if (resultado.resultCode != RESULT_OK) return@registerForActivityResult
        val escolhido = resultado.data?.getLongExtra(EXTRA_VEICULO_ID, 0L) ?: 0L
        if (escolhido > 0 && escolhido != viewModel.veiculoId) trocarVeiculo(escolhido)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        if (viewModel.veiculoId <= 0) {
            finish() // sem veículo não há o que diagnosticar
            return
        }

        binding = ActivityDiagnosticoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)

        onBackPressedDispatcher.addCallback(this, voltarAoPainel)
        menuLateral.ligar(binding.gaveta, binding.menuLateral, binding.toolbar, binding.root) {
            abrirMeusVeiculos.launch(DashboardActivity.intent(this, viewModel.veiculoId))
        }
        menuLateral.verificarAtualizacaoAoAbrir(savedInstanceState == null)
        binding.seletorVeiculo.setOnClickListener { escolherVeiculoRapido() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.acao_personalizar_painel) {
                startActivity(PersonalizarPainelActivity.intent(this))
                true
            } else {
                false
            }
        }

        binding.paginas.adapter = DiagnosticoPagerAdapter(this, viewModel.veiculoId)
        // Navegação só pela barra inferior: o deslize lateral brigaria com a rolagem e o arraste da tela.
        binding.paginas.isUserInputEnabled = false
        binding.paginas.offscreenPageLimit = DiagnosticoPagerAdapter.ABAS - 1
        binding.navegacao.setOnItemSelectedListener { item ->
            binding.paginas.setCurrentItem(DiagnosticoPagerAdapter.paginaDoItem(item.itemId), false)
            true
        }
        binding.paginas.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val item = DiagnosticoPagerAdapter.itemDaPagina(position)
                if (binding.navegacao.selectedItemId != item) binding.navegacao.selectedItemId = item
                voltarAoPainel.isEnabled = position != DiagnosticoPagerAdapter.ABA_PAINEL
                // Personalizar Painel fica só na Toolbar da aba Painel (sem repetir nas outras).
                binding.toolbar.menu.findItem(R.id.acao_personalizar_painel)?.isVisible = position == DiagnosticoPagerAdapter.ABA_PAINEL
            }
        })
        // Com a Activity recriada (tema trocado nas Configurações, rotação...), a aba é restaurada.
        if (savedInstanceState == null) {
            val aba = intent.getIntExtra(EXTRA_ABA, DiagnosticoPagerAdapter.ABA_PAINEL)
            binding.navegacao.selectedItemId = DiagnosticoPagerAdapter.itemDaPagina(aba)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.veiculo.combine(app.preferenciasAutoConexao.config) { v, c -> v to c.veiculoId }
                    .collect { (veiculo, favorito) -> if (veiculo != null) mostrarVeiculo(veiculo, favorito) }
            }
        }
    }

    private fun mostrarVeiculo(veiculo: Veiculo, favorito: Long) {
        binding.textoVeiculoToolbar.text = veiculo.descricao
        binding.textoPlacaToolbar.text = if (veiculo.id == favorito) {
            getString(R.string.veiculo_toolbar_favorito, veiculo.placaFormatada)
        } else {
            veiculo.placaFormatada
        }
        binding.seletorVeiculo.contentDescription = "${veiculo.descricao}. ${getString(R.string.veiculo_seletor_descricao)}"
    }

    /** Toque no nome do veículo: troca rápida (★ = favorito) ou Meus Veículos para gerenciar. */
    private fun escolherVeiculoRapido() {
        lifecycleScope.launch {
            val veiculos = app.veiculoRepository.observarTodos().first()
            val favorito = VeiculoFavorito.idEfetivo(veiculos.map { it.id }, app.preferenciasAutoConexao.config.value.veiculoId)
            val opcoes = veiculos.map { v ->
                getString(
                    if (v.id == favorito) R.string.veiculos_seletor_item_favorito else R.string.veiculos_seletor_item,
                    v.descricao,
                    v.placaFormatada,
                )
            } + getString(R.string.veiculos_gerenciar)
            val atual = veiculos.indexOfFirst { it.id == viewModel.veiculoId }
            MaterialAlertDialogBuilder(this@DiagnosticoActivity)
                .setTitle(R.string.veiculos_seletor_titulo)
                .setSingleChoiceItems(opcoes.toTypedArray(), atual) { dialogo, i ->
                    dialogo.dismiss()
                    val escolhido = veiculos.getOrNull(i)
                    when {
                        escolhido == null -> abrirMeusVeiculos.launch(DashboardActivity.intent(this@DiagnosticoActivity, viewModel.veiculoId))
                        escolhido.id != viewModel.veiculoId -> trocarVeiculo(escolhido.id)
                    }
                }
                .setNegativeButton(R.string.botao_cancelar, null)
                .show()
        }
    }

    /** Recria a tela no outro veículo, na mesma aba (as abas e ViewModels são por veículo). */
    private fun trocarVeiculo(id: Long) {
        startActivity(intent(this, id, DiagnosticoPagerAdapter.paginaDoItem(binding.navegacao.selectedItemId)))
        finish()
    }

    /** Troca de aba por código (ex.: a Oficina manda conectar o scanner no Painel). */
    fun mostrarAba(aba: Int) {
        binding.navegacao.selectedItemId = DiagnosticoPagerAdapter.itemDaPagina(aba)
    }

    // Callbacks do ciclo de vida registrados no Logcat (filtro "DiagnosticoActivity").
    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: tela ficando visível")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: tela em primeiro plano")
    }

    override fun onPause() {
        Log.d(TAG, "onPause: tela perdendo o foco")
        super.onPause()
    }

    override fun onStop() {
        Log.d(TAG, "onStop: tela não está mais visível")
        super.onStop()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VEICULO_ID = "extra_veiculo_id"
        const val EXTRA_ABA = "extra_aba"
        private const val TAG = "DiagnosticoActivity"

        fun intent(context: Context, veiculoId: Long, aba: Int = DiagnosticoPagerAdapter.ABA_PAINEL): Intent =
            Intent(context, DiagnosticoActivity::class.java)
                .putExtra(EXTRA_VEICULO_ID, veiculoId)
                .putExtra(EXTRA_ABA, aba)
    }
}
