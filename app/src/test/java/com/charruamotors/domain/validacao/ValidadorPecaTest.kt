package com.charruamotors.domain.validacao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidadorPecaTest {

    private val valida = FormularioPeca(nome = "Filtro de óleo", quantidade = "1", valorUnitario = "35,90")

    @Test
    fun `peca valida nao tem erros`() {
        assertTrue(ValidadorPeca.validar(valida).isEmpty())
    }

    @Test
    fun `nome obrigatorio`() {
        assertEquals(ErroValidacao.OBRIGATORIO, ValidadorPeca.validar(valida.copy(nome = " "))[CampoPeca.NOME])
    }

    @Test
    fun `quantidade deve ser inteira e maior que zero`() {
        assertEquals(
            ErroValidacao.OBRIGATORIO,
            ValidadorPeca.validar(valida.copy(quantidade = ""))[CampoPeca.QUANTIDADE],
        )
        assertEquals(
            ErroValidacao.NUMERO_INVALIDO,
            ValidadorPeca.validar(valida.copy(quantidade = "1,5"))[CampoPeca.QUANTIDADE],
        )
        assertEquals(
            ErroValidacao.MAIOR_QUE_ZERO,
            ValidadorPeca.validar(valida.copy(quantidade = "0"))[CampoPeca.QUANTIDADE],
        )
    }

    @Test
    fun `valor unitario deve ser maior que zero`() {
        assertEquals(
            ErroValidacao.MAIOR_QUE_ZERO,
            ValidadorPeca.validar(valida.copy(valorUnitario = "0"))[CampoPeca.VALOR],
        )
        assertEquals(
            ErroValidacao.NUMERO_INVALIDO,
            ValidadorPeca.validar(valida.copy(valorUnitario = "abc"))[CampoPeca.VALOR],
        )
    }

    @Test
    fun `linha em branco e considerada vazia`() {
        assertTrue(FormularioPeca("", " ", "").vazia)
        assertFalse(valida.vazia)
    }
}
