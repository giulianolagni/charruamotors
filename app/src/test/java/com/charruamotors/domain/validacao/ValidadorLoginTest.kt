package com.charruamotors.domain.validacao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ValidadorLoginTest {

    @Test
    fun `email vazio e obrigatorio`() {
        assertEquals(ErroValidacao.OBRIGATORIO, ValidadorLogin.validarEmail("  "))
    }

    @Test
    fun `email sem dominio e invalido`() {
        assertEquals(ErroValidacao.EMAIL_INVALIDO, ValidadorLogin.validarEmail("admin@"))
    }

    @Test
    fun `email valido passa`() {
        assertNull(ValidadorLogin.validarEmail("admin@charruamotors.com"))
    }

    @Test
    fun `senha curta e rejeitada`() {
        assertEquals(ErroValidacao.SENHA_CURTA, ValidadorLogin.validarSenha("123"))
    }

    @Test
    fun `senha com tamanho minimo passa`() {
        assertNull(ValidadorLogin.validarSenha("123456"))
    }
}
