package com.charruamotors.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.data.repository.UsuarioRepository
import com.charruamotors.data.session.SessaoManager
import com.charruamotors.domain.validacao.ErroValidacao
import com.charruamotors.domain.validacao.ValidadorLogin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Estados possíveis da tela de login. */
sealed interface EstadoLogin {
    data object Ocioso : EstadoLogin
    data object Carregando : EstadoLogin
    data class ErroFormulario(val erroEmail: ErroValidacao?, val erroSenha: ErroValidacao?) : EstadoLogin
    data object Sucesso : EstadoLogin
}

/** Eventos de uso único: consumidos uma vez, não reaparecem após rotação ou novo `onStart`. */
sealed interface EventoLogin {
    data object CredenciaisInvalidas : EventoLogin
}

/**
 * O ViewModel sobrevive a mudanças de configuração (ex.: rotação da tela),
 * então o estado do login não se perde quando a Activity é recriada.
 */
class LoginViewModel(
    private val usuarioRepository: UsuarioRepository,
    private val sessao: SessaoManager,
) : ViewModel() {

    private val _estado = MutableStateFlow<EstadoLogin>(EstadoLogin.Ocioso)
    val estado: StateFlow<EstadoLogin> = _estado.asStateFlow()

    // Channel (e não StateFlow): o Snackbar de erro é mostrado uma única vez.
    private val _eventos = Channel<EventoLogin>(Channel.BUFFERED)
    val eventos: Flow<EventoLogin> = _eventos.receiveAsFlow()

    fun entrar(email: String, senha: String) {
        val erroEmail = ValidadorLogin.validarEmail(email)
        val erroSenha = ValidadorLogin.validarSenha(senha)
        if (erroEmail != null || erroSenha != null) {
            _estado.value = EstadoLogin.ErroFormulario(erroEmail, erroSenha)
            return
        }

        _estado.value = EstadoLogin.Carregando
        viewModelScope.launch {
            val usuario = usuarioRepository.autenticar(email, senha)
            if (usuario == null) {
                _estado.value = EstadoLogin.Ocioso
                _eventos.send(EventoLogin.CredenciaisInvalidas)
            } else {
                sessao.iniciar(usuario)
                _estado.value = EstadoLogin.Sucesso
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CharruaMotorsApp
                LoginViewModel(app.usuarioRepository, app.sessao)
            }
        }
    }
}
