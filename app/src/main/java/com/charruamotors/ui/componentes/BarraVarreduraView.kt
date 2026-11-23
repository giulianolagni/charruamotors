package com.charruamotors.ui.componentes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.charruamotors.R
import com.charruamotors.oficina.varredura.MetricasVarredura

/**
 * Barra de progresso colorida da varredura amigável, em segmentos proporcionais ao total da faixa:
 * verde = identificados no catálogo, azul = responderam mas são desconhecidos, cinza = ignorados
 * (NO DATA / 7F / erro) e o trilho = o que ainda falta consultar.
 */
class BarraVarreduraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var metricas: MetricasVarredura = MetricasVarredura()
        set(v) {
            field = v
            contentDescription = context.getString(
                R.string.varredura_barra_descricao, v.feitos, v.total, v.identificados, v.desconhecidos, v.ignorados,
            )
            invalidate()
        }

    private val cores = listOf(R.color.varredura_identificado, R.color.varredura_desconhecido, R.color.varredura_ignorado)
        .map { ContextCompat.getColor(context, it) }
    private val pincel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trilho = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.varredura_trilho)
    }
    private val retangulo = RectF()
    private val recorte = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val largura = (width - paddingLeft - paddingRight).toFloat()
        val altura = (height - paddingTop - paddingBottom).toFloat()
        if (largura <= 0f || altura <= 0f) return
        val raio = altura / 2f
        retangulo.set(paddingLeft.toFloat(), paddingTop.toFloat(), paddingLeft + largura, paddingTop + altura)
        recorte.reset()
        recorte.addRoundRect(retangulo, raio, raio, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(recorte)
        canvas.drawRect(retangulo, trilho)
        val total = metricas.total.takeIf { it > 0 } ?: return canvas.restore()
        var x = retangulo.left
        listOf(metricas.identificados, metricas.desconhecidos, metricas.ignorados).forEachIndexed { i, n ->
            if (n <= 0) return@forEachIndexed
            val w = largura * n / total
            pincel.color = cores[i]
            canvas.drawRect(x, retangulo.top, x + w, retangulo.bottom, pincel)
            x += w
        }
        canvas.restore()
    }
}
