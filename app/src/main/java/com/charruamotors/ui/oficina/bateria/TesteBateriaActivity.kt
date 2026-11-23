package com.charruamotors.ui.oficina.bateria

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.R
import com.charruamotors.databinding.ActivityTesteBateriaBinding
import com.charruamotors.oficina.bateria.ClassificadorBateria
import com.charruamotors.oficina.bateria.EtapaBateria
import com.charruamotors.oficina.bateria.LaudoBateria
import com.charruamotors.oficina.bateria.ResultadoCarga
import com.charruamotors.oficina.bateria.Veredito
import com.charruamotors.oficina.bateria.VereditoResumo
import com.charruamotors.ui.componentes.ReferenciaTensao
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.util.TextoCompartilhavel
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.charruamotors.ui.util.comoNumero
import com.charruamotors.ui.util.definirTexto
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Ferramenta 1 — Teste de Bateria e Alternador guiado em 3 etapas (repouso, partida, alternador
 * em marcha lenta e com carga), com a tensão ao vivo em gráfico e o laudo resumido no fim.
 * A tela fica acesa durante o teste (o motorista está fora do carro olhando o celular).
 */
class TesteBateriaActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTesteBateriaBinding
    private val viewModel: TesteBateriaViewModel by viewModels { TesteBateriaViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityTesteBateriaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.botaoAcao.setOnClickListener { viewModel.alternarCaptura() }
        binding.botaoPular.setOnClickListener { viewModel.pularEtapa() }
        binding.botaoRefazer.setOnClickListener { viewModel.reiniciar() }
        binding.botaoCopiarLaudo.setOnClickListener {
            viewModel.ui.value.laudo?.let { TextoCompartilhavel.copiar(this, getString(R.string.bateria_laudo_assunto), it) }
        }
        binding.botaoCompartilharLaudo.setOnClickListener {
            viewModel.ui.value.laudo?.let { TextoCompartilhavel.compartilhar(this, getString(R.string.bateria_laudo_assunto), it) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.ui.collect(::mostrar) }
                launch {
                    viewModel.eventos.collect { evento ->
                        when (evento) {
                            is EventoBateria.Mensagem -> mensagem(getString(evento.resId))
                            is EventoBateria.EtapaConcluida -> mensagem(getString(R.string.bateria_etapa_ok, tituloEtapa(evento.etapa)))
                            is EventoBateria.SemConexao -> avisarSemConexao(evento.resId)
                        }
                    }
                }
            }
        }
    }

    private fun mostrar(ui: TesteBateriaUi) = with(binding) {
        val etapa = ui.etapa
        textoEtapa.definirTexto(
            if (etapa == EtapaBateria.CONCLUIDO) getString(R.string.bateria_etapa_concluida) else getString(R.string.bateria_etapa_n, etapa.numero),
        )
        tituloEtapa.definirTexto(tituloEtapa(etapa))
        instrucaoEtapa.definirTexto(getString(instrucao(etapa)))
        textoTensao.definirTexto(ui.tensao?.let { getString(R.string.bateria_tensao_ao_vivo, it.comoNumero(1)) } ?: getString(R.string.valor_indisponivel))
        textoTaxa.definirTexto(getString(R.string.bateria_taxa, ui.amostrasPorSegundo.comoNumero(0)))

        graficoTensao.definir(ui.pontos, referencias(etapa), ui.vale)

        progressoCaptura.isInvisible = !ui.capturando
        val progresso = ui.progresso
        if (ui.capturando) {
            if (progresso == null) {
                if (!progressoCaptura.isIndeterminate) {
                    progressoCaptura.isVisible = false
                    progressoCaptura.isIndeterminate = true
                    progressoCaptura.isVisible = true
                }
            } else {
                progressoCaptura.isIndeterminate = false
                progressoCaptura.setProgressCompat((progresso * 100).toInt(), false)
            }
        }
        val status = when {
            !ui.capturando -> null
            etapa == EtapaBateria.PARTIDA && ui.quedaDetectada ->
                getString(R.string.bateria_partida_em_curso, ui.vale?.let { LaudoBateria.v(it) } ?: "—")
            etapa == EtapaBateria.PARTIDA -> getString(R.string.bateria_aguardando_partida)
            else -> getString(R.string.bateria_medindo)
        }
        val comResets = if (status != null && ui.lvResets > 0) status + "\n" + getString(R.string.bateria_lv_resets, ui.lvResets) else status
        textoStatus.isVisible = comResets != null
        textoStatus.definirTexto(comResets)

        grupoAcoes.isVisible = etapa != EtapaBateria.CONCLUIDO
        botaoAcao.definirTexto(getString(when {
            ui.capturando -> R.string.bateria_cancelar
            etapa == EtapaBateria.PARTIDA -> R.string.bateria_iniciar_partida
            else -> R.string.bateria_medir
        }))
        botaoPular.isEnabled = !ui.capturando

        mostrarResultados(ui)
        cardLaudo.isVisible = ui.laudo != null
        if (ui.laudo != null) {
            mostrarVeredito(vereditoBateria, R.string.bateria_veredito_bateria, ui.vereditoBateria)
            mostrarVeredito(vereditoAlternador, R.string.bateria_veredito_alternador, ui.vereditoAlternador)
            textoLaudo.definirTexto(ui.laudo)
        }
    }

    private fun mostrarResultados(ui: TesteBateriaUi) = with(binding) {
        val r = ui.repouso
        linha(resRepouso, r?.let { getString(R.string.bateria_res_repouso, LaudoBateria.v(it.tensao), LaudoBateria.rotulo(it.veredito)) },
            r?.veredito, EtapaBateria.REPOUSO in ui.puladas, R.string.bateria_res_repouso_pendente, R.string.bateria_etapa1_titulo)
        val p = ui.partida
        linha(resPartida, p?.let { getString(R.string.bateria_res_partida, LaudoBateria.v(it.vMin), LaudoBateria.rotulo(it.veredito)) },
            p?.veredito, EtapaBateria.PARTIDA in ui.puladas, R.string.bateria_res_partida_pendente, R.string.bateria_etapa2_titulo)
        linha(resLenta, ui.lenta?.let { textoCarga(R.string.bateria_res_lenta, it) }, ui.lenta?.veredito,
            EtapaBateria.ALTERNADOR_LENTA in ui.puladas, R.string.bateria_res_lenta_pendente, R.string.bateria_etapa3a_titulo)
        linha(resCarga, ui.carga?.let { textoCarga(R.string.bateria_res_carga, it) }, ui.carga?.veredito,
            EtapaBateria.ALTERNADOR_CARGA in ui.puladas, R.string.bateria_res_carga_pendente, R.string.bateria_etapa3b_titulo)
        avisoMotorLigado.isVisible = r?.motorPareceLigado == true
    }

    private fun textoCarga(formato: Int, c: ResultadoCarga) =
        getString(formato, LaudoBateria.v(c.media), LaudoBateria.v(c.pico), LaudoBateria.v(c.ripple), LaudoBateria.rotulo(c.veredito))

    private fun linha(view: TextView, texto: String?, veredito: Veredito?, pulada: Boolean, pendente: Int, titulo: Int) {
        view.definirTexto(texto ?: if (pulada) getString(R.string.bateria_res_pulada, getString(titulo)) else getString(pendente))
        view.setTextColor(veredito?.let { corDaGravidade(it.gravidade) } ?: getColor(R.color.texto_secundario))
    }

    private fun mostrarVeredito(view: TextView, formato: Int, veredito: VereditoResumo?) {
        view.definirTexto(getString(formato, veredito?.titulo ?: "—"))
        view.setTextColor(veredito?.gravidade?.let(::corDaGravidade) ?: getColor(R.color.texto_secundario))
    }

    private fun corDaGravidade(gravidade: Int): Int = ContextCompat.getColor(
        this,
        when (gravidade) {
            0 -> R.color.veredito_bom
            1 -> R.color.veredito_atencao
            else -> R.color.veredito_ruim
        },
    )

    /** Limites da etapa no gráfico: verde = início da faixa boa, vermelho = início da faixa ruim. */
    private fun referencias(etapa: EtapaBateria): List<ReferenciaTensao> {
        val bom = getColor(R.color.veredito_bom)
        val ruim = getColor(R.color.veredito_ruim)
        return when (etapa) {
            EtapaBateria.REPOUSO -> listOf(
                ReferenciaTensao(ClassificadorBateria.REPOUSO_SAUDAVEL, bom),
                ReferenciaTensao(ClassificadorBateria.REPOUSO_CRITICA, ruim),
            )
            EtapaBateria.PARTIDA -> listOf(
                ReferenciaTensao(ClassificadorBateria.PARTIDA_OK, bom),
                ReferenciaTensao(ClassificadorBateria.PARTIDA_FRACA, ruim),
            )
            else -> listOf(
                ReferenciaTensao(ClassificadorBateria.ALTERNADOR_NORMAL_MIN, bom),
                ReferenciaTensao(ClassificadorBateria.ALTERNADOR_NORMAL_MAX, bom),
            )
        }
    }

    private fun tituloEtapa(etapa: EtapaBateria): String = getString(
        when (etapa) {
            EtapaBateria.REPOUSO -> R.string.bateria_etapa1_titulo
            EtapaBateria.PARTIDA -> R.string.bateria_etapa2_titulo
            EtapaBateria.ALTERNADOR_LENTA -> R.string.bateria_etapa3a_titulo
            EtapaBateria.ALTERNADOR_CARGA -> R.string.bateria_etapa3b_titulo
            EtapaBateria.CONCLUIDO -> R.string.bateria_laudo_titulo
        },
    )

    private fun instrucao(etapa: EtapaBateria): Int = when (etapa) {
        EtapaBateria.REPOUSO -> R.string.bateria_etapa1_instrucao
        EtapaBateria.PARTIDA -> R.string.bateria_etapa2_instrucao
        EtapaBateria.ALTERNADOR_LENTA -> R.string.bateria_etapa3a_instrucao
        EtapaBateria.ALTERNADOR_CARGA -> R.string.bateria_etapa3b_instrucao
        EtapaBateria.CONCLUIDO -> R.string.bateria_concluido_instrucao
    }

    private fun avisarSemConexao(resId: Int) {
        if (isFinishing) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bateria_titulo)
            .setMessage(resId)
            .setCancelable(false)
            .setPositiveButton(R.string.botao_fechar) { _, _ -> finish() }
            .show()
    }

    private fun mensagem(texto: String) {
        Snackbar.make(binding.root, texto, Snackbar.LENGTH_LONG).show()
    }

    companion object {
        fun intent(context: Context, veiculoId: Long): Intent =
            Intent(context, TesteBateriaActivity::class.java).putExtra(DiagnosticoActivity.EXTRA_VEICULO_ID, veiculoId)
    }
}
