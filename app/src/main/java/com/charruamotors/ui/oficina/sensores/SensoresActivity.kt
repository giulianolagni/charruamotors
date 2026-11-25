package com.charruamotors.ui.oficina.sensores

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.ActivitySensoresBinding
import com.charruamotors.direcao.painel.ResultadoFixar
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.oficina.sensores.LinhaSensor
import com.charruamotors.oficina.sensores.MontadorListaSensores
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Oficina → Todos os Sensores (v2.3.1): inspeção rápida de tudo o que a ECU responde na sessão (real
 * ou simulada), com valor ao vivo, mínimo/máximo, busca e o "+ Painel" do veículo ativo.
 */
class SensoresActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySensoresBinding
    private val app: CharruaMotorsApp get() = application as CharruaMotorsApp
    private val adaptador = SensorLinhaAdapter(::alternarNoPainel)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivitySensoresBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolbar.subtitle = app.preferenciasAutoConexao.config.value.veiculoDescricao

        binding.lista.layoutManager = LinearLayoutManager(this)
        binding.lista.adapter = adaptador
        binding.lista.itemAnimator = null
        binding.campoBusca.doAfterTextChanged { atualizar() }
        binding.grupoFiltro.addOnButtonCheckedListener { _, _, marcado -> if (marcado) atualizar() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    atualizar()
                    delay(INTERVALO_TELA_MS)
                }
            }
        }
    }

    private fun atualizar() {
        val soAtivos = binding.grupoFiltro.checkedButtonId != R.id.botao_todos
        val conectado = app.sessaoObd.estado.value is EstadoConexaoObd.Conectado
        val linhas = MontadorListaSensores.montar(
            definicoes = if (soAtivos) app.definicoesDaSessao() else app.definicoesDoPerfil(),
            estatisticas = app.sessaoObd.historico.estatisticas(),
            atual = app.sessaoObd.dados.value?.valores?.takeIf { conectado }.orEmpty(),
            conversor = app.preferenciasApp.config.value.conversor,
            painel = app.preferenciasPainel.config.value,
            filtro = binding.campoBusca.text?.toString().orEmpty(),
            soAtivos = soAtivos,
        )
        adaptador.submitList(linhas)
        binding.textoResumo.text = getString(R.string.sensores_resumo, linhas.size, linhas.count { it.respondeu })
        binding.textoVazio.isVisible = linhas.isEmpty()
        binding.textoVazio.setText(if (conectado) R.string.sensores_vazio else R.string.oficina_requer_conexao)
    }

    private fun alternarNoPainel(linha: LinhaSensor) {
        var resultado: ResultadoFixar? = null
        app.preferenciasPainel.atualizar { atual ->
            val (nova, feito) = atual.alternarNoPainel(linha.comando)
            resultado = feito
            nova
        }
        val mensagem = when (resultado ?: return) {
            ResultadoFixar.MOSTRADOR_ADICIONADO -> R.string.sensores_mostrador_adicionado
            ResultadoFixar.MOSTRADOR_REMOVIDO -> R.string.sensores_mostrador_removido
            ResultadoFixar.LISTA_ADICIONADO -> R.string.sensores_lista_adicionado
            ResultadoFixar.LISTA_REMOVIDO -> R.string.sensores_lista_removido
            ResultadoFixar.ULTIMO_MOSTRADOR -> R.string.sensores_ultimo_mostrador
        }
        Snackbar.make(binding.root, mensagem, Snackbar.LENGTH_SHORT).show()
        atualizar()
    }

    companion object {
        private const val INTERVALO_TELA_MS = 300L

        fun intent(context: Context): Intent = Intent(context, SensoresActivity::class.java)
    }
}
