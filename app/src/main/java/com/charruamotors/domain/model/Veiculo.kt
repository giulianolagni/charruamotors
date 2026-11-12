package com.charruamotors.domain.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Objeto de domínio que representa um veículo do usuário.
 * Também é a entidade Room da tabela "veiculos" (a placa é única).
 */
@Entity(tableName = "veiculos", indices = [Index(value = ["placa"], unique = true)])
data class Veiculo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val marca: String,
    val modelo: String,
    val ano: Int,
    /** Placa normalizada: maiúsculas, sem hífen (ex.: ABC1234 ou ABC1D23). */
    val placa: String,
    val cor: String? = null,
    val kmAtual: Int,
    val tipoCombustivel: TipoCombustivel,
    /** Capacidade do tanque em litros (opcional). */
    val capacidadeTanque: Double? = null,
) {
    val descricao: String
        get() = "$marca $modelo $ano"

    /** Placa no formato de exibição: padrão antigo com hífen (ABC-1234), Mercosul sem (ABC1D23). */
    val placaFormatada: String
        get() = if (placa.length == 7 && placa[4].isDigit()) "${placa.take(3)}-${placa.drop(3)}" else placa
}
