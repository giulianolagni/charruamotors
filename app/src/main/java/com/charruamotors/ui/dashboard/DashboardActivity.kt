package com.charruamotors.ui.dashboard

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
import com.charruamotors.ui.login.LoginActivity
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.charruamotors.ui.veiculo.VeiculoFormActivity
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/** Activity 2 — Dashboard / Home: lista de veículos, resumo e atalho para o scanner OBD2. */
class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private val sessao by lazy { (application as CharruaMotorsApp).sessao }
    private val viewModel: DashboardViewModel by viewModels { DashboardViewModel.Factory }
    private val adapter = VeiculoAdapter(aoClicar = { veiculo ->
        startActivity(VeiculoFormActivity.intent(this, veiculo.id))
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        // Ícones claros na status bar, porque a toolbar é azul.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)

        configurarToolbar()
        binding.textoBoasVindas.text = getString(R.string.dashboard_boas_vindas, sessao.nomeUsuario.orEmpty())
        binding.botaoScanner.setOnClickListener {
            Snackbar.make(binding.root, R.string.em_breve, Snackbar.LENGTH_SHORT).show()
        }
        binding.botaoNovoVeiculo.setOnClickListener {
            startActivity(VeiculoFormActivity.intent(this))
        }
        binding.listaVeiculos.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.veiculos.collect { veiculos -> veiculos?.let(::mostrarVeiculos) }
            }
        }
    }

    private fun mostrarVeiculos(veiculos: List<Veiculo>) {
        adapter.submitList(veiculos)
        binding.textoListaVazia.isVisible = veiculos.isEmpty()
        binding.textoTotalVeiculos.text =
            resources.getQuantityString(R.plurals.dashboard_total_veiculos, veiculos.size, veiculos.size)
    }

    private fun configurarToolbar() {
        binding.toolbar.inflateMenu(R.menu.menu_dashboard)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.acao_sair -> {
                    sair()
                    true
                }
                else -> false
            }
        }
    }

    /** Logout: encerra a sessão e volta ao login limpando a pilha de Activities. */
    private fun sair() {
        sessao.encerrar()
        startActivity(
            Intent(this, LoginActivity::class.java)
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

    private companion object {
        const val TAG = "DashboardActivity"
    }
}
