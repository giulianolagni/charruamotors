package com.charruamotors.ui.componentes

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.ColorUtils
import com.charruamotors.R
import com.google.android.material.color.MaterialColors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Mostrador analógico circular desenhado com `Canvas`/`Paint` (sem bibliotecas): arco de 270°,
 * escala com traços maiores/menores e rótulos, zona vermelha configurável, arco de progresso,
 * ponteiro animado e o valor digital no centro.
 *
 * Atributos XML (`app:`): `gaugeTitulo`, `gaugeUnidade`, `gaugeMin`, `gaugeMax`,
 * `gaugeZonaVermelha`, `gaugeDivisoes`, `gaugeSubdivisoes`, `gaugeDivisorRotulos`,
 * `gaugeCasasDecimais`. As cores vêm do tema Material (primária, secundária, erro, superfície).
 */
class GaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var titulo: String = ""
        set(v) { field = v; atualizarDescricao(); invalidate() }

    var unidade: String = ""
        set(v) { field = v; atualizarDescricao(); invalidate() }

    var escala: EscalaGauge = EscalaGauge()
        set(v) { field = v; invalidate() }

    var casasDecimais: Int = 0
        set(v) { field = v.coerceIn(0, 4); invalidate() }

    /** Valor exibido; null = sem leitura (ponteiro no mínimo e "—" no centro). */
    var valor: Float? = null
        private set

    /** Posição desenhada do ponteiro (animada até o [valor]). */
    private var valorPonteiro: Float = 0f
    private var animador: ValueAnimator? = null

    // ---------------------------------------------------------------- cores do tema
    private val corSuperficie = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val corSecundaria = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val corPrimaria = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    private val corPonteiroBase = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondary)
    private val corErro = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError)

    // ---------------------------------------------------------------- pincéis (criados uma vez, nunca no onDraw)
    private val pincelTrilho = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ColorUtils.setAlphaComponent(corSuperficie, 0x1F)
    }
    private val pincelProgresso = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val pincelZonaVermelha = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = corErro
    }
    private val pincelTraco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val pincelRotulo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = corSecundaria
    }
    private val pincelPonteiro = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val pincelCentro = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pincelValor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val pincelUnidade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = corSecundaria
    }
    private val pincelTitulo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = corSuperficie
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val arco = RectF()
    private val arcoZona = RectF()

    init {
        context.withStyledAttributes(attrs, R.styleable.GaugeView, defStyleAttr, 0) {
            titulo = getString(R.styleable.GaugeView_gaugeTitulo).orEmpty()
            unidade = getString(R.styleable.GaugeView_gaugeUnidade).orEmpty()
            val minimo = getFloat(R.styleable.GaugeView_gaugeMin, 0f)
            escala = EscalaGauge(
                minimo = minimo,
                maximo = getFloat(R.styleable.GaugeView_gaugeMax, 100f),
                zonaVermelha = if (hasValue(R.styleable.GaugeView_gaugeZonaVermelha)) {
                    getFloat(R.styleable.GaugeView_gaugeZonaVermelha, 0f)
                } else {
                    null
                },
                divisoes = getInt(R.styleable.GaugeView_gaugeDivisoes, 10),
                subdivisoes = getInt(R.styleable.GaugeView_gaugeSubdivisoes, 5),
                divisorRotulos = getFloat(R.styleable.GaugeView_gaugeDivisorRotulos, 1f),
            )
            casasDecimais = getInt(R.styleable.GaugeView_gaugeCasasDecimais, 0)
            valorPonteiro = minimo
        }
        atualizarDescricao()
    }

    /** Atualiza o valor; o ponteiro desliza até ele em [DURACAO_ANIMACAO_MS]. */
    fun definirValor(novo: Double?, animar: Boolean = true) {
        val v = novo?.toFloat()?.takeIf { it.isFinite() }
        valor = v
        atualizarDescricao()
        val alvo = v ?: escala.minimo
        animador?.cancel()
        if (!animar || !isLaidOut) {
            valorPonteiro = alvo
            invalidate()
            return
        }
        animador = ValueAnimator.ofFloat(valorPonteiro, alvo).apply {
            duration = DURACAO_ANIMACAO_MS
            interpolator = INTERPOLADOR
            addUpdateListener {
                valorPonteiro = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animador?.cancel()
        super.onDetachedFromWindow()
    }

    /** Quadrado: usa a largura disponível (em grade de colunas) ou o tamanho padrão. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val padrao = (TAMANHO_PADRAO_DP * resources.displayMetrics.density).toInt()
        val largura = resolveSize(padrao, widthMeasureSpec)
        val altura = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            resolveSize(largura, heightMeasureSpec)
        }
        val lado = min(largura, altura)
        setMeasuredDimension(lado, lado)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val largura = (width - paddingLeft - paddingRight).toFloat()
        val altura = (height - paddingTop - paddingBottom).toFloat()
        val tamanho = min(largura, altura)
        if (tamanho <= 0f) return

        val cx = paddingLeft + largura / 2f
        val cy = paddingTop + altura / 2f
        val espessura = tamanho * 0.055f
        val raio = tamanho / 2f - espessura
        arco.set(cx - raio, cy - raio, cx + raio, cy + raio)

        val inicio = EscalaGauge.ANGULO_INICIAL
        val varredura = EscalaGauge.VARREDURA

        // Trilho e arco de progresso
        pincelTrilho.strokeWidth = espessura
        canvas.drawArc(arco, inicio, varredura, false, pincelTrilho)
        val v = valor
        val alerta = v != null && escala.naZonaVermelha(v)
        val fracaoPonteiro = escala.fracao(valorPonteiro)
        if (v != null && fracaoPonteiro > 0f) {
            pincelProgresso.strokeWidth = espessura
            pincelProgresso.color = if (alerta) corErro else corPrimaria
            canvas.drawArc(arco, inicio, varredura * fracaoPonteiro, false, pincelProgresso)
        }

        // Zona vermelha: faixa fina por dentro do trilho
        val raioZona = raio - espessura * 1.4f
        escala.fracaoZonaVermelha?.let { fz ->
            pincelZonaVermelha.strokeWidth = espessura * 0.55f
            arcoZona.set(cx - raioZona, cy - raioZona, cx + raioZona, cy + raioZona)
            canvas.drawArc(arcoZona, inicio + varredura * fz, varredura * (1f - fz), false, pincelZonaVermelha)
        }

        // Traços da escala
        val raioExternoTraco = raioZona - espessura * 0.6f
        for (i in 0 until escala.totalTracos) {
            val maior = escala.tracoMaior(i)
            val angulo = Math.toRadians((inicio + varredura * i / (escala.totalTracos - 1)).toDouble())
            val comprimento = if (maior) tamanho * 0.07f else tamanho * 0.035f
            pincelTraco.strokeWidth = if (maior) tamanho * 0.012f else tamanho * 0.006f
            val fracaoTraco = i.toFloat() / (escala.totalTracos - 1)
            val naZona = escala.fracaoZonaVermelha?.let { fracaoTraco >= it - 1e-4f } ?: false
            pincelTraco.color = if (naZona) corErro else corSecundaria
            val c = cos(angulo).toFloat()
            val s = sin(angulo).toFloat()
            canvas.drawLine(
                cx + c * raioExternoTraco, cy + s * raioExternoTraco,
                cx + c * (raioExternoTraco - comprimento), cy + s * (raioExternoTraco - comprimento),
                pincelTraco,
            )
        }

        // Rótulos dos traços maiores
        pincelRotulo.textSize = tamanho * 0.065f
        val raioRotulo = raioExternoTraco - tamanho * 0.13f
        escala.rotulos.forEachIndexed { i, rotulo ->
            val angulo = Math.toRadians((inicio + varredura * i / escala.divisoes).toDouble())
            val x = cx + cos(angulo).toFloat() * raioRotulo
            val y = cy + sin(angulo).toFloat() * raioRotulo - (pincelRotulo.descent() + pincelRotulo.ascent()) / 2f
            canvas.drawText(rotulo, x, y, pincelRotulo)
        }

        // Ponteiro
        val corPonteiro = if (alerta) corErro else corPonteiroBase
        val anguloPonteiro = Math.toRadians(escala.angulo(valorPonteiro).toDouble())
        val raioPonteiro = raioExternoTraco - tamanho * 0.02f
        pincelPonteiro.strokeWidth = tamanho * 0.022f
        pincelPonteiro.color = corPonteiro
        canvas.drawLine(
            cx - cos(anguloPonteiro).toFloat() * tamanho * 0.05f,
            cy - sin(anguloPonteiro).toFloat() * tamanho * 0.05f,
            cx + cos(anguloPonteiro).toFloat() * raioPonteiro,
            cy + sin(anguloPonteiro).toFloat() * raioPonteiro,
            pincelPonteiro,
        )
        pincelCentro.color = corPonteiro
        canvas.drawCircle(cx, cy, tamanho * 0.045f, pincelCentro)

        // Valor digital, unidade e título (na abertura inferior do arco)
        // Abaixo dos rótulos inferiores (que ficam a ~0,15 do centro), sem sobrepor a escala.
        pincelValor.textSize = tamanho * 0.11f
        pincelValor.color = if (alerta) corErro else corSuperficie
        canvas.drawText(textoValor(), cx, cy + tamanho * 0.29f, pincelValor)
        pincelUnidade.textSize = tamanho * 0.055f
        canvas.drawText(unidadeExibida(), cx, cy + tamanho * 0.36f, pincelUnidade)
        pincelTitulo.textSize = tamanho * 0.065f
        canvas.drawText(titulo, cx, cy + tamanho * 0.465f, pincelTitulo)
    }

    private fun textoValor(): String {
        val v = valor ?: return "—"
        return String.format(LOCALE_BR, "%.${casasDecimais}f", v)
    }

    /** Com rótulos divididos (ex.: RPM ×1000), a unidade avisa a escala. */
    private fun unidadeExibida(): String =
        if (escala.divisorRotulos != 1f) "$unidade (escala ×${EscalaGauge.formatarRotulo(escala.divisorRotulos)})" else unidade

    private fun atualizarDescricao() {
        contentDescription = "$titulo: ${valor?.let { "${textoValor()} $unidade" } ?: "sem leitura"}"
    }

    companion object {
        /**
         * Curta e linear: a agulha chega ao valor lido bem antes da próxima leitura da fila rápida
         * (~100–200 ms no ELM327), sem o "arrasto" de uma desaceleração longa.
         */
        private const val DURACAO_ANIMACAO_MS = 90L
        private val INTERPOLADOR = LinearInterpolator()
        private const val TAMANHO_PADRAO_DP = 160
        private val LOCALE_BR = java.util.Locale.forLanguageTag("pt-BR")
    }
}
