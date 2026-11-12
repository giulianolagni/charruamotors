package com.charruamotors.domain.validacao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidadorVeiculoTest {

    private val anoAtual = 2026

    private val valido = FormularioVeiculo(
        marca = "Chevrolet",
        modelo = "Onix",
        ano = "2020",
        placa = "abc-1234",
        km = "45000",
        capacidadeTanque = "44",
    )

    @Test
    fun `formulario valido nao tem erros`() {
        assertTrue(ValidadorVeiculo.validar(valido, anoAtual).isEmpty())
    }

    @Test
    fun `aceita placa antiga e mercosul`() {
        assertTrue(ValidadorVeiculo.placaValida("ABC-1234"))
        assertTrue(ValidadorVeiculo.placaValida("abc1d23"))
    }

    @Test
    fun `rejeita placa fora do padrao`() {
        assertFalse(ValidadorVeiculo.placaValida("AB12345"))
        assertFalse(ValidadorVeiculo.placaValida("ABCD123"))
    }

    @Test
    fun `normaliza placa para maiusculas sem hifen`() {
        assertEquals("ABC1234", ValidadorVeiculo.normalizarPlaca(" abc-1234 "))
    }

    @Test
    fun `campos obrigatorios vazios geram erro`() {
        val erros = ValidadorVeiculo.validar(
            FormularioVeiculo("", "", "", "", "", ""),
            anoAtual,
        )
        assertEquals(ErroValidacao.OBRIGATORIO, erros[CampoVeiculo.MARCA])
        assertEquals(ErroValidacao.OBRIGATORIO, erros[CampoVeiculo.MODELO])
        assertEquals(ErroValidacao.OBRIGATORIO, erros[CampoVeiculo.ANO])
        assertEquals(ErroValidacao.OBRIGATORIO, erros[CampoVeiculo.PLACA])
        assertEquals(ErroValidacao.OBRIGATORIO, erros[CampoVeiculo.KM])
        assertFalse(erros.containsKey(CampoVeiculo.TANQUE))
    }

    @Test
    fun `ano fora do intervalo e invalido`() {
        assertEquals(
            ErroValidacao.ANO_INVALIDO,
            ValidadorVeiculo.validar(valido.copy(ano = "1899"), anoAtual)[CampoVeiculo.ANO],
        )
        assertEquals(
            ErroValidacao.ANO_INVALIDO,
            ValidadorVeiculo.validar(valido.copy(ano = "2028"), anoAtual)[CampoVeiculo.ANO],
        )
        assertTrue(ValidadorVeiculo.validar(valido.copy(ano = "2027"), anoAtual).isEmpty())
    }

    @Test
    fun `km negativa e tanque zero sao invalidos`() {
        val erros = ValidadorVeiculo.validar(valido.copy(km = "-1", capacidadeTanque = "0"), anoAtual)
        assertEquals(ErroValidacao.NUMERO_INVALIDO, erros[CampoVeiculo.KM])
        assertEquals(ErroValidacao.NUMERO_INVALIDO, erros[CampoVeiculo.TANQUE])
    }
}
