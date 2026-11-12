package com.charruamotors.ui.veiculo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.R
import com.charruamotors.databinding.ActivityVeiculoFormBinding
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.domain.validacao.CampoVeiculo
import com.charruamotors.domain.validacao.ErroValidacao
import com.charruamotors.domain.validacao.FormularioVeiculo
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.charruamotors.ui.util.mensagem
import com.charruamotors.ui.util.rotulo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

/**
 * Activity 3 — Formulário de Veículos (CRUD).
 * Sem EXTRA_VEICULO_ID: cadastro (Create). Com EXTRA_VEICULO_ID: leitura, edição e exclusão.
 */
class VeiculoFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVeiculoFormBinding
    private val viewModel: VeiculoFormViewModel by viewModels { VeiculoFormViewModel.Factory }

    private val rotulosCombustivel by lazy { TipoCombustivel.entries.map { it.rotulo(this) } }

    /** Associação campo do domínio -> campo da tela, usada para exibir os erros. */
    private val camposTela: Map<CampoVeiculo, Pair<TextInputLayout, TextInputEditText>> by lazy {
        with(binding) {
            mapOf(
                CampoVeiculo.MARCA to (layoutMarca to campoMarca),
                CampoVeiculo.MODELO to (layoutModelo to campoModelo),
                CampoVeiculo.ANO to (layoutAno to campoAno),
                CampoVeiculo.PLACA to (layoutPlaca to campoPlaca),
                CampoVeiculo.KM to (layoutKm to campoKm),
                CampoVeiculo.TANQUE to (layoutTanque to campoTanque),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)

        binding = ActivityVeiculoFormBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)

        configurarToolbar()
        configurarCampos()

        // Só preenche a partir do banco na primeira criação. Após rotação, os campos
        // já são restaurados automaticamente pelo estado salvo das views.
        if (savedInstanceState == null) {
            if (viewModel.modoEdicao) {
                lifecycleScope.launch { viewModel.carregar()?.let(::preencher) ?: finish() }
            } else {
                binding.campoCombustivel.setText(TipoCombustivel.FLEX.rotulo(this), false)
            }
        }

        observarViewModel()
    }

    private fun configurarToolbar() = with(binding.toolbar) {
        setTitle(if (viewModel.modoEdicao) R.string.veiculo_titulo_editar else R.string.veiculo_titulo_novo)
        setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        if (viewModel.modoEdicao) {
            inflateMenu(R.menu.menu_veiculo_form)
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.acao_excluir) {
                    confirmarExclusao()
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun configurarCampos() {
        binding.campoCombustivel.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, rotulosCombustivel),
        )
        camposTela.forEach { (campo, par) ->
            par.second.doAfterTextChanged { viewModel.limparErro(campo) }
        }
        binding.botaoSalvar.setOnClickListener { salvar() }
    }

    private fun preencher(veiculo: Veiculo) = with(binding) {
        campoMarca.setText(veiculo.marca)
        campoModelo.setText(veiculo.modelo)
        campoAno.setText(veiculo.ano.toString())
        campoPlaca.setText(veiculo.placaFormatada)
        campoCor.setText(veiculo.cor.orEmpty())
        campoKm.setText(veiculo.kmAtual.toString())
        campoCombustivel.setText(veiculo.tipoCombustivel.rotulo(this@VeiculoFormActivity), false)
        campoTanque.setText(veiculo.capacidadeTanque?.let { "%.1f".format(it) }.orEmpty())
    }

    private fun salvar() = with(binding) {
        val formulario = FormularioVeiculo(
            marca = campoMarca.text.toString(),
            modelo = campoModelo.text.toString(),
            ano = campoAno.text.toString(),
            placa = campoPlaca.text.toString(),
            km = campoKm.text.toString(),
            capacidadeTanque = campoTanque.text.toString(),
        )
        val indice = rotulosCombustivel.indexOf(campoCombustivel.text.toString())
        val combustivel = TipoCombustivel.entries.getOrElse(indice) { TipoCombustivel.FLEX }
        viewModel.salvar(formulario, campoCor.text.toString(), combustivel)
    }

    private fun confirmarExclusao() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.veiculo_excluir_titulo)
            .setMessage(R.string.veiculo_excluir_mensagem)
            .setNegativeButton(R.string.botao_cancelar, null)
            .setPositiveButton(R.string.botao_excluir) { _, _ -> viewModel.excluir() }
            .show()
    }

    private fun observarViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.erros.collect(::mostrarErros) }
                launch { viewModel.salvando.collect { binding.botaoSalvar.isEnabled = !it } }
                launch { viewModel.eventos.collect(::tratarEvento) }
            }
        }
    }

    private fun mostrarErros(erros: Map<CampoVeiculo, ErroValidacao>) {
        camposTela.forEach { (campo, par) -> par.first.error = erros[campo]?.mensagem(this) }
    }

    private fun tratarEvento(evento: EventoFormVeiculo) {
        val mensagem = when (evento) {
            is EventoFormVeiculo.Salvo ->
                if (evento.novo) R.string.veiculo_cadastrado else R.string.veiculo_atualizado
            EventoFormVeiculo.Excluido -> R.string.veiculo_excluido
        }
        Toast.makeText(this, mensagem, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val EXTRA_VEICULO_ID = "extra_veiculo_id"

        /** Cria o Intent de abertura; passe o id para editar ou null para cadastrar. */
        fun intent(context: Context, veiculoId: Long? = null): Intent =
            Intent(context, VeiculoFormActivity::class.java).apply {
                veiculoId?.let { putExtra(EXTRA_VEICULO_ID, it) }
            }
    }
}
