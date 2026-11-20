package com.charruamotors.ui.diagnostico.abastecimentos

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
import com.charruamotors.databinding.FragmentAbastecimentosBinding
import com.charruamotors.domain.model.Abastecimento
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/** Aba 3 — Abastecimentos: histórico, consumo médio (km/l), custo por km e CRUD. */
class AbastecimentosFragment : Fragment() {

    private var _binding: FragmentAbastecimentosBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AbastecimentosViewModel by viewModels { AbastecimentosViewModel.Factory }

    private val adapterIndicadores = IndicadoresAbastecimentoAdapter()
    private val adapterLista = AbastecimentoAdapter(
        aoClicar = { abrirFormulario(it.id) },
        aoExcluir = ::confirmarExclusao,
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAbastecimentosBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.listaAbastecimentos.adapter = ConcatAdapter(adapterIndicadores, adapterLista)
        binding.botaoNovoAbastecimento.setOnClickListener { abrirFormulario(0L) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.resumo.collect { it?.let(::mostrarResumo) } }
                launch { viewModel.eventos.collect(::tratarEvento) }
            }
        }
    }

    private fun mostrarResumo(resumo: ResumoAbastecimentos) {
        adapterLista.submitList(resumo.itens)
        adapterIndicadores.atualizar(resumo.itens.isNotEmpty(), resumo.consumoMedio, resumo.custoPorKm)
        binding.textoListaVazia.isVisible = resumo.itens.isEmpty()
    }

    private fun abrirFormulario(id: Long) {
        if (childFragmentManager.findFragmentByTag(AbastecimentoSheet.TAG) == null) {
            AbastecimentoSheet.novo(id).show(childFragmentManager, AbastecimentoSheet.TAG)
        }
    }

    private fun confirmarExclusao(abastecimento: Abastecimento) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.abastecimento_excluir_titulo)
            .setMessage(R.string.abastecimento_excluir_mensagem)
            .setNegativeButton(R.string.botao_cancelar, null)
            .setPositiveButton(R.string.botao_excluir) { _, _ -> viewModel.excluir(abastecimento) }
            .show()
    }

    private fun tratarEvento(evento: EventoAbastecimento) {
        val mensagem = when (evento) {
            EventoAbastecimento.Salvo -> {
                (childFragmentManager.findFragmentByTag(AbastecimentoSheet.TAG) as? DialogFragment)?.dismiss()
                R.string.abastecimento_salvo
            }
            EventoAbastecimento.Excluido -> R.string.abastecimento_excluido
        }
        Snackbar.make(binding.root, mensagem, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun novo(veiculoId: Long) = AbastecimentosFragment().apply {
            arguments = Bundle().apply { putLong(DiagnosticoActivity.EXTRA_VEICULO_ID, veiculoId) }
        }
    }
}
