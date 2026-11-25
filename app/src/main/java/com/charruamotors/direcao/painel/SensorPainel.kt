package com.charruamotors.direcao.painel

import com.charruamotors.domain.calculo.AlertaProtecao
import com.charruamotors.domain.calculo.CambioA750f
import com.charruamotors.domain.calculo.TipoAlertaProtecao
import com.charruamotors.domain.perfis.PerfilCambio
import com.charruamotors.domain.perfis.TipoCambio
import com.charruamotors.obd.ValoresSensores
import com.charruamotors.obd.pid.DefinicaoPid
import com.charruamotors.obd.pid.Pids
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.sistema.unidades.UnidadePressao
import com.charruamotors.sistema.unidades.ValorExibicao
import com.charruamotors.ui.componentes.EscalaGauge
import com.charruamotors.ui.util.comoNumero

/** Chave interna do consumo calculado (não existe na ECU); fora do enum para a entrada poder usá-la. */
private const val CHAVE_CONSUMO_KML_PAINEL = "CALC_KML"

/** Correção dos bicos: ±3,00 mm³/st é a faixa saudável; a escala vai até ±4 (zona vermelha em 3). */
private val ESCALA_BICO = mapOf("mm³/st" to EscalaGauge(-4f, 4f, 3f, 8, 2))

/**
 * Sensores que podem ocupar um dos 4 mostradores do Painel ou uma célula do HUD flutuante, com a
 * escala de cada unidade de exibição (métrica e imperial). [comando] é a chave do PID canônico no
 * catálogo (`010C`, `ATRV`...): os espelhos dos modos 21/22 publicam no mesmo lugar. Os
 * [alternativos] são lidos quando o principal não vem (ex.: o pedal pelo `2149` Toyota quando a
 * ECU não tem o `0149`; a alavanca pelo motor `7E0` quando o câmbio `7E1` não responde). Sensores
 * de estado (alavanca, 4x4, VSC) mostram o [rotulos] do valor em vez do número; sem leitura, "—".
 */
enum class SensorPainel(
    val comando: String,
    /** Título no mostrador do Painel. */
    val titulo: String,
    /** Rótulo curto, em maiúsculas, do HUD flutuante. */
    val rotuloHud: String,
    /** Unidade do catálogo de PIDs (antes da conversão). */
    val unidadeCatalogo: String,
    val casas: Int,
    private val escalas: Map<String, EscalaGauge>,
    val alternativos: List<String> = emptyList(),
    val rotulos: Map<Int, String> = emptyMap(),
    /** Valores positivos levam `+` (correção dos bicos injetores: `+0,45`). */
    val comSinal: Boolean = false,
) {
    RPM(
        "010C", "RPM", "RPM", "rpm", 0,
        mapOf("rpm" to EscalaGauge(0f, 8000f, 6500f, 8, 5, 1000f)),
    ),
    VELOCIDADE(
        "010D", "Velocidade", "VELOC.", "km/h", 0,
        mapOf(
            "km/h" to EscalaGauge(0f, 240f, null, 6, 4),
            "mph" to EscalaGauge(0f, 160f, null, 8, 4),
        ),
    ),
    TURBO(
        "010B", "Turbo/MAP", "TURBO/MAP", "kPa", 0,
        mapOf(
            "kPa" to EscalaGauge(0f, 250f, 200f, 5, 5),
            "bar" to EscalaGauge(0f, 2.5f, 2.0f, 5, 5),
            "psi" to EscalaGauge(0f, 35f, 29f, 7, 5),
        ),
    ),
    TEMP_MOTOR(
        "0105", "Temp. Água (°C)", "MOTOR", "°C", 0,
        mapOf(
            "°C" to EscalaGauge(40f, 130f, 110f, 9, 2),
            "°F" to EscalaGauge(100f, 280f, 230f, 9, 2),
        ),
    ),
    TEMP_ADMISSAO(
        "010F", "Temp. Admissão (°C)", "ADMISSÃO", "°C", 0,
        mapOf(
            "°C" to EscalaGauge(-20f, 100f, 70f, 6, 2),
            "°F" to EscalaGauge(0f, 210f, 160f, 7, 3),
        ),
    ),
    TEMP_OLEO(
        "015C", "Temp. Óleo (°C)", "ÓLEO", "°C", 0,
        mapOf(
            "°C" to EscalaGauge(40f, 160f, 130f, 6, 2),
            "°F" to EscalaGauge(100f, 320f, 265f, 11, 2),
        ),
    ),
    CARGA(
        "0104", "Carga Motor (%)", "CARGA", "%", 0,
        mapOf("%" to EscalaGauge(0f, 100f, 90f, 5, 4)),
    ),
    BORBOLETA(
        "0111", "Borboleta (%)", "BORBOLETA", "%", 0,
        mapOf("%" to EscalaGauge(0f, 100f, null, 5, 4)),
    ),
    ACELERADOR(
        "0149", "Pedal Acelerador", "PEDAL", "%", 0,
        mapOf("%" to EscalaGauge(0f, 100f, null, 5, 4)),
        alternativos = listOf("TOYOTA_PEDAL_D", "TOYOTA_PEDAL_E"),
    ),
    TENSAO(
        "ATRV", "Bateria (V)", "BATERIA", "V", 1,
        mapOf("V" to EscalaGauge(8f, 16f, 15f, 8, 2)),
    ),
    RAIL(
        "0123", "Pressão Rail (bar)", "RAIL", "kPa", 0,
        mapOf(
            "bar" to EscalaGauge(0f, 2000f, 1800f, 4, 5),
            "psi" to EscalaGauge(0f, 30000f, 26000f, 6, 5, 1000f),
        ),
    ),
    MAF(
        "0110", "MAF", "MAF", "g/s", 1,
        mapOf("g/s" to EscalaGauge(0f, 250f, null, 5, 5)),
    ),
    COMBUSTIVEL(
        "012F", "Nível Tanque (%)", "TANQUE", "%", 0,
        mapOf("%" to EscalaGauge(0f, 100f, null, 4, 5)),
    ),
    /**
     * Consumo instantâneo em km/L (v1.7), calculado do MAF e da velocidade pelo computador de
     * bordo: não é um PID, então [comando] é só a chave interna e a leitura vem de [consumoKmL].
     */
    CONSUMO_KML(
        CHAVE_CONSUMO_KML_PAINEL, "Consumo (km/L)", "CONSUMO", "km/L", 1,
        mapOf(
            "km/L" to EscalaGauge(0f, 20f, null, 5, 4),
            "MPG" to EscalaGauge(0f, 50f, null, 5, 5),
            "L/100km" to EscalaGauge(0f, 30f, null, 6, 5),
        ),
    ),

    // ---- Toyota SW4 / Hilux (perfil de montadora, pids_estendidos.json): só leem se o módulo responder.
    TEMP_CAMBIO(
        "TOYOTA_ATF_CAMBIO", "Temp. Câmbio (ATF)", "ATF", "°C", 0,
        mapOf(
            "°C" to EscalaGauge(40f, 150f, 120f, 11, 2),
            "°F" to EscalaGauge(100f, 300f, 250f, 10, 2),
        ),
        // GM (Cruze 1.4T): ATF por UDS 22 1940 no TCM 7E1 ou, na alternativa, no 7E2.
        alternativos = listOf("TOYOTA_ATF", "TOYOTA_ATF_ECM", "GM_ATF", "GM_ATF_7E2"),
    ),
    MARCHA_ALAVANCA(
        "TOYOTA_ALAVANCA", "Alavanca (P/R/D)", "ALAVANCA", "", 0,
        mapOf("" to EscalaGauge(0f, 9f, null, 9, 1)),
        // O 7E1 · 21D9 (bytes C/D) vem primeiro: o 7E0 · 21D9 devolve sempre 00 (P) na SW4 2013.
        alternativos = listOf("TOYOTA_ALAVANCA_ECM"),
        rotulos = mapOf(0 to "P", 1 to "R", 2 to "N", 3 to "D", 4 to "3", 5 to "M", 6 to "L", 7 to "P/N", 8 to "4", 9 to "2"),
    ),
    MARCHA_ATUAL(
        "TOYOTA_MARCHA", "Marcha (1ª–6ª)", "MARCHA", "", 0,
        mapOf("" to EscalaGauge(0f, 6f, null, 6, 1)),
        // 1..5 do 2130 (SW4) ou 1..6 estimada pela razão rpm/velocidade (CambioA750f / CambioGm6T); 11..15 = 1ª L..5ª L
        // (reduzida 4L, v2.2.6); 254 = R, 255 = parado.
        rotulos = CambioA750f.ROTULOS_MARCHA,
    ),
    TRACAO_4X4(
        "TOYOTA_4WD", "Tração 4x4", "4X4", "", 0,
        mapOf("" to EscalaGauge(0f, 4f, null, 4, 1)),
        rotulos = mapOf(0 to "4x2 (H2)", 1 to "4x4 (H4)", 2 to "H4 bloqueado", 3 to "Reduzida (L4)", 4 to "L4 + bloqueio traseiro"),
    ),
    VSC(
        "TOYOTA_VSC", "VSC / Estabilidade", "VSC", "", 0,
        mapOf("" to EscalaGauge(0f, 4f, 3f, 4, 1)),
        rotulos = mapOf(0 to "Normal", 1 to "Atuando", 2 to "VSC OFF", 3 to "TRC OFF", 4 to "Falha"),
    ),
    TENSAO_ECU(
        "TOYOTA_TENSAO_ECU", "Tensão ECU (V)", "ECU", "V", 2,
        mapOf("V" to EscalaGauge(8f, 16f, 15f, 8, 2)),
        // OBD2 padrão (Cruze e a maioria dos carros a gasolina/flex) quando não há o 2142 Toyota.
        alternativos = listOf(Pids.TENSAO_ECU),
    ),
    PRESSAO_BARO(
        "TOYOTA_BARO", "Pressão Barométrica", "BARO", "kPa", 0,
        mapOf(
            "kPa" to EscalaGauge(60f, 110f, null, 5, 5),
            "bar" to EscalaGauge(0.6f, 1.1f, null, 5, 5),
            "psi" to EscalaGauge(8f, 16f, null, 8, 2),
        ),
    ),
    BICO_1("TOYOTA_INJ1", "Bico Injetor #1", "BICO 1", "mm³/st", 2, ESCALA_BICO, comSinal = true),
    BICO_2("TOYOTA_INJ2", "Bico Injetor #2", "BICO 2", "mm³/st", 2, ESCALA_BICO, comSinal = true),
    BICO_3("TOYOTA_INJ3", "Bico Injetor #3", "BICO 3", "mm³/st", 2, ESCALA_BICO, comSinal = true),
    BICO_4("TOYOTA_INJ4", "Bico Injetor #4", "BICO 4", "mm³/st", 2, ESCALA_BICO, comSinal = true),

    // ---- Celular (v2.0): no fim do enum para não mudar a ordem de sugestão dos demais mostradores.
    /**
     * Velocidade pelo GPS do celular (v2.0), ativada nas Configurações: não é um PID, a sessão
     * mescla a leitura ([Pids.VELOCIDADE_GPS]) em cada instantâneo. Sem fix válido, "—".
     */
    VELOCIDADE_GPS(
        Pids.VELOCIDADE_GPS, "Velocidade GPS", "GPS", "km/h", 0,
        mapOf(
            "km/h" to EscalaGauge(0f, 240f, null, 6, 4),
            "mph" to EscalaGauge(0f, 160f, null, 8, 4),
        ),
    ),

    // ---- Mistura, sonda lambda e Modo Flex (v2.1): no fim do enum, pelo mesmo motivo.
    SONDA_LAMBDA_V(
        Pids.SONDA_LAMBDA_TENSAO, "Sonda Lambda 1 (V)", "LAMBDA V", "V", 3,
        mapOf("V" to EscalaGauge(0f, 1.2f, null, 6, 2)),
    ),
    /** λ medido pela sonda de banda larga (`0124`/`0134`); sem ela, o λ comandado pela ECU (`0144`). */
    SONDA_LAMBDA(
        Pids.LAMBDA_BANDA_LARGA, "Sonda Lambda 1 (λ)", "LAMBDA", "λ", 3,
        mapOf("λ" to EscalaGauge(0.6f, 1.4f, null, 8, 2)),
        alternativos = listOf(Pids.LAMBDA_BANDA_LARGA_CORRENTE, Pids.LAMBDA_COMANDADO),
    ),
    /** Relação ar/combustível real (λ × estequiometria do combustível em uso), calculada pela sessão. */
    RELACAO_AF(
        Pids.RELACAO_AF_REAL, "Relação A/F Real (:1)", "A/F", ":1", 1,
        mapOf(":1" to EscalaGauge(6f, 18f, null, 6, 2)),
    ),
    ETANOL(
        Pids.PERCENTUAL_ETANOL, "Teor de Etanol (%)", "ETANOL", "%", 0,
        mapOf("%" to EscalaGauge(0f, 100f, null, 4, 5)),
    ),
    STFT(
        Pids.STFT, "Correção Curto Prazo (STFT)", "STFT", "%", 1,
        mapOf("%" to EscalaGauge(-25f, 25f, 20f, 5, 2)),
        comSinal = true,
    ),
    LTFT(
        Pids.LTFT, "Correção Longo Prazo (LTFT)", "LTFT", "%", 1,
        mapOf("%" to EscalaGauge(-25f, 25f, 20f, 5, 2)),
        comSinal = true,
    ),
    AVANCO_IGNICAO(
        Pids.AVANCO_IGNICAO, "Avanço Ignição (°)", "AVANÇO", "°", 1,
        mapOf("°" to EscalaGauge(-10f, 50f, null, 6, 2)),
        comSinal = true,
    ),
    ;

    /**
     * Título no mostrador conforme o [cambio] ativo (v2.2.3): só a marcha muda. Automático de 5 ou 6
     * marchas → "Marcha (1ª–5ª)" / "Marcha (1ª–6ª)"; CVT, manual ou sem perfil exato → "Marcha".
     */
    fun tituloPara(cambio: PerfilCambio?): String =
        if (this == MARCHA_ATUAL) tituloMarcha(cambio) else titulo

    /** Seção em que o sensor aparece nos chips de escolha. */
    val grupo: GrupoSensor
        get() = when (this) {
            RPM, VELOCIDADE, VELOCIDADE_GPS, TURBO, CARGA, BORBOLETA, ACELERADOR, CONSUMO_KML -> GrupoSensor.PRINCIPAL
            TEMP_MOTOR, TEMP_ADMISSAO, TEMP_OLEO, TEMP_CAMBIO -> GrupoSensor.TEMPERATURAS
            RAIL, MAF, COMBUSTIVEL, PRESSAO_BARO, BICO_1, BICO_2, BICO_3, BICO_4, AVANCO_IGNICAO -> GrupoSensor.INJECAO
            SONDA_LAMBDA_V, SONDA_LAMBDA, RELACAO_AF, ETANOL, STFT, LTFT -> GrupoSensor.MISTURA
            MARCHA_ALAVANCA, MARCHA_ATUAL, TRACAO_4X4, VSC -> GrupoSensor.CAMBIO_CHASSI
            TENSAO, TENSAO_ECU -> GrupoSensor.ELETRICA
        }

    /** Proteção que este sensor evidencia em vermelho quando o limite do veículo é ultrapassado. */
    val alertaDeProtecao: TipoAlertaProtecao?
        get() = when (this) {
            TEMP_MOTOR -> TipoAlertaProtecao.AGUA
            TEMP_CAMBIO -> TipoAlertaProtecao.ATF
            TENSAO -> TipoAlertaProtecao.TENSAO
            else -> null
        }

    /** O mostrador deste sensor fica vermelho com estes [alertas] ativos? */
    fun emAlerta(alertas: Collection<AlertaProtecao>): Boolean = alertaDeProtecao?.let { t -> alertas.any { it.tipo == t } } ?: false

    /**
     * Leitura para o mostrador: o consumo em km/L vem do computador de bordo ([consumoKmL], null
     * parado ou sem dados); os demais, do instantâneo [dados].
     */
    fun ler(dados: ValoresSensores?, consumoKmL: Double?): Double? =
        if (this == CONSUMO_KML) consumoKmL?.takeIf { it.isFinite() }?.coerceAtMost(MAX_KM_L) else ler(dados)

    /** Leitura do próprio celular ou calculada (GPS, consumo, A/F real): nunca vem da ECU, então nunca é "não suportada". */
    val foraDaEcu: Boolean get() = this == CONSUMO_KML || this == VELOCIDADE_GPS || this == RELACAO_AF

    /** Todas as chaves que alimentam o mostrador, na ordem de preferência. */
    val comandos: List<String> get() = listOf(comando) + alternativos

    /** Sensor de estado (P/R/N/D, 4x2/4x4...): o texto vem de [rotulos]. */
    val ehEstado: Boolean get() = rotulos.isNotEmpty()

    /** Texto de exibição: o rótulo do estado (`D`, `4x4 (H4)`) ou o número já convertido; "—" sem leitura. */
    fun texto(valor: Double?, conversor: ConversorUnidades): String {
        if (valor == null || !valor.isFinite()) return if (this == TRACAO_4X4 || this == VSC) NAO_DISPONIVEL else SEM_LEITURA
        DefinicaoPid.rotuloDe(rotulos, valor)?.let { return it }
        val v = exibir(valor, conversor)
        val numero = v.valor.comoNumero(v.casas)
        return if (comSinal && v.valor > 0 && !numero.startsWith("+")) "+$numero" else numero
    }

    /**
     * Valor na unidade de exibição do [conversor]. O rail sai em bar no métrico (kPa teria 6
     * dígitos) e em psi no imperial ou com psi escolhido.
     */
    fun exibir(valor: Double, conversor: ConversorUnidades): ValorExibicao {
        if (this == RAIL) {
            val unidade = if (conversor.pressao == UnidadePressao.PSI) UnidadePressao.PSI else UnidadePressao.BAR
            return ValorExibicao(unidade.deKpa(valor), unidade.simbolo, 0)
        }
        return conversor.converter(valor, unidadeCatalogo, casas)
    }

    /** Símbolo de exibição (`km/h` ou `mph`, `°C` ou `°F`, `bar`...). */
    fun unidade(conversor: ConversorUnidades): String = exibir(0.0, conversor).unidade

    /** Escala do mostrador na unidade de exibição; sem uma específica, 0–100. */
    fun escala(conversor: ConversorUnidades): EscalaGauge = escalas[unidade(conversor)] ?: escalas.values.firstOrNull() ?: EscalaGauge()

    /** `TURBO/MAP bar`, `MOTOR °F`; o RPM dispensa a unidade. */
    fun rotuloHud(conversor: ConversorUnidades): String {
        val unidade = unidade(conversor)
        return if (unidade.isBlank() || unidade.equals(rotuloHud, ignoreCase = true)) rotuloHud else "$rotuloHud $unidade"
    }

    /** Leitura do sensor no instantâneo [dados] (ainda na unidade do catálogo): o principal ou o 1º alternativo lido. */
    fun ler(dados: ValoresSensores?): Double? {
        if (dados == null) return null
        return comandos.firstNotNullOfOrNull { c -> dados[c]?.takeIf { it.isFinite() } }
    }

    companion object {
        /** Rótulo dinâmico do mostrador de marcha pelo máximo de marchas do [cambio] (`relacoes`). */
        fun tituloMarcha(cambio: PerfilCambio?): String {
            val max = cambio?.takeIf { it.tipo == TipoCambio.AUTOMATICO }?.relacoes?.size ?: 0
            return if (max in 5..6) "Marcha (1ª–${max}ª)" else "Marcha"
        }


        /** Teto do km/L exibido (descida/banguela dispara o valor). */
        const val MAX_KM_L = 99.9

        const val SEM_LEITURA = "—"

        /** 4x4 (7C0) e VSC (7B0) não respondem na SW4 1KD-FTV + A750F. */
        const val NAO_DISPONIVEL = "N/D"

        /** `CONSUMO` (L/h, até a 1.7.0) foi unificado em [CONSUMO_KML]: configurações antigas migram sozinhas. */
        fun deCodigo(codigo: String?): SensorPainel? {
            val c = codigo?.trim()
            if (c.equals("CONSUMO", ignoreCase = true)) return CONSUMO_KML
            return entries.firstOrNull { it.name.equals(c, ignoreCase = true) }
        }

        /** Sensores sem duplicatas na ordem de [GrupoSensor], para os chips do HUD e do Painel. */
        fun agrupados(naoSuportados: Set<SensorPainel> = emptySet()): List<Pair<String, List<SensorPainel>>> {
            val suportados = GrupoSensor.entries.mapNotNull { g ->
                entries.filter { it.grupo == g && it !in naoSuportados }.takeIf { it.isNotEmpty() }?.let { g.titulo to it }
            }
            val fora = entries.filter { it in naoSuportados }
            return if (fora.isEmpty()) suportados else suportados + (TITULO_NAO_SUPORTADOS to fora)
        }

        const val TITULO_NAO_SUPORTADOS = "Não suportados neste veículo"

        fun doComando(comando: String): SensorPainel? =
            entries.firstOrNull { s -> s.comandos.any { it.equals(comando, ignoreCase = true) } }
    }
}

/** Seções curtas dos chips de sensores (HUD e Personalizar Painel). */
enum class GrupoSensor(val titulo: String) {
    PRINCIPAL("Principal"),
    TEMPERATURAS("Temperaturas"),
    INJECAO("Injeção & Diesel"),
    MISTURA("Mistura & Flex"),
    CAMBIO_CHASSI("Câmbio & Chassi"),
    ELETRICA("Elétrica"),
}
