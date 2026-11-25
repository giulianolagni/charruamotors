package com.charruamotors.ui.veiculo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.R
import com.google.android.material.snackbar.Snackbar
import com.charruamotors.ui.carros.PerfisCarroUi
import com.charruamotors.databinding.ActivityVeiculoFormBinding
import com.charruamotors.domain.calculo.LimitesProtecao
import com.charruamotors.domain.faixas.NivelPerfil
import com.charruamotors.domain.faixas.PresetVeiculo
import com.charruamotors.domain.faixas.ResolucaoPerfil
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.domain.perfis.StatusPerfil
import com.charruamotors.domain.perfis.TextoPerfis
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
            } else if (viewModel.modoDuplicar) {
                // v2.3.0: cópia do carro de origem, sem a placa (única); o perfil vem junto ao salvar.
                lifecycleScope.launch {
                    val origem = viewModel.carregarOrigem() ?: return@launch
                    preencher(origem)
                    binding.campoPlaca.setText("")
                    binding.campoPlaca.requestFocus()
                    Snackbar.make(binding.root, getString(R.string.veiculo_duplicado_dica, origem.descricao), Snackbar.LENGTH_LONG).show()
                }
            } else {
                binding.campoCombustivel.setText(TipoCombustivel.FLEX.rotulo(this), false)
            }
        }

        observarViewModel()
    }

    private fun configurarToolbar() = with(binding.toolbar) {
        setTitle(
            when {
                viewModel.modoEdicao -> R.string.veiculo_titulo_editar
                viewModel.modoDuplicar -> R.string.veiculo_titulo_duplicar
                else -> R.string.veiculo_titulo_novo
            },
        )
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

    /**
     * "Presets": os modelos de `assets/perfis/veiculos.json`, cada um com um selo discreto
     * (`Validado` = conferido em carro; `Base` = valores típicos) e a faixa de anos.
     */
    private fun escolherPreset() {
        val presets = PresetVeiculo.entries
        if (presets.isEmpty()) {
            Toast.makeText(this, R.string.veiculo_preset_indisponivel, Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.veiculo_preset_titulo)
            .setItems(presets.map(::itemPreset).toTypedArray()) { _, i -> aplicarPreset(presets[i]) }
            .setNegativeButton(R.string.botao_cancelar, null)
            .show()
    }

    /** `Toyota Hilux SW4 ... (1KD-FTV)  VALIDADO · 2005–2015`, com o selo menor e colorido. */
    private fun itemPreset(p: PresetVeiculo): CharSequence {
        val validado = p.status == StatusPerfil.VALIDADO
        val selo = getString(if (validado) R.string.veiculo_preset_selo_validado else R.string.veiculo_preset_selo_base)
        val anos = p.perfil.faixaAnos.takeIf { it.isNotEmpty() && !p.generico }?.let { " · $it" }.orEmpty()
        val cor = ContextCompat.getColor(this, if (validado) R.color.status_ok else R.color.texto_secundario)
        return SpannableStringBuilder(p.titulo).append("  ").apply {
            val inicio = length
            append(selo).append(anos)
            setSpan(RelativeSizeSpan(0.78f), inicio, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(cor), inicio, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * Preenche marca, modelo, ano, combustível e os limites de alerta do perfil. Nos genéricos só o
     * combustível e os limites (marca/modelo/ano ficam os digitados); no Flex Turbo genérico o
     * modelo ganha "Turbo" se ainda não indicar, para a cascata reconhecer a motorização depois.
     */
    private fun aplicarPreset(p: PresetVeiculo) = with(binding) {
        if (p.generico) {
            val modelo = campoModelo.text?.toString().orEmpty().trim()
            if (p.perfilMotor.turbo && p.combustivel != TipoCombustivel.DIESEL && !TextoPerfis.indicaTurbo(modelo)) {
                campoModelo.setText(if (modelo.isEmpty()) p.modelo else "$modelo Turbo")
            }
        } else {
            campoMarca.setText(p.marca)
            campoModelo.setText(p.modelo)
            p.ano?.let { campoAno.setText(it.toString()) }
        }
        campoCombustivel.setText(p.combustivel.rotulo(this@VeiculoFormActivity), false)
        campoLimiteAgua.setText(p.limiteAguaC.toString())
        campoLimiteAtf.setText(p.limiteAtfC.toString())
        campoLimiteTensao.setText("%.1f".format(LOCALE_BR, p.tensaoMinV))
        Toast.makeText(this@VeiculoFormActivity, getString(R.string.veiculo_preset_aplicado, p.titulo), Toast.LENGTH_LONG).show()
        atualizarFicha()
    }

    /**
     * Ficha técnica ao vivo pela cascata de perfis: o perfil exato reconhecido pelo que está
     * digitado, senão o genérico pela motorização (com os limites acima), senão o OBD2 padrão.
     */
    private fun atualizarFicha() = with(binding) {
        val ano = campoAno.text?.toString()?.trim()?.toIntOrNull()
        val indice = rotulosCombustivel.indexOf(campoCombustivel.text.toString())
        val combustivel = TipoCombustivel.entries.getOrNull(indice)
        val limites = LimitesProtecao(
            aguaMaxC = ValidadorVeiculo.numero(campoLimiteAgua.text.toString()) ?: Veiculo.LIMITE_AGUA_PADRAO_C.toDouble(),
            atfMaxC = ValidadorVeiculo.numero(campoLimiteAtf.text.toString()) ?: Veiculo.LIMITE_ATF_PADRAO_C.toDouble(),
            tensaoMinV = ValidadorVeiculo.numero(campoLimiteTensao.text.toString()) ?: Veiculo.LIMITE_TENSAO_PADRAO_V,
        )
        val marca = campoMarca.text?.toString()
        val modelo = campoModelo.text?.toString()
        // Limites ainda inválidos enquanto o usuário digita: a ficha mostra os padrões.
        val r = runCatching { ResolucaoPerfil.resolver(marca, modelo, ano, combustivel, limites) }
            .getOrElse { ResolucaoPerfil.resolver(marca, modelo, ano, combustivel) }
        val preset = r.preset
        textoPerfilFicha.text = when (r.nivel) {
            NivelPerfil.EXATO -> "${preset?.titulo} · ${preset?.status?.rotulo}"
            NivelPerfil.GENERICO -> getString(R.string.veiculo_faixas_generico_perfil, preset?.titulo.orEmpty())
            NivelPerfil.OBD2_PADRAO -> getString(R.string.veiculo_faixas_generico)
        }
        val rede = preset?.takeIf { r.nivel == NivelPerfil.EXATO }?.let { p ->
            p.linhasRede + getString(R.string.veiculo_ficha_layout, rotuloLayout(p.layoutSugerido))
        }.orEmpty()
        textoRedeFicha.text = rede.joinToString(QUEBRA)
        textoRedeFicha.isVisible = rede.isNotEmpty()
        textoFaixasFicha.text = r.faixas.linhasFicha().joinToString(QUEBRA) { "• $it" }
    }

    private fun rotuloLayout(id: String): String =
        getString(if (id == LAYOUT_TURBO_FLEX) R.string.veiculo_layout_turbo_flex else R.string.veiculo_layout_padrao)

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
        if (evento is EventoFormVeiculo.Salvo && evento.novo && evento.generico && evento.veiculo != null) {
            oferecerMapeamento(evento.veiculo)
            return
        }
        val mensagem = when (evento) {
            is EventoFormVeiculo.Salvo ->
                if (evento.novo) R.string.veiculo_cadastrado else R.string.veiculo_atualizado
            EventoFormVeiculo.Excluido -> R.string.veiculo_excluido
        }
        Toast.makeText(this, mensagem, Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * v2.3.0: carro novo sem perfil específico — começa no OBD2 Genérico; 1 toque abre a Sessão de
     * Mapeamento (Log Bruto ligado, conectando) para capturar o primeiro log.
     */
    private fun oferecerMapeamento(veiculo: Veiculo) {
        Toast.makeText(this, R.string.veiculo_cadastrado, Toast.LENGTH_SHORT).show()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.carro_novo_generico_titulo)
            .setMessage(getString(R.string.carro_novo_generico_mensagem, veiculo.descricao))
            .setCancelable(false)
            .setNegativeButton(R.string.carro_novo_depois) { _, _ -> finish() }
            .setPositiveButton(R.string.carro_novo_mapear) { _, _ ->
                PerfisCarroUi.iniciarMapeamento(this, veiculo)
                finish()
            }
            .show()
    }

    companion object {
        const val EXTRA_VEICULO_ID = "extra_veiculo_id"

        /** v2.3.0: id do carro a duplicar (cadastro novo preenchido com ele). */
        const val EXTRA_DUPLICAR_DE = "extra_duplicar_de"
        private val LOCALE_BR = java.util.Locale.forLanguageTag("pt-BR")
        private const val QUEBRA = "\n"
        private const val LAYOUT_TURBO_FLEX = "turbo_flex"

        /** Cria o Intent de abertura; passe o id para editar ou null para cadastrar. */
        fun intent(context: Context, veiculoId: Long? = null): Intent =
            Intent(context, VeiculoFormActivity::class.java).apply {
                veiculoId?.let { putExtra(EXTRA_VEICULO_ID, it) }
            }

        /** "Duplicar" em Meus Carros. */
        fun intentDuplicar(context: Context, origemId: Long): Intent =
            Intent(context, VeiculoFormActivity::class.java).putExtra(EXTRA_DUPLICAR_DE, origemId)
    }
}
