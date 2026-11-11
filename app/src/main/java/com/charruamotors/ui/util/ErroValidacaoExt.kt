package com.charruamotors.ui.util

import android.content.Context
import com.charruamotors.R
import com.charruamotors.domain.validacao.ErroValidacao
import com.charruamotors.domain.validacao.ValidadorLogin
import com.charruamotors.domain.validacao.ValidadorVeiculo

/** Converte um erro de validação do domínio no texto exibido no campo do formulário. */
fun ErroValidacao.mensagem(context: Context): String = when (this) {
    ErroValidacao.OBRIGATORIO -> context.getString(R.string.erro_obrigatorio)
    ErroValidacao.EMAIL_INVALIDO -> context.getString(R.string.erro_email_invalido)
    ErroValidacao.SENHA_CURTA ->
        context.getString(R.string.erro_senha_curta, ValidadorLogin.TAMANHO_MINIMO_SENHA)
    ErroValidacao.PLACA_INVALIDA -> context.getString(R.string.erro_placa_invalida)
    ErroValidacao.PLACA_DUPLICADA -> context.getString(R.string.erro_placa_duplicada)
    ErroValidacao.ANO_INVALIDO -> context.getString(R.string.erro_ano_invalido, ValidadorVeiculo.ANO_MINIMO)
    ErroValidacao.NUMERO_INVALIDO -> context.getString(R.string.erro_numero_invalido)
}
