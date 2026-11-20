package com.charruamotors.ui.diagnostico.abastecimentos

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.R
import com.charruamotors.databinding.SheetAbastecimentoBinding
import com.charruamotors.domain.model.Abastecimento
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.validacao.CampoAbastecimento
import com.charruamotors.domain.validacao.ErroValidacao
import com.charruamotors.domain.validacao.FormularioAbastecimento
import com.charruamotors.ui.util.comoTexto
import com.charruamotors.ui.util.mensagem
import com.charruamotors.ui.util.mostrarSeletorData
import com.charruamotors.ui.util.reconectarSeletorData
import com.charruamotors.ui.util.paraDataOuNulo
import com.charruamotors.ui.util.rotulo
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Formulário de abastecimento (cadastro e edição) exibido como BottomSheet dentro da aba. */
class AbastecimentoSheet : BottomSheetDialogFragment() {

    private var _binding: SheetAbastecimentoBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AbastecimentosViewModel by viewModels(
        ownerProducer = { requireParentFragment() },
        factoryProducer = { AbastecimentosViewModel.Factory },
    )

    private val abastecimentoId: Long get() = requireArguments().getLong(ARG_ID)

    private val rotulosCombustivel by lazy { TipoCombustivel.entries.map { it.rotulo(requireContext()) } }

    private val camposTela: Map<CampoAbastecimento, Pair<TextInputLayout, TextInputEditText>> by lazy {
        with(binding) {
            mapOf(
                CampoAbastecimento.DATA to (layoutData to campoData),
                CampoAbastecimento.KM to (layoutKm to campoKm),
                CampoAbastecimento.LITROS to (layoutLitros to campoLitros),
                CampoAbastecimento.VALOR to (layoutValor to campoValor),
            )
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetAbastecimentoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titulo.setText(
            if (abastecimentoId == 0L) R.string.abastecimento_novo else R.string.abastecimento_editar,
        )
        binding.campoCombustivel.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, rotulosCombustivel),
        )
        configurarCampos()

        // Só preenche na primeira criação; após rotação as views restauram o próprio estado.
        if (savedInstanceState == null) {
            viewModel.limparErros()
            viewLifecycleOwner.lifecycleScope.launch {
                if (abastecimentoId == 0L) {
                    binding.campoData.setText(LocalDate.now().comoTexto())
                    binding.campoCombustivel.setText(viewModel.combustivelPadrao().rotulo(requireContext()), false)
                } else {
                    val item = viewModel.carregar(abastecimentoId)
                    if (item == null) dismiss() else preencher(item)
                }
            }
        }

        observarViewModel()
    }

    private fun configurarCampos() = with(binding) {
        camposTela.forEach { (campo, par) -> par.second.doAfterTextChanged { viewModel.limparErro(campo) } }
        val abrirSeletor = View.OnClickListener {
            childFragmentManager.mostrarSeletorData(campoData.text.toString().paraDataOuNulo()) { data ->
                campoData.setText(data.comoTexto())
            }
        }
        campoData.setOnClickListener(abrirSeletor)
        // Rotação com o calendário aberto: religa o listener ao seletor restaurado.
        childFragmentManager.reconectarSeletorData { data -> campoData.setText(data.comoTexto()) }
        layoutData.setEndIconOnClickListener(abrirSeletor)
        botaoSalvar.setOnClickListener { salvar() }
    }

    private fun preencher(a: Abastecimento) = with(binding) {
        campoData.setText(a.data.comoTexto())
        campoKm.setText(a.km.toString())
        campoLitros.setText(a.litros.toString())
        campoValor.setText(a.valorTotal.toString())
        campoCombustivel.setText(a.tipoCombustivel.rotulo(requireContext()), false)
        switchTanqueCheio.isChecked = a.tanqueCheio
    }

    private fun salvar() = with(binding) {
        val indice = rotulosCombustivel.indexOf(campoCombustivel.text.toString())
        viewModel.salvar(
            abastecimentoId,
            DadosFormularioAbastecimento(
                formulario = FormularioAbastecimento(
                    data = campoData.text.toString().paraDataOuNulo(),
                    km = campoKm.text.toString(),
                    litros = campoLitros.text.toString(),
                    valorTotal = campoValor.text.toString(),
                ),
                tipoCombustivel = TipoCombustivel.entries.getOrElse(indice) { TipoCombustivel.GASOLINA },
                tanqueCheio = switchTanqueCheio.isChecked,
            ),
        )
    }

    private fun observarViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.erros.collect(::mostrarErros) }
                launch { viewModel.salvando.collect { binding.botaoSalvar.isEnabled = !it } }
            }
        }
    }

    private fun mostrarErros(erros: Map<CampoAbastecimento, ErroValidacao>) {
        camposTela.forEach { (campo, par) -> par.first.error = erros[campo]?.mensagem(requireContext()) }
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "AbastecimentoSheet"
        private const val ARG_ID = "arg_abastecimento_id"

        /** [id] = 0 abre o cadastro; outro valor edita o abastecimento existente. */
        fun novo(id: Long = 0L) = AbastecimentoSheet().apply { arguments = Bundle().apply { putLong(ARG_ID, id) } }
    }
}
