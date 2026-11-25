package com.charruamotors.obd

import com.charruamotors.direcao.hud.FormatadorHudEsportivo
import com.charruamotors.direcao.painel.ClusterPainel
import com.charruamotors.domain.calculo.AlavancaGmTcm
import com.charruamotors.domain.calculo.EstimadorCambioGm6T
import com.charruamotors.domain.calculo.FiltroVelocidadeEspuria
import com.charruamotors.obd.log.EntradaRegistro
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.pid.Pids
import com.charruamotors.obd.transporte.ObdTransport
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.3.7/v2.3.8 — Cruze 1.4T: respostas de ECM + TCM no CAN, alavanca do TCM `7E2` e lote de quadro único no v2.1; sonda da alavanca e `010D` espúrio. */
class HotfixCruzeV237Test {

    private class Ecu(val respostas: MutableMap<String, String>) : ObdTransport {
        val enviados = mutableListOf<String>()
        private var cabecalho = "7DF"

        override suspend fun conectar() = Unit

        override suspend fun enviarComando(comando: String, timeoutMs: Long): String {
            enviados += comando
            if (comando.startsWith("ATSH")) cabecalho = comando.removePrefix("ATSH")
            return respostas["$cabecalho:$comando"] ?: respostas[comando]
                ?: if (comando.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"
        }

        override fun fechar() = Unit
    }

    /** TCM responde antes da ECM no `0100`; a ECM tem 0B, 0C, 0D, 10 e a faixa até o `0142`. */
    private val cruze = mutableMapOf(
        "ATZ" to "ELM327 v2.1\r\r",
        "0100" to "410080000001\r410000398001\r\r",
        "01001" to "410080000001\r\r",
        "0120" to "412000000001\r\r",
        "0140" to "414040000000\r\r",
        "ATDP" to "ISO 15765-4 (CAN 11/500)\r\r",
        "ATDPN" to "A6\r\r",
        "010C0D" to "410C0FA00D32\r\r",
        "010B10" to "410B651001F4\r\r",
        "0142" to "41420000\r41423778\r\r",
        "01421" to "41420000\r\r",
        "7E2:221940" to "62194082\r\r",
        "7E2:2219401" to "62194082\r\r",
        "7E2:2219AD" to "6219AD04\r\r",
        "7E2:2219AD1" to "6219AD04\r\r",
        "7E2:22199A" to "62199A03\r\r",
        "7E2:22199A1" to "62199A03\r\r",
        "7E2:221941" to "6219410BB8\r\r", // candidato com 2 bytes
        "7E2:2219411" to "6219410BB8\r\r",
        "7E2:221925" to "7F2231\r\r",
    )

    @Test
    fun `bitmask une as respostas de todos os modulos`() {
        listOf("410080000001\r4100BE7FB813", "4100800000014100BE7FB813").forEach { bruto ->
            val r = ObdParser.parsePidsSuportados(bruto, 0x00) as RespostaObd.Ok
            assertTrue(bruto, r.valor.containsAll(listOf(0x01, 0x0C, 0x0D, 0x20)))
        }
    }

    @Test
    fun `tensao 0142 ignora modulo secundario abaixo de 5 V`() {
        val d = CatalogoTeste.pid(Pids.TENSAO_ECU)
        listOf("41420000", "4142000B").forEach { tcm ->
            assertEquals(RespostaObd.Ok(14.2), ObdParser.parsePid("$tcm\r41423778", d))
            assertTrue(ObdParser.parsePid(tcm, d) is RespostaObd.Invalido)
        }
        assertEquals(RespostaObd.SemDados, ObdParser.parsePid("NO DATA", d))
    }

    @Test
    fun `posicao da alavanca do TCM`() {
        fun rotulo(s: Int, m: Double? = null, v: Double? = 0.0) =
            AlavancaGmTcm.posicao(s.toDouble(), m, v)?.let { AlavancaGmTcm.ROTULOS[it] }
        assertEquals(listOf("P", "R", "N", "D", "M"), (0..4).map { rotulo(it) })
        (1..6).forEach { assertEquals("M$it", rotulo(4, it.toDouble(), 40.0)) }
        assertEquals("D", rotulo(3, 4.0, 60.0))
        assertNull(rotulo(0, v = 50.0))
        assertNull(rotulo(1, v = 50.0))
        assertNull(rotulo(9))
    }

    @Test
    fun `posicao do TCM substitui a marcha estimada no painel e no HUD`() {
        val andando = mapOf(Pids.RPM to 2000.0, Pids.VELOCIDADE to 50.0)
        val dados = EstimadorCambioGm6T().aplicar(
            DadosObd(andando + mapOf(AlavancaGmTcm.CHAVE_SELETOR to 4.0, AlavancaGmTcm.CHAVE_MARCHA_TCM to 3.0)),
        )
        assertNull(dados.valores["TOYOTA_MARCHA"])
        assertEquals("M3", FormatadorHudEsportivo.marcha(dados))
        assertEquals("M3", ClusterPainel.indicadorMarcha(dados).principal)
        // Sem posição do TCM, segue a estimativa.
        val estimado = EstimadorCambioGm6T().aplicar(DadosObd(andando))
        assertEquals(4.0, estimado.valores["TOYOTA_MARCHA"]!!, 0.0)
    }

    @Test
    fun `no v21 o lote cabe em um quadro, o 0142 vai sem sufixo e o TCM 7E2 e lido`() = runTest {
        val t = Ecu(cruze)
        val obd = Elm327BluetoothClient(
            adaptador = null,
            catalogo = CatalogoTeste.padrao,
            criarTransporte = { t },
            dispatcher = StandardTestDispatcher(testScheduler),
            catalogoEstendido = CatalogoTeste.estendido,
            catalogoCascata = CatalogoTeste.cascata,
        )
        assertTrue(obd.conectar(DispositivoObd("OBDII", "00:11:22:33:44:55"), "Chevrolet").isSuccess)
        assertTrue(obd.usaLoteMultiPid)
        val perfil = obd.ativarPerfilFabricante("Chevrolet") as ResultadoPerfil.Ativo
        assertEquals(
            setOf("GM_ATF_7E2", "GM_TCM_SONDA_19AD", "GM_TCM_SONDA_1941", AlavancaGmTcm.CHAVE_MARCHA_TCM),
            perfil.pids.map { it.comando }.toSet(),
        )

        val ultima = obd.lerTelemetria(intervaloMinimoMs = 1, lentosPorVez = 20, ciclosPorLento = 1).take(3).toList().last()

        assertTrue("010C0D" in t.enviados)
        assertTrue("010B10" in t.enviados)
        assertTrue(t.enviados.none { it == "010C0D0B" || it == "01421" })
        assertEquals(14.2, ultima[Pids.TENSAO_ECU]!!, 1e-9)
        assertEquals(4.0, ultima["GM_TCM_SONDA_19AD"]!!, 0.0)
        assertEquals(11.0, ultima["GM_TCM_SONDA_1941"]!!, 0.0) // 1º byte de 0B B8
        assertEquals(3.0, ultima[AlavancaGmTcm.CHAVE_MARCHA_TCM]!!, 0.0)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun TestScope.cliente(t: Ecu) = Elm327BluetoothClient(
        adaptador = null,
        catalogo = CatalogoTeste.padrao,
        criarTransporte = { t },
        dispatcher = StandardTestDispatcher(testScheduler),
        catalogoEstendido = CatalogoTeste.estendido,
        catalogoCascata = CatalogoTeste.cascata,
        relogioMs = { testScheduler.currentTime },
    )

    @Test
    fun `sonda os candidatos do 7E2 em ordem e grava o payload no log so quando muda`() = runTest {
        val t = Ecu(cruze)
        val obd = cliente(t)
        assertTrue(obd.conectar(DispositivoObd("OBDII", "00:11:22:33:44:55"), "Chevrolet").isSuccess)
        val antes = t.enviados.size
        obd.ativarPerfilFabricante("Chevrolet")

        val sondados = t.enviados.drop(antes).filter { it.startsWith("22") && it != "221940" && it != "22199A" }
        assertEquals(AlavancaGmTcm.IDS_SONDA.map { "22$it" }, sondados)

        suspend fun ler() = obd.lerTelemetria(intervaloMinimoMs = 1, lentosPorVez = 20, ciclosPorLento = 1).take(9).toList()
        ler()
        t.respostas["7E2:2219411"] = "6219410001\r\r"
        ler()

        assertTrue(t.enviados.none { it.startsWith("22199B") || it.startsWith("221942") })
        val eventos = obd.registroComunicacao().filterIsInstance<EntradaRegistro.Evento>().map { it.texto }
        val de1941 = eventos.filter { it.startsWith("TCM 7E2 · 221941:") }
        assertEquals(de1941.toString(), 2, de1941.size)
        assertTrue(de1941[0], "6219410BB8" in de1941[0])
        assertTrue(de1941[1], "6219410001" in de1941[1])
        assertEquals(1, eventos.count { it.startsWith("TCM 7E2 · 2219AD:") })
        assertTrue(eventos.none { it.startsWith("TCM 7E2 · 221925:") })
    }

    @Test
    fun `filtro descarta 255 com motor lento ou salto impossivel`() {
        val f = FiltroVelocidadeEspuria()
        assertFalse(f.aceitar(255.0, 800.0, 0))
        assertFalse(f.aceitar(255.0, null, 10_000)) // vale o último RPM conhecido
        assertTrue(f.aceitar(60.0, 2500.0, 11_000))
        assertFalse(f.aceitar(255.0, 2500.0, 11_500))
        assertTrue(f.aceitar(180.0, 4000.0, 12_000)) // só o 255 é suspeito
        assertTrue(f.aceitar(255.0, 5000.0, 12_500)) // salto de 75 km/h com o motor alto
    }

    @Test
    fun `010D FF com carro parado nao chega a telemetria`() = runTest {
        cruze["010C0D"] = "410C0C800DFF\r\r" // 800 rpm
        val obd = cliente(Ecu(cruze))
        assertTrue(obd.conectar(DispositivoObd("OBDII", "00:11:22:33:44:55"), "Chevrolet").isSuccess)

        val leituras = obd.lerTelemetria(intervaloMinimoMs = 1).take(3).toList()

        assertTrue(leituras.all { it[Pids.VELOCIDADE] == null })
        assertEquals(800.0, leituras.last()[Pids.RPM]!!, 0.0)
    }
}
