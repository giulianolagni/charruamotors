package com.charruamotors.domain.faixas

import com.charruamotors.domain.model.TipoCombustivel
import java.text.Normalizer

/**
 * Perfis prontos de veículo: dados do cadastro, endereçamento da rede (protocolo ELM327, ECU do motor
 * e TCM do câmbio), limites de alerta e as [faixas] mínimas/máximas esperadas. Escolhidos no
 * cadastro ("Aplicar preset") e reconhecidos depois pela marca/modelo/ano ([detectar]).
 */
enum class PresetVeiculo(
    val titulo: String,
    val marca: String,
    val modelo: String,
    val ano: Int,
    val combustivel: TipoCombustivel,
    /** Código do motor (`1KD-FTV`, `LE2`). */
    val motor: String,
    /** Comando de protocolo do ELM327 e a descrição (`ATSP6` = ISO 15765-4 CAN 11 bits 500 kbps). */
    val protocolo: String,
    val descricaoProtocolo: String,
    val ecuMotor: String,
    val ecuCambio: String,
    /** Serviço de diagnóstico da montadora usado além do OBD2 (`21` KWP Toyota, `22` UDS GM). */
    val servicoMontadora: String,
    val limiteAguaC: Int,
    val limiteAtfC: Int,
    val tensaoMinV: Double,
    val faixas: FaixasVeiculo,
) {
    SW4_2013(
        titulo = "Toyota Hilux SW4 3.0 D-4D 2013 (1KD-FTV)",
        marca = "Toyota",
        modelo = "Hilux SW4 3.0 D-4D",
        ano = 2013,
        combustivel = TipoCombustivel.DIESEL,
        motor = "1KD-FTV",
        protocolo = "ATSP6",
        descricaoProtocolo = "ISO 15765-4 CAN 11 bits / 500 kbps (M-OBD, KWP modo 21)",
        ecuMotor = "7E0",
        ecuCambio = "7E1",
        servicoMontadora = "21",
        limiteAguaC = 98,
        limiteAtfC = 105,
        tensaoMinV = 13.4,
        faixas = FaixasVeiculo(
            "Toyota Hilux SW4 3.0 D-4D 2013 (1KD-FTV)",
            listOf(
                FaixaEsperada(ParametroFaixa.RPM_LENTA, 650.0, 800.0),
                FaixaEsperada(ParametroFaixa.RPM_MAXIMO, null, 4_200.0),
                FaixaEsperada(ParametroFaixa.TEMP_AGUA, 80.0, 98.0),
                FaixaEsperada(ParametroFaixa.TEMP_ATF, 60.0, 105.0),
                FaixaEsperada(ParametroFaixa.TENSAO, 13.4, 14.6),
                FaixaEsperada(ParametroFaixa.RAIL, 280.0, 1_800.0),
                FaixaEsperada(ParametroFaixa.RAIL_LENTA, 280.0, 600.0),
                // Em altitude a marcha lenta fica em 73–75 kPa: 95 dava falso alerta vermelho.
                FaixaEsperada(ParametroFaixa.MAP_TURBO, 60.0, 215.0),
                FaixaEsperada(ParametroFaixa.BICOS, -3.0, 3.0),
                FaixaEsperada(ParametroFaixa.CARGA_MOTOR, 0.0, 100.0),
                FaixaEsperada(ParametroFaixa.TEMP_ADMISSAO, 10.0, 65.0),
                FaixaEsperada(ParametroFaixa.MAF, 10.0, 170.0),
                FaixaEsperada(ParametroFaixa.BORBOLETA, 0.0, 100.0),
                FaixaEsperada(ParametroFaixa.PRESSAO_BARO, 85.0, 105.0),
                FaixaEsperada(ParametroFaixa.TENSAO_ECU, 13.4, 14.6),
            ),
        ),
    ),
    CRUZE_2018(
        titulo = "Chevrolet Cruze 1.4 Turbo Flex 2018 (LE2)",
        marca = "Chevrolet",
        modelo = "Cruze 1.4 Turbo Flex",
        ano = 2018,
        combustivel = TipoCombustivel.FLEX,
        motor = "LE2",
        protocolo = "ATSP6",
        descricaoProtocolo = "ISO 15765-4 CAN 11 bits / 500 kbps (UDS ISO 14229)",
        ecuMotor = "7E0",
        ecuCambio = "7E1",
        servicoMontadora = "22",
        limiteAguaC = 108,
        limiteAtfC = 110,
        tensaoMinV = 12.8,
        faixas = FaixasVeiculo(
            "Chevrolet Cruze 1.4 Turbo Flex 2018 (LE2)",
            listOf(
                FaixaEsperada(ParametroFaixa.RPM_LENTA, 600.0, 800.0),
                FaixaEsperada(ParametroFaixa.RPM_MAXIMO, null, 6_000.0),
                FaixaEsperada(ParametroFaixa.TEMP_AGUA, 85.0, 108.0),
                FaixaEsperada(ParametroFaixa.TEMP_ATF, 50.0, 110.0),
                // Regulagem variável de tensão (RVC) da GM: em cruzeiro o alternador pode baixar a ~12,8 V.
                FaixaEsperada(ParametroFaixa.TENSAO, 12.8, 14.8),
                // Injeção direta (GDI): ~35 bar em marcha lenta a ~200 bar em plena carga.
                FaixaEsperada(ParametroFaixa.RAIL, 30.0, 200.0),
                FaixaEsperada(ParametroFaixa.RAIL_LENTA, 30.0, 60.0),
                FaixaEsperada(ParametroFaixa.MAP_TURBO, 25.0, 240.0),
                FaixaEsperada(ParametroFaixa.STFT, -10.0, 10.0),
                FaixaEsperada(ParametroFaixa.LTFT, -10.0, 10.0),
            ),
        ),
    ),
    ;

    /** Linhas técnicas da ficha (rede e endereçamento). */
    val linhasRede: List<String>
        get() = listOf(
            "Motor $motor · ${combustivelTexto()}",
            "$protocolo · $descricaoProtocolo",
            "ECU do motor $ecuMotor · TCM do câmbio $ecuCambio · serviço $servicoMontadora",
        )

    private fun combustivelTexto() = when (combustivel) {
        TipoCombustivel.DIESEL -> "Diesel Common Rail"
        TipoCombustivel.FLEX -> "Auto Flex (etanol pelo 0152)"
        TipoCombustivel.GASOLINA -> "Gasolina"
        TipoCombustivel.ETANOL -> "Etanol"
        TipoCombustivel.GNV -> "GNV"
    }

    companion object {
        /**
         * Reconhece o preset pelo cadastro: Toyota Hilux/SW4 com motor 3.0 (ou 1KD, ou ano 2005–2015)
         * e Chevrolet/GM Cruze 1.4 turbo (ou LE2, ou ano 2017+). Null = sem preset.
         */
        fun detectar(marca: String?, modelo: String?, ano: Int?): PresetVeiculo? {
            val m = normalizar(marca)
            val mod = normalizar(modelo)
            val toyota = "toyota" in m
            val gm = marcaGm(marca)
            return when {
                toyota && ("sw4" in mod || "hilux" in mod) &&
                    (Regex("3[.,]0").containsMatchIn(mod) || "1kd" in mod || (ano != null && ano in 2005..2015)) -> SW4_2013
                gm && "cruze" in mod &&
                    (Regex("1[.,]4").containsMatchIn(mod) || "le2" in mod || "turbo" in mod || (ano != null && ano >= 2017)) -> CRUZE_2018
                else -> null
            }
        }

        /** Marca da GM: `Chevrolet`, `Chevrolet Cruze`, `GM`, `Chevrolet / GM`, `General Motors`... */
        fun marcaGm(marca: String?): Boolean {
            val m = normalizar(marca)
            return MARCAS_GM.any { it == m || m.startsWith("$it ") || m.endsWith(" $it") }
        }

        private val MARCAS_GM = listOf("chevrolet", "chevy", "gm", "general motors")

        private fun normalizar(texto: String?): String =
            Normalizer.normalize(texto.orEmpty().trim().lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("\\s+"), " ")
    }
}
