package com.charruamotors.ui.componentes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import com.charruamotors.R
import com.google.android.material.color.MaterialColors
import java.text.NumberFormat
import java.util.Locale

/**
 * Gráfico de linhas da telemetria ao vivo, desenhado com `Canvas`/`Paint` (sem bibliotecas):
 * janela deslizante de [JanelaTelemetria.DURACAO_PADRAO_MS] com duas curvas — **RPM** (eixo
 * esquerdo) e **MAP/turbo em kPa** (eixo direito) —, cada eixo com escala automática
 * ([EscalaAutomatica]), grade, rótulos de tempo e legenda com o valor atual.
 *
 * Trechos sem leitura (PID sem resposta, polling pausado) interrompem a curva em vez de ligar
 * pontos distantes com uma reta falsa. Cores do tema Material (claro/escuro).
 */
class TelemetriaChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var pontos: List<PontoTelemetria> = emptyList()
    private var duracaoMs: Long = JanelaTelemetria.DURACAO_PADRAO_MS

    private val densidade = resources.displayMetrics.density
    private val formato: NumberFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("pt-BR"))

    // ---------------------------------------------------------------- cores do tema
    private val corRpm = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    private val corMap = MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary)
    private val corTexto = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val corGrade = ColorUtils.setAlphaComponent(
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface), 0x22,
    )

    // ---------------------------------------------------------------- pincéis (criados uma vez)
    private val pincelGrade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * densidade
        color = corGrade
    }
    private val pincelRpm = pincelCurva(corRpm)
    private val pincelMap = pincelCurva(corMap)
    private val pincelRotulo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        color = corTexto
    }
    private val pincelLegenda = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f)
        color = corTexto
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val pincelMarcador = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val caminhoRpm = Path()
    private val caminhoMap = Path()

    private fun sp(valor: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, valor, resources.displayMetrics)

    private fun pincelCurva(cor: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * densidade
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = cor
    }

    init {
        contentDescription = context.getString(R.string.obd_grafico_vazio)
    }

    /** Atualiza as curvas com a janela atual (o ponto mais recente fica na borda direita). */
    fun definirPontos(novos: List<PontoTelemetria>, duracao: Long = JanelaTelemetria.DURACAO_PADRAO_MS) {
        pontos = novos
        duracaoMs = duracao
        val ultimo = novos.lastOrNull()
        contentDescription = if (ultimo == null) {
            context.getString(R.string.obd_grafico_vazio)
        } else {
            context.getString(
                R.string.obd_grafico_descricao,
                ultimo.rpm?.let { formato.format(it) } ?: "—",
                ultimo.mapKpa?.let { formato.format(it) } ?: "—",
            )
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val altura = resolveSize((ALTURA_PADRAO_DP * densidade).toInt(), heightMeasureSpec)
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), altura)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val esquerda = paddingLeft + MARGEM_EIXO_ESQUERDO_DP * densidade
        val direita = width - paddingRight - MARGEM_EIXO_DIREITO_DP * densidade
        val topo = paddingTop + MARGEM_LEGENDA_DP * densidade
        val base = height - paddingBottom - MARGEM_TEMPO_DP * densidade
        if (direita <= esquerda || base <= topo) return

        val maxRpm = EscalaAutomatica.maximo(pontos.mapNotNull { it.rpm }, MIN_ESCALA_RPM)
        val maxMap = EscalaAutomatica.maximo(pontos.mapNotNull { it.mapKpa }, MIN_ESCALA_MAP)
        val divisoes = EscalaAutomatica.DIVISOES_PADRAO

        // Grade horizontal + rótulos dos dois eixos.
        val alturaTexto = pincelRotulo.textSize
        for (i in 0..divisoes) {
            val y = base - (base - topo) * i / divisoes
            canvas.drawLine(esquerda, y, direita, y, pincelGrade)
            pincelRotulo.textAlign = Paint.Align.RIGHT
            pincelRotulo.color = corRpm
            canvas.drawText(formato.format(maxRpm * i / divisoes), esquerda - 4 * densidade, y + alturaTexto / 3, pincelRotulo)
            pincelRotulo.textAlign = Paint.Align.LEFT
            pincelRotulo.color = corMap
            canvas.drawText(formato.format(maxMap * i / divisoes), direita + 4 * densidade, y + alturaTexto / 3, pincelRotulo)
        }

        // Eixo de tempo: −60 s, −30 s, agora (linhas verticais discretas).
        pincelRotulo.color = corTexto
        val segundos = (duracaoMs / 1000).toInt()
        for (i in 0..2) {
            val x = esquerda + (direita - esquerda) * i / 2
            if (i == 1) canvas.drawLine(x, topo, x, base, pincelGrade)
            pincelRotulo.textAlign = when (i) {
                0 -> Paint.Align.LEFT
                2 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            val rotulo = if (i == 2) context.getString(R.string.obd_grafico_agora) else "−${segundos - segundos * i / 2} s"
            canvas.drawText(rotulo, x, base + alturaTexto + 4 * densidade, pincelRotulo)
        }

        // Curvas.
        val agora = pontos.lastOrNull()?.instanteMs
        if (agora != null) {
            montarCaminho(caminhoRpm, agora, esquerda, direita, topo, base) { it.rpm?.let { v -> EscalaAutomatica.fracao(v, maxRpm) } }
            montarCaminho(caminhoMap, agora, esquerda, direita, topo, base) { it.mapKpa?.let { v -> EscalaAutomatica.fracao(v, maxMap) } }
            canvas.save()
            canvas.clipRect(esquerda, topo - pincelRpm.strokeWidth, direita, base + pincelRpm.strokeWidth)
            canvas.drawPath(caminhoMap, pincelMap)
            canvas.drawPath(caminhoRpm, pincelRpm)
            canvas.restore()
        }

        desenharLegenda(canvas, esquerda, paddingTop + pincelLegenda.textSize)
    }

    /** Liga os pontos com valor; quebra a linha em lacunas de leitura ou de tempo. */
    private inline fun montarCaminho(
        caminho: Path,
        agora: Long,
        esquerda: Float,
        direita: Float,
        topo: Float,
        base: Float,
        fracao: (PontoTelemetria) -> Float?,
    ) {
        caminho.reset()
        var anterior: Long? = null
        for (p in pontos) {
            val f = fracao(p)
            if (f == null) {
                anterior = null
                continue
            }
            val x = esquerda + (direita - esquerda) * (1f - (agora - p.instanteMs).toFloat() / duracaoMs)
            val y = base - (base - topo) * f
            if (anterior == null || p.instanteMs - anterior > LACUNA_MAXIMA_MS) caminho.moveTo(x, y) else caminho.lineTo(x, y)
            anterior = p.instanteMs
        }
    }

    private fun desenharLegenda(canvas: Canvas, x: Float, y: Float) {
        val ultimo = pontos.lastOrNull()
        val raio = 4f * densidade
        var cursor = x
        val itens = listOf(
            corRpm to context.getString(R.string.obd_grafico_legenda_rpm, ultimo?.rpm?.let { formato.format(it) } ?: "—"),
            corMap to context.getString(R.string.obd_grafico_legenda_map, ultimo?.mapKpa?.let { formato.format(it) } ?: "—"),
        )
        pincelLegenda.textAlign = Paint.Align.LEFT
        for ((cor, texto) in itens) {
            pincelMarcador.color = cor
            canvas.drawCircle(cursor + raio, y - pincelLegenda.textSize / 3, raio, pincelMarcador)
            cursor += raio * 2 + 6 * densidade
            canvas.drawText(texto, cursor, y, pincelLegenda)
            cursor += pincelLegenda.measureText(texto) + 18 * densidade
        }
    }

    private companion object {
        const val ALTURA_PADRAO_DP = 200
        const val MARGEM_EIXO_ESQUERDO_DP = 40
        const val MARGEM_EIXO_DIREITO_DP = 32
        const val MARGEM_LEGENDA_DP = 30
        const val MARGEM_TEMPO_DP = 20
        const val MIN_ESCALA_RPM = 2_000.0
        const val MIN_ESCALA_MAP = 100.0
        /** Acima disso entre duas leituras, a curva é interrompida (polling parado). */
        const val LACUNA_MAXIMA_MS = 2_500L
    }
}
