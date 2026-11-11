package com.charruamotors

import android.app.Application
import com.charruamotors.data.local.AppDatabase
import com.charruamotors.data.repository.UsuarioRepository
import com.charruamotors.data.session.SessaoManager

/**
 * Classe Application: criada uma única vez quando o processo do app inicia,
 * antes de qualquer Activity. Usada como ponto central para dependências
 * compartilhadas (banco de dados, repositórios, sessão).
 */
class CharruaMotorsApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    val sessao: SessaoManager by lazy { SessaoManager(this) }

    val usuarioRepository: UsuarioRepository by lazy { UsuarioRepository(database.usuarioDao()) }
}
