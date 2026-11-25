package com.charruamotors.domain.perfis

import com.charruamotors.domain.faixas.PresetVeiculo

/** Como o perfil em uso na sessão foi escolhido (cabeçalho do relatório `.txt`). */
enum class OrigemPerfil(val rotulo: String) {
    /** Pelo cadastro do veículo (perfil exato ou o genérico da motorização cadastrada). */
    MANUAL("Manual"),

    /** Pelo chassi: WMI + VDS do VIN casaram com `padroesVin` do catálogo. */
    VDS_VIN("VDS do VIN"),

    /** Pela impressão digital da ECU: combustível `0151`, PID `0123` e pico de MAP. */
    SONDAGEM_ECU("Sondagem ECU"),
}

/** Classe do combustível para a escolha do perfil genérico. */
enum class ClasseCombustivel { DIESEL, GASOLINA_FLEX, OUTRO }

/**
 * Tipo de combustível do PID `0151` (SAE J1979, tabela "Fuel Type Coding").
 *
 * Observação: no padrão o `08` é *elétrico*; ECUs flex brasileiras costumam responder `01`
 * (gasolina), `03` (etanol) ou `09`/`0B` (bicombustível gasolina/etanol).
 */
enum class CombustivelEcu(val rotulo: String, val classe: ClasseCombustivel) {
    GASOLINA("Gasolina", ClasseCombustivel.GASOLINA_FLEX),
    ETANOL("Etanol", ClasseCombustivel.GASOLINA_FLEX),
    FLEX("Flex (gasolina/etanol)", ClasseCombustivel.GASOLINA_FLEX),
    HIBRIDO("Híbrido (gasolina/etanol)", ClasseCombustivel.GASOLINA_FLEX),
    DIESEL("Diesel", ClasseCombustivel.DIESEL),
    HIBRIDO_DIESEL("Híbrido diesel", ClasseCombustivel.DIESEL),
    ELETRICO("Elétrico", ClasseCombustivel.OUTRO),
    GAS("Gás (GNV/GLP/propano)", ClasseCombustivel.OUTRO),
    OUTRO("Outro combustível", ClasseCombustivel.OUTRO),
    DESCONHECIDO("Desconhecido", ClasseCombustivel.OUTRO),
    ;

    companion object {
        /** Decodifica o byte do `0151`; valores fora da tabela (`00`, `18`–`FF`) viram [DESCONHECIDO]. */
        fun deByte(codigo: Int): CombustivelEcu = when (codigo) {
            0x01 -> GASOLINA
            0x03 -> ETANOL
            0x09, 0x0B -> FLEX
            0x11, 0x12, 0x14, 0x15, 0x16 -> HIBRIDO
            0x04, 0x17 -> DIESEL
            0x13 -> HIBRIDO_DIESEL
            0x08 -> ELETRICO
            0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E -> GAS
            0x02, 0x0A, 0x0F, 0x10 -> OUTRO
            else -> DESCONHECIDO
        }
    }
}

/**
 * "Impressão digital" lida da ECU na conexão. Todo campo null = a ECU não respondeu (`NO DATA`,
 * `7F`, timeout) ou a sondagem não foi feita; nada aqui é obrigatório para a sessão seguir.
 */
data class ImpressaoEcu(
    /** Byte do `0151` (null = sem resposta). */
    val codigoCombustivel: Int? = null,
    /** ID de Calibração (`0904`), texto ASCII limpo (null = sem resposta). */
    val calid: String? = null,
    /** TCM respondeu em `7E1`/`7E2`; null = não sondado (perfil já exato) ou sem sondagem. */
    val tcmDetectado: Boolean? = null,
    /** Cabeçalho do TCM que respondeu (`7E1`/`7E2`). */
    val tcmCabecalho: String? = null,
    /** O `0904` foi pulado de propósito na conexão (ELM327 v2.1); não é "sem resposta". */
    val calidNaoSondado: Boolean = false,
) {
    val combustivel: CombustivelEcu? get() = codigoCombustivel?.let(CombustivelEcu::deByte)

    /** `Diesel (04)`, ou null sem resposta. */
    val descricaoCombustivel: String?
        get() = codigoCombustivel?.let { "${CombustivelEcu.deByte(it).rotulo} (${"%02X".format(it)})" }

    val vazia: Boolean get() = copy(calidNaoSondado = false) == VAZIA

    companion object {
        val VAZIA = ImpressaoEcu()
    }
}

/** Perfil escolhido automaticamente, com a origem e o motivo (para o relatório e o aviso da aba OBD). */
data class PerfilIdentificado(
    val preset: PresetVeiculo,
    val origem: OrigemPerfil,
    val motivo: String,
)

/**
 * Cascata de identificação do veículo (pura, JVM): `Match VDS → Sondagem ECU → Fallback genérico`.
 * Só age quando o cadastro está num perfil genérico: perfil exato do cadastro ([OrigemPerfil.MANUAL])
 * nunca é trocado. Sem informação nenhuma da ECU, devolve null e o cadastro segue valendo.
 */
object IdentificadorPerfil {

    /** MAP acima disto (kPa) em algum instante da sessão indica motor turbo (pressão acima da atmosférica). */
    const val MAP_TURBO_KPA = 115.0

    const val ID_DIESEL = "generico_diesel_cr"
    const val ID_FLEX_TURBO = "generico_flex_turbo"
    const val ID_FLEX_ASPIRADO = "generico_flex_aspirado"

    /** PID de pressão do trilho de combustível absoluto (rail de injeção direta GDI/diesel). */
    const val PID_RAIL = "0123"

    /** Perfil exato pelo chassi (WMI + VDS em `padroesVin`, respeitando os anos do perfil). */
    fun porVds(catalogo: CatalogoPerfis, vin: VinDecodificado?): PerfilIdentificado? {
        val preset = catalogo.detectarPorVin(vin) ?: return null
        val v = vin ?: return null
        return PerfilIdentificado(
            preset,
            OrigemPerfil.VDS_VIN,
            "VIN ${v.wmiVds}${v.anoModelo?.let { " ($it)" }.orEmpty()} casou com ${preset.titulo}",
        )
    }

    /**
     * Fallback genérico pela impressão da ECU: diesel (`0151`) → `generico_diesel_cr`; gasolina/flex
     * com `0123` ou pico de MAP > [MAP_TURBO_KPA] → `generico_flex_turbo`; senão `generico_flex_aspirado`.
     * Null sem o `0151` (nada confiável para trocar o cadastro) ou se o catálogo não tiver o genérico.
     */
    fun porSondagem(
        catalogo: CatalogoPerfis,
        impressao: ImpressaoEcu,
        pidsSuportados: Set<String>,
        mapPicoKpa: Double?,
    ): PerfilIdentificado? {
        val combustivel = impressao.combustivel ?: return null
        val railGdi = PID_RAIL in pidsSuportados
        val turbo = mapPicoKpa != null && mapPicoKpa > MAP_TURBO_KPA
        val (id, motivo) = when {
            combustivel.classe == ClasseCombustivel.DIESEL -> ID_DIESEL to "0151 = ${combustivel.rotulo}"
            combustivel.classe == ClasseCombustivel.GASOLINA_FLEX && railGdi ->
                ID_FLEX_TURBO to "0151 = ${combustivel.rotulo} e a ECU suporta o 0123 (rail GDI)"
            combustivel.classe == ClasseCombustivel.GASOLINA_FLEX && turbo ->
                ID_FLEX_TURBO to "0151 = ${combustivel.rotulo} e MAP chegou a ${"%.0f".format(mapPicoKpa)} kPa"
            else -> ID_FLEX_ASPIRADO to "0151 = ${combustivel.rotulo}, sem rail GDI nem pressão acima da atmosférica"
        }
        val preset = catalogo[id] ?: return null
        return PerfilIdentificado(preset, OrigemPerfil.SONDAGEM_ECU, motivo)
    }

    /**
     * Cascata completa. [cadastroGenerico] = o cadastro caiu num perfil genérico (ou não há veículo
     * vinculado); com perfil exato do cadastro, devolve null (a origem fica [OrigemPerfil.MANUAL]).
     */
    fun resolver(
        catalogo: CatalogoPerfis,
        cadastroGenerico: Boolean,
        vin: VinDecodificado?,
        impressao: ImpressaoEcu,
        pidsSuportados: Set<String>,
        mapPicoKpa: Double? = null,
    ): PerfilIdentificado? {
        if (!cadastroGenerico) return null
        return porVds(catalogo, vin) ?: porSondagem(catalogo, impressao, pidsSuportados, mapPicoKpa)
    }

    /**
     * Refino tardio: o perfil em uso é o genérico aspirado e o MAP passou de [MAP_TURBO_KPA] na
     * telemetria → promove ao `generico_flex_turbo`. Null = nada a mudar.
     */
    fun refinarPorMap(catalogo: CatalogoPerfis, atual: PresetVeiculo?, mapPicoKpa: Double?): PerfilIdentificado? {
        if (atual?.id != ID_FLEX_ASPIRADO || mapPicoKpa == null || mapPicoKpa <= MAP_TURBO_KPA) return null
        val turbo = catalogo[ID_FLEX_TURBO] ?: return null
        return PerfilIdentificado(turbo, OrigemPerfil.SONDAGEM_ECU, "MAP chegou a ${"%.0f".format(mapPicoKpa)} kPa (turbo)")
    }
}
