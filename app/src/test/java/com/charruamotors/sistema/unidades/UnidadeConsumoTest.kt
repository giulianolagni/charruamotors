package com.charruamotors.sistema.unidades

import com.charruamotors.direcao.hud.FormatadorHud
import com.charruamotors.direcao.painel.ConfigHud
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.direcao.painel.TamanhoHud
import com.charruamotors.obd.DadosObd
import com.charruamotors.sistema.preferencias.ArmazenamentoChaveValor
import com.charruamotors.sistema.preferencias.PreferenciasApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.3.0 — consumo em km/L ou L/100km no Painel, HUD e relatórios; pressão bar/psi/kPa segue igual. */
class UnidadeConsumoTest {

    private val l100 = ConversorUnidades(SistemaUnidades.METRICO, UnidadePressao.BAR, UnidadeConsumo.L_100KM)

    @Test
    fun `km por litro vira litros a cada 100 km`() {
        assertEquals(8.0, UnidadeConsumo.L_100KM.deKmPorLitro(12.5), 1e-9)
        assertEquals(10.0, UnidadeConsumo.L_100KM.deKmPorLitro(10.0), 1e-9)
        assertEquals(12.5, UnidadeConsumo.KM_L.deKmPorLitro(12.5), 1e-9)
        // Parado (0 km/L) não tem L/100km finito; quase parado é limitado.
        assertTrue(UnidadeConsumo.L_100KM.deKmPorLitro(0.0).isNaN())
        assertTrue(UnidadeConsumo.L_100KM.deKmPorLitro(Double.NaN).isNaN())
        assertEquals(UnidadeConsumo.MAX_L_100KM, UnidadeConsumo.L_100KM.deKmPorLitro(0.2), 1e-9)
    }

    @Test
    fun `conversor aplica L por 100 km so no metrico`() {
        assertEquals("L/100km", l100.simboloConsumo)
        assertEquals(8.0, l100.consumo(12.5), 1e-9)
        val exibido = l100.converter(12.5, "km/L", 1)
        assertEquals("L/100km", exibido.unidade)
        assertEquals(8.0, exibido.valor, 1e-9)
        assertEquals(1, exibido.casas)
        // A pressão continua na escolha do usuário.
        assertEquals("bar", l100.simboloPressao)

        // No imperial o consumo é sempre MPG (a escolha de L/100km é ignorada).
        val imperial = ConversorUnidades(SistemaUnidades.IMPERIAL, UnidadePressao.KPA, UnidadeConsumo.L_100KM)
        assertEquals("MPG", imperial.simboloConsumo)
        assertEquals(12.5 * 3.785411784 / 1.609344, imperial.consumo(12.5), 1e-9)
        assertEquals("psi", imperial.simboloPressao)

        // Padrão: km/L (nada muda para quem não escolheu).
        assertEquals("km/L", ConversorUnidades.PADRAO.simboloConsumo)
        assertEquals(UnidadeConsumo.KM_L, UnidadeConsumo.deCodigo(null))
        assertEquals(UnidadeConsumo.L_100KM, UnidadeConsumo.deCodigo("l_100km"))
    }

    @Test
    fun `mostrador de consumo ganha escala e unidade de L por 100 km`() {
        assertEquals("L/100km", SensorPainel.CONSUMO_KML.unidade(l100))
        assertEquals(30f, SensorPainel.CONSUMO_KML.escala(l100).maximo)
        assertEquals("8,0", SensorPainel.CONSUMO_KML.texto(12.5, l100))
    }

    @Test
    fun `HUD mostra o consumo na unidade global`() {
        // 60 km/h gastando 5 L/h = 12 km/L = 8,3 L/100km.
        val dados = DadosObd(mapOf("015E" to 5.0, "010D" to 60.0))
        val config = ConfigHud(tamanho = TamanhoHud.EXPANDIDO, sensores = listOf(SensorPainel.CONSUMO_KML))
        val celula = FormatadorHud.montar(dados, conectado = true, config = config, conversor = l100).celulas.single()
        assertEquals("CONSUMO L/100km", celula.rotulo)
        assertEquals("8,3", celula.valor)
        val padrao = FormatadorHud.montar(dados, conectado = true, config = config).celulas.single()
        assertEquals("CONSUMO km/L", padrao.rotulo)
        assertEquals("12,0", padrao.valor)
    }

    @Test
    fun `preferencia de consumo persiste e entra no conversor`() {
        val memoria = object : ArmazenamentoChaveValor {
            val valores = mutableMapOf<String, Any?>()
            override fun lerTexto(chave: String): String? = valores[chave] as? String
            override fun lerBooleano(chave: String, padrao: Boolean): Boolean = valores[chave] as? Boolean ?: padrao
            override fun gravar(valores: Map<String, Any?>) {
                this.valores.putAll(valores)
            }
        }
        val prefs = PreferenciasApp(memoria)
        assertEquals(UnidadeConsumo.KM_L, prefs.unidadeConsumo)
        prefs.definirUnidadeConsumo(UnidadeConsumo.L_100KM)
        prefs.definirUnidadePressao(UnidadePressao.PSI)
        val reaberto = PreferenciasApp(memoria)
        assertEquals(UnidadeConsumo.L_100KM, reaberto.unidadeConsumo)
        assertEquals("L/100km", reaberto.config.value.conversor.simboloConsumo)
        assertEquals("psi", reaberto.config.value.conversor.simboloPressao)
        // Restaurar (backup) troca as unidades juntas.
        reaberto.restaurar(reaberto.tema, UnidadePressao.KPA, SistemaUnidades.METRICO, UnidadeConsumo.KM_L)
        assertEquals(UnidadeConsumo.KM_L, PreferenciasApp(memoria).unidadeConsumo)
    }
}
