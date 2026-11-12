package com.charruamotors.data.session

import android.content.Context
import androidx.core.content.edit
import com.charruamotors.domain.model.Usuario

/**
 * Guarda a sessão do usuário logado em SharedPreferences, para que o login
 * sobreviva ao fechamento do app até o usuário fazer logout.
 */
class SessaoManager(context: Context) {

    private val prefs = context.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    val estaLogado: Boolean
        get() = prefs.contains(CHAVE_ID_USUARIO)

    val nomeUsuario: String?
        get() = prefs.getString(CHAVE_NOME_USUARIO, null)

    fun iniciar(usuario: Usuario) = prefs.edit {
        putLong(CHAVE_ID_USUARIO, usuario.id)
        putString(CHAVE_NOME_USUARIO, usuario.nome)
    }

    fun encerrar() = prefs.edit { clear() }

    private companion object {
        const val ARQUIVO = "sessao"
        const val CHAVE_ID_USUARIO = "id_usuario"
        const val CHAVE_NOME_USUARIO = "nome_usuario"
    }
}
