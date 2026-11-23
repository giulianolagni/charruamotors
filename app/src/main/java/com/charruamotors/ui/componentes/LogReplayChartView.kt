package com.charruamotors.ui.componentes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.ColorUtils
import com.charruamotors.R
import com.charruamotors.oficina.replay.AnaliseReplay
import com.charruamotors.oficina.replay.JanelaReplay
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Gráfico do Visualizador de Logs (Replay), em `Canvas` sem bibliotecas: até 3 séries, cada uma
 * na própria escala normalizada (mínimo/máximo da janela visível, rótulos na cor da série), linha
 * do tempo com **pinça para zoom** ([ScaleGestureDetector]) e **arraste horizontal**
 * ([GestureDetector]), **cursor vertical** ao tocar (toque longo e arrastar move o cursor) e toque
 * duplo para ver o log inteiro. Com mais pontos que pixels, cada coluna desenha o mínimo e o máximo
 * do trecho (picos não somem no zoom afastado). Trechos sem leitura (`NaN`) interrompem a curva.
 */
class LogReplayChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Uma curva: [valores] alinhados aos instantes do log. */
    class Serie(val rotulo: String, val valores: DoubleArray, val cor: Int)

    interface Ouvinte {
        fun aoMudarJanela(janela: JanelaReplay)
        fun aoMoverCursor(indice: Int?)
    }

    var ouvinte: Ouvinte? = null

    /** Formata um instante do log para os rótulos do eixo (relógio ou tempo relativo). */
    var formatarTempo: (Long) -> String = { "${it / 1000} s" }

    /** Formata um valor de série nos rótulos de escala. */
    var formatarValor: (Double) -> String = { "%.1f".format(it) }

    private var instantes = LongArray(0)
    private var series: List<Serie> = emptyList()
    private var total = JanelaReplay(0, 1)
    private var janela = JanelaReplay(0, 1)
    private var duracaoMinimaMs = AnaliseReplay.DURACAO_MINIMA_MS
    private var cursor: Int? = null

    private val densidade = resources.displayMetrics.density
    private val corTexto = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val corCursor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val corGrade = ColorUtils.setAlphaComponent(corCursor, 0x22)

    private val pincelGrade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = densidade
        color = corGrade
    }
    private val pincelCurva = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * densidade
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val pincelCursor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * densidade
        color = corCursor
    }
    private val pincelPonto = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pincelRotulo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        color = corTexto
    }
    private val caminho = Path()

    private var arrastandoCursor = false

    private fun sp(valor: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, valor, resources.displayMetrics)

    // ---------------------------------------------------------------- dados

    /** Novo log ou nova seleção de sensores. */
    fun definirDados(
        novosInstantes: LongArray,
        novasSeries: List<Serie>,
        novoTotal: JanelaReplay,
        novaJanela: JanelaReplay,
        duracaoMinima: Long,
        indiceCursor: Int?,
    ) {
        instantes = novosInstantes
        series = novasSeries
        total = novoTotal
        janela = AnaliseReplay.limitar(novaJanela, novoTotal)
        duracaoMinimaMs = duracaoMinima
        cursor = indiceCursor?.takeIf { it in instantes.indices }
        contentDescription = context.getString(R.string.replay_grafico_descricao, novasSeries.size)
        invalidate()
    }

    /** Janela definida de fora (botão "Ver tudo"). */
    fun definirJanela(nova: JanelaReplay) {
        janela = AnaliseReplay.limitar(nova, total)
        invalidate()
        ouvinte?.aoMudarJanela(janela)
    }

    // ---------------------------------------------------------------- área útil

    private val esquerda get() = paddingLeft + MARGEM_ESQUERDA_DP * densidade
    private val direita get() = width - paddingRight - MARGEM_DIREITA_DP * densidade
    private val topo get() = paddingTop + MARGEM_TOPO_DP * densidade
    private val base get() = height - paddingBottom - MARGEM_BASE_DP * densidade

    private fun xDe(t: Long): Float = esquerda + (direita - esquerda) * ((t - janela.inicioMs).toFloat() / janela.duracaoMs.coerceAtLeast(1))

    private fun tempoDe(x: Float): Long =
        janela.inicioMs + ((x - esquerda) / (direita - esquerda) * janela.duracaoMs).toDouble().roundToLong()

    // ---------------------------------------------------------------- gestos

    private val escala = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Zoom só na linha do tempo: a abertura horizontal dos dedos; dedos na vertical, o fator geral.
            val fator = if (detector.previousSpanX > SPAN_MINIMO_DP * densidade) {
                detector.currentSpanX / detector.previousSpanX
            } else {
                detector.scaleFactor
            }
            if (fator.isFinite() && fator > 0f) mudarJanela(AnaliseReplay.zoom(janela, total, fator.toDouble(), tempoDe(detector.focusX), duracaoMinimaMs))
            return true
        }
    })

    private val gestos = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (escala.isInProgress || arrastandoCursor || e2.pointerCount > 1) return false
            val msPorPixel = janela.duracaoMs / (direita - esquerda).coerceAtLeast(1f)
            mudarJanela(AnaliseReplay.deslocar(janela, total, (distanceX * msPorPixel).toDouble().roundToLong()))
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            moverCursor(e.x)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            mudarJanela(total)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            arrastandoCursor = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            moverCursor(e.x)
        }
    })

    @SuppressLint("ClickableViewAccessibility") // o gráfico não tem ação de clique; o cursor é só leitura
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (instantes.isEmpty()) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        escala.onTouchEvent(event)
        if (arrastandoCursor && event.actionMasked == MotionEvent.ACTION_MOVE) moverCursor(event.x)
        gestos.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            arrastandoCursor = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun mudarJanela(nova: JanelaReplay) {
        if (nova == janela) return
        janela = nova
        invalidate()
        ouvinte?.aoMudarJanela(nova)
    }

    private fun moverCursor(x: Float) {
        if (instantes.isEmpty()) return
        val indice = AnaliseReplay.indiceMaisProximo(instantes, tempoDe(x.coerceIn(esquerda, direita)))
        if (indice == cursor) return
        cursor = indice
        invalidate()
        ouvinte?.aoMoverCursor(indice)
    }

    // ---------------------------------------------------------------- desenho

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (direita <= esquerda || base <= topo || instantes.isEmpty()) return

        // Grade horizontal discreta (as escalas são por série) e eixo de tempo com 4 marcas.
        for (i in 0..4) {
            val y = base - (base - topo) * i / 4
            canvas.drawLine(esquerda, y, direita, y, pincelGrade)
        }
        pincelRotulo.color = corTexto
        for (i in 0..3) {
            val x = esquerda + (direita - esquerda) * i / 3
            if (i in 1..2) canvas.drawLine(x, topo, x, base, pincelGrade)
            pincelRotulo.textAlign = when (i) {
                0 -> Paint.Align.LEFT
                3 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(formatarTempo(tempoDe(x)), x, base + pincelRotulo.textSize + 4 * densidade, pincelRotulo)
        }

        val faixa = AnaliseReplay.faixaIndices(instantes, janela)
        // Um ponto além de cada borda, para a curva sair do quadro em vez de parar antes dele.
        val de = (faixa.first - 1).coerceAtLeast(0)
        val ate = (faixa.last + 1).coerceAtMost(instantes.lastIndex)

        canvas.save()
        canvas.clipRect(esquerda, topo - pincelCurva.strokeWidth, direita, base + pincelCurva.strokeWidth)
        val escalas = series.map { serie ->
            AnaliseReplay.estatisticas(serie.valores, faixa)?.let(AnaliseReplay::escala)
        }
        series.forEachIndexed { s, serie ->
            val (minimo, maximo) = escalas[s] ?: return@forEachIndexed
            montarCaminho(serie.valores, de, ate, minimo, maximo)
            pincelCurva.color = serie.cor
            canvas.drawPath(caminho, pincelCurva)
        }
        canvas.restore()

        // Escalas próprias: máximo no topo e mínimo na base, um rótulo por série na cor dela.
        pincelRotulo.textAlign = Paint.Align.LEFT
        var deslocamento = 0f
        series.forEachIndexed { s, serie ->
            val (minimo, maximo) = escalas[s] ?: return@forEachIndexed
            pincelRotulo.color = serie.cor
            canvas.drawText(formatarValor(maximo), esquerda + 2 * densidade + deslocamento, topo + pincelRotulo.textSize, pincelRotulo)
            canvas.drawText(formatarValor(minimo), esquerda + 2 * densidade + deslocamento, base - 3 * densidade, pincelRotulo)
            deslocamento += maxOf(pincelRotulo.measureText(formatarValor(maximo)), pincelRotulo.measureText(formatarValor(minimo))) + 10 * densidade
        }

        desenharCursor(canvas, escalas)
    }

    private fun desenharCursor(canvas: Canvas, escalas: List<Pair<Double, Double>?>) {
        val i = cursor ?: return
        val t = instantes.getOrNull(i) ?: return
        if (t < janela.inicioMs || t > janela.fimMs) return
        val x = xDe(t)
        canvas.drawLine(x, topo, x, base, pincelCursor)
        series.forEachIndexed { s, serie ->
            val v = serie.valores.getOrNull(i)?.takeUnless { it.isNaN() } ?: return@forEachIndexed
            val (minimo, maximo) = escalas[s] ?: return@forEachIndexed
            val y = base - (base - topo) * AnaliseReplay.normalizar(v, minimo, maximo).toFloat()
            pincelPonto.color = serie.cor
            canvas.drawCircle(x, y, 4.5f * densidade, pincelPonto)
        }
        // Horário exato no topo da linha, dentro do quadro.
        val texto = formatarTempo(t)
        val largura = pincelRotulo.measureText(texto)
        pincelRotulo.color = corCursor
        pincelRotulo.textAlign = Paint.Align.LEFT
        val xt = (x - largura / 2).coerceIn(esquerda, direita - largura)
        canvas.drawText(texto, xt, paddingTop + pincelRotulo.textSize, pincelRotulo)
    }

    /**
     * Caminho de uma série entre os índices [de]..[ate]. Com mais amostras que pixels, agrupa por
     * coluna de pixel e liga mínimo e máximo de cada uma.
     */
    private fun montarCaminho(valores: DoubleArray, de: Int, ate: Int, minimo: Double, maximo: Double) {
        caminho.reset()
        if (ate < de) return
        val altura = base - topo
        fun y(v: Double) = base - altura * AnaliseReplay.normalizar(v, minimo, maximo).toFloat()
        val larguraPx = (direita - esquerda).toInt().coerceAtLeast(1)
        var aberto = false
        if (ate - de + 1 <= larguraPx * 2) {
            for (i in de..ate) {
                val v = valores[i]
                if (v.isNaN()) {
                    aberto = false
                    continue
                }
                val x = xDe(instantes[i])
                if (aberto) caminho.lineTo(x, y(v)) else caminho.moveTo(x, y(v))
                aberto = true
            }
            return
        }
        var coluna = Int.MIN_VALUE
        var minCol = 0.0
        var maxCol = 0.0
        var temValor = false
        fun emitir() {
            if (!temValor) {
                aberto = false
                return
            }
            val x = esquerda + coluna
            if (aberto) caminho.lineTo(x, y(minCol)) else caminho.moveTo(x, y(minCol))
            if (abs(maxCol - minCol) > 0) caminho.lineTo(x, y(maxCol))
            aberto = true
        }
        for (i in de..ate) {
            val c = (xDe(instantes[i]) - esquerda).toInt()
            if (c != coluna) {
                if (coluna != Int.MIN_VALUE) emitir()
                coluna = c
                temValor = false
            }
            val v = valores[i]
            if (v.isNaN()) continue
            if (!temValor) {
                minCol = v
                maxCol = v
                temValor = true
            } else {
                if (v < minCol) minCol = v
                if (v > maxCol) maxCol = v
            }
        }
        if (coluna != Int.MIN_VALUE) emitir()
    }

    private companion object {
        const val MARGEM_ESQUERDA_DP = 4
        const val MARGEM_DIREITA_DP = 4
        const val MARGEM_TOPO_DP = 18
        const val MARGEM_BASE_DP = 18
        const val SPAN_MINIMO_DP = 40
    }
}
