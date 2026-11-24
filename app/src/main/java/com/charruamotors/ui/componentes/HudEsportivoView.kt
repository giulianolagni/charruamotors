package com.charruamotors.ui.componentes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.charruamotors.direcao.hud.EscalaRpm
import com.charruamotors.direcao.hud.EscalaTurbo
import com.charruamotors.direcao.hud.EstadoHudEsportivo
import com.charruamotors.direcao.hud.FormatadorHudEsportivo
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * HUD flutuante esportivo (v2.2.7), desenhado só com `Canvas`/`Paint`: conta-giros grande à direita e
 * o mostrador de turbo (~45 % do diâmetro) acoplado embaixo à esquerda, ligados por uma base escura;
 * fora deles o fundo é transparente para não tampar o Waze.
 *
 * Acabamento: miolo grafite com anéis concêntricos sutis, moldura cinza metálico, ponteiros
 * prateados pontiagudos com sombra, faixa vermelha escura no fim das escalas e um arco externo
 * azul/ciano (com divisões pretas) que enche com o pedal do acelerador auto-calibrado (`PED`).
 * O conta-giros mostra 1–6 (até 7/8 nos redlines altos) ×1.000 rpm, a marcha com o shift-light e a
 * velocidade em 3 dígitos `KPH`.
 *
 * O desenho é feito num espaço virtual de [LARGURA_BASE] × [ALTURA_BASE] e escalado para a View.
 */
class HudEsportivoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var estado: EstadoHudEsportivo? = null
        set(v) {
            if (v == field) return
            field = v
            contentDescription = v?.let {
                "RPM ${it.rpm?.toInt() ?: "sem leitura"}, marcha ${it.marcha}, ${it.velocidade} km/h, " +
                    "turbo ${String.format(Locale.ROOT, "%.1f", it.boostBar)} bar"
            }
            invalidate()
        }

    private val italico: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)

    private val pincel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val traco = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = italico
    }
    private val caminho = Path()
    private val retangulo = RectF()

    // Geometria no espaço virtual (proporções da referência docs/ref_hud.png).
    private val cxRpm = 148f
    private val cyRpm = 106f
    private val raioRpm = 80f
    private val raioTurbo = raioRpm * 0.45f
    private val cxTurbo = 44f
    private val cyTurbo = 160f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = resources.displayMetrics.density
        val largura = resolveSize((LARGURA_PADRAO_DP * d).toInt(), widthMeasureSpec)
        val altura = resolveSize((LARGURA_PADRAO_DP * d * ALTURA_BASE / LARGURA_BASE).toInt(), heightMeasureSpec)
        setMeasuredDimension(largura, altura)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val e = estado ?: return
        val escala = min(width / LARGURA_BASE, height / ALTURA_BASE)
        canvas.save()
        canvas.translate((width - LARGURA_BASE * escala) / 2f, (height - ALTURA_BASE * escala) / 2f)
        canvas.scale(escala, escala)
        val alfa = if (e.conectado) 255 else ALFA_DESCONECTADO
        desenharBase(canvas)
        desenharArcoPedal(canvas, e.pedal, alfa)
        desenharConta(canvas, e, alfa)
        desenharTurbo(canvas, e.escalaTurbo, e.boostBar, alfa)
        canvas.restore()
    }

    // ------------------------------------------------------------------ base e molduras

    /** Base escura discreta que liga os dois mostradores (embaixo, entre eles). */
    private fun desenharBase(canvas: Canvas) {
        pincel.shader = null
        pincel.style = Paint.Style.FILL
        pincel.color = Color.argb(215, 16, 17, 19)
        caminho.reset()
        caminho.moveTo(cxTurbo, cyTurbo - raioTurbo * 0.9f)
        caminho.lineTo(cxRpm - raioRpm * 0.55f, cyRpm + raioRpm * 0.35f)
        caminho.lineTo(cxRpm - raioRpm * 0.1f, cyRpm + raioRpm * 0.98f)
        caminho.lineTo(cxTurbo + raioTurbo * 0.3f, cyTurbo + raioTurbo * 0.96f)
        caminho.close()
        canvas.drawPath(caminho, pincel)
    }

    /** Moldura metálica, miolo grafite com anéis concêntricos sutis. */
    private fun desenharFace(canvas: Canvas, cx: Float, cy: Float, raio: Float) {
        pincel.style = Paint.Style.FILL
        pincel.shader = LinearGradient(cx, cy - raio, cx, cy + raio, Color.rgb(92, 96, 102), Color.rgb(30, 32, 35), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, raio, pincel)
        pincel.shader = LinearGradient(cx, cy + raio, cx, cy - raio, Color.rgb(70, 74, 80), Color.rgb(24, 26, 29), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, raio * 0.95f, pincel)
        pincel.shader = RadialGradient(cx, cy, raio * 0.9f, Color.rgb(38, 41, 45), Color.rgb(12, 13, 15), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, raio * 0.9f, pincel)
        pincel.shader = null
        traco.shader = null
        traco.strokeWidth = 0.8f
        traco.color = Color.argb(26, 255, 255, 255)
        for (f in ANEIS) canvas.drawCircle(cx, cy, raio * f, traco)
        traco.color = Color.argb(140, 0, 0, 0)
        traco.strokeWidth = 1.2f
        canvas.drawCircle(cx, cy, raio * 0.9f, traco)
    }

    /** Faixa vermelha escura no fim da escala, de [inicio] (0–1) até 1. */
    private fun desenharRedline(canvas: Canvas, cx: Float, cy: Float, raio: Float, inicio: Float, alfa: Int) {
        if (inicio >= 1f) return
        traco.shader = null
        traco.strokeCap = Paint.Cap.BUTT
        traco.strokeWidth = raio * 0.09f
        traco.color = Color.argb(alfa, 150, 18, 22)
        retangulo.set(cx - raio * 0.8f, cy - raio * 0.8f, cx + raio * 0.8f, cy + raio * 0.8f)
        canvas.drawArc(retangulo, INICIO_ARCO + VARREDURA * inicio, VARREDURA * (1f - inicio), false, traco)
    }

    /** Traço radial de [r1] a [r2] na fração [f] do arco. */
    private fun desenharMarca(canvas: Canvas, cx: Float, cy: Float, r1: Float, r2: Float, f: Float) {
        val a = Math.toRadians((INICIO_ARCO + VARREDURA * f).toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        canvas.drawLine(cx + r1 * c, cy + r1 * s, cx + r2 * c, cy + r2 * s, traco)
    }

    private fun desenharRotulo(canvas: Canvas, cx: Float, cy: Float, r: Float, f: Float, rotulo: String) {
        val a = Math.toRadians((INICIO_ARCO + VARREDURA * f).toDouble())
        val x = cx + r * cos(a).toFloat()
        val y = cy + r * sin(a).toFloat() - (texto.descent() + texto.ascent()) / 2f
        canvas.drawText(rotulo, x, y, texto)
    }

    /** Ponteiro prateado pontiagudo com sombra e cubo metálico, na fração [f] do arco. */
    private fun desenharPonteiro(canvas: Canvas, cx: Float, cy: Float, raio: Float, f: Float) {
        val angulo = INICIO_ARCO + VARREDURA * f.coerceIn(0f, 1f)
        canvas.save()
        canvas.rotate(angulo, cx, cy)
        caminho.reset()
        caminho.moveTo(cx + raio * 0.82f, cy)
        caminho.lineTo(cx, cy - raio * 0.045f)
        caminho.lineTo(cx - raio * 0.16f, cy - raio * 0.025f)
        caminho.lineTo(cx - raio * 0.16f, cy + raio * 0.025f)
        caminho.lineTo(cx, cy + raio * 0.045f)
        caminho.close()
        // Sombra: o mesmo ponteiro deslocado e escuro.
        pincel.style = Paint.Style.FILL
        pincel.shader = null
        pincel.color = Color.argb(130, 0, 0, 0)
        canvas.save()
        canvas.translate(raio * 0.025f, raio * 0.035f)
        canvas.drawPath(caminho, pincel)
        canvas.restore()
        pincel.shader = LinearGradient(cx, cy - raio * 0.045f, cx, cy + raio * 0.045f,
            intArrayOf(Color.rgb(250, 250, 252), Color.rgb(196, 200, 206), Color.rgb(110, 114, 120)), null, Shader.TileMode.CLAMP)
        canvas.drawPath(caminho, pincel)
        canvas.restore()
        pincel.shader = RadialGradient(cx - raio * 0.02f, cy - raio * 0.02f, raio * 0.08f,
            Color.rgb(170, 174, 180), Color.rgb(40, 42, 46), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, raio * 0.075f, pincel)
        pincel.shader = null
    }

    // ------------------------------------------------------------------ pedal (arco azul/ciano)

    /** Arco externo superior azul/ciano com divisões pretas; enche com o pedal e leva o rótulo `PED`. */
    private fun desenharArcoPedal(canvas: Canvas, pedal: Float?, alfa: Int) {
        val r = raioRpm * 1.1f
        retangulo.set(cxRpm - r, cyRpm - r, cxRpm + r, cyRpm + r)
        traco.shader = null
        traco.strokeCap = Paint.Cap.BUTT
        traco.strokeWidth = raioRpm * 0.11f
        traco.color = Color.argb(alfa, 10, 52, 78)
        canvas.drawArc(retangulo, INICIO_PEDAL, VARREDURA_PEDAL, false, traco)
        val f = (pedal ?: 0f).coerceIn(0f, 1f)
        if (f > 0f) {
            traco.color = Color.argb(alfa, 30, 190, 240)
            canvas.drawArc(retangulo, INICIO_PEDAL, VARREDURA_PEDAL * f, false, traco)
        }
        // Divisões pretas.
        traco.color = Color.BLACK
        traco.strokeWidth = 1.4f
        val r1 = r - raioRpm * 0.06f
        val r2 = r + raioRpm * 0.06f
        var a = INICIO_PEDAL
        while (a <= INICIO_PEDAL + VARREDURA_PEDAL + 0.1f) {
            val rad = Math.toRadians(a.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            canvas.drawLine(cxRpm + r1 * c, cyRpm + r1 * s, cxRpm + r2 * c, cyRpm + r2 * s, traco)
            a += PASSO_DIVISAO_PEDAL
        }
        // Rótulo inclinado na ponta superior direita, tangente ao arco.
        val anguloRotulo = INICIO_PEDAL + VARREDURA_PEDAL + 9f
        canvas.save()
        canvas.rotate(anguloRotulo + 90f, cxRpm, cyRpm)
        texto.textSize = raioRpm * 0.12f
        texto.color = Color.argb(if (pedal == null) alfa / 3 else alfa, 120, 215, 245)
        canvas.drawText(FormatadorHudEsportivo.ROTULO_PEDAL, cxRpm, cyRpm - r + texto.textSize * 0.35f, texto)
        canvas.restore()
    }

    // ------------------------------------------------------------------ conta-giros

    private fun desenharConta(canvas: Canvas, e: EstadoHudEsportivo, alfa: Int) {
        val cx = cxRpm
        val cy = cyRpm
        val r = raioRpm
        val escala: EscalaRpm = e.escalaRpm
        desenharFace(canvas, cx, cy, r)
        desenharRedline(canvas, cx, cy, r, escala.fracaoRedline, alfa)
        // Marcas: maiores a cada 1.000 rpm, menores a cada 500 e 250.
        val quartos = escala.fimMil * 4
        for (i in 0..quartos) {
            val f = i / quartos.toFloat()
            val vermelho = f >= escala.fracaoRedline
            traco.color = if (vermelho) Color.argb(alfa, 230, 70, 70) else Color.argb(alfa, 235, 235, 235)
            when {
                i % 4 == 0 -> { traco.strokeWidth = 2.2f; desenharMarca(canvas, cx, cy, r * 0.73f, r * 0.86f, f) }
                i % 2 == 0 -> { traco.strokeWidth = 1.4f; desenharMarca(canvas, cx, cy, r * 0.78f, r * 0.86f, f) }
                else -> { traco.strokeWidth = 0.9f; traco.color = Color.argb(alfa * 3 / 4, 170, 170, 170); desenharMarca(canvas, cx, cy, r * 0.81f, r * 0.86f, f) }
            }
        }
        texto.textSize = r * 0.17f
        for (n in 1..escala.fimMil) {
            val f = n / escala.fimMil.toFloat()
            texto.color = if (n * 1_000.0 >= escala.redlineRpm) Color.argb(alfa, 235, 80, 80) else Color.argb(alfa, 245, 245, 245)
            desenharRotulo(canvas, cx, cy, r * 0.6f, f, n.toString())
        }
        // Quadrante direito: shift-light + marcha (no lugar do "N") e a velocidade (no lugar do "000 MPH").
        // Abaixo do "6" (fim da escala, à direita), sem encostar nele.
        val xMarcha = cx + r * 0.5f
        val yMarcha = cy + r * 0.3f
        texto.textSize = r * 0.25f
        texto.color = Color.argb(alfa, 245, 245, 245)
        canvas.drawText(e.marcha, xMarcha, yMarcha, texto)
        desenharShiftLight(canvas, xMarcha - r * 0.27f, yMarcha - r * 0.09f, r * 0.075f, e.shiftLight, alfa)
        texto.textSize = r * 0.33f
        canvas.drawText(e.velocidade, cx + r * 0.33f, cy + r * 0.62f, texto)
        texto.textSize = r * 0.11f
        texto.color = Color.argb(alfa * 4 / 5, 210, 210, 210)
        canvas.drawText(FormatadorHudEsportivo.ROTULO_VELOCIDADE, cx + r * 0.33f, cy + r * 0.75f, texto)
        desenharPonteiro(canvas, cx, cy, r, escala.fracao(e.rpm))
    }

    /** Triângulo branco do shift-light: aceso perto do redline, quase apagado no resto. */
    private fun desenharShiftLight(canvas: Canvas, x: Float, y: Float, lado: Float, aceso: Boolean, alfa: Int) {
        caminho.reset()
        caminho.moveTo(x, y - lado)
        caminho.lineTo(x + lado, y + lado * 0.8f)
        caminho.lineTo(x - lado, y + lado * 0.8f)
        caminho.close()
        pincel.shader = null
        pincel.style = Paint.Style.FILL
        pincel.color = if (aceso) Color.argb(alfa, 255, 255, 255) else Color.argb(alfa / 6, 255, 255, 255)
        canvas.drawPath(caminho, pincel)
    }

    // ------------------------------------------------------------------ turbo

    private fun desenharTurbo(canvas: Canvas, escala: EscalaTurbo, boost: Double, alfa: Int) {
        val cx = cxTurbo
        val cy = cyTurbo
        val r = raioTurbo
        desenharFace(canvas, cx, cy, r)
        desenharRedline(canvas, cx, cy, r, escala.fracaoRedline, alfa)
        val divisoes = (escala.fimBar / 0.1 + 0.5).toInt()
        for (i in 0..divisoes) {
            val bar = i * 0.1
            val f = escala.fracao(bar)
            val principal = i % 4 == 0 && bar <= escala.rotuloMaximo + 1e-9
            traco.color = if (f >= escala.fracaoRedline) Color.argb(alfa, 230, 70, 70) else Color.argb(alfa, 235, 235, 235)
            traco.strokeWidth = if (principal) 1.6f else 0.9f
            desenharMarca(canvas, cx, cy, if (principal) r * 0.7f else r * 0.78f, r * 0.86f, f)
        }
        texto.textSize = r * 0.2f
        texto.color = Color.argb(alfa, 245, 245, 245)
        for (v in escala.rotulos) desenharRotulo(canvas, cx, cy, r * 0.5f, escala.fracao(v), String.format(Locale.ROOT, "%.1f", v))
        texto.textSize = r * 0.17f
        texto.color = Color.argb(alfa * 4 / 5, 210, 210, 210)
        canvas.drawText("BAR", cx + r * 0.42f, cy + r * 0.55f, texto)
        desenharPonteiro(canvas, cx, cy, r, escala.fracao(boost))
    }

    companion object {
        const val LARGURA_BASE = 250f
        const val ALTURA_BASE = 200f
        private const val LARGURA_PADRAO_DP = 230

        /** Escalas: de 135° (embaixo à esquerda) a 360° (direita), livrando o quadrante inferior direito. */
        private const val INICIO_ARCO = 135f
        private const val VARREDURA = 225f

        /** Arco do pedal: em cima, de 200° a 320°. */
        private const val INICIO_PEDAL = 200f
        private const val VARREDURA_PEDAL = 120f
        private const val PASSO_DIVISAO_PEDAL = 12f

        private const val ALFA_DESCONECTADO = 110
        private val ANEIS = floatArrayOf(0.2f, 0.32f, 0.44f, 0.56f, 0.68f)
    }
}
