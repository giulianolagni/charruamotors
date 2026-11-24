package com.charruamotors.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.ViewGroup
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

    /**
     * Diálogo com o texto em fonte monoespaçada (selecionável) e as ações Copiar/Compartilhar,
     * aberto no **topo** (início do texto: o resumo do relatório vem primeiro).
     *
     * @param aoCompartilhar ação própria de compartilhar (ex.: o arquivo `.txt` inteiro em vez do texto exibido)
     */
    fun mostrarDialogo(contexto: Context, titulo: String, texto: String, aoCompartilhar: (() -> Unit)? = null) {
        val conteudo = DialogRegistroComunicacaoBinding.inflate(android.view.LayoutInflater.from(contexto))
        conteudo.textoRegistro.text = texto
        val rolagem = conteudo.root
        // O TextView selecionável pedia o foco ao abrir e o ScrollView rolava até ele (o fim do
        // texto). Com o ScrollView focável antes dos filhos, o foco inicial fica nele, no topo.
        rolagem.isFocusableInTouchMode = true
        rolagem.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        val dialogo = MaterialAlertDialogBuilder(contexto)
            .setTitle(titulo)
            .setView(rolagem)
            .setNegativeButton(R.string.oficina_compartilhar) { _, _ ->
                if (aoCompartilhar != null) aoCompartilhar() else compartilhar(contexto, titulo, texto)
            }
            .setPositiveButton(R.string.oficina_copiar) { _, _ -> copiar(contexto, titulo, texto) }
            .setNeutralButton(R.string.botao_fechar, null)
            .create()
        dialogo.setOnShowListener {
            rolagem.requestFocus()
            // Reforço para aparelhos que ainda rolam no primeiro layout do diálogo.
            fun topo() = rolagem.scrollTo(0, 0)
            rolagem.post(::topo)
            rolagem.postDelayed(::topo, 150)
        }
        dialogo.show()
    }
}
