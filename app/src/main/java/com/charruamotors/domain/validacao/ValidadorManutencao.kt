package com.charruamotors.domain.validacao

import java.time.LocalDate

/** Campos do formulário de manutenção que podem ter erro. */
enum class CampoManutencao { DATA, KM, CUSTO }

/** Valores digitados no formulário de manutenção (data já convertida; números ainda como texto). */
data class FormularioManutencao(
    val data: LocalDate?,
    val km: String,
    val custo: String,
)

/** Regras de negócio do registro de manutenção. */
object ValidadorManutencao {

    /**
     * @param hoje data de referência (injetada para permitir testes)
     * @param kmMinimo km do último registro do veículo; a nova km não pode ser menor
     */
    fun validar(
        formulario: FormularioManutencao,
        hoje: LocalDate,
        kmMinimo: Int,
    ): Map<CampoManutencao, ErroValidacao> {
        val erros = mutableMapOf<CampoManutencao, ErroValidacao>()

        when {
            formulario.data == null -> erros[CampoManutencao.DATA] = ErroValidacao.OBRIGATORIO
            formulario.data.isAfter(hoje) -> erros[CampoManutencao.DATA] = ErroValidacao.DATA_FUTURA
        }

        val km = formulario.km.trim().toIntOrNull()
        when {
            formulario.km.isBlank() -> erros[CampoManutencao.KM] = ErroValidacao.OBRIGATORIO
            km == null || km < 0 -> erros[CampoManutencao.KM] = ErroValidacao.NUMERO_INVALIDO
            km < kmMinimo -> erros[CampoManutencao.KM] = ErroValidacao.KM_MENOR_QUE_ULTIMO
        }

        val custo = formulario.custo.paraDecimalOuNulo()
        when {
            formulario.custo.isBlank() -> erros[CampoManutencao.CUSTO] = ErroValidacao.OBRIGATORIO
            custo == null -> erros[CampoManutencao.CUSTO] = ErroValidacao.NUMERO_INVALIDO
            custo <= 0 -> erros[CampoManutencao.CUSTO] = ErroValidacao.MAIOR_QUE_ZERO
        }

        return erros
    }
}
