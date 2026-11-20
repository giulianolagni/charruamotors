package com.charruamotors.ui.diagnostico.manutencoes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.children
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.R
import com.charruamotors.databinding.ItemPecaFormBinding
import com.charruamotors.databinding.SheetManutencaoBinding
import com.charruamotors.domain.model.ManutencaoComPecas
import com.charruamotors.domain.model.TipoManutencao
import com.charruamotors.domain.validacao.CampoManutencao
import com.charruamotors.domain.validacao.CampoPeca
import com.charruamotors.domain.validacao.ErroValidacao
import com.charruamotors.domain.validacao.FormularioManutencao
import com.charruamotors.domain.validacao.FormularioPeca
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

/**
 * Formulário de manutenção (cadastro e edição) exibido como BottomSheet dentro da aba.
 * Compartilha o [ManutencoesViewModel] com o Fragment pai e mostra os erros nos TextInputLayouts.
 */
class ManutencaoSheet : BottomSheetDialogFragment() {

    private var _binding: SheetManutencaoBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ManutencoesViewModel by viewModels(
        ownerProducer = { requireParentFragment() },
        factoryProducer = { ManutencoesViewModel.Factory },
    )

    private val manutencaoId: Long get() = requireArguments().getLong(ARG_ID)

    private val rotulosTipo by lazy { TipoManutencao.entries.map { it.rotulo(requireContext()) } }

    private val camposTela: Map<CampoManutencao, Pair<TextInputLayout, TextInputEditText>> by lazy {
        with(binding) {
            mapOf(
                CampoManutencao.DATA to (layoutData to campoData),
                CampoManutencao.KM to (layoutKm to campoKm),
                CampoManutencao.CUSTO to (layoutCusto to campoCusto),
            )
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetManutencaoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // As linhas de peças são criadas dinamicamente (ids repetidos), então o estado delas é
        // guardado manualmente em onSaveInstanceState e não pela restauração automática das views.
        binding.containerPecas.isSaveFromParentEnabled = false

        binding.titulo.setText(if (manutencaoId == 0L) R.string.manutencao_nova else R.string.manutencao_editar)
        binding.campoTipo.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, rotulosTipo))
        configurarCampos()

        if (savedInstanceState != null) {
            restaurarPecas(savedInstanceState)
        } else {
            viewModel.limparErros()
            if (manutencaoId == 0L) {
                binding.campoTipo.setText(TipoManutencao.TROCA_OLEO.rotulo(requireContext()), false)
                binding.campoData.setText(java.time.LocalDate.now().comoTexto())
            } else {
                viewLifecycleOwner.lifecycleScope.launch {
                    val item = viewModel.carregar(manutencaoId)
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
        botaoAdicionarPeca.setOnClickListener { adicionarLinhaPeca() }
        botaoSalvar.setOnClickListener { salvar() }
    }

    private fun preencher(item: ManutencaoComPecas) = with(binding) {
        val m = item.manutencao
        campoTipo.setText(m.tipo.rotulo(requireContext()), false)
        campoData.setText(m.data.comoTexto())
        campoKm.setText(m.km.toString())
        campoCusto.setText(m.custoTotal.toString())
        campoOficina.setText(m.oficina.orEmpty())
        campoDescricao.setText(m.descricao.orEmpty())
        containerPecas.removeAllViews()
        item.pecas.forEach { adicionarLinhaPeca(it.nome, it.quantidade.toString(), it.valorUnitario.toString()) }
    }

    // ------------------------------------------------------------------ peças dinâmicas

    private fun adicionarLinhaPeca(nome: String = "", quantidade: String = "1", valor: String = "") {
        val linha = ItemPecaFormBinding.inflate(layoutInflater, binding.containerPecas, false)
        linha.campoPecaNome.setText(nome)
        linha.campoPecaQuantidade.setText(quantidade)
        linha.campoPecaValor.setText(valor)
        // Digitar em um campo limpa o erro dele.
        listOf(
            linha.layoutPecaNome to linha.campoPecaNome,
            linha.layoutPecaQuantidade to linha.campoPecaQuantidade,
            linha.layoutPecaValor to linha.campoPecaValor,
        ).forEach { (layout, campo) -> campo.doAfterTextChanged { layout.error = null } }
        linha.botaoRemoverPeca.setOnClickListener { binding.containerPecas.removeView(linha.root) }
        binding.containerPecas.addView(linha.root)
    }

    private fun linhasPecas(): List<ItemPecaFormBinding> =
        binding.containerPecas.children.map { ItemPecaFormBinding.bind(it) }.toList()

    private fun formulariosPecas(): List<FormularioPeca> = linhasPecas().map {
        FormularioPeca(
            nome = it.campoPecaNome.text.toString(),
            quantidade = it.campoPecaQuantidade.text.toString(),
            valorUnitario = it.campoPecaValor.text.toString(),
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (_binding == null) return
        val pecas = formulariosPecas()
        outState.putStringArrayList(KEY_NOMES, ArrayList(pecas.map { it.nome }))
        outState.putStringArrayList(KEY_QUANTIDADES, ArrayList(pecas.map { it.quantidade }))
        outState.putStringArrayList(KEY_VALORES, ArrayList(pecas.map { it.valorUnitario }))
    }

    private fun restaurarPecas(estado: Bundle) {
        val nomes = estado.getStringArrayList(KEY_NOMES).orEmpty()
        val quantidades = estado.getStringArrayList(KEY_QUANTIDADES).orEmpty()
        val valores = estado.getStringArrayList(KEY_VALORES).orEmpty()
        nomes.indices.forEach { i ->
            adicionarLinhaPeca(nomes[i], quantidades.getOrElse(i) { "" }, valores.getOrElse(i) { "" })
        }
    }

    // ------------------------------------------------------------------ salvar e observar

    private fun salvar() = with(binding) {
        val indice = rotulosTipo.indexOf(campoTipo.text.toString())
        viewModel.salvar(
            manutencaoId,
            DadosFormularioManutencao(
                formulario = FormularioManutencao(
                    data = campoData.text.toString().paraDataOuNulo(),
                    km = campoKm.text.toString(),
                    custo = campoCusto.text.toString(),
                ),
                tipo = TipoManutencao.entries.getOrElse(indice) { TipoManutencao.OUTROS },
                descricao = campoDescricao.text.toString(),
                oficina = campoOficina.text.toString(),
                pecas = formulariosPecas(),
            ),
        )
    }

    private fun observarViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.erros.collect(::mostrarErros) }
                launch { viewModel.errosPecas.collect(::mostrarErrosPecas) }
                launch { viewModel.salvando.collect { binding.botaoSalvar.isEnabled = !it } }
            }
        }
    }

    private fun mostrarErros(erros: Map<CampoManutencao, ErroValidacao>) {
        camposTela.forEach { (campo, par) -> par.first.error = erros[campo]?.mensagem(requireContext()) }
    }

    private fun mostrarErrosPecas(erros: Map<Int, Map<CampoPeca, ErroValidacao>>) {
        linhasPecas().forEachIndexed { indice, linha ->
            val doIndice = erros[indice].orEmpty()
            linha.layoutPecaNome.error = doIndice[CampoPeca.NOME]?.mensagem(requireContext())
            linha.layoutPecaQuantidade.error = doIndice[CampoPeca.QUANTIDADE]?.mensagem(requireContext())
            linha.layoutPecaValor.error = doIndice[CampoPeca.VALOR]?.mensagem(requireContext())
        }
    }

    override fun onStart() {
        super.onStart()
        // Abre já totalmente expandido (o formulário é longo).
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
        const val TAG = "ManutencaoSheet"
        private const val ARG_ID = "arg_manutencao_id"
        private const val KEY_NOMES = "pecas_nomes"
        private const val KEY_QUANTIDADES = "pecas_quantidades"
        private const val KEY_VALORES = "pecas_valores"

        /** [id] = 0 abre o cadastro; outro valor edita a manutenção existente. */
        fun novo(id: Long = 0L) = ManutencaoSheet().apply { arguments = Bundle().apply { putLong(ARG_ID, id) } }
    }
}
