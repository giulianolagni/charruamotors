package com.charruamotors.domain.validacao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ValidadorAbastecimentoTest {

    private val hoje = LocalDate.of(2026, 5, 10)
    private val valido = FormularioAbastecimento(data = hoje, km = "50000", litros = "40,5", valorTotal = "230")

    private fun validar(f: FormularioAbastecimento, kmMinimo: Int = 0, tanque: Double? = 44.0) =
        ValidadorAbastecimento.validar(f, hoje, kmMinimo, tanque)

    @Test
    fun `formulario valido nao tem erros`() {
        assertTrue(validar(valido, kmMinimo = 49000).isEmpty())
    }

    @Test
    fun `data futura e invalida`() {
        assertEquals(
            ErroValidacao.DATA_FUTURA,
            validar(valido.copy(data = hoje.plusDays(1)))[CampoAbastecimento.DATA],
        )
    }

    @Test
    fun `km menor que a do ultimo registro e invalida`() {
        assertEquals(
            ErroValidacao.KM_MENOR_QUE_ULTIMO,
            validar(valido, kmMinimo = 50001)[CampoAbastecimento.KM],
        )
    }

    @Test
    fun `litros devem ser maiores que zero`() {
        assertEquals(ErroValidacao.MAIOR_QUE_ZERO, validar(valido.copy(litros = "0"))[CampoAbastecimento.LITROS])
        assertEquals(ErroValidacao.MAIOR_QUE_ZERO, validar(valido.copy(litros = "-3"))[CampoAbastecimento.LITROS])
    }

    @Test
    fun `litros nao podem exceder a capacidade do tanque`() {
        assertEquals(
            ErroValidacao.LITROS_ACIMA_DO_TANQUE,
            validar(valido.copy(litros = "44,1"))[CampoAbastecimento.LITROS],
        )
        assertTrue(validar(valido.copy(litros = "44")).isEmpty())
    }

    @Test
    fun `sem capacidade informada nao ha limite de litros`() {
        assertFalse(validar(valido.copy(litros = "500"), tanque = null).containsKey(CampoAbastecimento.LITROS))
    }

    @Test
    fun `valor deve ser maior que zero`() {
        assertEquals(ErroValidacao.MAIOR_QUE_ZERO, validar(valido.copy(valorTotal = "0"))[CampoAbastecimento.VALOR])
        assertEquals(ErroValidacao.NUMERO_INVALIDO, validar(valido.copy(valorTotal = "x"))[CampoAbastecimento.VALOR])
    }

    @Test
    fun `campos vazios sao obrigatorios`() {
        val erros = validar(FormularioAbastecimento(null, "", "", ""))
        assertEquals(ErroValidacao.OBRIGATORIO, erros[CampoAbastecimento.DATA])
        assertEquals(ErroValidacao.OBRIGATORIO, erros[CampoAbastecimento.KM])
        assertEquals(ErroValidacao.OBRIGATORIO, erros[CampoAbastecimento.LITROS])
        assertEquals(ErroValidacao.OBRIGATORIO, erros[CampoAbastecimento.VALOR])
    }
}
