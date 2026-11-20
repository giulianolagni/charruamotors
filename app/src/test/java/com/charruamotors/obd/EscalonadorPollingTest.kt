package com.charruamotors.obd

import com.charruamotors.obd.pid.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EscalonadorPollingTest {

    /** ECU típica: todos os candidatos da fila rápida + 7 PIDs lentos, em ordem de catálogo. */
    private val completa = listOf(
        "0104", "0105", "0106", "0107", "010B", "010C", "010D", "010F", "0110", "0111", "015C", "ATRV",
    )

    private fun escalonador(suportados: List<String> = completa, lentosPorCiclo: Int = 2) =
        EscalonadorPolling(lentosPorCiclo).apply { atualizarSuportados(suportados) }

    // ---------------------------------------------------------------- composição das filas

    @Test
    fun `fila rapida tem RPM velocidade MAP e MAF na ordem de prioridade`() {
        val e = escalonador()
        assertEquals(listOf(Pids.RPM, Pids.VELOCIDADE, Pids.PRESSAO_COLETOR, Pids.MAF), e.filaRapida)
        // Borboleta é o 5º candidato: com MAP e MAF suportados, vai para a fila lenta.
        assertEquals(listOf("0104", "0105", "0106", "0107", "010F", "0111", "015C", "ATRV"), e.filaLenta)
    }

    @Test
    fun `sem MAF ou sem MAP a borboleta entra na fila rapida`() {
        val semMaf = escalonador(completa - Pids.MAF)
        assertEquals(listOf(Pids.RPM, Pids.VELOCIDADE, Pids.PRESSAO_COLETOR, Pids.BORBOLETA), semMaf.filaRapida)

        val semMap = escalonador(completa - Pids.PRESSAO_COLETOR)
        assertEquals(listOf(Pids.RPM, Pids.VELOCIDADE, Pids.MAF, Pids.BORBOLETA), semMap.filaRapida)

        val minima = escalonador(listOf("0105", Pids.RPM))
        assertEquals(listOf(Pids.RPM), minima.filaRapida)
        assertEquals(listOf("0105"), minima.filaLenta)
    }

    @Test
    fun `fila lenta nunca repete PIDs da rapida e segue a ordem dos suportados`() {
        val e = escalonador()
        assertTrue(e.filaLenta.none { it in e.filaRapida })
        assertEquals(completa.filter { it !in e.filaRapida }, e.filaLenta)
    }

    // ---------------------------------------------------------------- ciclos

    @Test
    fun `todo ciclo le a fila rapida inteira mais dois lentos em rodizio`() {
        val e = escalonador()
        val ciclos = List(4) { e.proximoCiclo() }

        ciclos.forEach { assertEquals(e.filaRapida, it.take(4)) }
        assertEquals(listOf("0104", "0105"), ciclos[0].drop(4))
        assertEquals(listOf("0106", "0107"), ciclos[1].drop(4))
        assertEquals(listOf("010F", "0111"), ciclos[2].drop(4))
        assertEquals(listOf("015C", "ATRV"), ciclos[3].drop(4))
        assertEquals(6, ciclos.maxOf { it.size }) // 4 rápidos + 2 lentos: ciclo curto no ELM327
    }

    @Test
    fun `uma volta completa cobre todos os lentos exatamente uma vez e depois recomeca`() {
        val e = escalonador(lentosPorCiclo = 3)
        assertEquals(3, e.ciclosPorVolta) // 8 lentos / 3 por ciclo
        val volta = List(e.ciclosPorVolta) { e.proximoCiclo().drop(e.filaRapida.size) }.flatten()
        // A última vez do rodízio "dá a volta" e repete o início; nenhum lento fica de fora.
        assertEquals(e.filaLenta.toSet(), volta.toSet())
        assertEquals(9, volta.size)
        assertEquals(volta.drop(8), e.filaLenta.take(1))
    }

    @Test
    fun `com poucos lentos todos entram em todo ciclo`() {
        val e = escalonador(listOf(Pids.RPM, Pids.VELOCIDADE, "0105"), lentosPorCiclo = 2)
        repeat(3) { assertEquals(listOf(Pids.RPM, Pids.VELOCIDADE, "0105"), e.proximoCiclo()) }
        assertEquals(1, e.ciclosPorVolta)
    }

    @Test
    fun `sem suportados o ciclo e vazio`() {
        assertTrue(EscalonadorPolling().proximoCiclo().isEmpty())
    }

    // ---------------------------------------------------------------- instantâneo (amostra e retenção)

    @Test
    fun `instantaneo mantem o ultimo valor dos lentos nao consultados`() {
        val e = escalonador()
        val c1 = e.proximoCiclo()
        val s1 = e.consolidar(c1, mapOf("010C" to 900.0, "010D" to 0.0, "010B" to 35.0, "0110" to 3.0, "0104" to 20.0, "0105" to 80.0))
        assertEquals(80.0, s1["0105"]!!, 0.0)

        val c2 = e.proximoCiclo()
        val s2 = e.consolidar(c2, mapOf("010C" to 2000.0, "010D" to 30.0, "010B" to 60.0, "0110" to 9.0, "0106" to 1.5, "0107" to 2.0))
        assertEquals(2000.0, s2["010C"]!!, 0.0) // rápido atualizado
        assertEquals(80.0, s2["0105"]!!, 0.0) // lento retido do ciclo anterior
        assertEquals(1.5, s2["0106"]!!, 0.0)
        assertEquals(8, s2.size) // 4 rápidos + 2 lentos de cada ciclo
    }

    @Test
    fun `PID consultado sem resposta sai do instantaneo`() {
        val e = escalonador()
        e.consolidar(e.proximoCiclo(), mapOf("010C" to 900.0, "0104" to 20.0))
        val s = e.consolidar(listOf("010C", "0104"), mapOf("0104" to 22.0)) // RPM: NO DATA
        assertFalse("010C" in s)
        assertEquals(22.0, s["0104"]!!, 0.0)
    }

    @Test
    fun `instantaneo devolvido e uma copia`() {
        val e = escalonador()
        val s = e.consolidar(listOf("010C"), mapOf("010C" to 900.0))
        e.consolidar(listOf("010C"), mapOf("010C" to 1000.0))
        assertEquals(900.0, s["010C"]!!, 0.0)
    }

    // ---------------------------------------------------------------- mudanças de suporte e grupos

    @Test
    fun `PIDs novos entram no rodizio e os removidos somem do instantaneo`() {
        val e = escalonador()
        e.consolidar(listOf("0105"), mapOf("0105" to 90.0))
        e.proximoCiclo() // cursor anda 2

        e.atualizarSuportados(completa - "0105" + "VW_TEMP_OLEO")
        assertTrue("VW_TEMP_OLEO" in e.filaLenta)
        assertFalse("0105" in e.consolidar(emptyList(), emptyMap()))
        assertEquals(listOf("0107", "010F"), e.proximoCiclo().drop(4)) // continuou de onde estava
    }

    @Test
    fun `PIDs da mesma resposta ocupam uma unica vez na fila lenta`() {
        val injetores = listOf("TOYOTA_INJ1", "TOYOTA_INJ2", "TOYOTA_INJ3", "TOYOTA_INJ4")
        val e = EscalonadorPolling(lentosPorCiclo = 1)
        e.atualizarSuportados(listOf(Pids.RPM, "0105") + injetores) { if (it.startsWith("TOYOTA_INJ")) "TOYOTA_INJ1" else it }

        assertEquals(listOf("0105", "TOYOTA_INJ1"), e.filaLenta)
        // O cliente lê a requisição uma vez e informa os 4 valores: todos entram no instantâneo.
        val s = e.consolidar(injetores, injetores.associateWith { 0.5 })
        assertEquals(injetores.toSet(), s.keys)
    }

    @Test
    fun `parametros invalidos sao rejeitados`() {
        assertThrows(IllegalArgumentException::class.java) { EscalonadorPolling(lentosPorCiclo = 0) }
        assertThrows(IllegalArgumentException::class.java) { EscalonadorPolling(maxRapidos = -1) }
    }
}
