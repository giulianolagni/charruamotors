package com.charruamotors.domain.validacao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.7: limites de alerta e calibração do consumo no cadastro do veículo (opcionais, com faixas sensatas). */
class ValidadorLimitesVeiculoTest {

    private val base = FormularioVeiculo("Toyota", "SW4", "2013", "ABC1D23", "180000", "80")

    private fun erros(f: FormularioVeiculo) = ValidadorVeiculo.validar(f, 2026)

    @Test
    fun `campos vazios usam os padroes e nao geram erro`() {
        assertTrue(erros(base).isEmpty())
    }

    @Test
    fun `valores dentro das faixas sao aceitos com virgula ou ponto`() {
        val f = base.copy(limiteAgua = "98", limiteAtf = "105", limiteTensao = "13,2", calibracaoConsumo = "1.15")
        assertTrue(erros(f).isEmpty())
        assertEquals(13.2, ValidadorVeiculo.numero("13,2")!!, 0.0)
        assertEquals(1.15, ValidadorVeiculo.numero(" 1.15 ")!!, 0.0)
        assertNull(ValidadorVeiculo.numero("abc"))
        assertNull(ValidadorVeiculo.numero(""))
    }

    @Test
    fun `valores absurdos geram erro no campo certo`() {
        val e = erros(base.copy(limiteAgua = "40", limiteAtf = "400", limiteTensao = "20", calibracaoConsumo = "0"))
        assertEquals(ErroValidacao.NUMERO_INVALIDO, e[CampoVeiculo.LIMITE_AGUA])
        assertEquals(ErroValidacao.NUMERO_INVALIDO, e[CampoVeiculo.LIMITE_ATF])
        assertEquals(ErroValidacao.NUMERO_INVALIDO, e[CampoVeiculo.LIMITE_TENSAO])
        assertEquals(ErroValidacao.NUMERO_INVALIDO, e[CampoVeiculo.CALIBRACAO])
        assertEquals(ErroValidacao.NUMERO_INVALIDO, erros(base.copy(limiteAgua = "quente"))[CampoVeiculo.LIMITE_AGUA])
        assertEquals(4, e.size)
    }
}
