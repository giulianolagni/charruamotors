package com.charruamotors.ui.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Com o edge-to-edge (obrigatório a partir do Android 15), o conteúdo é desenhado atrás das
 * barras do sistema. Esta função adiciona padding para que a view não fique coberta pela
 * status bar, pela barra de navegação ou pelo teclado.
 */
fun View.aplicarInsetsDoSistema(topo: Boolean = true, base: Boolean = true) {
    val paddingTopoOriginal = paddingTop
    val paddingBaseOriginal = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val barras = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime(),
        )
        view.updatePadding(
            left = barras.left,
            right = barras.right,
            top = if (topo) paddingTopoOriginal + barras.top else paddingTopoOriginal,
            bottom = if (base) paddingBaseOriginal + barras.bottom else paddingBaseOriginal,
        )
        insets
    }
}
