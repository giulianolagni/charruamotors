package com.charruamotors.domain.validacao

import java.time.LocalDate

/** Campos do formulário de abastecimento que podem ter erro. */
enum class CampoAbastecimento { DATA, KM, LITROS, VALOR }

/** Valores digitados no formulário de abastecimento (data já convertida; números ainda como texto). */
data class FormularioAbastecimento(
    val data: LocalDate?,
    val km: String,
    val litros: String,
    val valorTotal: String,
)

/** Regras de negócio do registro de abastecimento. */
object ValidadorAbastecimento {

    /**
     * @param hoje data de referência (injetada para permitir testes)
     * @param kmMinimo km do último registro do veículo; a nova km não pode ser menor
     * @param capacidadeTanque litros máximos do tanque; null = não informada (sem limite)
     */
    fun validar(
        formulario: FormularioAbastecimento,
        hoje: LocalDate,
        kmMinimo: Int,
        capacidadeTanque: Double?,
    ): Map<CampoAbastecimento, ErroValidacao> {
        val erros = mutableMapOf<CampoAbastecimento, ErroValidacao>()

        when {
            formulario.data == null -> erros[CampoAbastecimento.DATA] = ErroValidacao.OBRIGATORIO
            formulario.data.isAfter(hoje) -> erros[CampoAbastecimento.DATA] = ErroValidacao.DATA_FUTURA
        }

        val km = formulario.km.trim().toIntOrNull()
        when {
            formulario.km.isBlank() -> erros[CampoAbastecimento.KM] = ErroValidacao.OBRIGATORIO
            km == null || km < 0 -> erros[CampoAbastecimento.KM] = ErroValidacao.NUMERO_INVALIDO
            km < kmMinimo -> erros[CampoAbastecimento.KM] = ErroValidacao.KM_MENOR_QUE_ULTIMO
        }

        val litros = formulario.litros.paraDecimalOuNulo()
        when {
            formulario.litros.isBlank() -> erros[CampoAbastecimento.LITROS] = ErroValidacao.OBRIGATORIO
            litros == null -> erros[CampoAbastecimento.LITROS] = ErroValidacao.NUMERO_INVALIDO
            litros <= 0 -> erros[CampoAbastecimento.LITROS] = ErroValidacao.MAIOR_QUE_ZERO
            capacidadeTanque != null && litros > capacidadeTanque ->
                erros[CampoAbastecimento.LITROS] = ErroValidacao.LITROS_ACIMA_DO_TANQUE
        }

        val valor = formulario.valorTotal.paraDecimalOuNulo()
        when {
            formulario.valorTotal.isBlank() -> erros[CampoAbastecimento.VALOR] = ErroValidacao.OBRIGATORIO
            valor == null -> erros[CampoAbastecimento.VALOR] = ErroValidacao.NUMERO_INVALIDO
            valor <= 0 -> erros[CampoAbastecimento.VALOR] = ErroValidacao.MAIOR_QUE_ZERO
        }

        return erros
    }
}
