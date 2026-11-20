package com.charruamotors.domain.validacao

/** Campos de uma peça trocada que podem ter erro. */
enum class CampoPeca { NOME, QUANTIDADE, VALOR }

/** Valores digitados em uma linha de peça do formulário de manutenção (ainda como texto). */
data class FormularioPeca(
    val nome: String,
    val quantidade: String,
    val valorUnitario: String,
) {
    /** Linha totalmente em branco é ignorada (o usuário adicionou e não preencheu). */
    val vazia: Boolean get() = nome.isBlank() && quantidade.isBlank() && valorUnitario.isBlank()
}

/** Regras de negócio de uma peça trocada. */
object ValidadorPeca {

    fun validar(formulario: FormularioPeca): Map<CampoPeca, ErroValidacao> {
        val erros = mutableMapOf<CampoPeca, ErroValidacao>()

        if (formulario.nome.isBlank()) erros[CampoPeca.NOME] = ErroValidacao.OBRIGATORIO

        val quantidade = formulario.quantidade.trim().toIntOrNull()
        when {
            formulario.quantidade.isBlank() -> erros[CampoPeca.QUANTIDADE] = ErroValidacao.OBRIGATORIO
            quantidade == null -> erros[CampoPeca.QUANTIDADE] = ErroValidacao.NUMERO_INVALIDO
            quantidade <= 0 -> erros[CampoPeca.QUANTIDADE] = ErroValidacao.MAIOR_QUE_ZERO
        }

        val valor = formulario.valorUnitario.paraDecimalOuNulo()
        when {
            formulario.valorUnitario.isBlank() -> erros[CampoPeca.VALOR] = ErroValidacao.OBRIGATORIO
            valor == null -> erros[CampoPeca.VALOR] = ErroValidacao.NUMERO_INVALIDO
            valor <= 0 -> erros[CampoPeca.VALOR] = ErroValidacao.MAIOR_QUE_ZERO
        }

        return erros
    }
}
