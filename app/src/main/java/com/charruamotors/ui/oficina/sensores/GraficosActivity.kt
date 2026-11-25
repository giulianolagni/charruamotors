package com.charruamotors.ui.oficina.sensores

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.ActivityGraficosBinding
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.obd.pid.DefinicaoPid
import com.charruamotors.oficina.sensores.ControleGrafico
import com.charruamotors.oficina.sensores.FormatadorSensor
import com.charruamotors.oficina.sensores.JanelaTempo
import com.charruamotors.oficina.sensores.SelecaoGrafico
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.charruamotors.ui.util.comoNumero
import com.charruamotors.ui.util.definirTexto
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Sobrevive à rotação: a pausa, o "Zerar", a janela e a seleção sem veículo ativo. */
class GraficosViewModel : ViewModel() {
    val controle = ControleGrafico()
    var selecaoSemVeiculo: List<String> = emptyList()
}

/**
 * Oficina → Gráficos em Tempo Real (v2.3.1): osciloscópio de 1 a 4 sensores da sessão, com janela de
 * 30/60/120 s, Pausar, Zerar e cursor de toque. A seleção fica no perfil do veículo ativo.
 */
class GraficosActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGraficosBinding
    private val app: CharruaMotorsApp get() = application as CharruaMotorsApp
    private val viewModel: GraficosViewModel by viewModels()
    private val veiculoAtivo: Long get() = app.perfisCarro.veiculoAtivo.value

    /** Combustível do veículo ativo (decide a sugestão); null = ainda não lido ou sem veículo. */
    private var diesel: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityGraficosBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolbar.subtitle = app.preferenciasAutoConexao.config.value.veiculoDescricao

        val controle = viewModel.controle
        binding.grupoJanela.check(
            when (controle.janela) {
                JanelaTempo.S30 -> R.id.botao_30s
                JanelaTempo.S60 -> R.id.botao_60s
                JanelaTempo.S120 -> R.id.botao_120s
            },
        )
        binding.grupoJanela.addOnButtonCheckedListener { _, id, marcado ->
            if (!marcado) return@addOnButtonCheckedListener
            controle.janela = when (id) {
                R.id.botao_30s -> JanelaTempo.S30
                R.id.botao_120s -> JanelaTempo.S120
                else -> JanelaTempo.S60
            }
            atualizar()
        }
        binding.botaoPausar.setOnClickListener {
            controle.alternarPausa(app.sessaoObd.historico.amostras())
            atualizar()
        }
        binding.botaoZerar.setOnClickListener {
            controle.zerar(app.sessaoObd.historico.amostras())
            atualizar()
        }
        binding.botaoSensores.setOnClickListener { escolherSensores() }
        binding.grafico.aoMoverCursor = ::atualizar

        lifecycleScope.launch {
            if (veiculoAtivo > 0) {
                diesel = app.veiculoRepository.buscarPorId(veiculoAtivo)?.let { it.tipoCombustivel == TipoCombustivel.DIESEL }
            }
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    atualizar()
                    delay(INTERVALO_TELA_MS)
                }
            }
        }
    }

    /** Sensores que responderam nesta sessão. */
    private fun disponiveis(): List<DefinicaoPid> {
        val responderam = app.sessaoObd.historico.estatisticas().keys
        return app.definicoesDaSessao().filter { it.comando in responderam }
    }

    private fun salvos(): List<String> =
        if (veiculoAtivo > 0) app.perfisCarro.ler(veiculoAtivo).favoritosGraficos else viewModel.selecaoSemVeiculo

    private fun selecionados(disponiveis: List<DefinicaoPid>): List<DefinicaoPid> {
        val porComando = disponiveis.associateBy { it.comando }
        return SelecaoGrafico.efetiva(salvos(), disponiveis.map { it.comando }, diesel).mapNotNull { porComando[it] }
    }

    private fun atualizar() {
        val controle = viewModel.controle
        val definicoes = selecionados(disponiveis())
        val conversor = app.preferenciasApp.config.value.conversor
        val quadro = controle.quadro(app.sessaoObd.historico.amostras(), definicoes.map { it.comando })
        binding.botaoPausar.setText(if (controle.pausado) R.string.graficos_retomar else R.string.graficos_pausar)
        if (binding.grafico.manterCursor != controle.pausado) binding.grafico.manterCursor = controle.pausado
        binding.grafico.mostrar(
            quadro,
            definicoes.zip(quadro.canais).map { (d, c) ->
                FormatadorSensor.valor(d, c.minimo, conversor) to FormatadorSensor.valor(d, c.pico, conversor)
            },
        )
        val leitura = binding.grafico.cursor?.let { quadro.cursor(it) }
        binding.textoCursor.definirTexto(
            when {
                definicoes.isEmpty() -> getString(R.string.graficos_sem_dados)
                leitura != null -> getString(R.string.graficos_cursor, ((quadro.fimMs - leitura.tMs) / 1000.0).comoNumero(1))
                else -> getString(R.string.graficos_dica_cursor)
            },
        )
        listOf(binding.legenda1, binding.legenda2, binding.legenda3, binding.legenda4).forEachIndexed { i, view ->
            val d = definicoes.getOrNull(i)
            view.isVisible = d != null
            if (d == null) return@forEachIndexed
            val canal = quadro.canais[i]
            val unidade = FormatadorSensor.unidade(d, conversor)
            view.setTextColor(GraficoOsciloscopioView.CORES[i])
            view.definirTexto(
                if (leitura != null) {
                    getString(R.string.graficos_legenda_cursor, d.nome, FormatadorSensor.valor(d, leitura.valores[d.comando], conversor), unidade)
                } else {
                    getString(
                        R.string.graficos_legenda, d.nome, FormatadorSensor.valor(d, canal.atual, conversor), unidade,
                        FormatadorSensor.valor(d, canal.pico, conversor),
                    )
                },
            )
        }
    }

    private fun escolherSensores() {
        val disponiveis = disponiveis()
        if (disponiveis.isEmpty()) {
            Toast.makeText(this, R.string.graficos_sem_dados, Toast.LENGTH_SHORT).show()
            return
        }
        val escolhidos = selecionados(disponiveis).map { it.comando }.toMutableList()
        val nomes = disponiveis.map { "${it.nome} (${it.rotuloComando})" }.toTypedArray<CharSequence>()
        val marcados = BooleanArray(disponiveis.size) { disponiveis[it].comando in escolhidos }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.graficos_escolher, SelecaoGrafico.MAXIMO))
            .setMultiChoiceItems(nomes, marcados) { dialogo, i, marcado ->
                val comando = disponiveis[i].comando
                when {
                    !marcado -> escolhidos.remove(comando)
                    escolhidos.size < SelecaoGrafico.MAXIMO -> escolhidos.add(comando)
                    else -> {
                        marcados[i] = false
                        (dialogo as AlertDialog).listView.setItemChecked(i, false)
                        Toast.makeText(this, getString(R.string.graficos_limite, SelecaoGrafico.MAXIMO), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setPositiveButton(R.string.graficos_aplicar) { _, _ -> salvar(escolhidos.toList()) }
            .setNegativeButton(R.string.botao_cancelar, null)
            .show()
    }

    /** Lista vazia = volta à sugestão. */
    private fun salvar(comandos: List<String>) {
        val id = veiculoAtivo
        if (id > 0) app.perfisCarro.atualizar(id) { it.copy(favoritosGraficos = comandos) } else viewModel.selecaoSemVeiculo = comandos
        atualizar()
    }

    companion object {
        private const val INTERVALO_TELA_MS = 150L

        fun intent(context: Context): Intent = Intent(context, GraficosActivity::class.java)
    }
}
