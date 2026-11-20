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
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch

/**
 * Activity 4 — Diagnóstico OBD2 e Manutenções.
 * Recebe [EXTRA_VEICULO_ID] e organiza três abas (TabLayout + ViewPager2 com Fragments):
 * Diagnóstico OBD2, Manutenções e Abastecimentos.
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
        binding.conteudo.aplicarInsetsDoSistema(topo = false)

        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolbar.setTitle(R.string.diagnostico_titulo)

        binding.paginas.adapter = DiagnosticoPagerAdapter(this, viewModel.veiculoId)
        TabLayoutMediator(binding.abas, binding.paginas) { aba, posicao ->
            aba.setText(
                when (posicao) {
                    DiagnosticoPagerAdapter.ABA_OBD -> R.string.aba_obd
                    DiagnosticoPagerAdapter.ABA_MANUTENCOES -> R.string.aba_manutencoes
                    else -> R.string.aba_abastecimentos
                },
            )
        }.attach()

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
        private const val TAG = "DiagnosticoActivity"

        fun intent(context: Context, veiculoId: Long): Intent =
            Intent(context, DiagnosticoActivity::class.java).putExtra(EXTRA_VEICULO_ID, veiculoId)
    }
}
