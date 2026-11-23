package com.charruamotors.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.Toast
import com.charruamotors.R
import com.charruamotors.databinding.DialogRegistroComunicacaoBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Copiar e compartilhar texto (laudos, relatórios de varredura, log do terminal), como o Log de
 * comunicação da aba OBD2 já faz.
 */
object TextoCompartilhavel {

    fun copiar(contexto: Context, rotulo: String, texto: String) {
        contexto.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(rotulo, texto))
        // Android 13+ já mostra a confirmação do sistema ao copiar.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(contexto, R.string.oficina_copiado, Toast.LENGTH_SHORT).show()
        }
    }

    fun compartilhar(contexto: Context, assunto: String, texto: String) {
        val envio = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, assunto)
            .putExtra(Intent.EXTRA_TEXT, texto)
        runCatching { contexto.startActivity(Intent.createChooser(envio, assunto)) }
    }

    /** Diálogo com o texto em fonte monoespaçada (selecionável) e as ações Copiar/Compartilhar. */
    fun mostrarDialogo(contexto: Context, titulo: String, texto: String) {
        val conteudo = DialogRegistroComunicacaoBinding.inflate(android.view.LayoutInflater.from(contexto))
        conteudo.textoRegistro.text = texto
        conteudo.root.post { conteudo.root.fullScroll(View.FOCUS_UP) }
        MaterialAlertDialogBuilder(contexto)
            .setTitle(titulo)
            .setView(conteudo.root)
            .setNegativeButton(R.string.oficina_compartilhar) { _, _ -> compartilhar(contexto, titulo, texto) }
            .setPositiveButton(R.string.oficina_copiar) { _, _ -> copiar(contexto, titulo, texto) }
            .setNeutralButton(R.string.botao_fechar, null)
            .show()
    }
}
