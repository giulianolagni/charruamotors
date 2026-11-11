package com.charruamotors.data.repository

import com.charruamotors.data.local.dao.UsuarioDao
import com.charruamotors.domain.model.Usuario

/**
 * Login simulado: as credenciais são verificadas contra a tabela local de usuários,
 * sem backend. Um usuário padrão é criado na primeira tentativa de login.
 */
class UsuarioRepository(private val dao: UsuarioDao) {

    suspend fun autenticar(email: String, senha: String): Usuario? {
        garantirUsuarioPadrao()
        val usuario = dao.buscarPorEmail(email.trim().lowercase()) ?: return null
        return usuario.takeIf { it.senhaConfere(senha) }
    }

    private suspend fun garantirUsuarioPadrao() {
        if (dao.contar() == 0) {
            dao.inserir(Usuario(nome = NOME_PADRAO, email = EMAIL_PADRAO, senha = SENHA_PADRAO))
        }
    }

    companion object {
        const val NOME_PADRAO = "Administrador"
        const val EMAIL_PADRAO = "admin@charruamotors.com"
        const val SENHA_PADRAO = "123456"
    }
}
