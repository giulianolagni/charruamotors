package com.charruamotors.oficina.checkup

import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.EstadoMonitor
import com.charruamotors.obd.InfoConexao
import com.charruamotors.obd.ModoComunicacaoEcu
import com.charruamotors.obd.ObdClientFake
import com.charruamotors.obd.RespostaBruta
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** v2.2.9: Checkup OBD2 / Prontidão e Falhas de Ignição (Modo 06) da aba Oficina. */
class CheckupObdTest {

    /** ELM327 roteirizado: `NO DATA` para o que não estiver no mapa; [mudos] = sem prompt. */
    private class Roteiro(val respostas: Map<String, String>, val mudos: Set<String> = emptySet()) {
        val enviados = mutableListOf<String>()
        val enviar: EnvioManual = { comando, _ ->
            enviados += comando
            when {
                comando in mudos -> RespostaBruta(comando, "", 2_000, semPrompt = true)
                else -> RespostaBruta(comando, respostas[comando] ?: if (comando.startsWith("AT")) "OK" else "NO DATA", 30)
            }
        }
    }

    private val cruze = mapOf(
        "0101" to "41 01 00 07 65 04",
        "0121" to "41210000",
        "0130" to "41302D",
        "0131" to "413104D2",
        "014D" to "414D0000",
        "014E" to "414E0315",
        "0902" to "014\r0:490201384147\r1:424E363953304A\r2:52313233343536",
        "0904" to "013\r0:490401313236\r1:38313233340000\r2:00000000000000",
        "0906" to "4906015A3C910E",
    )

    // ---------------------------------------------------------------- Checkup / Prontidão

    @Test
    fun `checkup completo le prontidao, contadores e o modo 09 com ATST32 antes`() = runTest {
        val r = Roteiro(cruze)

        val resultado = CheckupProntidao(r.enviar).executar()

        assertEquals(listOf("ATST32", "ATSH7DF", "0101", "0121", "0130", "0131", "014D", "014E", "0902", "0904", "0906"), r.enviados)
        val p = resultado.prontidao!!
        assertFalse(p.milAcesa)
        assertEquals(listOf("Sistema evaporativo (EVAP)"), p.pendentes.map { it.nome })
        assertEquals(1234, resultado.kmDesdeLimpeza)
        assertEquals(45, resultado.ciclosAquecimento)
        assertEquals(789, resultado.minutosDesdeLimpeza)
        assertEquals(0, resultado.kmComMil)
        assertEquals(0, resultado.minutosComMil)
        assertEquals("8AGBN69S0JR123456", resultado.vin)
        assertEquals("12681234", resultado.calid)
        assertEquals("5A3C910E", resultado.cvn)
        assertTrue(resultado.semResposta.isEmpty())
        // Só o EVAP pendente vira ponto de atenção (1.234 km e 45 ciclos não são limpeza recente).
        assertEquals(1, resultado.alertas.size)
        assertTrue(resultado.alertas.single().startsWith("1 monitor(es) pendente(s) (Sistema evaporativo (EVAP))"))
    }

    @Test
    fun `resumo em texto limpo para copiar e compartilhar`() = runTest {
        val resultado = CheckupProntidao(Roteiro(cruze).enviar).executar()

        val texto = CheckupProntidao.resumo(resultado, "Chevrolet Cruze 2018", "25/11/2026 10:00")

        assertTrue(texto.startsWith("CHECKUP OBD2 / PRONTIDÃO — Charrua OBD Pro\nVeículo: Chevrolet Cruze 2018"))
        assertTrue("Luz de injeção (MIL): apagada · códigos armazenados: 0" in texto)
        assertTrue("  [OK] Catalisador — pronto" in texto)
        assertTrue("  [..] Sistema evaporativo (EVAP) — pendente" in texto)
        assertTrue("  [--] Ar secundário — não aplicável" in texto)
        assertTrue("  Distância (0131): 1.234 km" in texto)
        assertTrue("  Ciclos de aquecimento (0130): 45" in texto)
        assertTrue("  CVN (0906): 5A3C910E" in texto)
        assertTrue("PONTOS DE ATENÇÃO" in texto)
        assertFalse(texto.endsWith("\n"))
    }

    @Test
    fun `luz acesa, rodou com ela e falhas apagadas ha pouco viram alertas`() = runTest {
        val r = Roteiro(
            cruze + mapOf(
                "0101" to "41018207FF00", // MIL acesa, 2 DTCs, tudo pronto
                "0121" to "4121002A", // 42 km com a luz acesa
                "0130" to "413003",
                "0131" to "41310019", // 25 km desde a limpeza
            ),
        )

        val a = CheckupProntidao(r.enviar).executar().alertas

        assertTrue(a[0].startsWith("Luz de injeção ACESA com 2 código(s)"))
        assertTrue(a.any { it == "O carro rodou 42 km com a luz de injeção acesa." })
        assertTrue(a.any { it.startsWith("Falhas apagadas recentemente: 25 km e 3 ciclo(s)") })
    }

    @Test
    fun `NO DATA, 7F e sem prompt nao travam o checkup`() = runTest {
        val r = Roteiro(
            mapOf("0101" to "41 01 00 07 65 04", "0131" to "7F0112", "0906" to "NO DATA"),
            mudos = setOf("0902"),
        )

        val resultado = CheckupProntidao(r.enviar).executar()

        assertEquals(11, r.enviados.size) // todos os comandos foram tentados
        assertTrue(resultado.algumaResposta)
        assertNull(resultado.kmDesdeLimpeza)
        assertNull(resultado.vin)
        assertEquals(listOf("0121", "0130", "0131", "014D", "014E", "0902", "0904", "0906"), resultado.semResposta)
        val texto = CheckupProntidao.resumo(resultado, null, "agora")
        assertTrue("  VIN (0902): não suportado" in texto)
        assertTrue("Sem resposta da ECU: 0121, 0130" in texto)
    }

    @Test
    fun `checkup e Modo 06 indisponiveis no modo 21 da SW4 e o 06 so no CAN`() {
        val sw4 = InfoConexao(protocolo = "AUTO, ISO 15765-4 (CAN 11/500)", modo = ModoComunicacaoEcu.MODO_21, nomeModo = "Modo 21 (M-OBD / KWP)")
        assertTrue(DisponibilidadeCheckup.motivoIndisponivel(sw4, exigirCan = false)!!.startsWith("Indisponível no Modo 21"))
        assertTrue(DisponibilidadeCheckup.motivoIndisponivel(sw4, exigirCan = true) != null)

        val cruze = InfoConexao(protocolo = "AUTO, ISO 15765-4 (CAN 11/500)")
        assertNull(DisponibilidadeCheckup.motivoIndisponivel(cruze, exigirCan = false))
        assertNull(DisponibilidadeCheckup.motivoIndisponivel(cruze, exigirCan = true))

        val kLine = InfoConexao(protocolo = "AUTO, ISO 9141-2")
        assertNull(DisponibilidadeCheckup.motivoIndisponivel(kLine, exigirCan = false))
        assertTrue(DisponibilidadeCheckup.motivoIndisponivel(kLine, exigirCan = true)!!.contains("exige protocolo CAN"))
    }

    // ---------------------------------------------------------------- Modo 06

    private fun registro(mid: Int, tid: Int, valor: Int) =
        "%02X%02X24%04X0000FFFF".format(mid, tid, valor)

    @Test
    fun `modo 06 le os cilindros 1 a 4 com EWMA e ciclo atual`() = runTest {
        val r = Roteiro(
            mapOf(
                "06A2" to "46" + registro(0xA2, 0x0B, 0) + registro(0xA2, 0x0C, 0),
                "06A3" to "46" + registro(0xA3, 0x0B, 0) + registro(0xA3, 0x0C, 1),
                "06A4" to "46" + registro(0xA4, 0x0B, 2) + registro(0xA4, 0x0C, 7),
                "06A5" to "46" + registro(0xA5, 0x0B, 0) + registro(0xA5, 0x0C, 0),
            ),
        )

        val resultado = FalhasIgnicaoModo06(r.enviar).executar()

        assertEquals(listOf("ATST32", "ATSH7DF", "06A2", "06A3", "06A4", "06A5"), r.enviados)
        assertEquals(listOf(0, 1, 7, 0), resultado.cilindros.map { it.cicloAtual })
        assertEquals(listOf(0, 0, 2, 0), resultado.cilindros.map { it.mediaEwma })
        assertEquals(3, resultado.pior!!.cilindro)
        val texto = FalhasIgnicaoModo06.resumo(resultado, "Cruze", "agora")
        assertTrue("  #3: 7 falha(s) · média 2" in texto)
        assertTrue("cilindro #3 com mais falhas" in texto)
    }

    @Test
    fun `modo 06 com NO DATA, resposta parcial e sem prompt nao trava`() = runTest {
        val r = Roteiro(
            mapOf(
                "06A2" to "NO DATA",
                "06A3" to "46" + registro(0xA3, 0x0B, 4) + "A30C24", // o TID 0C veio cortado
                "06A5" to "7F0612",
            ),
            mudos = setOf("06A4"),
        )

        val resultado = FalhasIgnicaoModo06(r.enviar).executar()

        assertEquals(4, resultado.cilindros.size)
        assertFalse(resultado.cilindros[0].respondeu)
        assertEquals(4, resultado.cilindros[1].mediaEwma)
        assertNull(resultado.cilindros[1].cicloAtual)
        assertFalse(resultado.cilindros[2].respondeu)
        assertFalse(resultado.cilindros[3].respondeu)
        assertTrue("  #1: sem resposta (MID A2)" in FalhasIgnicaoModo06.resumo(resultado, null, "agora"))
    }

    @Test
    fun `modo 06 sem nenhuma resposta e adaptador caido`() = runTest {
        val semNada = FalhasIgnicaoModo06(Roteiro(emptyMap()).enviar).executar()
        assertFalse(semNada.algumaResposta)
        assertNull(semNada.pior)
        assertTrue("A ECU não respondeu ao Modo 06" in FalhasIgnicaoModo06.resumo(semNada, null, "agora"))

        // Adaptador que caiu (null): depois de 2 falhas seguidas não insiste nos outros cilindros.
        val enviados = mutableListOf<String>()
        val caido: EnvioManual = { c, _ -> enviados += c; null }
        FalhasIgnicaoModo06(caido).executar()
        assertEquals(listOf("ATST32", "ATSH7DF", "06A2", "06A3"), enviados)
    }

    // ---------------------------------------------------------------- ponta a ponta no simulador

    @Test
    fun `Cruze simulado responde ao checkup e ao Modo 06 pela sessao manual`() = runTest {
        val fake = ObdClientFake(random = Random(7), latenciaMs = 0, latenciaComandoMs = 0)
        fake.conectar(DispositivoObd("Simulado", "00:00:00:00:00:00"), "Chevrolet Cruze")
        val enviar: EnvioManual = { c, t -> fake.enviarComandoManual(c, t).getOrNull() }

        val checkup = CheckupProntidao(enviar).executar()
        assertEquals(EstadoMonitor.PENDENTE, checkup.prontidao!!.monitores.first { it.nome == "Sistema evaporativo (EVAP)" }.estado)
        assertEquals(1234, checkup.kmDesdeLimpeza)
        assertEquals("8AGBN69S0JR123456", checkup.vin)
        assertEquals("12681234", checkup.calid)
        assertEquals("5A3C910E", checkup.cvn)

        val misfire = FalhasIgnicaoModo06(enviar).executar()
        assertEquals(listOf(0, 0, 3, 0), misfire.cilindros.map { it.cicloAtual })
        assertEquals(3, misfire.pior!!.cilindro)
    }
}
