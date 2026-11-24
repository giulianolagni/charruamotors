package com.charruamotors.ui.util

import android.text.TextUtils
import android.widget.TextView

/**
 * `setText` só quando o conteúdo muda. Na telemetria a 4 atualizações/s isso importa: num
 * TextView `wrap_content`, todo `setText` pede um novo layout que sobe até o ScrollView e
 * remede a aba inteira (grade de sensores incluída), mesmo com o texto igual.
 */
fun TextView.definirTexto(novo: CharSequence?) {
    if (!TextUtils.equals(text, novo ?: "")) text = novo
}
