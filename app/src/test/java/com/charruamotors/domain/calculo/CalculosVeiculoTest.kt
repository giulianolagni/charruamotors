package com.charruamotors.domain.calculo

import com.charruamotors.domain.model.Abastecimento
import com.charruamotors.domain.model.Manutencao
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.TipoManutencao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalculosVeiculoTest {

    private val hoje = LocalDate.of(2026, 6, 1)

    private fun abast(km: Int, litros: Double, valor: Double, cheio: Boolean, data: LocalDate = hoje) =
        Abastecimento(
            veiculoId = 1, data = data, km = km, litros = litros, valorTotal = valor,
            tipoCombustivel = TipoCombustivel.GASOLINA, tanqueCheio = cheio,
        )

    private fun manut(km: Int, tipo: TipoManutencao, custo: Double, data: LocalDate = hoje) =
        Manutencao(veiculoId = 1, data = data, km = km, tipo = tipo, custoTotal = custo)

    // --- consumo médio ---

    @Test
    fun `consumo medio entre dois tanques cheios`() {
        val lista = listOf(abast(1000, 40.0, 240.0, true), abast(1400, 40.0, 240.0, true))
        assertEquals(10.0, CalculosVeiculo.consumoMedio(lista)!!, 0.001)
    }

    @Test
    fun `abastecimento parcial entra na soma de litros do trecho`() {
        val lista = listOf(
            abast(1000, 40.0, 240.0, true),
            abast(1200, 10.0, 60.0, false),
            abast(1500, 20.0, 120.0, true),
        )
        // 500 km / (10 + 20) litros
        assertEquals(500.0 / 30.0, CalculosVeiculo.consumoMedio(lista)!!, 0.001)
    }

    @Test
    fun `ordem da lista nao importa`() {
        val lista = listOf(abast(1400, 40.0, 240.0, true), abast(1000, 40.0, 240.0, true))
        assertEquals(10.0, CalculosVeiculo.consumoMedio(lista)!!, 0.001)
    }

    @Test
    fun `consumo medio nulo sem dois tanques cheios`() {
        assertNull(CalculosVeiculo.consumoMedio(emptyList()))
        assertNull(CalculosVeiculo.consumoMedio(listOf(abast(1000, 40.0, 240.0, true))))
        assertNull(CalculosVeiculo.consumoMedio(listOf(abast(1000, 40.0, 240.0, false), abast(1400, 40.0, 240.0, false))))
    }

    // --- custo por km ---

    @Test
    fun `custo por km desconsidera gastos no km inicial`() {
        val abastecimentos = listOf(abast(1000, 40.0, 200.0, true), abast(1500, 40.0, 250.0, true))
        val manutencoes = listOf(manut(1200, TipoManutencao.REVISAO, 250.0))
        // (250 + 250) / 500 km
        assertEquals(1.0, CalculosVeiculo.custoPorKm(abastecimentos, manutencoes)!!, 0.001)
    }

    @Test
    fun `custo por km nulo sem distancia`() {
        assertNull(CalculosVeiculo.custoPorKm(emptyList(), emptyList()))
        assertNull(CalculosVeiculo.custoPorKm(listOf(abast(1000, 40.0, 200.0, true)), emptyList()))
    }

    // --- gasto por período ---

    @Test
    fun `gasto por periodo inclui as duas pontas e soma os dois tipos`() {
        val abastecimentos = listOf(
            abast(1, 10.0, 100.0, true, LocalDate.of(2026, 1, 1)),
            abast(2, 10.0, 50.0, true, LocalDate.of(2026, 1, 31)),
            abast(3, 10.0, 999.0, true, LocalDate.of(2026, 2, 1)),
        )
        val manutencoes = listOf(
            manut(2, TipoManutencao.FREIOS, 300.0, LocalDate.of(2026, 1, 15)),
            manut(3, TipoManutencao.FREIOS, 999.0, LocalDate.of(2025, 12, 31)),
        )
        val total = CalculosVeiculo.gastoPorPeriodo(
            abastecimentos, manutencoes, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
        )
        assertEquals(450.0, total, 0.001)
    }

    // --- próxima manutenção ---

    @Test
    fun `preve proxima troca de oleo por km e por data`() {
        val m = listOf(manut(40000, TipoManutencao.TROCA_OLEO, 200.0, LocalDate.of(2026, 3, 1)))
        val p = CalculosVeiculo.proximaManutencao(m, kmAtual = 45000, hoje = hoje)!!
        assertEquals(TipoManutencao.TROCA_OLEO, p.tipo)
        assertEquals(50000, p.kmPrevisto)
        assertEquals(LocalDate.of(2027, 3, 1), p.dataPrevista)
        assertFalse(p.vencida)
    }

    @Test
    fun `vencida por km ou por data`() {
        val porKm = listOf(manut(40000, TipoManutencao.TROCA_OLEO, 200.0, LocalDate.of(2026, 3, 1)))
        assertTrue(CalculosVeiculo.proximaManutencao(porKm, 50000, hoje)!!.vencida)

        val porData = listOf(manut(40000, TipoManutencao.TROCA_OLEO, 200.0, LocalDate.of(2025, 5, 31)))
        assertTrue(CalculosVeiculo.proximaManutencao(porData, 41000, hoje)!!.vencida)
    }

    @Test
    fun `usa a ultima manutencao de cada tipo e ordena vencidas primeiro`() {
        val m = listOf(
            manut(20000, TipoManutencao.TROCA_OLEO, 200.0),
            manut(40000, TipoManutencao.TROCA_OLEO, 200.0),
            manut(1000, TipoManutencao.FREIOS, 400.0),
        )
        val lista = CalculosVeiculo.proximasManutencoes(m, kmAtual = 45000, hoje = hoje)
        assertEquals(listOf(TipoManutencao.FREIOS, TipoManutencao.TROCA_OLEO), lista.map { it.tipo })
        assertEquals(50000, lista.last().kmPrevisto)
    }

    @Test
    fun `tipos sem periodicidade ou sem historico sao ignorados`() {
        assertNull(CalculosVeiculo.proximaManutencao(emptyList(), 1000, hoje))
        assertNull(CalculosVeiculo.proximaManutencao(listOf(manut(1000, TipoManutencao.ELETRICA, 100.0)), 1000, hoje))
    }
}
