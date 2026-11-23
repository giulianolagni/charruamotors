package com.charruamotors.ui.diagnostico

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.R
import com.charruamotors.databinding.ActivityDiagnosticoBinding
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.launch

/**
 * Activity 4 — tela do veículo. Recebe [EXTRA_VEICULO_ID] e organiza as 4 abas principais na
 * navegação inferior (BottomNavigationView + ViewPager2 sem deslizar): Painel (direção e
 * mostradores), Oficina (bateria, DTCs, varredura e terminal), Manutenções e Histórico
 * (abastecimentos, logs salvos e diário de viagens). [EXTRA_ABA] abre direto numa delas.
 */
class DiagnosticoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticoBinding
    private val viewModel: DiagnosticoViewModel by viewModels { DiagnosticoViewModel.Factory }

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

        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolbar.setTitle(R.string.diagnostico_titulo)

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
            }
        })
        if (savedInstanceState == null) {
            val aba = intent.getIntExtra(EXTRA_ABA, DiagnosticoPagerAdapter.ABA_PAINEL)
            binding.navegacao.selectedItemId = DiagnosticoPagerAdapter.itemDaPagina(aba)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.veiculo.collect { veiculo ->
                    if (veiculo != null) {
                        binding.toolbar.title = veiculo.descricao
                        binding.toolbar.subtitle = veiculo.placaFormatada
                    }
                }
            }
        }
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
