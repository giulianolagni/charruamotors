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
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.R
import com.charruamotors.databinding.ActivityVeiculoFormBinding
import com.charruamotors.domain.calculo.LimitesProtecao
import com.charruamotors.domain.faixas.FaixasVeiculo
import com.charruamotors.domain.faixas.PresetVeiculo
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.domain.validacao.CampoVeiculo
import com.charruamotors.domain.validacao.ErroValidacao
import com.charruamotors.domain.validacao.FormularioVeiculo
import com.charruamotors.domain.validacao.ValidadorVeiculo
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
                CampoVeiculo.LIMITE_AGUA to (layoutLimiteAgua to campoLimiteAgua),
                CampoVeiculo.LIMITE_ATF to (layoutLimiteAtf to campoLimiteAtf),
                CampoVeiculo.LIMITE_TENSAO to (layoutLimiteTensao to campoLimiteTensao),
                CampoVeiculo.CALIBRACAO to (layoutCalibracao to campoCalibracao),
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
        inflateMenu(R.menu.menu_veiculo_form)
        // Excluir só existe na edição; os presets (SW4 2013 / Cruze 2018) valem também no cadastro.
        menu.findItem(R.id.acao_excluir)?.isVisible = viewModel.modoEdicao
        setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.acao_excluir -> {
                    confirmarExclusao()
                    true
                }
                R.id.acao_preset -> {
                    escolherPreset()
                    true
                }
                else -> false
            }
        }
    }

    private fun configurarCampos() {
        binding.campoCombustivel.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, rotulosCombustivel),
        )
        camposTela.forEach { (campo, par) ->
            par.second.doAfterTextChanged {
                viewModel.limparErro(campo)
                atualizarFicha()
            }
        }
        binding.campoCombustivel.doAfterTextChanged { atualizarFicha() }
        binding.botaoPreset.setOnClickListener { escolherPreset() }
        binding.botaoSalvar.setOnClickListener { salvar() }
        atualizarFicha()
    }

    /** "Aplicar preset": preenche marca, modelo, ano, combustível e os limites de alerta do perfil. */
    private fun escolherPreset() {
        val presets = PresetVeiculo.entries
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.veiculo_preset_titulo)
            .setItems(presets.map { it.titulo }.toTypedArray()) { _, i -> aplicarPreset(presets[i]) }
            .setNegativeButton(R.string.botao_cancelar, null)
            .show()
    }

    private fun aplicarPreset(p: PresetVeiculo) = with(binding) {
        campoMarca.setText(p.marca)
        campoModelo.setText(p.modelo)
        campoAno.setText(p.ano.toString())
        campoCombustivel.setText(p.combustivel.rotulo(this@VeiculoFormActivity), false)
        campoLimiteAgua.setText(p.limiteAguaC.toString())
        campoLimiteAtf.setText(p.limiteAtfC.toString())
        campoLimiteTensao.setText("%.1f".format(LOCALE_BR, p.tensaoMinV))
        Toast.makeText(this@VeiculoFormActivity, getString(R.string.veiculo_preset_aplicado, p.titulo), Toast.LENGTH_LONG).show()
        atualizarFicha()
    }

    /** Ficha técnica ao vivo: o preset reconhecido pelo que está digitado (ou as faixas genéricas). */
    private fun atualizarFicha() = with(binding) {
        val ano = campoAno.text?.toString()?.trim()?.toIntOrNull()
        val preset = PresetVeiculo.detectar(campoMarca.text?.toString(), campoModelo.text?.toString(), ano)
        val faixas = preset?.faixas ?: run {
            val indice = rotulosCombustivel.indexOf(campoCombustivel.text.toString())
            val limites = LimitesProtecao(
                aguaMaxC = ValidadorVeiculo.numero(campoLimiteAgua.text.toString()) ?: Veiculo.LIMITE_AGUA_PADRAO_C.toDouble(),
                atfMaxC = ValidadorVeiculo.numero(campoLimiteAtf.text.toString()) ?: Veiculo.LIMITE_ATF_PADRAO_C.toDouble(),
                tensaoMinV = ValidadorVeiculo.numero(campoLimiteTensao.text.toString()) ?: Veiculo.LIMITE_TENSAO_PADRAO_V,
            )
            // Limites ainda inválidos enquanto o usuário digita: a ficha mostra os padrões.
            runCatching { FaixasVeiculo.genericas(TipoCombustivel.entries.getOrNull(indice), limites) }
                .getOrElse { FaixasVeiculo.genericas(TipoCombustivel.entries.getOrNull(indice)) }
        }
        textoPerfilFicha.text = preset?.titulo ?: getString(R.string.veiculo_faixas_generico)
        textoRedeFicha.text = preset?.linhasRede?.joinToString(QUEBRA).orEmpty()
        textoRedeFicha.isVisible = preset != null
        textoFaixasFicha.text = faixas.linhasFicha().joinToString(QUEBRA) { "• $it" }
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
        campoLimiteAgua.setText(veiculo.tempMaxAguaC.toString())
        campoLimiteAtf.setText(veiculo.tempMaxAtfC.toString())
        campoLimiteTensao.setText("%.1f".format(LOCALE_BR, veiculo.tensaoMinAlternadorV))
        campoCalibracao.setText("%.2f".format(LOCALE_BR, veiculo.calibracaoConsumo))
    }

    private fun salvar() = with(binding) {
        val formulario = FormularioVeiculo(
            marca = campoMarca.text.toString(),
            modelo = campoModelo.text.toString(),
            ano = campoAno.text.toString(),
            placa = campoPlaca.text.toString(),
            km = campoKm.text.toString(),
            capacidadeTanque = campoTanque.text.toString(),
            limiteAgua = campoLimiteAgua.text.toString(),
            limiteAtf = campoLimiteAtf.text.toString(),
            limiteTensao = campoLimiteTensao.text.toString(),
            calibracaoConsumo = campoCalibracao.text.toString(),
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
        private val LOCALE_BR = java.util.Locale.forLanguageTag("pt-BR")
        private const val QUEBRA = "\n"

        /** Cria o Intent de abertura; passe o id para editar ou null para cadastrar. */
        fun intent(context: Context, veiculoId: Long? = null): Intent =
            Intent(context, VeiculoFormActivity::class.java).apply {
                veiculoId?.let { putExtra(EXTRA_VEICULO_ID, it) }
            }
    }
}
