package com.charruamotors.domain.calculo

import com.charruamotors.domain.perfis.PerfilCambio
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.pid.Pids

/**
 * v2.2: marcha estimada pelas razões `rpm / velocidade` do perfil de câmbio do catálogo
 * (`assets/perfis/cambios/<id>.json`), para os câmbios automáticos sem regra própria (ex.: Aisin de 6
 * marchas dos VW TSI). Mesma semântica do [EstimadorCambioGm6T]: publica em
 * [CambioA750f.CHAVE_MARCHA] só quando a ECU não traz marcha; parado (< 5 km/h) = [CambioA750f.MARCHA_PARADO].
 */
class EstimadorMarchaPerfil(private val cambio: PerfilCambio) {

    init {
        require(cambio.estimaMarcha) { "Câmbio ${cambio.id} sem relações rpm/velocidade" }
    }

    fun marchaExibida(rpm: Double?, velocidadeKmh: Double?): Int? {
        if (velocidadeKmh == null || !velocidadeKmh.isFinite()) return null
        if (velocidadeKmh < PerfilCambio.VELOCIDADE_MINIMA_KMH) return CambioA750f.MARCHA_PARADO
        return cambio.marchaPorRazao(rpm, velocidadeKmh)
    }

    fun aplicar(dados: DadosObd): DadosObd {
        if (dados.valores[CambioA750f.CHAVE_MARCHA]?.isFinite() == true) return dados
        val velocidade = dados.valores[Pids.VELOCIDADE]?.takeIf { it.isFinite() }
        val marcha = marchaExibida(dados.valores[Pids.RPM], velocidade) ?: return dados
        return DadosObd(dados.valores + (CambioA750f.CHAVE_MARCHA to marcha.toDouble()))
    }
}
