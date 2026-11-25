package com.charruamotors.sistema

import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.domain.perfis.ImpressaoEcu
import com.charruamotors.obd.InfoConexao
import com.charruamotors.obd.ModoComunicacaoEcu
import com.charruamotors.obd.sessao.EstadoSessao
import com.charruamotors.obd.sessao.FaseSessao
import com.charruamotors.obd.sessao.OrigemSessao
import com.charruamotors.sistema.ResumoTecnicoConexao.SEM_DADO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.3.0 — indicadores separados do adaptador ELM327 e da ECU, e o resumo técnico da tela inicial. */
class StatusElmEcuTest {

    private val semSessao = EstadoSessao()
    private val conectando = EstadoSessao(fase = FaseSessao.Conectando(1, 1), origem = OrigemSessao.MANUAL)
    private val v21 = "ELM327 v2.1"

    @Test
    fun `desconectado sem nada lido`() {
        val s = StatusElmEcu.de(EstadoConexaoObd.Desconectado, semSessao, InfoConexao())
        assertEquals(EstadoElm.DESCONECTADO, s.elm)
        assertEquals(EstadoEcu.AGUARDANDO, s.ecu)
        assertEquals("Desconectado", s.textoElm)
        assertEquals("Aguardando", s.textoEcu)
    }

    @Test
    fun `conectando antes e depois do ATZ`() {
        val antes = StatusElmEcu.de(EstadoConexaoObd.Conectando, conectando, InfoConexao())
        assertEquals(EstadoElm.CONECTANDO, antes.elm)
        assertEquals(EstadoEcu.AGUARDANDO, antes.ecu)

        // Adaptador já respondeu (ATZ → v2.1), a ECU ainda não: ELM conectado, ECU aguardando.
        val depois = StatusElmEcu.de(EstadoConexaoObd.Conectando, conectando, InfoConexao(adaptador = v21))
        assertEquals(EstadoElm.CONECTADO, depois.elm)
        assertEquals("Conectado (ELM327 v2.1)", depois.textoElm)
        assertEquals(EstadoEcu.AGUARDANDO, depois.ecu)
        assertNull(depois.camadaEcu)
    }

    @Test
    fun `sessao automatica esperando a ECU conta como conectando`() {
        val aguardando = EstadoSessao(fase = FaseSessao.AguardandoEcu, origem = OrigemSessao.AUTOMATICA)
        val s = StatusElmEcu.de(EstadoConexaoObd.Desconectado, aguardando, InfoConexao())
        assertEquals(EstadoElm.CONECTANDO, s.elm)
        assertEquals(EstadoEcu.AGUARDANDO, s.ecu)
    }

    @Test
    fun `conectado mostra a camada e o protocolo curto`() {
        val obd2 = InfoConexao(protocolo = "AUTO, ISO 15765-4 (CAN 11/500)", adaptador = v21)
        val s = StatusElmEcu.de(EstadoConexaoObd.Conectado, semSessao, obd2)
        assertEquals(EstadoElm.CONECTADO, s.elm)
        assertEquals(EstadoEcu.CONECTADA, s.ecu)
        assertEquals("Conectado · OBD2 Padrão · CAN 11/500", s.textoEcu)

        val modo21 = InfoConexao(protocolo = "ISO 15765-4 (CAN 11/500)", modo = ModoComunicacaoEcu.MODO_21, nomeModo = "Modo 21 (M-OBD / KWP)", adaptador = v21)
        assertEquals("Conectado · Modo 21 (M-OBD / KWP)", StatusElmEcu.de(EstadoConexaoObd.Conectado, semSessao, modo21).textoEcu)
    }

    @Test
    fun `erro depois do adaptador responder e ECU sem resposta`() {
        val s = StatusElmEcu.de(EstadoConexaoObd.Erro("ECU não respondeu"), semSessao, InfoConexao(adaptador = v21))
        assertEquals(EstadoElm.DESCONECTADO, s.elm)
        assertEquals(EstadoEcu.SEM_RESPOSTA, s.ecu)
        assertEquals("Desconectado (ELM327 v2.1 respondeu)", s.textoElm)
        assertEquals("Sem resposta", s.textoEcu)
    }

    @Test
    fun `erro de bluetooth antes do adaptador nao culpa a ECU`() {
        val s = StatusElmEcu.de(EstadoConexaoObd.Erro("socket"), semSessao, InfoConexao())
        assertEquals(EstadoElm.DESCONECTADO, s.elm)
        assertEquals(EstadoEcu.AGUARDANDO, s.ecu)
    }

    @Test
    fun `erro com sessao ainda ativa entre tentativas nao vira conectando`() {
        val s = StatusElmEcu.de(EstadoConexaoObd.Erro("ECU"), conectando, InfoConexao(adaptador = v21))
        assertEquals(EstadoEcu.SEM_RESPOSTA, s.ecu)
    }

    @Test
    fun `resumo tecnico so com a ECU conectada e TCM quando detectado`() {
        val info = InfoConexao(protocolo = "AUTO, ISO 15765-4 (CAN 11/500)")
        assertTrue(ResumoTecnicoConexao.linhas(false, info, "8AJ", null).isEmpty())

        val semTcm = ResumoTecnicoConexao.linhas(true, info, "8AJFZ29G4D6012345", ImpressaoEcu(calid = "896610K770"))
        assertEquals(listOf("Protocolo", "VIN", "CALID"), semTcm.map { it.rotulo })
        assertEquals("ISO 15765-4 (CAN 11/500)", semTcm[0].valor)
        assertEquals("8AJFZ29G4D6012345", semTcm[1].valor)
        assertEquals("896610K770", semTcm[2].valor)

        val comTcm = ResumoTecnicoConexao.linhas(true, info, null, ImpressaoEcu(tcmDetectado = true, tcmCabecalho = "7E1"))
        assertEquals(SEM_DADO, comTcm[1].valor)
        assertEquals(SEM_DADO, comTcm[2].valor)
        assertEquals("TCM" to "7E1", comTcm[3].rotulo to comTcm[3].valor)

        // TCM do perfil (SW4/Cruze) quando a sondagem não rodou.
        val doPerfil = ResumoTecnicoConexao.linhas(true, InfoConexao(), null, null, tcmPerfil = "7E1")
        assertEquals(SEM_DADO, doPerfil[0].valor)
        assertEquals("7E1 (perfil)", doPerfil.last().valor)
        // Sondado e não detectado, sem câmbio com TCM: a linha nem aparece.
        assertEquals(3, ResumoTecnicoConexao.linhas(true, info, null, ImpressaoEcu(tcmDetectado = false)).size)
    }
}
