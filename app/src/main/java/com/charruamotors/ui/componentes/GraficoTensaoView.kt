package com.charruamotors.ui.componentes

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.charruamotors.R
import com.google.android.material.color.MaterialColors
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/** Amostra do gráfico de tensão; [volts] null = amostra perdida ou `LV RESET` (marcada no eixo). */
data class PontoTensao(val instanteMs: Long, val volts: Double?, val lvReset: Boolean = false)

/** Linha de referência horizontal (limite de uma faixa do teste), com rótulo e cor própria. */
data class ReferenciaTensao(val volts: Double, val cor: Int)

/**
 * Gráfico da tensão da bateria em tempo real (Teste de Bateria e Alternador), em `Canvas`: janela
 * deslizante de [janelaMs], escala vertical de 9–15 V (ampliada se a tensão sair dela), linhas de
 * referência das faixas da etapa, o vale mínimo marcado e os `LV RESET` como traços no eixo.
 */
class GraficoTensaoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var pontos: List<PontoTensao> = emptyList()
    private var referencias: List<ReferenciaTensao> = emptyList()
    private var vale: Double? = null
    private var janelaMs: Long = JANELA_PADRAO_MS

    private val densidade = resources.displayMetrics.density
    private val locale = Locale.forLanguageTag("pt-BR")

    private val corCurva = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    private val corTexto = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val corGrade = ColorUtils.setAlphaComponent(
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface), 0x22,
    )
    private val corAlerta = ContextCompat.getColor(context, R.color.veredito_ruim)

    private val pincelGrade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = densidade
        color = corGrade
    }
    private val pincelReferencia = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * densidade
        pathEffect = DashPathEffect(floatArrayOf(6 * densidade, 4 * densidade), 0f)
    }
    private val pincelCurva = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * densidade
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = corCurva
    }
    private val pincelRotulo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        color = corTexto
    }
    private val pincelMarcador = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val caminho = Path()

    private fun sp(valor: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, valor, resources.displayMetrics)

    init {
        contentDescription = context.getString(R.string.bateria_grafico_descricao)
    }

    fun definir(
        novos: List<PontoTensao>,
        novasReferencias: List<ReferenciaTensao> = emptyList(),
        valeMinimo: Double? = null,
        janela: Long = JANELA_PADRAO_MS,
    ) {
        pontos = novos
        referencias = novasReferencias
        vale = valeMinimo
        janelaMs = janela
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val altura = resolveSize(resources.getDimensionPixelSize(R.dimen.altura_grafico_tensao), heightMeasureSpec)
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), altura)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val esquerda = paddingLeft + MARGEM_ESQUERDA_DP * densidade
        val direita = width - paddingRight - MARGEM_DIREITA_DP * densidade
        val topo = paddingTop + 8 * densidade
        val base = height - paddingBottom - MARGEM_BASE_DP * densidade
        if (direita <= esquerda || base <= topo) return

        val validos = pontos.mapNotNull { it.volts }
        val minimo = floor(minOf(MIN_ESCALA, (validos.minOrNull() ?: MIN_ESCALA) - 0.3))
        val maximo = ceil(maxOf(MAX_ESCALA, (validos.maxOrNull() ?: MAX_ESCALA) + 0.3))
        fun y(v: Double) = (base - (base - topo) * ((v - minimo) / (maximo - minimo))).toFloat()

        // Grade a cada 1 V com rótulos.
        pincelRotulo.textAlign = Paint.Align.RIGHT
        pincelRotulo.color = corTexto
        var v = minimo
        while (v <= maximo + 1e-9) {
            val yy = y(v)
            canvas.drawLine(esquerda, yy, direita, yy, pincelGrade)
            canvas.drawText(String.format(locale, "%.0f", v), esquerda - 4 * densidade, yy + pincelRotulo.textSize / 3, pincelRotulo)
            v += 1.0
        }

        // Referências das faixas da etapa (rótulo à direita).
        pincelRotulo.textAlign = Paint.Align.LEFT
        for (r in referencias) {
            val yy = y(r.volts)
            pincelReferencia.color = r.cor
            canvas.drawLine(esquerda, yy, direita, yy, pincelReferencia)
            pincelRotulo.color = r.cor
            canvas.drawText(String.format(locale, "%.1f", r.volts), direita + 3 * densidade, yy + pincelRotulo.textSize / 3, pincelRotulo)
        }

        // Eixo de tempo: −20 s … agora.
        pincelRotulo.color = corTexto
        val segundos = (janelaMs / 1000).toInt()
        pincelRotulo.textAlign = Paint.Align.LEFT
        canvas.drawText("−$segundos s", esquerda, base + pincelRotulo.textSize + 4 * densidade, pincelRotulo)
        pincelRotulo.textAlign = Paint.Align.RIGHT
        canvas.drawText(context.getString(R.string.obd_grafico_agora), direita, base + pincelRotulo.textSize + 4 * densidade, pincelRotulo)

        val agora = pontos.lastOrNull()?.instanteMs ?: return
        fun x(t: Long) = esquerda + (direita - esquerda) * (1f - (agora - t).toFloat() / janelaMs)

        caminho.reset()
        var aberto = false
        for (p in pontos) {
            val volts = p.volts
            if (volts == null) {
                aberto = false
                continue
            }
            if (aberto) caminho.lineTo(x(p.instanteMs), y(volts)) else caminho.moveTo(x(p.instanteMs), y(volts))
            aberto = true
        }
        canvas.save()
        canvas.clipRect(esquerda, topo - pincelCurva.strokeWidth, direita, base + pincelCurva.strokeWidth)
        canvas.drawPath(caminho, pincelCurva)
        canvas.restore()

        // LV RESET: traço vermelho na base do gráfico.
        pincelMarcador.color = corAlerta
        for (p in pontos) {
            if (!p.lvReset) continue
            val xx = x(p.instanteMs)
            if (xx < esquerda) continue
            canvas.drawRect(xx - 1.5f * densidade, base - 10 * densidade, xx + 1.5f * densidade, base, pincelMarcador)
        }

        // Vale mínimo da captura.
        val valeMin = vale ?: return
        val ponto = pontos.filter { it.volts == valeMin }.lastOrNull()
        val yy = y(valeMin)
        pincelReferencia.color = corAlerta
        canvas.drawLine(esquerda, yy, direita, yy, pincelReferencia)
        if (ponto != null && x(ponto.instanteMs) >= esquerda) {
            canvas.drawCircle(x(ponto.instanteMs), yy, 4 * densidade, pincelMarcador)
        }
        pincelRotulo.color = corAlerta
        pincelRotulo.textAlign = Paint.Align.LEFT
        val texto = context.getString(R.string.bateria_grafico_vale, String.format(locale, "%.1f V", valeMin))
        canvas.drawText(texto, esquerda + 4 * densidade, (yy + pincelRotulo.textSize + 3 * densidade).coerceAtMost(base - 2 * densidade), pincelRotulo)
    }

    companion object {
        const val JANELA_PADRAO_MS = 20_000L
        private const val MIN_ESCALA = 9.0
        private const val MAX_ESCALA = 15.0
        private const val MARGEM_ESQUERDA_DP = 26
        private const val MARGEM_DIREITA_DP = 30
        private const val MARGEM_BASE_DP = 18
    }
}
