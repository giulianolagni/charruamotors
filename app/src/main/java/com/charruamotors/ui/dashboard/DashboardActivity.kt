package com.charruamotors.ui.dashboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import com.charruamotors.databinding.ActivityDashboardBinding
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.navegacao.MenuLateralGlobal
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.charruamotors.ui.veiculo.VeiculoFormActivity
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Meus Veículos (v1.5): lista com o resumo de cada veículo, a estrela do **favorito** (o app abre
 * direto nele, já no Painel) e o botão "Abrir Painel" de cada um.
 *
 * Dois jeitos de chegar aqui:
 * - **tela inicial** ([EXTRA_RAIZ]), quando ainda não há veículo cadastrado: com o menu lateral
 *   (3 riscos). Ao cadastrar o primeiro veículo, o Painel dele abre sozinho;
 * - **pelo menu lateral ou pelo seletor da Toolbar** das abas: com a seta de Voltar, que retorna
 *   exatamente para a aba de onde o usuário veio.
 */
class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private val app get() = application as CharruaMotorsApp
    private val viewModel: DashboardViewModel by viewModels { DashboardViewModel.Factory }
    private val menuLateral = MenuLateralGlobal(this)
    private val adapter = VeiculoAdapter(
        aoClicar = { resumo -> startActivity(VeiculoFormActivity.intent(this, resumo.veiculo.id)) },
        aoAbrirPainel = { resumo -> abrirPainel(resumo.veiculo) },
        aoFavoritar = { resumo -> marcarFavorito(resumo.veiculo) },
    )

    private val comoRaiz: Boolean get() = intent.getBooleanExtra(EXTRA_RAIZ, false)

    /** Veículo das abas que abriram esta tela (0 = aberta como tela inicial). */
    private val veiculoAtual: Long get() = intent.getLongExtra(EXTRA_VEICULO_ATUAL, 0)

    /** A lista já carregou vazia nesta tela? (para abrir o Painel sozinho no primeiro cadastro) */
    private var estavaVazia = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Ícones claros na status bar, porque a toolbar é azul.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)

        // Menu lateral (3 riscos) sempre acessível, também quando aberta pelas abas (v1.7); o Voltar
        // do sistema retorna à aba de onde o usuário veio.
        menuLateral.ligar(binding.gaveta, binding.menuLateral, binding.toolbar, binding.root) {
            // Já está em Meus Veículos: o item só fecha a gaveta.
        }
        if (comoRaiz) menuLateral.verificarAtualizacaoAoAbrir(savedInstanceState == null)
        binding.textoBoasVindas.text = getString(R.string.dashboard_boas_vindas, app.sessao.nomeUsuario.orEmpty())
        binding.botaoNovoVeiculo.setOnClickListener { startActivity(VeiculoFormActivity.intent(this)) }
        binding.listaVeiculos.adapter = adapter
        estavaVazia = savedInstanceState?.getBoolean(ESTADO_VAZIA) ?: false

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.itens.collect { itens -> itens?.let(::mostrarItens) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(ESTADO_VAZIA, estavaVazia)
    }

    private fun mostrarItens(itens: List<ItemVeiculo>) {
        adapter.submitList(itens)
        binding.textoListaVazia.isVisible = itens.isEmpty()
        binding.textoTotalVeiculos.text =
            resources.getQuantityString(R.plurals.dashboard_total_veiculos, itens.size, itens.size)
        // Tela inicial sem veículos: assim que o primeiro for cadastrado, entra direto no Painel dele.
        if (itens.isEmpty()) {
            estavaVazia = true
        } else if (comoRaiz && estavaVazia) {
            estavaVazia = false
            itens.firstOrNull { it.favorito }?.let { abrirPainel(it.resumo.veiculo) }
        }
    }

    /** Estrela: vira o favorito (veículo padrão da caixa-preta e o da abertura direta). */
    private fun marcarFavorito(veiculo: Veiculo) {
        app.preferenciasAutoConexao.vincularVeiculo(veiculo.id, veiculo.descricao)
        Snackbar.make(binding.root, getString(R.string.veiculos_favorito_definido, veiculo.descricao), Snackbar.LENGTH_LONG).show()
    }

    /**
     * "Abrir Painel": o mesmo veículo das abas de baixo → só volta para elas; outro veículo, aberto
     * pelas abas → devolve o id para elas trocarem no lugar (pilha intacta, sessão ativa preservada);
     * a partir da tela inicial → as abas desse veículo viram a raiz da pilha.
     */
    private fun abrirPainel(veiculo: Veiculo) {
        if (!comoRaiz) {
            if (veiculo.id != veiculoAtual) setResult(RESULT_OK, Intent().putExtra(DiagnosticoActivity.EXTRA_VEICULO_ID, veiculo.id))
            finish()
            return
        }
        startActivity(
            DiagnosticoActivity.intent(this, veiculo.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    // Callbacks do ciclo de vida registrados no Logcat (filtro "DashboardActivity")
    // para demonstrar a ordem em que o Android os chama.
    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: tela ficando visível")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: tela em primeiro plano, interagindo com o usuário")
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
        private const val TAG = "DashboardActivity"
        private const val EXTRA_RAIZ = "extra_raiz"
        private const val EXTRA_VEICULO_ATUAL = "extra_veiculo_atual"
        private const val ESTADO_VAZIA = "estava_vazia"

        /** Aberta pelo menu/seletor das abas do [veiculoAtual]: seta de Voltar. */
        fun intent(context: Context, veiculoAtual: Long = 0): Intent =
            Intent(context, DashboardActivity::class.java).putExtra(EXTRA_VEICULO_ATUAL, veiculoAtual)

        /** Tela inicial (sem veículo cadastrado): com o menu lateral. */
        fun intentRaiz(context: Context): Intent =
            Intent(context, DashboardActivity::class.java).putExtra(EXTRA_RAIZ, true)
    }
}
