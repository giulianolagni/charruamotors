package com.charruamotors.ui.diagnostico.oficina

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.FragmentOficinaBinding
import com.charruamotors.databinding.ItemBicoInjetorBinding
import com.charruamotors.databinding.ItemCardOficinaBinding
import com.charruamotors.direcao.desempenho.FonteVelocidade
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.obd.DadosObd
import com.charruamotors.oficina.bicos.AvaliacaoBicos
import com.charruamotors.oficina.bicos.SituacaoBico
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.diagnostico.DiagnosticoPagerAdapter
import com.charruamotors.ui.oficina.bateria.TesteBateriaActivity
import com.charruamotors.ui.oficina.falhas.DiagnosticoErrosActivity
import com.charruamotors.ui.oficina.terminal.TerminalObdActivity
import com.charruamotors.ui.util.definirTexto
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Aba Oficina: as ferramentas de diagnóstico em cards grandes e ilustrados — Teste de Bateria
 * (guiado, com laudo), Diagnóstico de Erros (ler e apagar DTCs), Varredura de PIDs (busca de
 * sensores extras) e Terminal ELM327. Todas usam o scanner conectado no Painel; bateria, varredura
 * e terminal abrem uma sessão manual que pausa o polling até voltar.
 */
class OficinaFragment : Fragment() {

    private var _binding: FragmentOficinaBinding? = null
    private val binding get() = _binding!!

    private val app: CharruaMotorsApp get() = requireActivity().application as CharruaMotorsApp
    private val veiculoId: Long get() = requireArguments().getLong(DiagnosticoActivity.EXTRA_VEICULO_ID)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOficinaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(binding) {
            preencher(cardBateria, R.string.oficina_card_bateria, R.string.oficina_card_bateria_desc, R.drawable.ic_bateria, R.color.veredito_bom)
            preencher(cardFalhas, R.string.oficina_card_falhas, R.string.oficina_card_falhas_desc, R.drawable.ic_falha, R.color.dtc_confirmado)
            preencher(cardVarredura, R.string.oficina_card_varredura, R.string.oficina_card_varredura_desc, R.drawable.ic_varredura, R.color.azul_primario)
            preencher(cardTerminal, R.string.oficina_card_terminal, R.string.oficina_card_terminal_desc, R.drawable.ic_terminal, R.color.terminal_card)

            cardBateria.root.setOnClickListener { abrirConectada { TesteBateriaActivity.intent(requireContext(), veiculoId) } }
            // O DTC abre sempre: a tela mostra o estado e só habilita ler/apagar com o scanner ligado.
            cardFalhas.root.setOnClickListener { startActivity(DiagnosticoErrosActivity.intent(requireContext(), veiculoId)) }
            cardVarredura.root.setOnClickListener {
                abrirConectada { TerminalObdActivity.intent(requireContext(), veiculoId, TerminalObdActivity.ABA_VARREDURA) }
            }
            cardTerminal.root.setOnClickListener {
                abrirConectada { TerminalObdActivity.intent(requireContext(), veiculoId, TerminalObdActivity.ABA_TERMINAL) }
            }
            faixaConexao.setOnClickListener { irParaPainel() }
            cardDesempenho.root.setOnClickListener { app.telemetriaCompartilhada.cronometro.reiniciar() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.sessaoObd.estado.collect(::mostrarConexao) }
                launch { app.sessaoObd.dados.collect { mostrarBicos(it) } }
                launch {
                    combine(
                        app.telemetriaCompartilhada.cronometro.estado,
                        app.telemetriaCompartilhada.fonteCronometroAtiva,
                        app.recordesDesempenho.recordes,
                    ) { e, fonte, _ -> e to fonte }
                        .collect { (e, fonte) ->
                            binding.cardDesempenho.textoDesempenhoEstado.text =
                                if (fonte == FonteVelocidade.GPS) "${e.texto} · via GPS" else e.texto
                            binding.cardDesempenho.textoDesempenhoRecorde.text = app.recordesDesempenho.recordeDe(app.chaveRecorde()).texto
                        }
                }
            }
        }
    }

    private fun preencher(card: ItemCardOficinaBinding, @StringRes titulo: Int, @StringRes descricao: Int, @DrawableRes icone: Int, @ColorRes cor: Int) {
        card.textoTitulo.setText(titulo)
        card.textoDescricao.setText(descricao)
        card.imagemIcone.setImageResource(icone)
        card.fundoIcone.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), cor))
        card.root.contentDescription = "${getString(titulo)}. ${getString(descricao)}"
    }

    private fun mostrarConexao(estado: EstadoConexaoObd) = with(binding) {
        val conectado = estado is EstadoConexaoObd.Conectado
        textoConexao.definirTexto(getString(if (conectado) R.string.oficina_conectado else R.string.oficina_desconectado))
        val cor = if (conectado) R.color.status_ok else R.color.status_espera
        pontoConexao.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), cor))
        listOf(cardBateria, cardVarredura, cardTerminal).forEach { card ->
            card.textoRequisito.isVisible = !conectado
            card.root.alpha = if (conectado) 1f else ALFA_INDISPONIVEL
        }
    }

    /** Os 4 cilindros lado a lado: correção em mm³/st com a cor da situação (verde dentro de ±3,0). */
    private fun mostrarBicos(dados: DadosObd?) {
        val bicos = listOf(binding.bico1, binding.bico2, binding.bico3, binding.bico4)
        val sensores = listOf(SensorPainel.BICO_1, SensorPainel.BICO_2, SensorPainel.BICO_3, SensorPainel.BICO_4)
        bicos.forEachIndexed { i, bico ->
            val valor = sensores[i].ler(dados)
            preencherBico(bico, i + 1, sensores[i].texto(valor, ConversorUnidades.PADRAO), AvaliacaoBicos.avaliar(valor))
        }
    }

    private fun preencherBico(bico: ItemBicoInjetorBinding, numero: Int, valor: String, situacao: SituacaoBico) {
        val cor = when (situacao) {
            SituacaoBico.OK -> R.color.status_ok
            SituacaoBico.ATENCAO -> R.color.veredito_atencao
            SituacaoBico.FORA -> R.color.veredito_ruim
            SituacaoBico.SEM_LEITURA -> R.color.status_espera
        }
        bico.textoNome.text = "#$numero"
        bico.textoValor.text = if (situacao == SituacaoBico.SEM_LEITURA) SensorPainel.NAO_DISPONIVEL else valor
        bico.textoSituacao.text = situacao.texto
        bico.textoSituacao.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), cor))
    }

    private fun abrirConectada(intent: () -> Intent) {
        if (app.sessaoObd.estado.value !is EstadoConexaoObd.Conectado) {
            Snackbar.make(binding.root, R.string.oficina_requer_conexao, Snackbar.LENGTH_LONG)
                .setAction(R.string.oficina_ir_painel) { irParaPainel() }
                .show()
            return
        }
        startActivity(intent())
    }

    private fun irParaPainel() {
        (activity as? DiagnosticoActivity)?.mostrarAba(DiagnosticoPagerAdapter.ABA_PAINEL)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ALFA_INDISPONIVEL = 0.72f

        fun novo(veiculoId: Long) = OficinaFragment().apply {
            arguments = Bundle().apply { putLong(DiagnosticoActivity.EXTRA_VEICULO_ID, veiculoId) }
        }
    }
}
