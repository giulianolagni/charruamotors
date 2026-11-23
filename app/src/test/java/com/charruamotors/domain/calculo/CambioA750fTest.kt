package com.charruamotors.domain.calculo

import com.charruamotors.direcao.painel.ClusterPainel
import com.charruamotors.direcao.painel.IndicadorMarcha
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.ObdParser
import com.charruamotors.obd.RespostaObd
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.pid.Decodificadores
import com.charruamotors.obd.pid.Pids
import com.charruamotors.sistema.unidades.ConversorUnidades
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Câmbio A750F da SW4 2013 pelo log real: alavanca nos bytes C/D do `7E1 · 21D9` e marcha estimada
 * pela razão rpm/velocidade (o `7E1 · 2130` devolve `6130FF` fixo mesmo a 110 km/h).
 */
class CambioA750fTest {

    private val conversor = ConversorUnidades.PADRAO
    private val alavanca = CatalogoTeste.estendido["TOYOTA_ALAVANCA"]!!

    /** `0:61D9 A B C D` + ATF: só C e D variam. */
    private fun d9(c: Int, d: Int): Double {
        val bruto = "00D\r0:61D90000%02X%02X\r1:42E00000000000\r\r".format(c, d)
        return (ObdParser.parsePid(bruto, alavanca) as RespostaObd.Ok).valor
    }

    private fun dados(vararg v: Pair<String, Double>) = DadosObd(mapOf(*v))

    // ---------------------------------------------------------------- 7E1 · 21D9: alavanca

    @Test
    fun `21D9 do 7E1 com C D 00 00 e P-N`() {
        assertEquals(Decodificadores.ALAVANCA_P_N, d9(0x00, 0x00), 0.0)
        assertEquals("P/N", alavanca.rotulo(d9(0x00, 0x00)))
    }

    @Test
    fun `21D9 do 7E1 com C D 04 00 e R`() {
        assertEquals(Decodificadores.ALAVANCA_R, d9(0x04, 0x00), 0.0)
        assertEquals("R", alavanca.rotulo(d9(0x04, 0x00)))
    }

    @Test
    fun `21D9 do 7E1 com C D 00 04 e D`() {
        assertEquals(Decodificadores.ALAVANCA_D, d9(0x00, 0x04), 0.0)
        assertEquals("D", alavanca.rotulo(d9(0x00, 0x04)))
    }

    @Test
    fun `mostrador Alavanca prioriza o 7E1 21D9 sobre o 7E0 que sempre devolve P`() {
        val leitura = dados("TOYOTA_ALAVANCA" to Decodificadores.ALAVANCA_D, "TOYOTA_ALAVANCA_ECM" to 0.0)
        assertEquals("D", SensorPainel.MARCHA_ALAVANCA.texto(SensorPainel.MARCHA_ALAVANCA.ler(leitura), conversor))
        // Antes o 00 00 virava NaN e o mostrador caía no 7E0 (sempre "P"); agora o 7E1 responde P/N.
        val pn = dados("TOYOTA_ALAVANCA" to d9(0, 0), "TOYOTA_ALAVANCA_ECM" to 0.0)
        assertEquals("P/N", SensorPainel.MARCHA_ALAVANCA.texto(SensorPainel.MARCHA_ALAVANCA.ler(pn), conversor))
    }

    // ---------------------------------------------------------------- marcha pela razão rpm/velocidade

    @Test
    fun `razao rpm por velocidade da 1a a 5a da A750F`() {
        assertEquals(1, CambioA750f.marchaPorRazao(1_800.0, 20.0)) // 90
        assertEquals(1, CambioA750f.marchaPorRazao(850.0, 10.0)) // 85 (limite)
        assertEquals(2, CambioA750f.marchaPorRazao(1_680.0, 20.0)) // 84
        assertEquals(2, CambioA750f.marchaPorRazao(1_650.0, 30.0)) // 55 (limite)
        assertEquals(3, CambioA750f.marchaPorRazao(1_620.0, 30.0)) // 54
        assertEquals(3, CambioA750f.marchaPorRazao(1_800.0, 50.0)) // 36 (limite)
        assertEquals(4, CambioA750f.marchaPorRazao(1_750.0, 50.0)) // 35
        assertEquals(4, CambioA750f.marchaPorRazao(2_080.0, 80.0)) // 26 (limite)
        assertEquals(5, CambioA750f.marchaPorRazao(2_000.0, 80.0)) // 25
        assertEquals(5, CambioA750f.marchaPorRazao(2_200.0, 110.0)) // 20: o log a 110 km/h
    }

    @Test
    fun `razao nao vale abaixo de 5 km por h nem sem leitura`() {
        assertNull(CambioA750f.marchaPorRazao(900.0, 4.9))
        assertNull(CambioA750f.marchaPorRazao(900.0, 0.0))
        assertNull(CambioA750f.marchaPorRazao(null, 40.0))
        assertNull(CambioA750f.marchaPorRazao(Double.NaN, 40.0))
        assertNull(CambioA750f.marchaPorRazao(1_500.0, null))
    }

    @Test
    fun `marcha exibida R em re e Parado em P-N ou abaixo de 5 km por h`() {
        assertEquals(CambioA750f.MARCHA_RE, CambioA750f.marchaExibida(Decodificadores.ALAVANCA_R, 1_000.0, 6.0))
        assertEquals(CambioA750f.MARCHA_PARADO, CambioA750f.marchaExibida(Decodificadores.ALAVANCA_P, 750.0, 0.0))
        assertEquals(CambioA750f.MARCHA_PARADO, CambioA750f.marchaExibida(Decodificadores.ALAVANCA_P_N, 750.0, 0.0))
        assertEquals(CambioA750f.MARCHA_PARADO, CambioA750f.marchaExibida(Decodificadores.ALAVANCA_D, 750.0, 3.0))
        assertEquals(3, CambioA750f.marchaExibida(Decodificadores.ALAVANCA_D, 1_800.0, 40.0))
        assertEquals("R", SensorPainel.MARCHA_ATUAL.texto(CambioA750f.MARCHA_RE.toDouble(), conversor))
        assertEquals("Parado", SensorPainel.MARCHA_ATUAL.texto(CambioA750f.MARCHA_PARADO.toDouble(), conversor))
    }

    // ---------------------------------------------------------------- estimador da sessão

    @Test
    fun `estimador troca o 0xFF do 2130 pela marcha estimada em D`() {
        val e = EstimadorCambioA750f()
        val saida = e.aplicar(
            dados("TOYOTA_ALAVANCA" to Decodificadores.ALAVANCA_D, "TOYOTA_MARCHA" to 255.0, Pids.RPM to 2_200.0, Pids.VELOCIDADE to 110.0),
            agoraMs = 0,
        )
        assertEquals(5.0, saida["TOYOTA_MARCHA"]!!, 0.0)
        assertEquals(IndicadorMarcha("5ª", "D"), ClusterPainel.indicadorMarcha(saida))
    }

    @Test
    fun `estimador sem 2130 no polling publica a marcha e em re mostra R`() {
        val e = EstimadorCambioA750f()
        val d = e.aplicar(dados("TOYOTA_ALAVANCA" to 3.0, Pids.RPM to 1_700.0, Pids.VELOCIDADE to 25.0), agoraMs = 0)
        assertEquals(2.0, d["TOYOTA_MARCHA"]!!, 0.0) // 68
        val r = e.aplicar(dados("TOYOTA_ALAVANCA" to 1.0, Pids.RPM to 1_000.0, Pids.VELOCIDADE to 3.0), agoraMs = 100)
        assertEquals("R", SensorPainel.MARCHA_ATUAL.texto(r["TOYOTA_MARCHA"], conversor))
        assertEquals(IndicadorMarcha("R"), ClusterPainel.indicadorMarcha(r))
    }

    @Test
    fun `P-N vira P parado e N em movimento ou logo depois dele`() {
        val e = EstimadorCambioA750f(memoriaMovimentoMs = 3_000)
        val pn = Decodificadores.ALAVANCA_P_N
        // Ligou parado: P e "Parado".
        val parado = e.aplicar(dados("TOYOTA_ALAVANCA" to pn, Pids.RPM to 750.0, Pids.VELOCIDADE to 0.0), agoraMs = 0)
        assertEquals(Decodificadores.ALAVANCA_P, parado["TOYOTA_ALAVANCA"]!!, 0.0)
        assertEquals("Parado", SensorPainel.MARCHA_ATUAL.texto(parado["TOYOTA_MARCHA"], conversor))
        assertEquals(IndicadorMarcha("P"), ClusterPainel.indicadorMarcha(parado))
        // Banguela a 40 km/h: N.
        val andando = e.aplicar(dados("TOYOTA_ALAVANCA" to pn, Pids.RPM to 800.0, Pids.VELOCIDADE to 40.0), agoraMs = 10_000)
        assertEquals(Decodificadores.ALAVANCA_N, andando["TOYOTA_ALAVANCA"]!!, 0.0)
        assertEquals(IndicadorMarcha("N"), ClusterPainel.indicadorMarcha(andando))
        // Acabou de parar: ainda N (movimento recente)...
        val logoDepois = e.aplicar(dados("TOYOTA_ALAVANCA" to pn, Pids.VELOCIDADE to 0.0), agoraMs = 11_000)
        assertEquals(Decodificadores.ALAVANCA_N, logoDepois["TOYOTA_ALAVANCA"]!!, 0.0)
        assertEquals("Parado", SensorPainel.MARCHA_ATUAL.texto(logoDepois["TOYOTA_MARCHA"], conversor))
        // ...e P depois da memória de movimento.
        val depois = e.aplicar(dados("TOYOTA_ALAVANCA" to pn, Pids.VELOCIDADE to 0.0), agoraMs = 13_500)
        assertEquals(Decodificadores.ALAVANCA_P, depois["TOYOTA_ALAVANCA"]!!, 0.0)
    }

    @Test
    fun `estimador respeita marcha valida do 2130 e ignora veiculos sem a alavanca do 7E1`() {
        val e = EstimadorCambioA750f()
        val valida = e.aplicar(dados("TOYOTA_ALAVANCA" to 3.0, "TOYOTA_MARCHA" to 4.0, Pids.RPM to 3_000.0, Pids.VELOCIDADE to 20.0), 0)
        assertEquals(4.0, valida["TOYOTA_MARCHA"]!!, 0.0)
        val outro = dados(Pids.RPM to 2_000.0, Pids.VELOCIDADE to 60.0)
        assertSame(outro, e.aplicar(outro, 0))
    }
}
