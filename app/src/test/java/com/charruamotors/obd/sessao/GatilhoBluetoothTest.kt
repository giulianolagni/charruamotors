package com.charruamotors.obd.sessao

import com.charruamotors.obd.DispositivoObd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reconhecimento do Bluetooth do carro ("Toyota SW4") e a decisão de iniciar/encerrar a sessão. */
class GatilhoBluetoothTest {

    private val padrao = ConfigAutoConexao()
    private val carro = "AA:BB:CC:DD:EE:01"
    private val obd = DispositivoObd("OBDII", "00:1D:A5:68:98:8B")

    @Test
    fun `gatilho padrao e Toyota SW4 com auto-conexao e logs ligados`() {
        assertEquals("Toyota SW4", padrao.gatilhoNome)
        assertTrue(padrao.autoConexaoAtiva)
        assertTrue(padrao.gravarLogsAutomaticos)
    }

    @Test
    fun `nome do carro casa ignorando maiusculas e espacos`() {
        listOf("Toyota SW4", "toyota sw4", "TOYOTA SW4", "  Toyota   SW4 ", "ToyotaSW4", "Toyota\tSW4")
            .forEach { assertTrue(it, GatilhoBluetooth.corresponde(it, carro, padrao)) }
    }

    @Test
    fun `outros aparelhos nao disparam`() {
        listOf("Toyota", "SW4", "Toyota SW4 2", "Galaxy Buds2 Pro", "OBDII", "", null)
            .forEach { assertFalse(it.toString(), GatilhoBluetooth.corresponde(it, carro, padrao)) }
    }

    @Test
    fun `aparelho escolhido na lista casa pelo endereco mesmo com outro nome`() {
        val config = padrao.copy(gatilhoNome = "Hilux", gatilhoEndereco = carro)
        assertTrue(GatilhoBluetooth.corresponde("CAR MULTIMEDIA", carro.lowercase(), config))
        assertTrue(GatilhoBluetooth.corresponde("hilux", "11:22:33:44:55:66", config))
        assertFalse(GatilhoBluetooth.corresponde("Toyota SW4", "11:22:33:44:55:66", config))
    }

    @Test
    fun `carro conectou com auto-conexao ligada inicia a sessao`() {
        assertEquals(
            AcaoGatilho.INICIAR_SESSAO,
            GatilhoBluetooth.decidir(EventoBluetooth.CONECTADO, "Toyota SW4", carro, padrao, sessaoAutomaticaAtiva = false),
        )
    }

    @Test
    fun `auto-conexao desligada ignora o carro`() {
        val desligada = padrao.copy(autoConexaoAtiva = false)
        assertEquals(AcaoGatilho.IGNORAR, GatilhoBluetooth.decidir(EventoBluetooth.CONECTADO, "Toyota SW4", carro, desligada, false))
    }

    @Test
    fun `carro desconectou encerra so a sessao automatica`() {
        assertEquals(
            AcaoGatilho.ENCERRAR_SESSAO,
            GatilhoBluetooth.decidir(EventoBluetooth.DESCONECTADO, "Toyota SW4", carro, padrao, sessaoAutomaticaAtiva = true),
        )
        // Sessão manual (ou nenhuma): o carro desligar o rádio não derruba a conexão do usuário.
        assertEquals(
            AcaoGatilho.IGNORAR,
            GatilhoBluetooth.decidir(EventoBluetooth.DESCONECTADO, "Toyota SW4", carro, padrao, sessaoAutomaticaAtiva = false),
        )
    }

    @Test
    fun `o proprio scanner OBD nunca e gatilho`() {
        // Mesmo que o usuário configure o nome do adaptador por engano, a conexão RFCOMM do scanner
        // gera ACL_CONNECTED/DISCONNECTED e não pode iniciar nem encerrar a sessão.
        val config = padrao.copy(gatilhoNome = "OBDII", scanner = obd)
        assertEquals(AcaoGatilho.IGNORAR, GatilhoBluetooth.decidir(EventoBluetooth.CONECTADO, "OBDII", obd.endereco, config, false))
        assertEquals(AcaoGatilho.IGNORAR, GatilhoBluetooth.decidir(EventoBluetooth.DESCONECTADO, "OBDII", obd.endereco, config, true))
    }

    @Test
    fun `fones e relogio sao ignorados nos dois eventos`() {
        EventoBluetooth.entries.forEach { evento ->
            assertEquals(AcaoGatilho.IGNORAR, GatilhoBluetooth.decidir(evento, "Galaxy Watch6", "99:88:77:66:55:44", padrao, true))
        }
    }

    // ------------------------------------------------------------------ scanner da sessão automática

    private val buds = DispositivoObd("Galaxy Buds2 Pro", "01")
    private val radio = DispositivoObd("Toyota SW4", "02")
    private val vlink = DispositivoObd("V-LINK", "03")

    @Test
    fun `sessao automatica usa o scanner vinculado se ainda estiver pareado`() {
        assertEquals(obd, EscolhaScanner.paraSessaoAutomatica(listOf(buds, radio, vlink, obd), vinculado = obd))
    }

    @Test
    fun `sem vinculo usa o primeiro com nome de OBD e nunca o radio do carro`() {
        assertEquals(vlink, EscolhaScanner.paraSessaoAutomatica(listOf(buds, radio, vlink), vinculado = null))
        assertNull(EscolhaScanner.paraSessaoAutomatica(listOf(buds, radio), vinculado = null))
    }

    @Test
    fun `lista de pareados indisponivel tenta o scanner salvo`() {
        assertEquals(obd, EscolhaScanner.paraSessaoAutomatica(emptyList(), vinculado = obd))
    }

    // ------------------------------------------------------------------ texto da notificação

    @Test
    fun `status da notificacao`() {
        val base = EstadoSessao(origem = OrigemSessao.AUTOMATICA, veiculo = "Toyota SW4", scanner = "OBDII", gatilho = "Toyota SW4")
        assertEquals("Conectado · Toyota SW4 · Gravando log", SessaoObd.descreverStatus(base.copy(fase = FaseSessao.Conectada, gravandoLogs = true)))
        assertEquals("Conectado · Toyota SW4", SessaoObd.descreverStatus(base.copy(fase = FaseSessao.Conectada)))
        assertEquals("Aguardando a ECU · Toyota SW4", SessaoObd.descreverStatus(base.copy(fase = FaseSessao.AguardandoEcu)))
        assertEquals(
            "Conectando ao OBDII (tentativa 2/3) · Toyota SW4",
            SessaoObd.descreverStatus(base.copy(fase = FaseSessao.Conectando(2, 3))),
        )
        assertEquals("Encerrando · salvando logs", SessaoObd.descreverStatus(base.copy(fase = FaseSessao.Encerrando)))
    }
}
