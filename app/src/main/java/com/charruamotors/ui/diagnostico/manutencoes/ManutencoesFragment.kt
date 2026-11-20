package com.charruamotors.ui.diagnostico.manutencoes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import com.charruamotors.R
import com.charruamotors.databinding.FragmentManutencoesBinding
import com.charruamotors.domain.calculo.ProximaManutencao
import com.charruamotors.domain.model.ManutencaoComPecas
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.util.comoMilhar
import com.charruamotors.ui.util.comoTexto
import com.charruamotors.ui.util.rotulo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/** Aba 2 — Manutenções: histórico, previsão da próxima manutenção e CRUD. */
class ManutencoesFragment : Fragment() {

    private var _binding: FragmentManutencoesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ManutencoesViewModel by viewModels { ManutencoesViewModel.Factory }

    private val adapterProximas = ProximasManutencoesAdapter()
    private val adapterLista = ManutencaoAdapter(
        aoClicar = { abrirFormulario(it.manutencao.id) },
        aoExcluir = ::confirmarExclusao,
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentManutencoesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.listaManutencoes.adapter = ConcatAdapter(adapterProximas, adapterLista)
        binding.botaoNovaManutencao.setOnClickListener { abrirFormulario(0L) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.resumo.collect { it?.let(::mostrarResumo) } }
                launch { viewModel.eventos.collect(::tratarEvento) }
            }
        }
    }

    private fun mostrarResumo(resumo: ResumoManutencoes) {
        adapterLista.submitList(resumo.itens)
        adapterProximas.texto = textoProximas(resumo.proximas)
        binding.textoListaVazia.isVisible = resumo.itens.isEmpty()
    }

    /** Uma linha por tipo com previsão (a mais urgente primeiro); null quando não há previsão. */
    private fun textoProximas(proximas: List<ProximaManutencao>): String? {
        if (proximas.isEmpty()) return null
        val contexto = requireContext()
        return proximas.take(MAX_PREVISOES).joinToString("\n") { p ->
            val alvo = listOfNotNull(
                p.kmPrevisto?.let { contexto.getString(R.string.manutencao_previsao_km, it.comoMilhar()) },
                p.dataPrevista?.let { contexto.getString(R.string.manutencao_previsao_data, it.comoTexto()) },
            ).joinToString(" ")
            val linha = contexto.getString(R.string.manutencao_previsao_linha, p.tipo.rotulo(contexto), alvo)
            if (p.vencida) contexto.getString(R.string.manutencao_previsao_vencida, linha) else linha
        }
    }

    private fun abrirFormulario(id: Long) {
        if (childFragmentManager.findFragmentByTag(ManutencaoSheet.TAG) == null) {
            ManutencaoSheet.novo(id).show(childFragmentManager, ManutencaoSheet.TAG)
        }
    }

    private fun confirmarExclusao(item: ManutencaoComPecas) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.manutencao_excluir_titulo)
            .setMessage(R.string.manutencao_excluir_mensagem)
            .setNegativeButton(R.string.botao_cancelar, null)
            .setPositiveButton(R.string.botao_excluir) { _, _ -> viewModel.excluir(item.manutencao) }
            .show()
    }

    private fun tratarEvento(evento: EventoManutencao) {
        val mensagem = when (evento) {
            EventoManutencao.Salva -> {
                (childFragmentManager.findFragmentByTag(ManutencaoSheet.TAG) as? DialogFragment)?.dismiss()
                R.string.manutencao_salva
            }
            EventoManutencao.Excluida -> R.string.manutencao_excluida
        }
        Snackbar.make(binding.root, mensagem, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val MAX_PREVISOES = 3

        fun novo(veiculoId: Long) = ManutencoesFragment().apply {
            arguments = Bundle().apply { putLong(DiagnosticoActivity.EXTRA_VEICULO_ID, veiculoId) }
        }
    }
}
