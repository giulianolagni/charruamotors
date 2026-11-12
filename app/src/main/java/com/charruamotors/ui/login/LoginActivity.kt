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
import com.charruamotors.ui.dashboard.DashboardActivity
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.charruamotors.ui.util.mensagem
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/** Activity 1 — Login (simulado, sem backend). É a Activity inicial do app. */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val viewModel: LoginViewModel by viewModels { LoginViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Sessão já iniciada anteriormente: pula o login e vai direto ao Dashboard.
        if ((application as CharruaMotorsApp).sessao.estaLogado) {
            abrirDashboard()
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
                viewModel.estado.collect(::renderizar)
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
            EstadoLogin.CredenciaisInvalidas ->
                Snackbar.make(root, R.string.erro_credenciais_invalidas, Snackbar.LENGTH_LONG).show()
            EstadoLogin.Sucesso -> abrirDashboard()
            EstadoLogin.Ocioso, EstadoLogin.Carregando -> Unit
        }
    }

    /** finish() remove o Login da pilha: o botão voltar no Dashboard fecha o app. */
    private fun abrirDashboard() {
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }
}
