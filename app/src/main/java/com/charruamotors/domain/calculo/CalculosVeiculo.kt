package com.charruamotors.domain.calculo

import com.charruamotors.domain.model.Abastecimento
import com.charruamotors.domain.model.Manutencao
import com.charruamotors.domain.model.TipoManutencao
import com.charruamotors.domain.model.Veiculo
import java.time.LocalDate

/** Previsão da próxima manutenção de um tipo de serviço (por km e/ou por data). */
data class ProximaManutencao(
    val tipo: TipoManutencao,
    val kmPrevisto: Int?,
    val dataPrevista: LocalDate?,
    val vencida: Boolean,
)

/** Resumo de um veículo exibido no Dashboard (todos os valores já calculados pelo domínio). */
data class ResumoVeiculo(
    val veiculo: Veiculo,
    /** km/l pelo método do tanque cheio; null sem dois tanques cheios. */
    val consumoMedio: Double?,
    /** Serviço mais urgente (vencido primeiro); null sem histórico de serviços periódicos. */
    val proximaManutencao: ProximaManutencao?,
    /** Quantidade de códigos de falha (DTC) com status ATIVO. */
    val falhasAtivas: Int,
) {
    val temFalhasAtivas: Boolean get() = falhasAtivas > 0
}

/** Cálculos de domínio (sem Android/UI), cobertos por testes unitários. */
object CalculosVeiculo {

    /**
     * Consumo médio (km/l) pelo método do tanque cheio: a distância entre dois tanques cheios
     * dividida pelos litros colocados depois do primeiro até o segundo (inclusive).
     * Abastecimentos parciais entram na soma de litros. Retorna null sem ao menos dois tanques cheios.
     */
    fun consumoMedio(abastecimentos: List<Abastecimento>): Double? {
        val ordenados = abastecimentos.sortedWith(compareBy({ it.km }, { it.data }))
        val primeiroCheio = ordenados.indexOfFirst { it.tanqueCheio }
        if (primeiroCheio == -1) return null
        val ultimoCheio = ordenados.indexOfLast { it.tanqueCheio }
        if (ultimoCheio == primeiroCheio) return null

        val distancia = ordenados[ultimoCheio].km - ordenados[primeiroCheio].km
        val litros = ordenados.subList(primeiroCheio + 1, ultimoCheio + 1).sumOf { it.litros }
        if (distancia <= 0 || litros <= 0) return null
        return distancia / litros
    }

    /**
     * Custo por km = (abastecimentos + manutenções) / km rodados entre o menor e o maior km
     * registrados. O primeiro registro serve só de marco inicial, então seu valor não entra na soma.
     * Retorna null se não houver distância percorrida.
     */
    fun custoPorKm(abastecimentos: List<Abastecimento>, manutencoes: List<Manutencao>): Double? {
        val kms = abastecimentos.map { it.km } + manutencoes.map { it.km }
        if (kms.isEmpty()) return null
        val kmInicial = kms.min()
        val kmFinal = kms.max()
        val distancia = kmFinal - kmInicial
        if (distancia <= 0) return null

        // Gastos feitos no km inicial são anteriores ao trecho medido; os demais pertencem a ele.
        val gasto = abastecimentos.filter { it.km > kmInicial }.sumOf { it.valorTotal } +
            manutencoes.filter { it.km > kmInicial }.sumOf { it.custoTotal }
        return gasto / distancia
    }

    /** Gasto total (abastecimentos + manutenções) entre [inicio] e [fim], ambos inclusive. */
    fun gastoPorPeriodo(
        abastecimentos: List<Abastecimento>,
        manutencoes: List<Manutencao>,
        inicio: LocalDate,
        fim: LocalDate,
    ): Double {
        val periodo = inicio..fim
        return abastecimentos.filter { it.data in periodo }.sumOf { it.valorTotal } +
            manutencoes.filter { it.data in periodo }.sumOf { it.custoTotal }
    }

    /**
     * Para cada tipo com periodicidade que já tem histórico, prevê a próxima manutenção a partir
     * da última realizada (por km e por data). Ordena as vencidas primeiro e depois pela km prevista.
     */
    fun proximasManutencoes(
        manutencoes: List<Manutencao>,
        kmAtual: Int,
        hoje: LocalDate,
    ): List<ProximaManutencao> =
        TipoManutencao.entries.mapNotNull { tipo ->
            if (tipo.intervaloKm == null && tipo.intervaloMeses == null) return@mapNotNull null
            val ultima = manutencoes.filter { it.tipo == tipo }.maxByOrNull { it.km } ?: return@mapNotNull null

            val kmPrevisto = tipo.intervaloKm?.let { ultima.km + it }
            val dataPrevista = tipo.intervaloMeses?.let { ultima.data.plusMonths(it.toLong()) }
            val vencida = (kmPrevisto != null && kmAtual >= kmPrevisto) ||
                (dataPrevista != null && !hoje.isBefore(dataPrevista))
            ProximaManutencao(tipo, kmPrevisto, dataPrevista, vencida)
        }.sortedWith(compareByDescending<ProximaManutencao> { it.vencida }.thenBy { it.kmPrevisto ?: Int.MAX_VALUE })

    /** A manutenção mais urgente, ou null se não houver histórico de serviços periódicos. */
    fun proximaManutencao(manutencoes: List<Manutencao>, kmAtual: Int, hoje: LocalDate): ProximaManutencao? =
        proximasManutencoes(manutencoes, kmAtual, hoje).firstOrNull()

    /** Monta o resumo do Dashboard a partir do histórico do veículo. */
    fun resumir(
        veiculo: Veiculo,
        abastecimentos: List<Abastecimento>,
        manutencoes: List<Manutencao>,
        falhasAtivas: Int,
        hoje: LocalDate,
    ): ResumoVeiculo = ResumoVeiculo(
        veiculo = veiculo,
        consumoMedio = consumoMedio(abastecimentos),
        proximaManutencao = proximaManutencao(manutencoes, veiculo.kmAtual, hoje),
        falhasAtivas = falhasAtivas,
    )
}
