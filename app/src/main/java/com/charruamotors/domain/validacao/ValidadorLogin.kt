package com.charruamotors.domain.validacao

/** Regras de validação dos campos da tela de login. Retorna null quando o campo é válido. */
object ValidadorLogin {

    const val TAMANHO_MINIMO_SENHA = 6

    private val REGEX_EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    fun validarEmail(email: String): ErroValidacao? = when {
        email.isBlank() -> ErroValidacao.OBRIGATORIO
        !REGEX_EMAIL.matches(email.trim()) -> ErroValidacao.EMAIL_INVALIDO
        else -> null
    }

    fun validarSenha(senha: String): ErroValidacao? = when {
        senha.isEmpty() -> ErroValidacao.OBRIGATORIO
        senha.length < TAMANHO_MINIMO_SENHA -> ErroValidacao.SENHA_CURTA
        else -> null
    }
}
