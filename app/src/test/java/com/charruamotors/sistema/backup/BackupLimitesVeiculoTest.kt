package com.charruamotors.sistema.backup

import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.Veiculo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.7: limites de alerta e calibração do consumo viajam no backup; arquivos antigos voltam aos padrões. */
class BackupLimitesVeiculoTest {

    private val sw4 = Veiculo(
        id = 7, marca = "Toyota", modelo = "SW4", ano = 2013, placa = "ABC1D23", kmAtual = 182_450,
        tipoCombustivel = TipoCombustivel.DIESEL, tempMaxAguaC = 103, tempMaxAtfC = 112, tensaoMinAlternadorV = 13.6, calibracaoConsumo = 1.18,
    )

    @Test
    fun `limites e calibracao fazem ida e volta no backup`() {
        val json = BackupJson.serializar(DadosBackup(geradoEm = "2026-11-23T10:15:30Z", versaoApp = "1.7.0 (9)", veiculos = listOf(sw4)))
        val volta = BackupJson.desserializar(json).veiculos.single()
        assertEquals(103, volta.tempMaxAguaC)
        assertEquals(112, volta.tempMaxAtfC)
        assertEquals(13.6, volta.tensaoMinAlternadorV, 0.0)
        assertEquals(1.18, volta.calibracaoConsumo, 0.0)
        assertEquals(sw4, volta)
    }

    @Test
    fun `backup de versao anterior sem os campos usa os padroes`() {
        val raiz = JSONObject(BackupJson.serializar(DadosBackup(geradoEm = "2026-11-23T10:15:30Z", versaoApp = "1.7.0 (9)", veiculos = listOf(sw4))))
        val veiculo = raiz.getJSONArray("veiculos").getJSONObject(0)
        listOf("tempMaxAguaC", "tempMaxAtfC", "tensaoMinAlternadorV", "calibracaoConsumo").forEach(veiculo::remove)
        val volta = BackupJson.desserializar(raiz.toString()).veiculos.single()
        assertEquals(98, volta.tempMaxAguaC)
        assertEquals(105, volta.tempMaxAtfC)
        assertEquals(13.2, volta.tensaoMinAlternadorV, 0.0)
        assertEquals(1.0, volta.calibracaoConsumo, 0.0)
    }
}
