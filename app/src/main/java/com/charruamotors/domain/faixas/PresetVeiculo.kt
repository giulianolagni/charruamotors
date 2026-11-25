package com.charruamotors.domain.faixas

import com.charruamotors.domain.calculo.LimitesProtecao
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.domain.perfis.CarregadorPerfis
import com.charruamotors.domain.perfis.CatalogoPerfis
import com.charruamotors.domain.perfis.PerfilCambio
import com.charruamotors.domain.perfis.PerfilMotor
import com.charruamotors.domain.perfis.PerfilVeiculo
import com.charruamotors.domain.perfis.StatusPerfil
import com.charruamotors.domain.perfis.TipoCambio
import java.text.Normalizer

/**
 * Perfil pronto de veículo (v2.2: montado do catálogo modular `assets/perfis/`): a entrada de
 * `veiculos.json` com o motor (`motores/<id>.json`) e o câmbio (`cambios/<id>.json`). Traz os dados do
 * cadastro, o endereçamento da rede (protocolo ELM327, ECU do motor e TCM do câmbio), os limites
 * de alerta e as [faixas] esperadas. Escolhido no cadastro ("Presets") e reconhecido depois pela
 * marca/modelo/ano ([detectar]). Nada vai para o banco: o veículo salvo continua só com marca,
 * modelo, ano e limites, e o perfil é reconhecido de novo a cada uso.
 */
class PresetVeiculo(
    val perfil: PerfilVeiculo,
    val perfilMotor: PerfilMotor,
    val perfilCambio: PerfilCambio,
) {
    val id: String get() = perfil.id
    val titulo: String get() = perfil.titulo
    val marca: String get() = perfil.montadora
    val modelo: String get() = perfil.modelo

    /** Ano preenchido ao aplicar o preset; null nos genéricos (mantém o do cadastro). */
    val ano: Int? get() = perfil.anoReferencia
    val status: StatusPerfil get() = perfil.status
    val generico: Boolean get() = perfil.generico
    val layoutSugerido: String get() = perfil.layoutSugerido

    val combustivel: TipoCombustivel get() = perfilMotor.combustivel

    /** Código do motor (`1KD-FTV`, `LE2`). */
    val motor: String get() = perfilMotor.codigo

    /** Comando de protocolo do ELM327 e a descrição (`ATSP6` = ISO 15765-4 CAN 11 bits 500 kbps). */
    val protocolo: String get() = perfilMotor.protocolo
    val descricaoProtocolo: String get() = perfilMotor.descricaoProtocolo
    val ecuMotor: String get() = perfilMotor.cabecalho

    /** TCM principal do câmbio (`7E1`); vazio = sem TCM (manual). */
    val ecuCambio: String get() = perfilCambio.cabecalhos.firstOrNull().orEmpty()

    /** Serviço de diagnóstico da montadora usado além do OBD2 (`21` KWP Toyota, `22` UDS); null = só modo 01. */
    val servicoMontadora: String? get() = perfilMotor.servicoExtra
    val limiteAguaC: Int get() = perfilMotor.aguaMaxC
    val limiteAtfC: Int get() = perfilCambio.limiteAtfC
    val tensaoMinV: Double get() = perfilMotor.tensaoMinV

    /** Faixas do motor e do câmbio, na ordem de [ParametroFaixa]. */
    val faixas: FaixasVeiculo = FaixasVeiculo(
        perfil.titulo,
        (perfilMotor.faixas + perfilCambio.faixas).sortedBy { it.parametro.ordinal },
    )

    /** Linhas técnicas da ficha (rede e endereçamento). */
    val linhasRede: List<String>
        get() = listOf(
            "Motor $motor · ${combustivelTexto()}",
            "$protocolo · $descricaoProtocolo",
            "ECU do motor $ecuMotor · ${descricaoCambio()} · ${servicoMontadora?.let { "serviço $it" } ?: "OBD2 modo 01"}",
        )

    private fun descricaoCambio(): String = when {
        perfilCambio.tipo == TipoCambio.MANUAL || !perfilCambio.temTcm -> "câmbio sem TCM"
        else -> "TCM do câmbio ${perfilCambio.cabecalhos.joinToString("/")}"
    }

    private fun combustivelTexto() = when (combustivel) {
        TipoCombustivel.DIESEL -> "Diesel Common Rail"
        TipoCombustivel.FLEX -> "Auto Flex (etanol pelo 0152)"
        TipoCombustivel.GASOLINA -> "Gasolina"
        TipoCombustivel.ETANOL -> "Etanol"
        TipoCombustivel.GNV -> "GNV"
    }

    override fun equals(other: Any?): Boolean = other is PresetVeiculo && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "PresetVeiculo($id)"

    companion object {
        const val ID_SW4_2013 = "toyota_sw4_1kd"
        const val ID_CRUZE_2018 = "chevrolet_cruze_14t"

        private val catalogo: CatalogoPerfis get() = CarregadorPerfis.catalogo

        /** Todos os presets do catálogo, na ordem de `veiculos.json` (genéricos no fim). */
        val entries: List<PresetVeiculo> get() = catalogo.presets

        /** Toyota Hilux SW4 3.0 D-4D 2013 (1KD-FTV + A750F), validada em carro. */
        val SW4_2013: PresetVeiculo get() = porId(ID_SW4_2013)

        /** Chevrolet Cruze 1.4 Turbo Flex 2018 (LE2 + 6T35/6T40). */
        val CRUZE_2018: PresetVeiculo get() = porId(ID_CRUZE_2018)

        private fun porId(id: String): PresetVeiculo =
            catalogo[id] ?: throw IllegalStateException("Perfil $id ausente do catálogo (${catalogo.erros.joinToString()})")

        /**
         * Reconhece o perfil exato pelo cadastro (regras de `deteccao` em `veiculos.json`): Toyota
         * Hilux/SW4 com motor 3.0 (ou 1KD, ou ano 2005–2015), Chevrolet/GM Cruze 1.4 turbo (ou LE2,
         * ou ano 2017+), Onix/Tracker Turbo, VW TSI, Fiat T200 e Honda. Null = sem perfil exato.
         */
        fun detectar(marca: String?, modelo: String?, ano: Int?): PresetVeiculo? = catalogo.detectar(marca, modelo, ano)

        /** Perfil genérico pela motorização/combustível (segundo nível da cascata). */
        fun generico(combustivel: TipoCombustivel?, modelo: String?): PresetVeiculo? = catalogo.genericoPara(combustivel, modelo)

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

/** Nível da cascata de fallback em que o veículo caiu. */
enum class NivelPerfil(val descricao: String) {
    /** Perfil exato do catálogo (SW4, Cruze, VW TSI...). */
    EXATO("perfil exato"),

    /** Perfil genérico pela motorização/combustível, com os limites do cadastro. */
    GENERICO("perfil genérico pela motorização"),

    /** Sem catálogo: só os limites do cadastro sobre o OBD2 modo 01 padrão. */
    OBD2_PADRAO("OBD2 modo 01 padrão"),
}

/** Resultado da cascata: perfil exato → genérico pela motorização → OBD2 modo 01 padrão. */
data class ResolucaoPerfil(val nivel: NivelPerfil, val preset: PresetVeiculo?, val faixas: FaixasVeiculo) {
    /** O veículo ainda está num perfil genérico (vale sugerir um exato, ex.: pelo VIN)? */
    val generico: Boolean get() = nivel != NivelPerfil.EXATO

    companion object {
        /**
         * Cascata de fallback do cadastro: [PresetVeiculo.detectar] → [PresetVeiculo.generico]
         * (faixas do motor genérico com os limites de água, ATF e alternador do cadastro) →
         * [FaixasVeiculo.genericas] (catálogo indisponível).
         */
        fun resolver(
            marca: String?,
            modelo: String?,
            ano: Int?,
            combustivel: TipoCombustivel?,
            limites: LimitesProtecao = LimitesProtecao(),
        ): ResolucaoPerfil {
            PresetVeiculo.detectar(marca, modelo, ano)?.let { return ResolucaoPerfil(NivelPerfil.EXATO, it, it.faixas) }
            PresetVeiculo.generico(combustivel, modelo)?.let { g ->
                return ResolucaoPerfil(NivelPerfil.GENERICO, g, FaixasVeiculo.comLimitesDoCadastro(g, limites))
            }
            return ResolucaoPerfil(NivelPerfil.OBD2_PADRAO, null, FaixasVeiculo.genericas(combustivel, limites))
        }

        fun deVeiculo(v: Veiculo): ResolucaoPerfil =
            resolver(v.marca, v.modelo, v.ano, v.tipoCombustivel, LimitesProtecao.deVeiculo(v))
    }
}
