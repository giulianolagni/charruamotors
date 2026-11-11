package com.charruamotors.domain.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Objeto de domínio que representa o usuário do login simulado.
 * Também é a entidade Room da tabela "usuarios" (e-mail único).
 */
@Entity(tableName = "usuarios", indices = [Index(value = ["email"], unique = true)])
data class Usuario(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nome: String,
    val email: String,
    val senha: String,
) {
    /** Regra de autenticação: a senha informada precisa ser igual à cadastrada. */
    fun senhaConfere(senhaInformada: String): Boolean = senha == senhaInformada
}
