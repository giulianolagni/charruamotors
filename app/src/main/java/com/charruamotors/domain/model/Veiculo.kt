package com.charruamotors.domain.model

import androidx.room.ColumnInfo
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
    /** Limite de temperatura da água (°C): acima disso o Painel avisa (v1.7). */
    @ColumnInfo(defaultValue = "98") val tempMaxAguaC: Int = LIMITE_AGUA_PADRAO_C,
    /** Limite de temperatura do óleo do câmbio ATF (°C). */
    @ColumnInfo(defaultValue = "105") val tempMaxAtfC: Int = LIMITE_ATF_PADRAO_C,
    /** Tensão mínima do alternador (V), avaliada com o motor ligado (> 600 rpm). */
    @ColumnInfo(defaultValue = "13.2") val tensaoMinAlternadorV: Double = LIMITE_TENSAO_PADRAO_V,
    /** Calibração do consumo via MAF: multiplica os litros estimados (1,0 = sem ajuste). */
    @ColumnInfo(defaultValue = "1.0") val calibracaoConsumo: Double = CALIBRACAO_PADRAO,
) {
    val descricao: String
        get() = "$marca $modelo $ano"

    /** Placa no formato de exibição: padrão antigo com hífen (ABC-1234), Mercosul sem (ABC1D23). */
    val placaFormatada: String
        get() = if (placa.length == 7 && placa[4].isDigit()) "${placa.take(3)}-${placa.drop(3)}" else placa

    companion object {
        const val LIMITE_AGUA_PADRAO_C = 98
        const val LIMITE_ATF_PADRAO_C = 105
        const val LIMITE_TENSAO_PADRAO_V = 13.2
        const val CALIBRACAO_PADRAO = 1.0
    }
}
