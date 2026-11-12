package com.charruamotors.domain.validacao

/** Erros de validação de formulário, independentes da UI (testáveis em testes unitários). */
enum class ErroValidacao {
    OBRIGATORIO,
    EMAIL_INVALIDO,
    SENHA_CURTA,
    PLACA_INVALIDA,
    PLACA_DUPLICADA,
    ANO_INVALIDO,
    NUMERO_INVALIDO,
}
