package com.charruamotors.domain.calculo

import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.pid.Pids
import com.charruamotors.obd.sessao.SessaoObd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.1: Modo Flex Automático — estequiometria pelo 0152, classificação, A/F real e consumo flex de 0 a 100 % de etanol. */
class CombustivelFlexTest {

    private val flex = ParametrosMotor(cilindradaLitros = 1.4, tipoCombustivel = TipoCombustivel.FLEX)

    private fun dados(vararg pares: Pair<String, Double>) = DadosObd(mapOf(*pares))

    @Test
    fun `estequiometria de referencia vai de 13,2 no E27 a 9,0 no E100`() {
        assertEquals(14.7, EstequiometriaFlex.relacaoAf(0.0), 0.0)
        assertEquals(13.2, EstequiometriaFlex.relacaoAf(27.0), 0.0)
        assertEquals(9.0, EstequiometriaFlex.relacaoAf(100.0), 0.0)
        // Interpolação linear entre as âncoras: E50 ≈ 11,88:1; E13,5 = meio caminho 14,7 → 13,2.
        assertEquals(13.2 - 4.2 * 23.0 / 73.0, EstequiometriaFlex.relacaoAf(50.0), 1e-9)
        assertEquals(13.95, EstequiometriaFlex.relacaoAf(13.5), 1e-9)
        // Satura fora de 0–100 %.
        assertEquals(9.0, EstequiometriaFlex.relacaoAf(130.0), 0.0)
        assertEquals(14.7, EstequiometriaFlex.relacaoAf(-5.0), 0.0)
    }

    @Test
    fun `estequiometria cai sempre que o etanol sobe`() {
        val curva = (0..100).map { EstequiometriaFlex.relacaoAf(it.toDouble()) }
        assertTrue(curva.zipWithNext().all { (a, b) -> b < a })
    }

    @Test
    fun `classifica gasolina E27, mistura flex e etanol E100`() {
        assertEquals(ClasseCombustivel.GASOLINA_E27, EstequiometriaFlex.classificar(0.0))
        assertEquals(ClasseCombustivel.GASOLINA_E27, EstequiometriaFlex.classificar(27.0))
        assertEquals(ClasseCombustivel.GASOLINA_E27, EstequiometriaFlex.classificar(39.9))
        assertEquals(ClasseCombustivel.MISTURA_FLEX, EstequiometriaFlex.classificar(40.0))
        assertEquals(ClasseCombustivel.MISTURA_FLEX, EstequiometriaFlex.classificar(84.0))
        assertEquals(ClasseCombustivel.ETANOL_E100, EstequiometriaFlex.classificar(85.0))
        assertEquals(ClasseCombustivel.ETANOL_E100, EstequiometriaFlex.classificar(100.0))
    }

    @Test
    fun `auto flex segue o 0152 e a selecao manual fixa o combustivel`() {
        val mistura = EstequiometriaFlex.avaliar(TipoCombustivel.FLEX, 48.0)
        assertTrue(mistura.automatico)
        assertEquals(ClasseCombustivel.MISTURA_FLEX, mistura.classe)
        assertEquals(EstequiometriaFlex.relacaoAf(48.0), mistura.relacaoAfReferencia, 0.0)
        assertTrue(mistura.resumo, mistura.resumo.startsWith("Mistura Flex (E48) · A/F 12,0:1"))

        val semPid = EstequiometriaFlex.avaliar(TipoCombustivel.FLEX, null)
        assertFalse(semPid.automatico)
        assertEquals(ClasseCombustivel.GASOLINA_E27, semPid.classe)
        assertEquals(13.2, semPid.relacaoAfReferencia, 0.0)

        // Manual: o 0152 não muda Gasolina, Etanol nem Diesel.
        assertEquals(13.2, EstequiometriaFlex.avaliar(TipoCombustivel.GASOLINA, 90.0).relacaoAfReferencia, 0.0)
        assertEquals(9.0, EstequiometriaFlex.avaliar(TipoCombustivel.ETANOL, 10.0).relacaoAfReferencia, 0.0)
        assertEquals(ClasseCombustivel.DIESEL, EstequiometriaFlex.avaliar(TipoCombustivel.DIESEL, 50.0).classe)
        assertEquals(ClasseCombustivel.GASOLINA_E27, EstequiometriaFlex.avaliar(null, 10.0).classe) // sem cadastro = Auto Flex
    }

    @Test
    fun `mistura flex usa a mesma estequiometria e densidade por volume`() {
        assertEquals(Combustivel.GASOLINA_PURA, Combustivel.mistura(0.0))
        assertEquals(Combustivel.ETANOL_HIDRATADO, Combustivel.mistura(1.0))
        val e50 = Combustivel.mistura(0.5)
        assertEquals(EstequiometriaFlex.relacaoAf(50.0), e50.relacaoEstequiometrica, 1e-9)
        assertEquals((740.0 + 809.0) / 2, e50.densidadeGL, 1e-9)
    }

    @Test
    fun `consumo flex via MAF de 0 a 100 por cento de etanol`() {
        val maf = 10.0
        val litros = (0..100 step 10).map { pct ->
            val c = TelemetriaCalculada.consumoInstantaneo(dados(Pids.MAF to maf, Pids.PERCENTUAL_ETANOL to pct.toDouble()), flex)!!
            assertEquals(MetodoConsumo.MAF, c.metodo)
            val esperado = maf / EstequiometriaFlex.relacaoAf(pct.toDouble()) * 3600.0 / (740.0 + 69.0 * pct / 100.0)
            assertEquals("E$pct", esperado, c.litrosPorHora, 1e-9)
            c.litrosPorHora
        }
        // Mais etanol = mais litros pela mesma massa de ar: E0 3,31 L/h → E100 4,94 L/h (+49 %).
        assertTrue(litros.zipWithNext().all { (a, b) -> b > a })
        assertEquals(3.31, litros.first(), 0.01)
        assertEquals(4.94, litros.last(), 0.01)
    }

    @Test
    fun `relacao AF real e lambda medido vezes a estequiometria`() {
        // Banda larga 0124 tem prioridade; sem ela, 0134; sem nenhuma, o λ comandado 0144.
        val e48 = dados(Pids.PERCENTUAL_ETANOL to 48.0, Pids.LAMBDA_BANDA_LARGA to 0.95, Pids.LAMBDA_COMANDADO to 1.0)
        assertEquals(0.95 * EstequiometriaFlex.relacaoAf(48.0), EstequiometriaFlex.relacaoAfReal(e48, TipoCombustivel.FLEX)!!, 1e-9)
        val soComandado = dados(Pids.LAMBDA_COMANDADO to 1.0)
        assertEquals(13.2, EstequiometriaFlex.relacaoAfReal(soComandado, TipoCombustivel.GASOLINA)!!, 1e-9)
        assertEquals(9.0 * 1.02, EstequiometriaFlex.relacaoAfReal(dados(Pids.LAMBDA_BANDA_LARGA_CORRENTE to 1.02), TipoCombustivel.ETANOL)!!, 1e-9)
    }

    @Test
    fun `sem lambda, diesel ou corte de combustivel nao ha AF real`() {
        assertNull(EstequiometriaFlex.relacaoAfReal(dados(Pids.PERCENTUAL_ETANOL to 27.0), TipoCombustivel.FLEX))
        assertNull(EstequiometriaFlex.relacaoAfReal(dados(Pids.LAMBDA_BANDA_LARGA to 1.0), TipoCombustivel.DIESEL))
        // Corte na desaceleração: a sonda satura no pobre (1,99) e cai para o comandado.
        val corte = dados(Pids.LAMBDA_BANDA_LARGA to 1.99, Pids.LAMBDA_COMANDADO to 1.0)
        assertEquals(13.2, EstequiometriaFlex.relacaoAfReal(corte, TipoCombustivel.FLEX)!!, 1e-9)
        assertNull(EstequiometriaFlex.relacaoAfReal(dados(Pids.LAMBDA_BANDA_LARGA to 1.99), TipoCombustivel.FLEX))
    }

    @Test
    fun `sessao mescla a AF real calculada no instantaneo`() {
        val amostra = dados(Pids.RPM to 2000.0, Pids.LAMBDA_BANDA_LARGA to 1.0, Pids.PERCENTUAL_ETANOL to 100.0)
        val com = SessaoObd.comCalculados(amostra, TipoCombustivel.FLEX)
        assertEquals(9.0, com[Pids.RELACAO_AF_REAL]!!, 1e-9)
        assertEquals(2000.0, com[Pids.RPM]!!, 0.0)
        // Diesel: nada muda.
        assertSame(amostra, SessaoObd.comCalculados(amostra, TipoCombustivel.DIESEL))
    }
}
