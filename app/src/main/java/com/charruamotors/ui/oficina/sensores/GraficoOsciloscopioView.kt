package com.charruamotors.ui.oficina.sensores

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.charruamotors.oficina.sensores.QuadroGrafico

/**
 * Osciloscópio ao vivo (v2.3.1): fundo escuro automotivo, até 4 traços de alto contraste, cada um na
 * própria escala (máximo no topo, mínimo na base, na cor do canal), e o cursor de toque.
 */
class GraficoOsciloscopioView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val densidade = resources.displayMetrics.density
    private val grade = Paint().apply { color = Color.parseColor("#26323D"); strokeWidth = densidade }
    private val traco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * densidade
        strokeJoin = Paint.Join.ROUND
    }
    private val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * resources.displayMetrics.scaledDensity }
    private val linhaCursor = Paint().apply { color = Color.WHITE; strokeWidth = 1.5f * densidade }
    private val caminho = Path()

    private var quadro: QuadroGrafico? = null
    private var rotulosEscala: List<Pair<String, String>> = emptyList()

    /** Posição do cursor (0–1); null = sem cursor. */
    var cursor: Float? = null
        private set

    /** Com o gráfico pausado o cursor continua na tela depois de tirar o dedo. */
    var manterCursor: Boolean = false
        set(valor) {
            field = valor
            if (!valor) cursor = null
        }

    var aoMoverCursor: () -> Unit = {}

    /** @param escalas por canal, os textos (mínimo, máximo) já na unidade de exibição. */
    fun mostrar(novo: QuadroGrafico, escalas: List<Pair<String, String>>) {
        quadro = novo
        rotulosEscala = escalas
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(FUNDO)
        for (i in 0..DIVISOES_X) {
            val x = width * i / DIVISOES_X.toFloat()
            canvas.drawLine(x, 0f, x, height.toFloat(), grade)
        }
        for (i in 0..DIVISOES_Y) {
            val y = height * i / DIVISOES_Y.toFloat()
            canvas.drawLine(0f, y, width.toFloat(), y, grade)
        }
        val q = quadro ?: return
        val duracao = (q.fimMs - q.inicioMs).coerceAtLeast(1).toFloat()
        val margem = 4f * densidade
        q.canais.forEachIndexed { i, canal ->
            traco.color = CORES[i % CORES.size]
            val escala = canal.escala()
            val amplitude = (escala.endInclusive - escala.start).toFloat()
            caminho.reset()
            var emTraco = false
            for (p in canal.pontos) {
                if (p == null) {
                    emTraco = false // leitura que faltou: o traço não inventa a ligação
                    continue
                }
                val x = width * (p.tMs - q.inicioMs) / duracao
                val y = margem + (height - 2 * margem) * (1f - ((p.valor - escala.start).toFloat() / amplitude))
                if (emTraco) caminho.lineTo(x, y) else caminho.moveTo(x, y)
                emTraco = true
            }
            canvas.drawPath(caminho, traco)
        }
        val altura = texto.textSize + 2 * densidade
        rotulosEscala.forEachIndexed { i, (minimo, maximo) ->
            texto.color = CORES[i % CORES.size]
            canvas.drawText(maximo, margem, altura * (i + 1), texto)
            canvas.drawText(minimo, margem, height - margem - altura * (rotulosEscala.size - 1 - i), texto)
        }
        cursor?.let { canvas.drawLine(width * it, 0f, width * it, height.toFloat(), linhaCursor) }
    }

    @SuppressLint("ClickableViewAccessibility") // leitura por arrasto; os valores também estão na legenda
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                cursor = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!manterCursor) cursor = null
            else -> return true
        }
        invalidate()
        aoMoverCursor()
        return true
    }

    companion object {
        private val FUNDO = Color.parseColor("#0B0F14")

        /** Ciano, amarelo, magenta e verde: alto contraste sobre o fundo escuro. */
        val CORES = intArrayOf(
            Color.parseColor("#00E5FF"), Color.parseColor("#FFD600"),
            Color.parseColor("#FF4081"), Color.parseColor("#76FF03"),
        )
        private const val DIVISOES_X = 6
        private const val DIVISOES_Y = 4
    }
}
