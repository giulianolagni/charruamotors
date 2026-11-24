package com.charruamotors.ui.componentes

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * Arrastar e soltar numa [RecyclerView] só pela alça (`≡`): o toque longo no item rolaria a tela
 * sem querer. Durante o arraste, [moverVisual] reposiciona o item no adapter; ao soltar, [aoSoltar]
 * recebe a posição inicial e a final — um arraste inteiro equivale a um único "mover de → para",
 * aplicado de uma vez na configuração.
 */
class ArrasteReordenavel(
    lista: RecyclerView,
    private val moverVisual: (de: Int, para: Int) -> Unit,
    private val aoSoltar: (de: Int, para: Int) -> Unit,
) {
    private var inicio = RecyclerView.NO_POSITION
    private var fim = RecyclerView.NO_POSITION

    private val toque = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun onMove(rv: RecyclerView, origem: RecyclerView.ViewHolder, destino: RecyclerView.ViewHolder): Boolean {
            val de = origem.bindingAdapterPosition
            val para = destino.bindingAdapterPosition
            if (de == RecyclerView.NO_POSITION || para == RecyclerView.NO_POSITION) return false
            if (inicio == RecyclerView.NO_POSITION) inicio = de
            fim = para
            moverVisual(de, para)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direcao: Int) = Unit

        override fun isLongPressDragEnabled() = false

        override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(rv, viewHolder)
            val de = inicio
            val para = fim
            inicio = RecyclerView.NO_POSITION
            fim = RecyclerView.NO_POSITION
            if (de != RecyclerView.NO_POSITION && de != para) aoSoltar(de, para)
        }
    })

    init {
        toque.attachToRecyclerView(lista)
    }

    /** Liga a [alca] do [holder]: encostar o dedo já começa o arraste. */
    @SuppressLint("ClickableViewAccessibility")
    fun ligarAlca(alca: View, holder: RecyclerView.ViewHolder) {
        alca.setOnTouchListener { _, evento ->
            if (evento.actionMasked == MotionEvent.ACTION_DOWN) toque.startDrag(holder)
            false
        }
    }
}
