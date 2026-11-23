package com.charruamotors.sistema.unidades

import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.sistema.preferencias.ArmazenamentoChaveValor
import com.charruamotors.sistema.preferencias.PreferenciasApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conversão global métrico ↔ imperial de todas as grandezas exibidas no app e no HUD. */
class ConversorUnidadesTest {

    private val metrico = ConversorUnidades(SistemaUnidades.METRICO)
    private val imperial = ConversorUnidades(SistemaUnidades.IMPERIAL)
    private val delta = 1e-6

    @Test
    fun `metrico mantem os valores do OBD2`() {
        assertEquals(100.0, metrico.velocidade(100.0), delta)
        assertEquals(90.0, metrico.temperatura(90.0), delta)
        assertEquals(182.0, metrico.pressao(182.0), delta)
        assertEquals(12.5, metrico.consumo(12.5), delta)
        assertEquals(42.0, metrico.distancia(42.0), delta)
        assertEquals(50.0, metrico.volume(50.0), delta)
        assertEquals("km/h", metrico.simboloVelocidade)
        assertEquals("°C", metrico.simboloTemperatura)
        assertEquals("kPa", metrico.simboloPressao)
        assertEquals("km/L", metrico.simboloConsumo)
        assertEquals("km", metrico.simboloDistancia)
        assertEquals("L", metrico.simboloVolume)
        assertEquals("L/h", metrico.simboloVazao)
    }

    @Test
    fun `imperial converte velocidade para mph`() {
        assertEquals(62.137119, imperial.velocidade(100.0), 1e-5)
        assertEquals(0.0, imperial.velocidade(0.0), delta)
        assertEquals("mph", imperial.simboloVelocidade)
    }

    @Test
    fun `imperial converte temperatura para Fahrenheit`() {
        assertEquals(32.0, imperial.temperatura(0.0), delta)
        assertEquals(212.0, imperial.temperatura(100.0), delta)
        assertEquals(-40.0, imperial.temperatura(-40.0), delta)
        assertEquals(197.6, imperial.temperatura(92.0), 1e-9)
        assertEquals("°F", imperial.simboloTemperatura)
    }

    @Test
    fun `imperial usa sempre psi mesmo com bar escolhido no metrico`() {
        val imperialComBar = ConversorUnidades(SistemaUnidades.IMPERIAL, UnidadePressao.BAR)
        assertEquals(UnidadePressao.PSI, imperialComBar.pressao)
        assertEquals(14.503774, imperialComBar.pressao(100.0), 1e-5)
        assertEquals("psi", imperialComBar.simboloPressao)
        // No métrico vale a escolha de kPa / bar.
        assertEquals(UnidadePressao.BAR, ConversorUnidades(SistemaUnidades.METRICO, UnidadePressao.BAR).pressao)
        assertEquals(1.82, ConversorUnidades(SistemaUnidades.METRICO, UnidadePressao.BAR).pressao(182.0), delta)
    }

    @Test
    fun `imperial converte consumo para MPG americano`() {
        assertEquals(2.352146, imperial.consumo(1.0), 1e-6)
        assertEquals(23.52146, imperial.consumo(10.0), 1e-5)
        assertEquals("MPG", imperial.simboloConsumo)
    }

    @Test
    fun `imperial converte distancia volume e vazao`() {
        assertEquals(1.0, imperial.distancia(1.609344), delta)
        assertEquals(62.137119, imperial.distancia(100.0), 1e-5)
        assertEquals(1.0, imperial.volume(3.785411784), delta)
        assertEquals(13.208603, imperial.volume(50.0), 1e-5)
        assertEquals(0.792516, imperial.vazao(3.0), 1e-5)
        assertEquals("mi", imperial.simboloDistancia)
        assertEquals("gal", imperial.simboloVolume)
        assertEquals("gal/h", imperial.simboloVazao)
    }

    @Test
    fun `converter pela unidade do catalogo devolve simbolo e casas de destino`() {
        assertEquals(ValorExibicao(100.0, "km/h", 0), metrico.converter(100.0, "km/h"))
        val vel = imperial.converter(100.0, "km/h")
        assertEquals("mph", vel.unidade)
        assertEquals(62.137, vel.valor, 1e-3)

        assertEquals("°F", imperial.converter(90.0, "°C").unidade)
        assertEquals(194.0, imperial.converter(90.0, "°C").valor, delta)

        val turbo = imperial.converter(182.0, "kPa")
        assertEquals("psi", turbo.unidade)
        assertEquals(1, turbo.casas) // psi com 1 casa
        assertEquals(2, ConversorUnidades(pressaoMetrica = UnidadePressao.BAR).converter(182.0, "kPa").casas)

        assertEquals("MPG", imperial.converter(12.0, "km/L").unidade)
        assertEquals("mi", imperial.converter(12.0, "km").unidade)
        assertEquals(1, imperial.converter(12.0, "km").casas)
        assertEquals("gal", imperial.converter(12.0, "L").unidade)
        assertEquals("gal/h", imperial.converter(3.0, "L/h").unidade)
    }

    @Test
    fun `unidades sem equivalente imperial passam intactas`() {
        for (unidade in listOf("rpm", "%", "V", "g/s", "°", "λ", "s", "mm³/st")) {
            assertEquals(ValorExibicao(12.3, unidade, 1), imperial.converter(12.3, unidade, 1))
        }
    }

    @Test
    fun `aceita variacoes de grafia do catalogo`() {
        assertEquals("mph", imperial.simbolo("KM/H"))
        assertEquals("°F", imperial.simbolo("ºC"))
        assertEquals("psi", imperial.simbolo("kpa"))
        assertEquals("MPG", imperial.simbolo("km/l"))
    }

    @Test
    fun `escalas dos mostradores acompanham a unidade global`() {
        assertEquals(240f, SensorPainel.VELOCIDADE.escala(metrico).maximo)
        assertEquals(160f, SensorPainel.VELOCIDADE.escala(imperial).maximo)
        assertEquals("°F", SensorPainel.TEMP_MOTOR.unidade(imperial))
        assertEquals(230f, SensorPainel.TEMP_MOTOR.escala(imperial).zonaVermelha)
        assertEquals(35f, SensorPainel.TURBO.escala(imperial).maximo)
        assertEquals(2.5f, SensorPainel.TURBO.escala(ConversorUnidades(pressaoMetrica = UnidadePressao.BAR)).maximo)
        assertEquals("bar", SensorPainel.RAIL.unidade(metrico))
        assertEquals("psi", SensorPainel.RAIL.unidade(imperial))
        // Todos os sensores têm escala válida nos dois sistemas.
        for (s in SensorPainel.entries) {
            assertTrue(s.escala(metrico).maximo > s.escala(metrico).minimo)
            assertTrue(s.escala(imperial).maximo > s.escala(imperial).minimo)
        }
    }

    @Test
    fun `preferencia de unidades globais persiste e gera o conversor`() {
        val memoria = mutableMapOf<String, Any?>()
        val armazenamento = object : ArmazenamentoChaveValor {
            override fun lerTexto(chave: String) = memoria[chave] as? String
            override fun lerBooleano(chave: String, padrao: Boolean) = memoria[chave] as? Boolean ?: padrao
            override fun gravar(valores: Map<String, Any?>) = memoria.putAll(valores)
        }
        val prefs = PreferenciasApp(armazenamento)
        assertEquals(SistemaUnidades.METRICO, prefs.sistemaUnidades)
        assertFalse(prefs.config.value.conversor.imperial)
        prefs.definirSistemaUnidades(SistemaUnidades.IMPERIAL)
        val reaberto = PreferenciasApp(armazenamento)
        assertEquals(SistemaUnidades.IMPERIAL, reaberto.sistemaUnidades)
        assertEquals(UnidadePressao.PSI, reaberto.config.value.conversor.pressao)
        assertEquals(SistemaUnidades.METRICO, SistemaUnidades.deCodigo("desconhecido"))
    }
}
