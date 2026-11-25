package com.charruamotors.ui.painel

import android.view.View
import android.widget.LinearLayout
import com.charruamotors.R
import com.charruamotors.direcao.painel.EstiloMostrador
import com.charruamotors.direcao.painel.GradeMostradores
import com.charruamotors.direcao.painel.Mostrador
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.domain.perfis.PerfilCambio
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.ui.componentes.GaugeView

/**
 * Monta os relógios num [LinearLayout] vertical seguindo a [GradeMostradores] (2 colunas; Fita em
 * largura total). Usado pela aba Painel e pela prévia do Personalizar Painel.
 */
object MontadorMostradores {

    /** Recria as linhas e devolve os [GaugeView]s na ordem dos [mostradores]. */
    fun montar(
        container: LinearLayout,
        mostradores: List<Mostrador>,
        conversor: ConversorUnidades,
        cambio: PerfilCambio? = null,
    ): List<GaugeView> {
        container.removeAllViews()
        val contexto = container.context
        val espaco = contexto.resources.getDimensionPixelSize(R.dimen.espaco_minimo)
        val espacoFita = contexto.resources.getDimensionPixelSize(R.dimen.espaco_pequeno)
        val gauges = arrayOfNulls<GaugeView>(mostradores.size)
        for (linha in GradeMostradores.linhas(mostradores)) {
            val fileira = LinearLayout(contexto).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            for (i in linha) {
                val m = mostradores[i]
                val gauge = GaugeView(contexto).apply {
                    // Fita: mais respiro lateral para os rótulos das pontas da escala.
                    val lateral = if (m.estilo == EstiloMostrador.FITA) espacoFita else espaco
                    setPadding(lateral, espaco, lateral, espaco)
                }
                configurarGauge(gauge, m.sensor, m.estilo, conversor, cambio)
                fileira.addView(gauge, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                gauges[i] = gauge
            }
            // Relógio sozinho na linha (antes de uma Fita ou no fim): meia largura, como na grade.
            if (linha.size == 1 && mostradores[linha.first()].estilo != EstiloMostrador.FITA) {
                fileira.addView(View(contexto), LinearLayout.LayoutParams(0, 0, 1f))
            }
            container.addView(fileira, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        return gauges.filterNotNull()
    }

    /** Título, unidade, escala, casas e estilo do [sensor] num mostrador. */
    fun configurarGauge(
        gauge: GaugeView,
        sensor: SensorPainel,
        estilo: EstiloMostrador,
        conversor: ConversorUnidades,
        cambio: PerfilCambio? = null,
    ) {
        gauge.titulo = sensor.tituloPara(cambio)
        gauge.unidade = sensor.unidade(conversor)
        gauge.escala = sensor.escala(conversor)
        gauge.casasDecimais = sensor.exibir(0.0, conversor).casas
        gauge.rotulos = sensor.rotulos
        gauge.estilo = estilo
    }
}
