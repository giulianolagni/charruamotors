package com.charruamotors.sistema

import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.sessao.ConfigAutoConexao
import com.charruamotors.obd.sessao.EstadoSessao
import com.charruamotors.obd.sessao.FaseSessao
import com.charruamotors.obd.sessao.OrigemSessao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Status do cabeçalho do menu lateral, faixa da aba OBD2 e pré-seleções do assistente. */
class StatusCaixaPretaTest {

    private val obd = DispositivoObd("OBDII", "00:1D:A5:68:98:8B")
    private val carro = DispositivoObd("Toyota SW4", "AA:BB:CC:DD:EE:01")
    private val fone = DispositivoObd("Galaxy Buds2 Pro", "11:22:33:44:55:66")

    @Test
    fun `cabecalho conectado gravando fica verde`() {
        val e = EstadoSessao(FaseSessao.Conectada, OrigemSessao.AUTOMATICA, veiculo = "Toyota SW4", scanner = "OBDII", gravandoLogs = true)
        assertEquals(StatusCabecalho(true, "Conectado · Gravando log"), StatusCaixaPreta.cabecalho(e))
        assertEquals(StatusCabecalho(true, "Conectado"), StatusCaixaPreta.cabecalho(e.copy(gravandoLogs = false)))
    }

    @Test
    fun `cabecalho sem sessao fica cinza em espera`() {
        assertEquals(StatusCabecalho(false, "Em espera"), StatusCaixaPreta.cabecalho(EstadoSessao()))
    }

    @Test
    fun `cabecalho conectando fica cinza com o scanner`() {
        val e = EstadoSessao(FaseSessao.Conectando(2, 3), OrigemSessao.AUTOMATICA, veiculo = "Toyota SW4", scanner = "OBDII")
        val status = StatusCaixaPreta.cabecalho(e)
        assertFalse(status.conectado)
        assertEquals("Conectando ao OBDII (tentativa 2/3)", status.texto)
    }

    @Test
    fun `faixa da aba OBD2 mostra o gatilho`() {
        assertEquals("Caixa-preta ativa · Gatilho: Toyota SW4", StatusCaixaPreta.faixa(ConfigAutoConexao(), EstadoSessao()))
        assertEquals(
            "Caixa-preta desligada · toque para configurar",
            StatusCaixaPreta.faixa(ConfigAutoConexao(autoConexaoAtiva = false, gravarLogsAutomaticos = false), EstadoSessao()),
        )
        assertTrue(StatusCaixaPreta.faixa(ConfigAutoConexao(autoConexaoAtiva = false), EstadoSessao()).contains("só logs"))
    }

    @Test
    fun `faixa com sessao ativa mostra o status ao vivo`() {
        val e = EstadoSessao(FaseSessao.Conectada, OrigemSessao.AUTOMATICA, veiculo = "Toyota SW4", gravandoLogs = true)
        assertEquals("Caixa-preta · Conectado · Toyota SW4 · Gravando log", StatusCaixaPreta.faixa(ConfigAutoConexao(), e))
    }

    @Test
    fun `assistente pre-seleciona o OBDII`() {
        val adaptadorGenerico = DispositivoObd("Vgate iCar", "22:22:22:22:22:22")
        assertEquals(obd, PreSelecaoAssistente.scanner(listOf(fone, adaptadorGenerico, carro, obd), atual = null))
        assertEquals(DispositivoObd("OBD II", "33"), PreSelecaoAssistente.scanner(listOf(fone, DispositivoObd("OBD II", "33")), null))
        assertEquals(adaptadorGenerico, PreSelecaoAssistente.scanner(listOf(fone, adaptadorGenerico), null))
        assertNull(PreSelecaoAssistente.scanner(listOf(fone, carro), null))
    }

    @Test
    fun `assistente respeita o scanner ja vinculado`() {
        val outro = DispositivoObd("ELM327", "44:44:44:44:44:44")
        assertEquals(outro, PreSelecaoAssistente.scanner(listOf(obd, outro), atual = outro))
    }

    @Test
    fun `assistente pre-seleciona o Toyota SW4 pareado`() {
        val config = ConfigAutoConexao()
        assertEquals(carro, PreSelecaoAssistente.gatilho(listOf(fone, obd, carro), config, obd))
        assertNull(PreSelecaoAssistente.gatilho(listOf(fone, obd), config, obd))
        val porEndereco = config.copy(gatilhoNome = "Hilux", gatilhoEndereco = carro.endereco)
        assertEquals(carro, PreSelecaoAssistente.gatilho(listOf(fone, carro), porEndereco, null))
    }
}
