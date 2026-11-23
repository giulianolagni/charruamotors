package com.charruamotors.domain.validacao

/** Campos do formulário de veículo que podem ter erro. */
enum class CampoVeiculo { MARCA, MODELO, ANO, PLACA, KM, TANQUE, LIMITE_AGUA, LIMITE_ATF, LIMITE_TENSAO, CALIBRACAO }

/** Valores digitados no formulário, ainda como texto (antes de virar um [Veiculo]). */
data class FormularioVeiculo(
    val marca: String,
    val modelo: String,
    val ano: String,
    val placa: String,
    val km: String,
    val capacidadeTanque: String,
    /** Limites de alerta (v1.7); vazio = padrão do veículo (98 °C, 105 °C, 13,2 V, calibração 1,0). */
    val limiteAgua: String = "",
    val limiteAtf: String = "",
    val limiteTensao: String = "",
    val calibracaoConsumo: String = "",
)

/** Regras de negócio do cadastro de veículo. */
object ValidadorVeiculo {

    const val ANO_MINIMO = 1900
    val FAIXA_LIMITE_AGUA_C = 80.0..130.0
    val FAIXA_LIMITE_ATF_C = 80.0..150.0
    val FAIXA_LIMITE_TENSAO_V = 11.0..15.5
    val FAIXA_CALIBRACAO = 0.3..3.0

    /** Número com vírgula ou ponto; null = vazio ou ilegível. */
    fun numero(texto: String): Double? = texto.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    /** Padrão antigo (ABC1234) ou Mercosul (ABC1D23), já sem hífen e em maiúsculas. */
    private val REGEX_PLACA = Regex("^[A-Z]{3}[0-9][A-Z0-9][0-9]{2}$")

    fun normalizarPlaca(placa: String): String =
        placa.trim().uppercase().replace("-", "").replace(" ", "")

    fun placaValida(placa: String): Boolean = REGEX_PLACA.matches(normalizarPlaca(placa))

    /**
     * Retorna os erros encontrados por campo; um mapa vazio significa formulário válido.
     * A unicidade da placa depende do banco e é verificada no ViewModel.
     *
     * @param kmMinimo maior km já registrada em manutenções/abastecimentos do veículo; a km do
     * veículo não pode ser reduzida abaixo do histórico existente (0 = sem histórico).
     */
    fun validar(
        formulario: FormularioVeiculo,
        anoAtual: Int,
        kmMinimo: Int = 0,
    ): Map<CampoVeiculo, ErroValidacao> {
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
            km < kmMinimo -> erros[CampoVeiculo.KM] = ErroValidacao.KM_MENOR_QUE_ULTIMO
        }

        // Capacidade do tanque é opcional, mas, se informada, deve ser maior que zero.
        if (formulario.capacidadeTanque.isNotBlank()) {
            val tanque = formulario.capacidadeTanque.trim().replace(',', '.').toDoubleOrNull()
            if (tanque == null || tanque <= 0) erros[CampoVeiculo.TANQUE] = ErroValidacao.NUMERO_INVALIDO
        }

        // Limites de alerta e calibração: opcionais (vazio = padrão), mas dentro de faixas sensatas.
        fun conferir(campo: CampoVeiculo, texto: String, faixa: ClosedFloatingPointRange<Double>) {
            if (texto.isBlank()) return
            val valor = numero(texto)
            if (valor == null || valor !in faixa) erros[campo] = ErroValidacao.NUMERO_INVALIDO
        }
        conferir(CampoVeiculo.LIMITE_AGUA, formulario.limiteAgua, FAIXA_LIMITE_AGUA_C)
        conferir(CampoVeiculo.LIMITE_ATF, formulario.limiteAtf, FAIXA_LIMITE_ATF_C)
        conferir(CampoVeiculo.LIMITE_TENSAO, formulario.limiteTensao, FAIXA_LIMITE_TENSAO_V)
        conferir(CampoVeiculo.CALIBRACAO, formulario.calibracaoConsumo, FAIXA_CALIBRACAO)

        return erros
    }
}
