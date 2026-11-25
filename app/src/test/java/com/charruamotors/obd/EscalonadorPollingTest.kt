package com.charruamotors.obd

import com.charruamotors.obd.pid.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EscalonadorPollingTest {

    /** ECU típica: todos os candidatos da fila rápida + 8 PIDs lentos, em ordem de catálogo. */
    private val completa = listOf(
        "0104", "0105", "0106", "0107", "010B", "010C", "010D", "010F", "0110", "0111", "015C", "ATRV",
    )

    private val lentosGasolina = listOf("0104", "0105", "0106", "0107", "010F", "0111", "015C", "ATRV")

    private fun escalonador(
        suportados: List<String> = completa,
        lentosPorVez: Int = EscalonadorPolling.LENTOS_POR_VEZ,
        ciclosPorLento: Int = EscalonadorPolling.CICLOS_POR_LENTO,
        consumoPorMaf: Boolean = true,
    ) = EscalonadorPolling(lentosPorVez, ciclosPorLento, Pids.candidatosFilaRapida(consumoPorMaf))
        .apply { atualizarSuportados(suportados) }

    // ---------------------------------------------------------------- composição das filas

    @Test
    fun `fila rapida tem so RPM MAP e velocidade e o MAF quando o consumo e por ele`() {
        val gasolina = escalonador()
        assertEquals(listOf(Pids.RPM, Pids.PRESSAO_COLETOR, Pids.VELOCIDADE, Pids.MAF), gasolina.filaRapida)
        // Borboleta nunca disputa a fila rápida.
        assertEquals(lentosGasolina, gasolina.filaLenta)

        val diesel = escalonador(consumoPorMaf = false)
        assertEquals(listOf(Pids.RPM, Pids.PRESSAO_COLETOR, Pids.VELOCIDADE), diesel.filaRapida)
        assertTrue(Pids.MAF in diesel.filaLenta && Pids.BORBOLETA in diesel.filaLenta)
    }

    @Test
    fun `sem MAP ou sem MAF a borboleta continua na fila lenta`() {
        val semMaf = escalonador(completa - Pids.MAF)
        assertEquals(listOf(Pids.RPM, Pids.PRESSAO_COLETOR, Pids.VELOCIDADE), semMaf.filaRapida)

        val semMap = escalonador(completa - Pids.PRESSAO_COLETOR)
        assertEquals(listOf(Pids.RPM, Pids.VELOCIDADE, Pids.MAF), semMap.filaRapida)
        assertTrue(Pids.BORBOLETA in semMap.filaLenta)

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
    fun `um PID lento a cada 3 ciclos rapidos`() {
        val e = escalonador()
        val ciclos = List(9) { e.proximoCiclo() }

        ciclos.forEach { assertEquals(e.filaRapida, it.take(4)) }
        // Ciclos 0, 3 e 6 levam 1 lento; os outros, só os mostradores.
        assertEquals(listOf(listOf("0104"), emptyList(), emptyList(), listOf("0105"), emptyList(), emptyList(), listOf("0106"), emptyList(), emptyList()), ciclos.map { it.drop(4) })
        assertEquals(5, ciclos.maxOf { it.size }) // nunca mais que 4 rápidos + 1 lento
    }

    @Test
    fun `diesel le 3 rapidos por ciclo e o MAF entra no rodizio`() {
        val e = escalonador(consumoPorMaf = false)
        val lentos = List(e.ciclosPorVolta) { e.proximoCiclo() }.onEach { assertEquals(e.filaRapida, it.take(3)) }.flatMap { it.drop(3) }
        assertEquals(e.filaLenta, lentos)
        assertTrue(Pids.MAF in lentos)
    }

    @Test
    fun `uma volta completa cobre todos os lentos exatamente uma vez e depois recomeca`() {
        val e = escalonador(lentosPorVez = 3, ciclosPorLento = 1)
        assertEquals(3, e.ciclosPorVolta) // 8 lentos / 3 por vez
        val volta = List(e.ciclosPorVolta) { e.proximoCiclo().drop(e.filaRapida.size) }.flatten()
        // A última vez do rodízio "dá a volta" e repete o início; nenhum lento fica de fora.
        assertEquals(e.filaLenta.toSet(), volta.toSet())
        assertEquals(9, volta.size)
        assertEquals(volta.drop(8), e.filaLenta.take(1))
    }

    @Test
    fun `ciclos por volta contam os ciclos so de rapidos`() {
        val e = escalonador()
        assertEquals(24, e.ciclosPorVolta) // 8 lentos, 1 a cada 3 ciclos
        val lentos = List(e.ciclosPorVolta) { e.proximoCiclo().drop(e.filaRapida.size) }.flatten()
        assertEquals(e.filaLenta, lentos)
    }

    @Test
    fun `com poucos lentos todos entram quando chega a vez`() {
        val e = escalonador(listOf(Pids.RPM, Pids.VELOCIDADE, "0105"), lentosPorVez = 2, ciclosPorLento = 1)
        repeat(3) { assertEquals(listOf(Pids.RPM, Pids.VELOCIDADE, "0105"), e.proximoCiclo()) }
        assertEquals(1, e.ciclosPorVolta)
    }

    @Test
    fun `sem PID rapido suportado a fila lenta anda em todo ciclo`() {
        val e = escalonador(listOf("0105", "0104", "ATRV"))
        assertTrue(e.filaRapida.isEmpty())
        assertEquals(listOf(listOf("0105"), listOf("0104"), listOf("ATRV")), List(3) { e.proximoCiclo() })
        assertEquals(3, e.ciclosPorVolta)
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
        val s1 = e.consolidar(c1, mapOf("010C" to 900.0, "010D" to 0.0, "010B" to 35.0, "0110" to 3.0, "0104" to 20.0))
        assertEquals(20.0, s1["0104"]!!, 0.0)

        val c2 = e.proximoCiclo()
        val s2 = e.consolidar(c2, mapOf("010C" to 2000.0, "010D" to 30.0, "010B" to 60.0, "0110" to 9.0))
        assertEquals(2000.0, s2["010C"]!!, 0.0) // rápido atualizado
        assertEquals(20.0, s2["0104"]!!, 0.0) // lento retido do ciclo anterior
        assertEquals(5, s2.size) // 4 rápidos + o lento do 1º ciclo
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
        val e = escalonador(lentosPorVez = 2, ciclosPorLento = 1)
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
        val e = EscalonadorPolling()
        e.atualizarSuportados(listOf(Pids.RPM, "0105") + injetores) { if (it.startsWith("TOYOTA_INJ")) "TOYOTA_INJ1" else it }

        assertEquals(listOf("0105", "TOYOTA_INJ1"), e.filaLenta)
        // O cliente lê a requisição uma vez e informa os 4 valores: todos entram no instantâneo.
        val s = e.consolidar(injetores, injetores.associateWith { 0.5 })
        assertEquals(injetores.toSet(), s.keys)
    }

    @Test
    fun `parametros invalidos sao rejeitados`() {
        assertThrows(IllegalArgumentException::class.java) { EscalonadorPolling(lentosPorVez = 0) }
        assertThrows(IllegalArgumentException::class.java) { EscalonadorPolling(ciclosPorLento = 0) }
    }

    @Test
    fun `invalidar tira o valor do instantaneo ate a proxima leitura`() {
        val e = EscalonadorPolling()
        e.atualizarSuportados(listOf("010C", "ATRV"))
        assertEquals(mapOf("010C" to 800.0, "ATRV" to 12.6), e.consolidar(listOf("010C", "ATRV"), mapOf("010C" to 800.0, "ATRV" to 12.6)))
        e.invalidar("ATRV")
        // Ciclo sem o ATRV: o valor velho não volta (v2.2.6, retomada da sessão manual).
        assertEquals(mapOf("010C" to 820.0), e.consolidar(listOf("010C"), mapOf("010C" to 820.0)))
        assertEquals(14.1, e.consolidar(listOf("010C", "ATRV"), mapOf("010C" to 820.0, "ATRV" to 14.1))["ATRV"]!!, 0.0)
    }
}
