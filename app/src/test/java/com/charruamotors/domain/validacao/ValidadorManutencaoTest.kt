package com.charruamotors.domain.validacao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ValidadorManutencaoTest {

    private val hoje = LocalDate.of(2026, 5, 10)
    private val valido = FormularioManutencao(data = hoje, km = "50000", custo = "350,90")

    @Test
    fun `formulario valido nao tem erros`() {
        assertTrue(ValidadorManutencao.validar(valido, hoje, kmMinimo = 45000).isEmpty())
    }

    @Test
    fun `data obrigatoria e nao futura`() {
        assertEquals(
            ErroValidacao.OBRIGATORIO,
            ValidadorManutencao.validar(valido.copy(data = null), hoje, 0)[CampoManutencao.DATA],
        )
        assertEquals(
            ErroValidacao.DATA_FUTURA,
            ValidadorManutencao.validar(valido.copy(data = hoje.plusDays(1)), hoje, 0)[CampoManutencao.DATA],
        )
    }

    @Test
    fun `km nao pode ser menor que a do ultimo registro mas pode ser igual`() {
        assertEquals(
            ErroValidacao.KM_MENOR_QUE_ULTIMO,
            ValidadorManutencao.validar(valido.copy(km = "49999"), hoje, 50000)[CampoManutencao.KM],
        )
        assertTrue(ValidadorManutencao.validar(valido, hoje, 50000).isEmpty())
    }

    @Test
    fun `km vazia ou negativa e invalida`() {
        assertEquals(
            ErroValidacao.OBRIGATORIO,
            ValidadorManutencao.validar(valido.copy(km = ""), hoje, 0)[CampoManutencao.KM],
        )
        assertEquals(
            ErroValidacao.NUMERO_INVALIDO,
            ValidadorManutencao.validar(valido.copy(km = "-5"), hoje, 0)[CampoManutencao.KM],
        )
    }

    @Test
    fun `custo deve ser maior que zero`() {
        assertEquals(
            ErroValidacao.MAIOR_QUE_ZERO,
            ValidadorManutencao.validar(valido.copy(custo = "0"), hoje, 0)[CampoManutencao.CUSTO],
        )
        assertEquals(
            ErroValidacao.NUMERO_INVALIDO,
            ValidadorManutencao.validar(valido.copy(custo = "abc"), hoje, 0)[CampoManutencao.CUSTO],
        )
        assertEquals(
            ErroValidacao.OBRIGATORIO,
            ValidadorManutencao.validar(valido.copy(custo = " "), hoje, 0)[CampoManutencao.CUSTO],
        )
    }
}
