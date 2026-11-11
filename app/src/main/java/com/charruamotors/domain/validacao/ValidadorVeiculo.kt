package com.charruamotors.domain.validacao

/** Campos do formulário de veículo que podem ter erro. */
enum class CampoVeiculo { MARCA, MODELO, ANO, PLACA, KM, TANQUE }

/** Valores digitados no formulário, ainda como texto (antes de virar um [Veiculo]). */
data class FormularioVeiculo(
    val marca: String,
    val modelo: String,
    val ano: String,
    val placa: String,
    val km: String,
    val capacidadeTanque: String,
)

/** Regras de negócio do cadastro de veículo. */
object ValidadorVeiculo {

    const val ANO_MINIMO = 1900

    /** Padrão antigo (ABC1234) ou Mercosul (ABC1D23), já sem hífen e em maiúsculas. */
    private val REGEX_PLACA = Regex("^[A-Z]{3}[0-9][A-Z0-9][0-9]{2}$")

    fun normalizarPlaca(placa: String): String =
        placa.trim().uppercase().replace("-", "").replace(" ", "")

    fun placaValida(placa: String): Boolean = REGEX_PLACA.matches(normalizarPlaca(placa))

    /**
     * Retorna os erros encontrados por campo; um mapa vazio significa formulário válido.
     * A unicidade da placa depende do banco e é verificada no ViewModel.
     */
    fun validar(formulario: FormularioVeiculo, anoAtual: Int): Map<CampoVeiculo, ErroValidacao> {
        val erros = mutableMapOf<CampoVeiculo, ErroValidacao>()

        if (formulario.marca.isBlank()) erros[CampoVeiculo.MARCA] = ErroValidacao.OBRIGATORIO
        if (formulario.modelo.isBlank()) erros[CampoVeiculo.MODELO] = ErroValidacao.OBRIGATORIO

        val ano = formulario.ano.trim().toIntOrNull()
        when {
            formulario.ano.isBlank() -> erros[CampoVeiculo.ANO] = ErroValidacao.OBRIGATORIO
            ano == null || ano !in ANO_MINIMO..anoAtual + 1 -> erros[CampoVeiculo.ANO] = ErroValidacao.ANO_INVALIDO
        }

        when {
            formulario.placa.isBlank() -> erros[CampoVeiculo.PLACA] = ErroValidacao.OBRIGATORIO
            !placaValida(formulario.placa) -> erros[CampoVeiculo.PLACA] = ErroValidacao.PLACA_INVALIDA
        }

        val km = formulario.km.trim().toIntOrNull()
        when {
            formulario.km.isBlank() -> erros[CampoVeiculo.KM] = ErroValidacao.OBRIGATORIO
            km == null || km < 0 -> erros[CampoVeiculo.KM] = ErroValidacao.NUMERO_INVALIDO
        }

        // Capacidade do tanque é opcional, mas, se informada, deve ser maior que zero.
        if (formulario.capacidadeTanque.isNotBlank()) {
            val tanque = formulario.capacidadeTanque.trim().replace(',', '.').toDoubleOrNull()
            if (tanque == null || tanque <= 0) erros[CampoVeiculo.TANQUE] = ErroValidacao.NUMERO_INVALIDO
        }

        return erros
    }
}
