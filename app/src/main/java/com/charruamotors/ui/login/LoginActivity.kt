package com.charruamotors.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.ActivityLoginBinding
import com.charruamotors.sistema.navegacao.DestinoInicial
import com.charruamotors.sistema.navegacao.VeiculoFavorito
import com.charruamotors.ui.assistente.AssistenteActivity
import com.charruamotors.ui.dashboard.DashboardActivity
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.charruamotors.ui.util.mensagem
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Activity 1 — Login (simulado, sem backend). É a Activity inicial do app e decide a primeira
 * tela ([VeiculoFavorito.destinoInicial]): com o login feito e algum veículo cadastrado, entra
 * direto nas 4 abas do veículo favorito (aba Painel); sem veículo, em Meus Veículos.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val viewModel: LoginViewModel by viewModels { LoginViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Sessão já iniciada anteriormente: pula o login e vai direto ao veículo favorito.
        if ((application as CharruaMotorsApp).sessao.estaLogado) {
            abrirInicio()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.aplicarInsetsDoSistema()

        configurarFormulario()
        observarEstado()
    }

    private fun configurarFormulario() = with(binding) {
        botaoEntrar.setOnClickListener {
            viewModel.entrar(campoEmail.text.toString(), campoSenha.text.toString())
        }
        campoSenha.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                botaoEntrar.performClick()
                true
            } else {
                false
            }
        }
        // Limpa a mensagem de erro assim que o usuário corrige o campo.
        campoEmail.doAfterTextChanged { layoutEmail.error = null }
        campoSenha.doAfterTextChanged { layoutSenha.error = null }
    }

    /**
     * repeatOnLifecycle(STARTED): a coleta do estado só acontece enquanto a tela está visível
     * (entre onStart e onStop) e é cancelada automaticamente quando ela sai de cena.
     */
    private fun observarEstado() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.estado.collect(::renderizar) }
                launch { viewModel.eventos.collect(::tratarEvento) }
            }
        }
    }

    private fun renderizar(estado: EstadoLogin) = with(binding) {
        val carregando = estado is EstadoLogin.Carregando
        progresso.isVisible = carregando
        botaoEntrar.isEnabled = !carregando

        when (estado) {
            is EstadoLogin.ErroFormulario -> {
                layoutEmail.error = estado.erroEmail?.mensagem(this@LoginActivity)
                layoutSenha.error = estado.erroSenha?.mensagem(this@LoginActivity)
            }
            EstadoLogin.Sucesso -> abrirInicio()
            EstadoLogin.Ocioso, EstadoLogin.Carregando -> Unit
        }
    }

    private fun tratarEvento(evento: EventoLogin) {
        when (evento) {
            EventoLogin.CredenciaisInvalidas ->
                Snackbar.make(binding.root, R.string.erro_credenciais_invalidas, Snackbar.LENGTH_LONG).show()
        }
    }

    /**
     * Abre a primeira tela e tira o Login da pilha (o Voltar na tela principal fecha o app). Na
     * primeira abertura após o login, o Assistente Inicial entra por cima dela.
     */
    private fun abrirInicio() {
        if (abrindo) return
        abrindo = true
        val app = application as CharruaMotorsApp
        lifecycleScope.launch {
            val veiculos = app.veiculoRepository.observarTodos().first()
            val prefs = app.preferenciasAutoConexao
            val principal: Intent = when (val destino = VeiculoFavorito.destinoInicial(app.sessao.estaLogado, veiculos.map { it.id }, prefs.config.value.veiculoId)) {
                is DestinoInicial.Painel -> {
                    // Favorito vazio ou de um veículo apagado: grava o efetivo (a caixa-preta usa o mesmo).
                    if (destino.gravarFavorito) {
                        veiculos.firstOrNull { it.id == destino.veiculoId }?.let { prefs.vincularVeiculo(it.id, it.descricao) }
                    }
                    DiagnosticoActivity.intent(this@LoginActivity, destino.veiculoId)
                }
                DestinoInicial.MeusVeiculos -> DashboardActivity.intentRaiz(this@LoginActivity)
                DestinoInicial.Login -> {
                    abrindo = false
                    return@launch
                }
            }
            val pilha = if (app.preferenciasApp.deveMostrarAssistente) {
                arrayOf(principal, AssistenteActivity.intent(this@LoginActivity))
            } else {
                arrayOf(principal)
            }
            startActivities(pilha)
            finish()
        }
    }

    /** Evita abrir a tela principal duas vezes (estado de sucesso reemitido ao voltar ao STARTED). */
    private var abrindo = false
}
