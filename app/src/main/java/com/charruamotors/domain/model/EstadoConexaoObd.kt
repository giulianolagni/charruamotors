package com.charruamotors.domain.model

/** Estado da conexão com o scanner OBD2, exposto pelo `ObdClient` via `StateFlow`. */
sealed class EstadoConexaoObd {
    data object Desconectado : EstadoConexaoObd()
    data object Conectando : EstadoConexaoObd()
    data object Conectado : EstadoConexaoObd()
    data class Erro(val mensagem: String) : EstadoConexaoObd()
}
